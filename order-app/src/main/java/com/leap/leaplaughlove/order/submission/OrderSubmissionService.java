package com.leap.leaplaughlove.order.submission;

import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.instrument.InstrumentRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.events.OrderEventPublisher;
import com.leap.leaplaughlove.order.history.FillRecorder;
import com.leap.leaplaughlove.order.validation.TradeValidationResult;
import com.leap.leaplaughlove.order.validation.TradeValidationService;
import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.order.quote.StaleQuoteException;
import com.leap.leaplaughlove.order.working.ReservationService;
import com.leap.leaplaughlove.order.working.TriggerEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Service to orchestrate the order submission and execution lifecycle.
 * Lifecycle: SUBMITTED -> ACCEPTED or REJECTED -> FILLED.
 * All orders and executions are persisted for audit retention.
 *
 * <p>Order types: a MARKET order executes at once. LIMIT, STOP and STOP_LIMIT orders that can't
 * fill yet are stored OPEN, holding back the cash or shares they may need (see
 * {@link ReservationService}), and {@code OrderTriggerEngine} fills them through
 * {@link #fillOpenOrder} once the market reaches their price.
 */
@Service
public class OrderSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(OrderSubmissionService.class);

    /** A DAY order works until midnight US Eastern time, the end of the US trading day. */
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    /** A GTC order is cancelled automatically after this many days, as at most brokers. */
    private static final int GTC_DAYS = 90;

    private final AccountClient accountClient;
    private final InstrumentRepository instrumentRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final FillRecorder fillRecorder;
    private final TradeValidationService tradeValidationService;
    private final CurrentQuoteService currentQuoteService;
    private final TransactionTemplate transactionTemplate;
    private final OrderEventPublisher orderEventPublisher;
    private final ReservationService reservationService;

    /**
     * What the first step of a submission decided: a finished rejection, an OPEN order waiting
     * for its price (no execution), or an accepted order with its committed FILLED execution,
     * waiting to be settled.
     */
    private record FirstStep(OrderSubmissionResponse rejection, Order order, Execution execution) {}

    /**
     * How settling a committed execution ended.
     * @param balanceAfter the account's cash after settling, when account-app reported it
     * @param refusal account-app's refusal, when it refused the fill and the order is now REJECTED
     */
    private record SettleOutcome(BigDecimal balanceAfter, RuntimeException refusal) {}

    /**
     * Constructs an instance of OrderSubmissionService with the specified dependencies.
     * @param accountClient the client used for account-related operations
     * @param instrumentRepository the repository for accessing instrument data
     * @param orderRepository the repository for persisting order data
     * @param executionRepository the repository for persisting execution data
     * @param fillRecorder the component responsible for recording fill details
     * @param tradeValidationService the service used for validating trades
     * @param currentQuoteService the service used for obtaining current market quotes
     * @param transactionTemplate runs each step of a submission in its own transaction
     * @param orderEventPublisher reports each order to Kafka once it is final
     * @param reservationService works out what the account's open orders hold back
     */
    @Autowired
    public OrderSubmissionService(AccountClient accountClient,
                                  InstrumentRepository instrumentRepository,
                                  OrderRepository orderRepository,
                                  ExecutionRepository executionRepository,
                                  FillRecorder fillRecorder,
                                  TradeValidationService tradeValidationService,
                                  CurrentQuoteService currentQuoteService,
                                  TransactionTemplate transactionTemplate,
                                  OrderEventPublisher orderEventPublisher,
                                  ReservationService reservationService) {
        this.accountClient = accountClient;
        this.instrumentRepository = instrumentRepository;
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.fillRecorder = fillRecorder;
        this.tradeValidationService = tradeValidationService;
        this.currentQuoteService = currentQuoteService;
        this.transactionTemplate = transactionTemplate;
        this.orderEventPublisher = orderEventPublisher;
        this.reservationService = reservationService;
    }

    /**
     * Constructs an OrderSubmissionService whose holds are summed from the given order repository.
     * @param accountClient the client used for account-related operations
     * @param instrumentRepository the repository for accessing instrument data
     * @param orderRepository the repository for persisting order data
     * @param executionRepository the repository for persisting execution data
     * @param fillRecorder the component responsible for recording fill details
     * @param tradeValidationService the service used for validating trades
     * @param currentQuoteService the service used for obtaining current market quotes
     * @param transactionTemplate runs each step of a submission in its own transaction
     * @param orderEventPublisher reports each order to Kafka once it is final
     */
    public OrderSubmissionService(AccountClient accountClient,
                                  InstrumentRepository instrumentRepository,
                                  OrderRepository orderRepository,
                                  ExecutionRepository executionRepository,
                                  FillRecorder fillRecorder,
                                  TradeValidationService tradeValidationService,
                                  CurrentQuoteService currentQuoteService,
                                  TransactionTemplate transactionTemplate,
                                  OrderEventPublisher orderEventPublisher) {
        this(accountClient, instrumentRepository, orderRepository, executionRepository, fillRecorder,
                tradeValidationService, currentQuoteService, transactionTemplate, orderEventPublisher,
                new ReservationService(orderRepository));
    }

    /**
     * Submits an order and, unless it has to wait for its price, executes it immediately.
     *
     * <p>Runs in three steps rather than one transaction, because settling is account-app's
     * work and it can only see what this app has committed (see {@link FillRecorder}): the order
     * and its execution are committed first, then settled, and only then marked FILLED.
     *
     * <p>If account-app doesn't answer settling with a refusal (it is unreachable, times out or
     * fails), or the fill can't be recorded after settling, the order is returned ACCEPTED rather
     * than failed: the trade may already have moved cash and holdings, and PendingFillRecovery
     * finishes it.
     *
     * @param request the order submission request
     * @return OrderSubmissionResponse containing order, execution, and balance details; an OPEN
     *         order has no execution yet
     */
    public OrderSubmissionResponse submitOrder(OrderSubmissionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order request body is required");
        }
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be at least 1 whole share");
        }

        FirstStep firstStep = transactionTemplate.execute(status -> acceptOrReject(request));
        if (firstStep.rejection() != null) {
            return firstStep.rejection();
        }
        Order order = firstStep.order();
        Execution execution = firstStep.execution();
        if (execution == null) {
            // OPEN: waiting for the market to reach its price.
            return toResponse(order, null, null);
        }

        SettleOutcome outcome = settleAndRecord(order, execution, null);
        if (outcome.refusal() != null) {
            // A 4xx, so the customer is told it definitely didn't go through, and why.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, order.getRejectionReason(),
                    outcome.refusal());
        }
        return toResponse(order, execution, outcome.balanceAfter());
    }

    /**
     * Fills an OPEN order the market has reached the price of, as {@code OrderTriggerEngine}
     * found. The order is checked again under its row lock - a cancel may have won - and the
     * account is validated again, since cash or shares may have gone while it waited. Settling
     * then follows the same steps as {@link #submitOrder}.
     *
     * @param orderId the OPEN order to fill
     * @param fillPrice the price to fill at, the side's live executable price
     * @param stopReached whether this price reached the order's stop
     * @param bearerToken a settlement token issued to the order's owner, for account-app
     * @return the order's status afterwards, or null if it was no longer OPEN
     * @throws RuntimeException if account-app can't be reached for validation; the order is
     *         left OPEN and tried again
     */
    public Order.Status fillOpenOrder(UUID orderId, BigDecimal fillPrice, boolean stopReached, String bearerToken) {
        FirstStep firstStep = transactionTemplate.execute(status -> acceptOpenOrder(orderId, fillPrice, stopReached, bearerToken));
        if (firstStep == null) {
            return null;
        }
        Order order = firstStep.order();
        if (firstStep.rejection() != null) {
            log.info("Open order {} reached its price but was rejected: {}", orderId, order.getRejectionReason());
            return order.getStatus();
        }
        settleAndRecord(order, firstStep.execution(), bearerToken);
        return order.getStatus();
    }

    /**
     * Settles a committed FILLED execution with account-app and records the fill.
     * @param order the accepted order
     * @param execution its committed FILLED execution
     * @param bearerToken the token to settle with, or null to forward the caller's
     * @return the cash balance after settling, or account-app's refusal, after which the order
     *         is REJECTED
     */
    private SettleOutcome settleAndRecord(Order order, Execution execution, String bearerToken) {
        SettlementResponse settlement;
        try {
            settlement = fillRecorder.settle(order, execution, bearerToken);
        } catch (RuntimeException ex) {
            if (!FillRecorder.isRefused(ex)) {
                // account-app may have booked it, so it must not be recorded as rejected. The
                // order stays ACCEPTED and PendingFillRecovery settles it once account-app answers.
                log.warn("Settling order {} had an unknown outcome; left for recovery: {}",
                        order.getOrderId(), ex.getMessage());
                return new SettleOutcome(null, null);
            }
            // account-app refused it, so nothing was booked. The execution is immutable, so it
            // stays as the record of the attempt; the order says it never filled.
            String reason = "Settlement failed: " + FillRecorder.refusalReason(ex);
            OffsetDateTime rejectedAt = OffsetDateTime.now();
            transactionTemplate.executeWithoutResult(status -> {
                order.markRejected(reason, rejectedAt);
                lockedCopy(order).markRejected(reason, rejectedAt);
            });
            // It executed and is now final, so reporting gets it; validation rejections don't.
            orderEventPublisher.publishRejected(order);
            return new SettleOutcome(null, ex);
        }

        BigDecimal balanceAfter = settlement != null ? settlement.balanceAfter() : null;
        boolean finishedHere;
        try {
            finishedHere = Boolean.TRUE.equals(
                    transactionTemplate.execute(status -> fillRecorder.completeFill(lockedCopy(order), execution)));
        } catch (RuntimeException ex) {
            // Cash and holdings have moved; only this app's record of it is missing, and
            // PendingFillRecovery writes it once this app's database is back.
            log.warn("Order {} settled but its fill could not be recorded; left for recovery: {}",
                    order.getOrderId(), ex.getMessage());
            return new SettleOutcome(balanceAfter, null);
        }
        order.markFilled(execution.getExecutedAt());
        // If recovery finished it first, recovery published it.
        if (finishedHere) {
            orderEventPublisher.publishFilled(order, execution);
        }
        return new SettleOutcome(balanceAfter, null);
    }

    /**
     * Loads the order's row with its row lock in the current transaction, so a status change
     * made after settling is written by dirty checking, and can't race PendingFillRecovery
     * finishing the same fill. Saving the order itself would merge a copy detached when the
     * first transaction committed, and its account association - never loaded, as orders are
     * created by account ID - would be copied over the stored one, which Hibernate warns it
     * can't update (HHH000502).
     * @param order the order, committed by an earlier transaction
     * @return the managed, locked order
     */
    private Order lockedCopy(Order order) {
        return orderRepository.findByIdForUpdate(order.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order " + order.getOrderId() + " is not stored"));
    }

    /**
     * Stores the order, prices and validates it, and either rejects it, leaves it OPEN, or
     * accepts it and writes its FILLED execution. Runs in one transaction, committed before the
     * fill is settled.
     * @param request the order submission request
     * @return the rejection, the OPEN order, or the accepted order and its execution
     */
    private FirstStep acceptOrReject(OrderSubmissionRequest request) {
        Order.OrderType orderType = request.effectiveOrderType();

        // 1. Resolve instrument
        Instrument instrument = resolveInstrument(request);

        // 2. Pre-trade check via AccountClient
        AccountValidationDto accountValidation;
        try {
            accountValidation = accountClient.getValidationData(request.accountId(), instrument.getInstrumentId());
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to validate account: " + ex.getMessage());
        }

        // The order's own price tolerance, or else the one saved on the account. A limit price
        // already caps the fill, so only market orders get one.
        BigDecimal maxSlippagePercent = null;
        if (orderType == Order.OrderType.MARKET) {
            maxSlippagePercent = request.maxSlippagePercent() != null
                    ? request.maxSlippagePercent()
                    : accountValidation.maxSlippagePercent();
            if (maxSlippagePercent != null && request.quotedPrice() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "quotedPrice is required: this account has a saved price tolerance");
            }
        }

        // Cash and shares other open orders hold aren't free for this one. The account's row lock
        // keeps two orders from counting the same cash or shares at once.
        orderRepository.lockAccount(request.accountId());
        ReservationService.Holds holds = reservationService.holdsFor(
                request.accountId(), instrument.getInstrumentId(), null);
        AccountValidationDto available = ReservationService.available(accountValidation, holds);

        // 3. Store order immediately with status SUBMITTED for audit retention
        OffsetDateTime now = OffsetDateTime.now();
        Order order = new Order(request.accountId(), instrument, request.side(), request.quantity(), now);
        order.setAccountNumber(accountValidation.accountNumber());
        order.setPriceTolerance(request.quotedPrice(), maxSlippagePercent);
        if (orderType != Order.OrderType.MARKET) {
            Order.TimeInForce timeInForce = request.timeInForce() != null ? request.timeInForce() : Order.TimeInForce.DAY;
            order.setConditions(orderType, request.limitPrice(), request.stopPrice(), timeInForce,
                    expiryOf(timeInForce, now));
        }
        order = orderRepository.saveAndFlush(order);

        // 4. Resolve execution price (bid for SELL, ask for BUY from CurrentQuoteService)
        BigDecimal executionPrice;
        try {
            executionPrice = resolvePrice(request.side(), instrument);
        } catch (QuoteUnavailableException | StaleQuoteException | ResponseStatusException ex) {
            String rejectReason = ex instanceof ResponseStatusException rse ? rse.getReason() : ex.getMessage();
            return new FirstStep(rejectOrder(order, accountValidation,
                    rejectReason != null ? rejectReason : "Market quote unavailable"), null, null);
        }

        if (orderType != Order.OrderType.MARKET) {
            return placeConditionalOrder(order, accountValidation, available, holds, executionPrice);
        }

        // 5. Price tolerance: reject if the market moved too far from the client's quote
        if (maxSlippagePercent != null) {
            TradeValidationResult toleranceResult = tradeValidationService.checkPriceTolerance(
                    request.quotedPrice(), executionPrice, maxSlippagePercent);
            if (!toleranceResult.isValid()) {
                return new FirstStep(rejectOrder(order, accountValidation, toleranceResult.reason()), null, null);
            }
        }

        // 6. Execute Trade Validation
        TradeValidationResult validationResult = tradeValidationService.validateTrade(
                available, instrument, request.side(), request.quantity(), executionPrice);

        if (!validationResult.isValid()) {
            return new FirstStep(rejectOrder(order, accountValidation,
                    ReservationService.explain(validationResult.reason(), request.side(), holds)), null, null);
        }

        // Acceptance flow: transition order to ACCEPTED
        OffsetDateTime acceptTime = OffsetDateTime.now();
        order.markAccepted(acceptTime);
        order = orderRepository.saveAndFlush(order);

        // 7. Execution success: record the FILLED execution; settlement follows once it is committed
        Execution execution = fillRecorder.recordExecution(order, executionPrice, OffsetDateTime.now());
        return new FirstStep(null, order, execution);
    }

    /**
     * Places a LIMIT, STOP or STOP_LIMIT order: validates the account can cover the most it could
     * cost (or holds the shares it sells), then fills it now if the market is already at its
     * price, or else leaves it OPEN holding that cash.
     * @param order the stored SUBMITTED order
     * @param accountValidation the account's validation data from account-app
     * @param available the same, less what other open orders hold
     * @param holds what other open orders hold
     * @param executionPrice the side's live executable price
     * @return the rejection, the OPEN order, or the accepted order and its execution
     */
    private FirstStep placeConditionalOrder(Order order, AccountValidationDto accountValidation,
                                            AccountValidationDto available, ReservationService.Holds holds,
                                            BigDecimal executionPrice) {
        Order.OrderType orderType = order.getOrderType();
        Order.Side side = order.getSide();

        // A stop the price has already passed would trigger at once - almost always a mistake,
        // e.g. a sell stop entered above the market. The ticket points the client to a market
        // or limit order instead.
        if (orderType.hasStop() && TriggerEvaluator.stopReached(side, order.getStopPrice(), executionPrice)) {
            return new FirstStep(rejectOrder(order, accountValidation, String.format(
                    "Stop price $%s must be %s the current %s of $%s - order rejected",
                    order.getStopPrice().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    side == Order.Side.BUY ? "above" : "below",
                    side == Order.Side.BUY ? "ask" : "bid",
                    executionPrice.setScale(2, RoundingMode.HALF_UP).toPlainString())), null, null);
        }

        BigDecimal reservePrice = ReservationService.reservePricePerShare(
                orderType, order.getLimitPrice(), order.getStopPrice());
        TradeValidationResult validationResult = tradeValidationService.validateTrade(
                available, order.getInstrument(), side, order.getQuantity(), reservePrice);
        if (!validationResult.isValid()) {
            return new FirstStep(rejectOrder(order, accountValidation,
                    ReservationService.explain(validationResult.reason(), side, holds)), null, null);
        }

        TriggerEvaluator.Evaluation evaluation = TriggerEvaluator.evaluate(order, executionPrice);
        if (evaluation.fills()) {
            // A limit the market is already at or better than fills now, at the live price.
            OffsetDateTime acceptTime = OffsetDateTime.now();
            order.markAccepted(acceptTime);
            order = orderRepository.saveAndFlush(order);
            Execution execution = fillRecorder.recordExecution(order, evaluation.fillPrice(), acceptTime,
                    executionReason(order));
            return new FirstStep(null, order, execution);
        }

        order.markOpen(ReservationService.reservedCash(side, order.getQuantity(), reservePrice));
        order = orderRepository.saveAndFlush(order);
        return new FirstStep(null, order, null);
    }

    /**
     * Locks an OPEN order and, if it is still OPEN, validates it against the account as it is now
     * and either rejects it or accepts it and writes its FILLED execution.
     * @param orderId the order to fill
     * @param fillPrice the price to fill at
     * @param stopReached whether this price reached the order's stop
     * @param bearerToken a settlement token issued to the order's owner
     * @return the rejection or the accepted order and its execution; null if it wasn't OPEN
     */
    private FirstStep acceptOpenOrder(UUID orderId, BigDecimal fillPrice, boolean stopReached, String bearerToken) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != Order.Status.OPEN) {
            return null;
        }
        // Loaded inside the transaction, so settling and publishing can read it afterwards.
        Instrument instrument = order.getInstrument();
        instrument.getSymbol();
        OffsetDateTime now = OffsetDateTime.now();
        if (stopReached) {
            order.markTriggered(now);
        }

        AccountValidationDto accountValidation;
        try {
            accountValidation = accountClient.getValidationDataAs(order.getAccountId(),
                    instrument.getInstrumentId(), bearerToken);
        } catch (HttpClientErrorException ex) {
            int status = ex.getStatusCode().value();
            if (status != 400 && status != 404 && status != 422) {
                // Not an answer about the account (e.g. our token was refused): try again later
                // rather than reject an order the client still wants.
                throw ex;
            }
            // account-app answered: the account is gone, closed or can't trade.
            String reason = "Account could not be validated: " + FillRecorder.refusalReason(ex);
            return new FirstStep(rejectOrder(order, null, reason), order, null);
        }
        order.setAccountNumber(accountValidation.accountNumber());

        orderRepository.lockAccount(order.getAccountId());
        ReservationService.Holds holds = reservationService.holdsFor(
                order.getAccountId(), instrument.getInstrumentId(), order.getOrderId());
        TradeValidationResult validationResult = tradeValidationService.validateTrade(
                ReservationService.available(accountValidation, holds), instrument, order.getSide(),
                order.getQuantity(), fillPrice);
        if (!validationResult.isValid()) {
            return new FirstStep(rejectOrder(order, accountValidation,
                    ReservationService.explain(validationResult.reason(), order.getSide(), holds)), order, null);
        }

        order.markAccepted(now);
        order = orderRepository.saveAndFlush(order);
        Execution execution = fillRecorder.recordExecution(order, fillPrice, now, executionReason(order));
        return new FirstStep(null, order, execution);
    }

    /**
     * Describes how an order other than a market order was priced, for its execution record.
     * @param order the order being filled
     * @return the execution's reason
     */
    private static String executionReason(Order order) {
        return switch (order.getOrderType()) {
            case MARKET -> "Executed at market price";
            case LIMIT -> "Executed at limit price or better";
            case STOP -> "Stop price reached; executed at market price";
            case STOP_LIMIT -> "Stop price reached; executed at limit price or better";
        };
    }

    /**
     * When an order with the given time in force expires if it hasn't filled.
     * @param timeInForce DAY or GTC
     * @param now when the order is placed
     * @return midnight US Eastern time after placing for DAY, 90 days later for GTC
     */
    static OffsetDateTime expiryOf(Order.TimeInForce timeInForce, OffsetDateTime now) {
        if (timeInForce == Order.TimeInForce.GTC) {
            return now.plusDays(GTC_DAYS);
        }
        return now.atZoneSameInstant(MARKET_ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(MARKET_ZONE).toOffsetDateTime();
    }

    /**
     * Rejects the specified order with the given reason and returns the corresponding response.
     * @param order the order to be rejected
     * @param accountValidation the account validation details associated with the order
     * @param reason the reason for rejecting the order
     * @return the response containing the result of the order rejection
     */
    private OrderSubmissionResponse rejectOrder(Order order, AccountValidationDto accountValidation, String reason) {
        OffsetDateTime rejectTime = OffsetDateTime.now();
        order.markRejected(reason, rejectTime);
        order = orderRepository.saveAndFlush(order);

        Execution execution = new Execution(
                order, null, null, Execution.Status.REJECTED, reason, rejectTime);
        execution = executionRepository.saveAndFlush(execution);

        BigDecimal currentBalance = accountValidation != null ? accountValidation.cashBalance() : BigDecimal.ZERO;
        return toResponse(order, execution, currentBalance);
    }

    /**
     * Returns the instrument associated with the order submission request given Id, or symbol if Id is not provided.
     * @param request the order submission request containing instrument details
     * @return the resolved instrument
     * @throws ResponseStatusException not found (404) if the instrument cannot be resolved by ID or symbol
     * @throws ResponseStatusException bad request (400) if neither instrumentId nor symbol is provided.
     */
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

    /**
     * Resolves the price the order fills at from the current market quote. The client's quoted
     * price is never used: it is only checked against this one.
     * @param side the side of the order: BUY fills at the ask, SELL at the bid
     * @param instrument the instrument associated with the order
     * @return the resolved price for the order
     * @throws ResponseStatusException unprocessable entity (422) if no valid price can be determined
     */
    private BigDecimal resolvePrice(Order.Side side, Instrument instrument) {
        QuoteSnapshot quote = currentQuoteService.getCurrentQuote(instrument.getSymbol());
        if (quote == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No quote returned for symbol: " + instrument.getSymbol());
        }

        BigDecimal price = TriggerEvaluator.executablePrice(side, quote);
        if (price == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No valid executable price found for symbol: " + instrument.getSymbol());
        }
        return price;
    }

    /**
     * Converts the given order and execution details into an OrderSubmissionResponse.
     * @param order the order entity containing order details
     * @param execution the execution entity containing execution details; null for an OPEN order
     * @param currentBalance the current account balance after the order execution
     * @return an OrderSubmissionResponse representing the order and execution details
     */
    private OrderSubmissionResponse toResponse(Order order, Execution execution, BigDecimal currentBalance) {
        OrderSubmissionResponse.ExecutionDto execDto = execution == null ? null : new OrderSubmissionResponse.ExecutionDto(
                execution.getExecutionId(),
                execution.getFillQuantity(),
                execution.getFillPrice(),
                execution.getStatus().name(),
                execution.getExecutedAt(),
                execution.getReason()
        );

        return new OrderSubmissionResponse(
                order.getOrderId(),
                order.getAccountId(),
                order.getAccountNumber(),
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
                currentBalance,
                order.getOrderType().name(),
                order.getLimitPrice(),
                order.getStopPrice(),
                order.getTimeInForce() != null ? order.getTimeInForce().name() : null,
                order.getExpiresAt(),
                order.getReservedCash()
        );
    }
}
