package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountAuthorizationService;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionMovement;
import com.leap.leaplaughlove.trading.position.PositionMovementRepository;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import com.leap.leaplaughlove.trading.quote.CurrentQuoteService;
import com.leap.leaplaughlove.trading.quote.QuoteSnapshot;
import com.leap.leaplaughlove.trading.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.trading.quote.StaleQuoteException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Service to orchestrate the order submission and immediate execution lifecycle.
 * Lifecycle: SUBMITTED -> ACCEPTED or REJECTED -> FILLED.
 * All orders and executions are persisted for audit retention.
 */
@Service
public class OrderSubmissionService {

    private final AccountAuthorizationService accountAuthorizationService;
    private final InstrumentRepository instrumentRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final CashLedgerRepository cashLedgerRepository;
    private final PositionMovementRepository positionMovementRepository;
    private final PositionRepository positionRepository;
    private final TradeValidationService tradeValidationService;
    private final CurrentQuoteService currentQuoteService;

    public OrderSubmissionService(AccountAuthorizationService accountAuthorizationService,
                                  InstrumentRepository instrumentRepository,
                                  OrderRepository orderRepository,
                                  ExecutionRepository executionRepository,
                                  CashLedgerRepository cashLedgerRepository,
                                  PositionMovementRepository positionMovementRepository,
                                  PositionRepository positionRepository,
                                  TradeValidationService tradeValidationService,
                                  CurrentQuoteService currentQuoteService) {
        this.accountAuthorizationService = accountAuthorizationService;
        this.instrumentRepository = instrumentRepository;
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.cashLedgerRepository = cashLedgerRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.positionRepository = positionRepository;
        this.tradeValidationService = tradeValidationService;
        this.currentQuoteService = currentQuoteService;
    }

