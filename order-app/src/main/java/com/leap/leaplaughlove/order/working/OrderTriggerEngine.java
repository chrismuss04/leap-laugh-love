package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.submission.OrderSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watches OPEN orders against the live market and fills each once the market reaches its price.
 *
 * <p>Every second - the simulator's tick - it reads all live quotes in one call and evaluates
 * every open order (see {@link TriggerEvaluator}). Stale quotes are skipped, so an order never
 * fills on an old price. A fill runs through {@link OrderSubmissionService#fillOpenOrder}, the
 * same validation, settlement and recovery path as a market order; a STOP_LIMIT whose stop is
 * reached but whose limit isn't is only marked triggered, and keeps waiting as a limit order.
 *
 * <p>Only orders the market session allows are evaluated ({@link SessionPolicy}): outside regular
 * hours stops don't trigger and trailing stops don't trail, and only extended-hours limit orders
 * can fill in pre-market and after-hours.
 *
 * <p>A separate, slower schedule expires orders past their time in force.
 */
@Service
@ConditionalOnProperty(prefix = "trading.order-triggers", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class OrderTriggerEngine {

    private static final Logger log = LoggerFactory.getLogger(OrderTriggerEngine.class);

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final CurrentQuoteService currentQuoteService;
    private final OrderSubmissionService orderSubmissionService;
    private final OpenOrderService openOrderService;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;
    private final OrderUpdateBroadcaster broadcaster;
    private final MarketClock marketClock;

    /**
     * Constructs an OrderTriggerEngine.
     * @param orderRepository finds and locks open orders
     * @param accountRepository finds the client an order acts for
     * @param currentQuoteService reads the live quotes
     * @param orderSubmissionService fills an order whose price was reached
     * @param openOrderService expires orders past their time in force
     * @param jwtService issues the service tokens the engine calls other apps with
     * @param transactionTemplate marks stop-limit orders triggered and moves trailing stops
     * @param broadcaster tells clients, as it happens, that an order of theirs ended
     */
    public OrderTriggerEngine(OrderRepository orderRepository,
                              AccountRepository accountRepository,
                              CurrentQuoteService currentQuoteService,
                              OrderSubmissionService orderSubmissionService,
                              OpenOrderService openOrderService,
                              JwtService jwtService,
                              TransactionTemplate transactionTemplate,
                              OrderUpdateBroadcaster broadcaster) {
        this(orderRepository, accountRepository, currentQuoteService, orderSubmissionService, openOrderService,
                jwtService, transactionTemplate, broadcaster, MarketClock.alwaysOpen());
    }

    /**
     * Constructs an OrderTriggerEngine that only works orders in a session they may trade in.
     * @param orderRepository finds and locks open orders
     * @param accountRepository finds the client an order acts for
     * @param currentQuoteService reads the live quotes
     * @param orderSubmissionService fills an order whose price was reached
     * @param openOrderService expires orders past their time in force
     * @param jwtService issues the service tokens the engine calls other apps with
     * @param transactionTemplate marks stop-limit orders triggered and moves trailing stops
     * @param broadcaster tells clients, as it happens, that an order of theirs ended
     * @param marketClock decides which session is in force
     */
    @Autowired
    public OrderTriggerEngine(OrderRepository orderRepository,
                              AccountRepository accountRepository,
                              CurrentQuoteService currentQuoteService,
                              OrderSubmissionService orderSubmissionService,
                              OpenOrderService openOrderService,
                              JwtService jwtService,
                              TransactionTemplate transactionTemplate,
                              OrderUpdateBroadcaster broadcaster,
                              MarketClock marketClock) {
        this.marketClock = marketClock;
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.currentQuoteService = currentQuoteService;
        this.orderSubmissionService = orderSubmissionService;
        this.openOrderService = openOrderService;
        this.jwtService = jwtService;
        this.transactionTemplate = transactionTemplate;
        this.broadcaster = broadcaster;
    }

    /**
     * Scheduled entry point: evaluates every open order against the live market.
     */
    @Scheduled(initialDelayString = "${trading.order-triggers.initial-delay-ms:10000}",
               fixedDelayString = "${trading.order-triggers.interval-ms:1000}")
    public void run() {
        try {
            evaluateOpenOrders();
        } catch (RuntimeException ex) {
            log.warn("Evaluating open orders failed: {}", ex.getMessage());
        }
    }

    /**
     * Scheduled entry point: expires open orders past their time in force.
     */
    @Scheduled(initialDelayString = "${trading.order-triggers.initial-delay-ms:10000}",
               fixedDelayString = "${trading.order-triggers.expiry-interval-ms:30000}")
    public void expire() {
        try {
            openOrderService.expireDue();
        } catch (RuntimeException ex) {
            log.warn("Expiring open orders failed: {}", ex.getMessage());
        }
    }

    /**
     * Evaluates every open order against the live market once.
     * @return how many orders were sent to be filled
     */
    public int evaluateOpenOrders() {
        MarketSession session = marketClock.session();
        List<Order> open = orderRepository.findOpen().stream()
                .filter(order -> SessionPolicy.canExecute(order, session))
                .toList();
        if (open.isEmpty()) {
            return 0;
        }
        Map<String, QuoteSnapshot> quotes = currentQuoteService.getFreshQuotes(jwtService.generateQuoteToken());
        int filled = 0;
        for (Order order : open) {
            try {
                if (evaluate(order, quotes.get(order.getInstrument().getSymbol()))) {
                    filled++;
                }
            } catch (RuntimeException ex) {
                // One order failing (e.g. account-app down) must not hold up the rest.
                log.warn("Open order {} could not be processed; will retry: {}", order.getOrderId(), ex.getMessage());
            }
        }
        return filled;
    }

    /**
     * Evaluates one open order against its quote, filling it or marking its stop reached.
     * @param order the open order
     * @param quote its instrument's fresh quote, or null if there is none
     * @return whether the order was sent to be filled
     */
    private boolean evaluate(Order order, QuoteSnapshot quote) {
        BigDecimal price = TriggerEvaluator.executablePrice(order.getSide(), quote);
        if (price == null) {
            return false;
        }
        if (order.getOrderType().hasTrail() && !trail(order, price)) {
            return false;
        }
        TriggerEvaluator.Evaluation evaluation = TriggerEvaluator.evaluate(order, price);
        if (evaluation.fills()) {
            Order.Status status = orderSubmissionService.fillOpenOrder(order.getOrderId(), evaluation.fillPrice(),
                    evaluation.stopReached(), jwtService.generateSettlementToken(clientIdOf(order)));
            log.info("Open {} {} order {} reached its price at {}; now {}", order.getOrderType(), order.getSide(),
                    order.getOrderId(), evaluation.fillPrice(), status);
            if (status != null) {
                notifyClient(order, status, evaluation.fillPrice());
            }
            return status != null;
        }
        if (evaluation.stopReached() && order.getTriggeredAt() == null) {
            markTriggered(order.getOrderId());
        }
        return false;
    }

    /**
     * Tells the client their order ended, if they have the order stream open. Never fails the
     * fill: the order has already been decided.
     * @param order the order, with its instrument loaded
     * @param status how it ended
     * @param fillPrice the price it executed at
     */
    private void notifyClient(Order order, Order.Status status, BigDecimal fillPrice) {
        try {
            String reason = status == Order.Status.REJECTED
                    ? orderRepository.findById(order.getOrderId()).map(Order::getRejectionReason).orElse(null)
                    : null;
            broadcaster.publish(clientIdOf(order), OrderUpdate.of(order, status,
                    status == Order.Status.REJECTED ? null : fillPrice, reason));
        } catch (RuntimeException ex) {
            log.warn("Could not tell the client about order {}: {}", order.getOrderId(), ex.getMessage());
        }
    }

    /**
     * Moves a trailing stop behind a new best price, saving it under the order's row lock so a
     * cancel or edit can't be overwritten. The in-memory order is moved too, for the evaluation
     * that follows.
     * @param order the open trailing order
     * @param price the side's executable price now
     * @return false if the order is no longer OPEN, so there is nothing to evaluate
     */
    private boolean trail(Order order, BigDecimal price) {
        TrailingStop.Move move = TrailingStop.ratchet(order, price);
        if (move == null) {
            return true;
        }
        Boolean moved = transactionTemplate.execute(status -> orderRepository.findByIdForUpdate(order.getOrderId())
                .filter(locked -> locked.getStatus() == Order.Status.OPEN)
                .map(locked -> {
                    locked.moveTrail(move.best(), move.stop());
                    return true;
                })
                .orElse(false));
        if (Boolean.TRUE.equals(moved)) {
            order.moveTrail(move.best(), move.stop());
        }
        return Boolean.TRUE.equals(moved);
    }

    /**
     * Records that a stop-limit order's stop was reached, if it is still OPEN.
     * @param orderId the order
     */
    private void markTriggered(UUID orderId) {
        transactionTemplate.executeWithoutResult(status -> orderRepository.findByIdForUpdate(orderId)
                .filter(locked -> locked.getStatus() == Order.Status.OPEN)
                .ifPresent(locked -> {
                    locked.markTriggered(OffsetDateTime.now());
                    log.info("Stop-limit order {} triggered; now working as a limit order", orderId);
                }));
    }

    /**
     * The client who owns the order's account, whose behalf the fill settles on.
     * @param order the order
     * @return the client ID
     */
    private UUID clientIdOf(Order order) {
        return accountRepository.findById(order.getAccountId())
                .map(Account::getClientId)
                .orElseThrow(() -> new IllegalStateException("Account not found for order: " + order.getOrderId()));
    }
}
