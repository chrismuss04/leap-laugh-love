package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountAuthorizationService;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
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
    private final FillRecorder fillRecorder;
    private final TradeValidationService tradeValidationService;
    private final CurrentQuoteService currentQuoteService;

    public OrderSubmissionService(AccountAuthorizationService accountAuthorizationService,
                                  InstrumentRepository instrumentRepository,
                                  OrderRepository orderRepository,
                                  ExecutionRepository executionRepository,
                                  CashLedgerRepository cashLedgerRepository,
                                  FillRecorder fillRecorder,
                                  TradeValidationService tradeValidationService,
                                  CurrentQuoteService currentQuoteService) {
        this.accountAuthorizationService = accountAuthorizationService;
        this.instrumentRepository = instrumentRepository;
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.cashLedgerRepository = cashLedgerRepository;
        this.fillRecorder = fillRecorder;
        this.tradeValidationService = tradeValidationService;
        this.currentQuoteService = currentQuoteService;
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

        // 6. Execution success: record the FILLED execution, Cash Ledger, Position Ledger, Positions
        OffsetDateTime fillTime = OffsetDateTime.now();
        Execution execution = fillRecorder.recordFill(order, executionPrice, fillTime);

        // Transition order to FILLED
        order.markFilled(fillTime);
        order = orderRepository.saveAndFlush(order);

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