    /**
     * Submits an order and triggers immediate execution processing.
     *
     * @param request the order submission request
     * @return OrderSubmissionResponse containing order, execution, and balance details
     */
    // LLL-133
    // Roll back all settlement writes for checked as well as unchecked exceptions.
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmissionResponse submitOrder(OrderSubmissionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order request body is required");
        }
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be at least 1 whole share");
        }

        // LLL-133
        // 1. Lock and authorize the account before validating cash or holdings.
        // The existing transaction holds this lock until settlement commits or rolls back.
        Account account = accountAuthorizationService.getAuthorizedTradingAccountForUpdate(request.accountId());

        // 2. Resolve instrument
        Instrument instrument = resolveInstrument(request);

        // 3. Store order immediately with status SUBMITTED for audit retention
        OffsetDateTime now = OffsetDateTime.now();
        Order order = new Order(account, instrument, request.side(), request.quantity(), now);
        order = orderRepository.saveAndFlush(order);

        // 4. Resolve execution price (bid for SELL, ask for BUY from CurrentQuoteService)
        BigDecimal executionPrice;
        try {
            executionPrice = resolvePrice(request, instrument);
        } catch (QuoteUnavailableException | StaleQuoteException | ResponseStatusException ex) {
            String rejectReason = ex instanceof ResponseStatusException rse ? rse.getReason() : ex.getMessage();
            return rejectOrder(order, account, rejectReason != null ? rejectReason : "Market quote unavailable");
        }

        // 5. Execute Trade Validation Stub
        TradeValidationResult validationResult = tradeValidationService.validateTrade(
                account, instrument, request.side(), request.quantity(), executionPrice);

        if (!validationResult.isValid()) {
            return rejectOrder(order, account, validationResult.reason());
        }

        // Acceptance flow: transition order to ACCEPTED
        OffsetDateTime acceptTime = OffsetDateTime.now();
        order.markAccepted(acceptTime);
        order = orderRepository.saveAndFlush(order);

        // Execution success: record Execution as FILLED
        OffsetDateTime fillTime = OffsetDateTime.now();
        Execution execution = new Execution(
                order, request.quantity(), executionPrice, Execution.Status.FILLED,
                "Executed at market price", fillTime);
        execution = executionRepository.saveAndFlush(execution);

        // Transition order to FILLED
        order.markFilled(fillTime);
        order = orderRepository.saveAndFlush(order);

        // 6. Post-execution updates: Cash Ledger, Position Ledger, Positions
        propagateCashLedger(account, order, execution, request.side(), request.quantity(), executionPrice, fillTime);
        propagatePositionLedgerAndHoldings(account, instrument, order, execution, request.side(), request.quantity(), executionPrice, fillTime);

        // 7. Calculate updated balance after the trade
        BigDecimal balanceAfter = cashLedgerRepository.sumAmountByAccountIdAndCurrency(
                account.getAccountId(), account.getBaseCurrency());

        return toResponse(order, execution, balanceAfter);
    }

    private OrderSubmissionResponse rejectOrder(Order order, Account account, String reason) {
        OffsetDateTime rejectTime = OffsetDateTime.now();
        order.markRejected(reason, rejectTime);
        order = orderRepository.saveAndFlush(order);

        Execution execution = new Execution(
                order, null, null, Execution.Status.REJECTED, reason, rejectTime);
        execution = executionRepository.saveAndFlush(execution);

        BigDecimal currentBalance = cashLedgerRepository.sumAmountByAccountIdAndCurrency(
                account.getAccountId(), account.getBaseCurrency());

        return toResponse(order, execution, currentBalance);
    }

    private Instrument resolveInstrument(OrderSubmissionRequest request) {
        if (request.instrumentId() != null) {
            return instrumentRepository.findById(request.instrumentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument not found"));
        }
        if (request.symbol() != null && !request.symbol().isBlank()) {
            return instrumentRepository.findBySymbol(request.symbol().trim().toUpperCase())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Instrument not found for symbol: " + request.symbol()));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either instrumentId or symbol must be provided");
    }

    private BigDecimal resolvePrice(OrderSubmissionRequest request, Instrument instrument) {
        if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
            return request.price().setScale(4, RoundingMode.HALF_UP);
        }

        QuoteSnapshot quote = currentQuoteService.getCurrentQuote(instrument.getSymbol());
        if (quote == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No quote returned for symbol: " + instrument.getSymbol());
        }

        BigDecimal price;
        if (request.side() == Order.Side.BUY) {
            price = (quote.askPrice() != null && quote.askPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? quote.askPrice()
                    : quote.lastPrice();
        } else {
            price = (quote.bidPrice() != null && quote.bidPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? quote.bidPrice()
                    : quote.lastPrice();
        }

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No valid executable price found for symbol: " + instrument.getSymbol());
        }

        return price.setScale(4, RoundingMode.HALF_UP);
    }

    private void propagateCashLedger(Account account, Order order, Execution execution,
                                     Order.Side side, long quantity, BigDecimal price, OffsetDateTime time) {
        BigDecimal tradeAmount = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ledgerAmount;
        String entryType;
        String description;

        if (side == Order.Side.BUY) {
            ledgerAmount = tradeAmount.negate();
            entryType = "BUY_SETTLEMENT";
            description = "Buy settlement: " + order.getInstrument().getSymbol() + " " + quantity + " @ $" + price;
        } else {
            ledgerAmount = tradeAmount;
            entryType = "SELL_SETTLEMENT";
            description = "Sell settlement: " + order.getInstrument().getSymbol() + " " + quantity + " @ $" + price;
        }

        CashLedgerEntry entry = new CashLedgerEntry(
                account.getAccountId(), order.getOrderId(), execution.getExecutionId(),
                entryType, ledgerAmount, account.getBaseCurrency(), time, description);
        cashLedgerRepository.save(entry);
    }

    private void propagatePositionLedgerAndHoldings(Account account, Instrument instrument, Order order,
                                                    Execution execution, Order.Side side, long quantity,
                                                    BigDecimal price, OffsetDateTime time) {
        BigDecimal tradeCost = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        String movementType;
        long quantityDelta;
        BigDecimal costDelta;

        if (side == Order.Side.BUY) {
            movementType = "BUY_FILL";
            quantityDelta = quantity;
            costDelta = tradeCost;
        } else {
            movementType = "SELL_FILL";
            quantityDelta = -quantity;
            costDelta = tradeCost.negate();
        }

        // Record in position movements ledger (audit trail)
        PositionMovement movement = new PositionMovement(
                account.getAccountId(), instrument.getInstrumentId(), order.getOrderId(),
                execution.getExecutionId(), movementType, quantityDelta, costDelta, time);
        positionMovementRepository.save(movement);

        // Update current position holdings (trading.positions); locked to prevent
        // concurrent executions on the same account/instrument from losing an update
        Optional<Position> existingOpt = positionRepository.findByIdForUpdate(
                account.getAccountId(), instrument.getInstrumentId());

        if (side == Order.Side.BUY) {
            if (existingOpt.isEmpty()) {
                Position newPosition = new Position(
                        account.getAccountId(), instrument.getInstrumentId(), quantity, price, time);
                positionRepository.save(newPosition);
            } else {
                Position p = existingOpt.get();
                long oldQty = p.getQuantity();
                BigDecimal oldAvg = p.getAvgCost();
                long newQty = oldQty + quantity;
                BigDecimal totalCost = oldAvg.multiply(BigDecimal.valueOf(oldQty)).add(tradeCost);
                BigDecimal newAvgCost = totalCost.divide(BigDecimal.valueOf(newQty), 6, RoundingMode.HALF_UP);

                p.setQuantity(newQty);
                p.setAvgCost(newAvgCost);
                p.setUpdatedAt(time);
                positionRepository.save(p);
            }
        } else {
            // LLL-133
            // A sell must reduce holdings in full or roll back the entire settlement.
            Position p = existingOpt.orElseThrow(() ->
                    new IllegalStateException("Cannot settle sell: position does not exist"));
            if (p.getQuantity() < quantity) {
                throw new IllegalStateException("Cannot settle sell: insufficient position quantity");
            }

            long newQty = p.getQuantity() - quantity;
            p.setQuantity(newQty);
            if (newQty == 0) {
                p.setAvgCost(BigDecimal.ZERO);
            }
            p.setUpdatedAt(time);
            positionRepository.save(p);
        }
    }

    private OrderSubmissionResponse toResponse(Order order, Execution execution, BigDecimal currentBalance) {
        OrderSubmissionResponse.ExecutionDto execDto = new OrderSubmissionResponse.ExecutionDto(
                execution.getExecutionId(),
                execution.getFillQuantity(),
                execution.getFillPrice(),
                execution.getStatus().name(),
                execution.getExecutedAt(),
                execution.getReason()
        );

        return new OrderSubmissionResponse(
                order.getOrderId(),
                order.getAccount().getAccountId(),
                order.getAccount().getAccountNumber(),
                order.getInstrument().getInstrumentId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getSubmittedAt(),
                order.getAcceptedAt(),
                order.getRejectedAt(),
                order.getFilledAt(),
                order.getRejectionReason(),
                execDto,
                currentBalance
        );
    }
}

