package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import com.leap.leaplaughlove.order.quote.PriceHistoryClient;
import com.leap.leaplaughlove.order.quote.PriceHistoryClient.CandleClose;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Books the fills of seeded orders, priced from market data at the moment each one filled.
 *
 * <p>The seed SQL can't do this itself: it runs when the database is created, before
 * market-data-app has generated any price history, and the fill ledgers are append-only so a
 * placeholder price can't be corrected later. So the seed inserts filled orders with only a
 * {@code filled_at}, and this books each one - execution, cash settlement, position movement,
 * holding - through the same {@link FillRecorder} steps live orders use, at the close of the last
 * candle that ended at or before the fill. That is the price the portfolio chart values the
 * holding at, so the chart moves smoothly through the trade instead of jumping by the gap
 * between a hard-coded fill price and the simulated market.
 *
 * <p>Market data may still be starting, or still backfilling, when this app is ready, so the
 * work runs on its own thread and retries until every pending fill is booked. It is a no-op once
 * nothing is pending, which is every boot after the first.
 */
@Service
@ConditionalOnProperty(prefix = "trading.seeded-fills", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SeededFillService {

    private static final Logger log = LoggerFactory.getLogger(SeededFillService.class);

    /** Candle widths market data serves, finest first; the finest that covers a fill wins. */
    private static final int[] CANDLE_WIDTHS = {60, 300, 3600, 86400};

    /** Live fills are priced to 4 decimal places; seeded ones match. */
    private static final int PRICE_SCALE = 4;

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final ExecutionRepository executionRepository;
    private final PositionMovementRepository positionMovementRepository;
    private final FillRecorder fillRecorder;
    private final PriceHistoryClient priceHistoryClient;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;
    private final long retrySeconds;
    private final int maxAttempts;

    public SeededFillService(OrderRepository orderRepository,
                             AccountRepository accountRepository,
                             ExecutionRepository executionRepository,
                             PositionMovementRepository positionMovementRepository,
                             FillRecorder fillRecorder,
                             PriceHistoryClient priceHistoryClient,
                             JwtService jwtService,
                             TransactionTemplate transactionTemplate,
                             @Value("${trading.seeded-fills.retry-seconds:10}") long retrySeconds,
                             @Value("${trading.seeded-fills.max-attempts:60}") int maxAttempts) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.executionRepository = executionRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.fillRecorder = fillRecorder;
        this.priceHistoryClient = priceHistoryClient;
        this.jwtService = jwtService;
        this.transactionTemplate = transactionTemplate;
        this.retrySeconds = retrySeconds;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Starts booking pending seeded fills in the background once the app is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread worker = new Thread(this::bookUntilDone, "seeded-fills");
        worker.setDaemon(true);
        worker.start();
    }

    private void bookUntilDone() {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            int remaining;
            try {
                remaining = bookPendingFills();
            } catch (RuntimeException ex) {
                log.warn("Booking seeded fills failed (attempt {}/{}): {}", attempt, maxAttempts, ex.getMessage());
                remaining = -1;
            }
            if (remaining == 0) {
                return;
            }
            try {
                Thread.sleep(retrySeconds * 1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Gave up booking seeded fills after {} attempts; they will be retried on the next start",
                maxAttempts);
    }

    /**
     * Books every pending fill that market data can price now, in fill-time order.
     * @return how many pending fills are still unbooked
     */
    int bookPendingFills() {
        List<Order> pending = orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED);
        // A holding's average cost depends on the order of its fills, so once one fill for an
        // account/instrument can't be priced yet, its later fills wait for it.
        Set<String> blocked = new HashSet<>();
        int booked = 0;
        for (Order order : pending) {
            String holding = order.getAccountId() + "/" + order.getInstrument().getInstrumentId();
            if (blocked.contains(holding)) {
                continue;
            }
            Optional<BigDecimal> price = order.getFilledAt() == null ? Optional.empty() : priceAt(order);
            if (price.isEmpty()) {
                blocked.add(holding);
                continue;
            }
            try {
                if (book(order, price.get())) {
                    booked++;
                }
            } catch (RuntimeException ex) {
                // e.g. a seeded sell with no holding to settle against (LLL-133). The fill stays
                // pending and is retried on the next pass; hold back this holding's later fills
                // and carry on with the rest.
                log.warn("Could not book seeded fill for order {}: {}", order.getOrderId(), ex.getMessage());
                blocked.add(holding);
            }
        }
        int remaining = pending.size() - booked;
        if (booked > 0) {
            log.info("Booked {} seeded fill(s) at market data prices", booked);
        }
        if (remaining > 0) {
            log.info("{} seeded fill(s) waiting for market data price history", remaining);
        }
        return remaining;
    }

    /**
     * Books one fill in FillRecorder's three steps, committing between them. A fill whose
     * booking stopped part-way - its execution written but settling failed - is picked up again
     * here and settled with that same execution, which account-app settles only once.
     */
    private boolean book(Order order, BigDecimal price) {
        Execution execution = transactionTemplate.execute(status -> {
            Optional<Order> locked = orderRepository.findByIdForUpdate(order.getOrderId());
            // Another instance may have booked it between the query and the lock.
            if (locked.isEmpty() || positionMovementRepository.existsByOrderId(order.getOrderId())) {
                return null;
            }
            return executionRepository.findFirstByOrder_OrderIdAndStatus(order.getOrderId(), Execution.Status.FILLED)
                    .orElseGet(() -> fillRecorder.recordExecution(locked.get(), price, locked.get().getFilledAt()));
        });
        if (execution == null) {
            return false;
        }

        // no request is behind this, so there is no caller's token to forward; sign as the order's
        // owner, whose request this fill would have been.
        fillRecorder.settle(order, execution, jwtService.generateToken(getClientId(order), null));

        Boolean booked = transactionTemplate.execute(status -> {
            orderRepository.findByIdForUpdate(order.getOrderId());
            if (positionMovementRepository.existsByOrderId(order.getOrderId())) {
                return false;
            }
            fillRecorder.recordPositionMovement(order, execution);
            return true;
        });
        return Boolean.TRUE.equals(booked);
    }

    /**
     * Prices a fill at the close of the last candle that ended at or before it, using the
     * finest candle width market data still holds for that moment.
     * @param order the filled order
     * @return the fill price, or empty if market data has no history covering the fill yet
     */
    private Optional<BigDecimal> priceAt(Order order) {
        OffsetDateTime filledAt = order.getFilledAt();
        String symbol = order.getInstrument().getSymbol();
        // market data only needs a valid token; sign it as the order's owner, whose request
        // this fill would have been.
        String token = jwtService.generateToken(getClientId(order), null);
        try {
            for (int width : CANDLE_WIDTHS) {
                List<CandleClose> closes = priceHistoryClient.fetchCloses(
                        symbol, filledAt.minusSeconds(2L * width), filledAt, width, token);
                Optional<BigDecimal> close = closes.stream()
                        .filter(candle -> !candle.bucketStart().plusSeconds(width).isAfter(filledAt))
                        .reduce((earlier, later) -> later)
                        .map(CandleClose::close);
                if (close.isPresent()) {
                    return close.map(value -> value.setScale(PRICE_SCALE, RoundingMode.HALF_UP));
                }
            }
        } catch (QuoteUnavailableException ex) {
            log.debug("Market data unavailable pricing seeded fill for {}: {}", symbol, ex.getMessage());
        }
        return Optional.empty();
    }

    private UUID getClientId(Order order) {
        return accountRepository.findById(order.getAccountId())
                .map(Account::getClientId)
                .orElseThrow(() -> new IllegalStateException("Account not found for order: " + order.getOrderId()));
    }
}
