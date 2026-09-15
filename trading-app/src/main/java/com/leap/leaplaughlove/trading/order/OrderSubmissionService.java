package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountAuthorizationService;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionId;
import com.leap.leaplaughlove.trading.position.PositionMovement;
import com.leap.leaplaughlove.trading.position.PositionMovementRepository;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Service orchestrating the order submission lifecycle:
 * 1. Client authentication & trading account authorization
 * 2. Immediate order creation with status SUBMITTED
 * 3. Whole-share trade validation (sufficient cash for BUY / sufficient holdings for SELL)
 * 4. Immediate execution recording (FILLED or REJECTED) for retention and audit compliance
 * 5. State transition: ACCEPTED -> FILLED, or REJECTED
 * 6. Atomic propagation to cash ledger (balance), position ledger (position movements), and positions
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
    private final MarketDataPriceService marketDataPriceService;

    public OrderSubmissionService(AccountAuthorizationService accountAuthorizationService,
                                  InstrumentRepository instrumentRepository,
                                  OrderRepository orderRepository,
                                  ExecutionRepository executionRepository,
                                  CashLedgerRepository cashLedgerRepository,
                                  PositionMovementRepository positionMovementRepository,
                                  PositionRepository positionRepository,
                                  TradeValidationService tradeValidationService,
                                  MarketDataPriceService marketDataPriceService) {
        this.accountAuthorizationService = accountAuthorizationService;
        this.instrumentRepository = instrumentRepository;
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.cashLedgerRepository = cashLedgerRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.positionRepository = positionRepository;
        this.tradeValidationService = tradeValidationService;
        this.marketDataPriceService = marketDataPriceService;
    }

    /**
     * Submits an order and triggers immediate execution processing.
     *
     * @param request the order submission request
     * @return OrderSubmissionResponse containing order, execution, and balance details
     */
    @Transactional
    public OrderSubmissionResponse submitOrder(OrderSubmissionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order request body is required");
        }
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be at least 1 whole share");
        }

        // 1. Authorize account (verifies ownership, active status, and tradingEnabled == true)
        Account account = accountAuthorizationService.getAuthorizedTradingAccount(request.accountId());

        // 2. Resolve instrument
        Instrument instrument = resolveInstrument(request);

        // 3. Resolve execution price (either supplied in request or fetched from market data service)
        BigDecimal executionPrice = resolvePrice(request, instrument);

        // 4. Store order immediately with status SUBMITTED
        OffsetDateTime now = OffsetDateTime.now();
        Order order = new Order(account, instrument, request.side(), request.quantity(), now);
        order = orderRepository.saveAndFlush(order);

        // 5. Execute Trade Validation Stub
        TradeValidationResult validationResult = tradeValidationService.validateTrade(
                account, instrument, request.side(), request.quantity(), executionPrice);

        if (!validationResult.isValid()) {
            // Rejection flow: update order to REJECTED
            OffsetDateTime rejectTime = OffsetDateTime.now();
            order.markRejected(validationResult.reason(), rejectTime);
            order = orderRepository.saveAndFlush(order);

            // Record execution as REJECTED for audit retention
            Execution execution = new Execution(
                    order, null, null, Execution.Status.REJECTED, validationResult.reason(), rejectTime);
            execution = executionRepository.saveAndFlush(execution);

            BigDecimal currentBalance = cashLedgerRepository.sumAmountByAccountIdAndCurrency(
                    account.getAccountId(), account.getBaseCurrency());

            return toResponse(order, execution, currentBalance);
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
        return marketDataPriceService.getPrice(instrument.getSymbol(), request.side());
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

        // Update current position holdings (trading.positions)
        PositionId positionId = new PositionId(account.getAccountId(), instrument.getInstrumentId());
        Optional<Position> existingOpt = positionRepository.findById(positionId);

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
            if (existingOpt.isPresent()) {
                Position p = existingOpt.get();
                long newQty = Math.max(0L, p.getQuantity() - quantity);
                p.setQuantity(newQty);
                if (newQty == 0) {
                    p.setAvgCost(BigDecimal.ZERO);
                }
                p.setUpdatedAt(time);
                positionRepository.save(p);
            }
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

