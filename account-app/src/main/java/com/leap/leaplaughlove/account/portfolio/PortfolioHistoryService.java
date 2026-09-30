package com.leap.leaplaughlove.account.portfolio;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.instrument.Instrument;
import com.leap.leaplaughlove.account.instrument.InstrumentRepository;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.account.position.PositionMovement;
import com.leap.leaplaughlove.account.position.PositionMovementRepository;
import com.leap.leaplaughlove.account.position.PositionRepository;
import com.leap.leaplaughlove.account.quote.PriceHistoryClient;
import com.leap.leaplaughlove.account.quote.PriceHistoryClient.CandleClose;
import com.leap.leaplaughlove.account.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.common.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Values the authenticated client's portfolio over a time range.
 *
 * <p>The walk runs backwards from what is known exactly - today's holdings and cash - undoing
 * each position movement and cash ledger entry as the timeline passes it. Walking back rather
 * than replaying forward from zero means holdings and cash that predate the ledgers (seeded
 * positions, opening balances) are still counted, as held for the whole range. Each holding is
 * priced from market data candles at that point, carrying the last close forward over gaps; the
 * final point is "now", priced at the live price so the chart ends on the value the rest of the
 * dashboard shows.
 */
@Service
public class PortfolioHistoryService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final PositionMovementRepository positionMovementRepository;
    private final CashLedgerRepository cashLedgerRepository;
    private final InstrumentRepository instrumentRepository;
    private final PriceHistoryClient priceHistoryClient;
    private final Clock clock;

    /**
     * Constructs a new PortfolioHistoryService on the system clock.
     */
    @Autowired
    public PortfolioHistoryService(AccountRepository accountRepository,
                                   PositionRepository positionRepository,
                                   PositionMovementRepository positionMovementRepository,
                                   CashLedgerRepository cashLedgerRepository,
                                   InstrumentRepository instrumentRepository,
                                   PriceHistoryClient priceHistoryClient) {
        this(accountRepository, positionRepository, positionMovementRepository, cashLedgerRepository,
                instrumentRepository, priceHistoryClient, Clock.systemUTC());
    }

    PortfolioHistoryService(AccountRepository accountRepository,
                            PositionRepository positionRepository,
                            PositionMovementRepository positionMovementRepository,
                            CashLedgerRepository cashLedgerRepository,
                            InstrumentRepository instrumentRepository,
                            PriceHistoryClient priceHistoryClient,
                            Clock clock) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.cashLedgerRepository = cashLedgerRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceHistoryClient = priceHistoryClient;
        this.clock = clock;
    }

    /**
     * Values every active account of the authenticated client, combined, across the range.
     * Accounts are summed in their base currencies without conversion.
     * @param range the range to chart
     * @return the portfolio value at each point of the range
     * @throws QuoteUnavailableException if market data could not be reached
     */
    public PortfolioHistoryResponse getHistory(PortfolioRange range) {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();
        List<Account> accounts = accountRepository.findByClientIdAndStatus(
                clientId, AccountAuthorizationService.ACTIVE_STATUS);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime earliestOpen = accounts.stream()
                .map(Account::getCreatedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        PortfolioRange.Window window = range.window(now, earliestOpen);
        List<OffsetDateTime> timeline = timeline(window, now);

        if (accounts.isEmpty()) {
            return toResponse(range, window, timeline, new BigDecimal[timeline.size()]);
        }

        Map<UUID, String> baseCurrencies = new HashMap<>();
        accounts.forEach(account -> baseCurrencies.put(account.getAccountId(), account.getBaseCurrency()));
        List<UUID> accountIds = List.copyOf(baseCurrencies.keySet());

        // Today's holdings and cash: the known end state the walk starts from.
        Map<UUID, Long> quantities = new HashMap<>();
        Map<UUID, String> symbols = new HashMap<>();
        for (UUID accountId : accountIds) {
            positionRepository.findPositionsByAccountId(accountId).forEach(row -> {
                UUID instrumentId = UUID.fromString(row.getInstrumentId());
                quantities.merge(instrumentId, row.getQuantity(), Long::sum);
                symbols.put(instrumentId, row.getSymbol());
            });
        }
        BigDecimal cash = cashLedgerRepository.sumAmountsByAccountIds(accountIds).stream()
                .filter(total -> total.getCurrency().equals(baseCurrencies.get(total.getAccountId())))
                .map(CashLedgerRepository.AccountTotal::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Everything that happened inside the range, newest first, to undo on the way back.
        List<PositionMovement> movements = new ArrayList<>(
                positionMovementRepository.findByAccountIdInAndCreatedAtAfter(accountIds, window.from()));
        movements.sort(Comparator.comparing(PositionMovement::getCreatedAt).reversed());
        List<CashLedgerEntry> entries = new ArrayList<>(
                cashLedgerRepository.findByAccountIdInAndCreatedAtAfter(accountIds, window.from()));
        entries.removeIf(entry -> !entry.getCurrency().equals(baseCurrencies.get(entry.getAccountId())));
        entries.sort(Comparator.comparing(CashLedgerEntry::getCreatedAt).reversed());

        // Only instruments held at some point in the range need pricing.
        Set<UUID> priced = new HashSet<>();
        quantities.forEach((instrumentId, quantity) -> {
            if (quantity != 0) {
                priced.add(instrumentId);
            }
        });
        movements.forEach(movement -> priced.add(movement.getInstrumentId()));
        Set<UUID> unnamed = new HashSet<>(priced);
        unnamed.removeAll(symbols.keySet());
        if (!unnamed.isEmpty()) {
            instrumentRepository.findAllById(unnamed)
                    .forEach(instrument -> symbols.put(instrument.getInstrumentId(), instrument.getSymbol()));
        }

        Map<UUID, BigDecimal[]> prices = new LinkedHashMap<>();
        for (UUID instrumentId : priced) {
            String symbol = symbols.get(instrumentId);
            if (symbol != null) {
                prices.put(instrumentId, priceSeries(symbol, window, timeline,
                        quantities.getOrDefault(instrumentId, 0L) != 0));
            }
        }

        BigDecimal[] values = new BigDecimal[timeline.size()];
        int movementIndex = 0;
        int entryIndex = 0;
        for (int k = timeline.size() - 1; k >= 0; k--) {
            OffsetDateTime at = timeline.get(k);
            while (movementIndex < movements.size() && movements.get(movementIndex).getCreatedAt().isAfter(at)) {
                PositionMovement movement = movements.get(movementIndex++);
                quantities.merge(movement.getInstrumentId(), -movement.getQuantityDelta(), Long::sum);
            }
            while (entryIndex < entries.size() && entries.get(entryIndex).getCreatedAt().isAfter(at)) {
                cash = cash.subtract(entries.get(entryIndex++).getAmount());
            }
            BigDecimal value = cash;
            for (Map.Entry<UUID, BigDecimal[]> price : prices.entrySet()) {
                long quantity = quantities.getOrDefault(price.getKey(), 0L);
                BigDecimal unitPrice = price.getValue()[k];
                if (quantity != 0 && unitPrice != null) {
                    value = value.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
                }
            }
            values[k] = value.setScale(2, RoundingMode.HALF_UP);
        }
        return toResponse(range, window, timeline, values);
    }

    /**
     * Builds the chart's timestamps: the end of each candle bucket from the range start up to
     * now, with "now" itself as the final point. Buckets are aligned to the epoch the same way
     * market data aligns candles, so point k lines up with the candle starting one width earlier.
     */
    static List<OffsetDateTime> timeline(PortfolioRange.Window window, OffsetDateTime now) {
        long width = window.intervalSeconds();
        long nowSeconds = now.toEpochSecond();
        long bucketStart = Math.floorDiv(window.from().toEpochSecond(), width) * width;
        List<OffsetDateTime> points = new ArrayList<>();
        for (long end = bucketStart + width; end < nowSeconds; end += width) {
            points.add(OffsetDateTime.ofInstant(Instant.ofEpochSecond(end), ZoneOffset.UTC));
        }
        points.add(now);
        return points;
    }

    /**
     * Prices one symbol at every timeline point: the close of the latest candle that ended at or
     * before the point, carried forward over gaps. Points before the first candle take that
     * candle's close, so a symbol with thin history doesn't drop out of the early chart. The final
     * point uses the live price when the instrument is currently held.
     */
    private BigDecimal[] priceSeries(String symbol, PortfolioRange.Window window,
                                     List<OffsetDateTime> timeline, boolean currentlyHeld) {
        int width = window.intervalSeconds();
        OffsetDateTime historyFrom = timeline.get(0).minusSeconds(width);
        List<CandleClose> closes = priceHistoryClient.fetchCloses(
                symbol, historyFrom, timeline.get(timeline.size() - 1), width);

        BigDecimal[] series = new BigDecimal[timeline.size()];
        int candle = 0;
        BigDecimal last = closes.isEmpty() ? null : closes.get(0).close();
        for (int k = 0; k < timeline.size(); k++) {
            OffsetDateTime at = timeline.get(k);
            while (candle < closes.size()
                    && !closes.get(candle).bucketStart().plusSeconds(width).isAfter(at)) {
                last = closes.get(candle++).close();
            }
            series[k] = last;
        }
        if (currentlyHeld) {
            priceHistoryClient.fetchLatestPrice(symbol)
                    .ifPresent(live -> series[series.length - 1] = live);
        }
        // No candles at all: the live price (if any) is the best estimate for every point.
        if (closes.isEmpty() && series[series.length - 1] != null) {
            Arrays.fill(series, series[series.length - 1]);
        }
        return series;
    }

    private static PortfolioHistoryResponse toResponse(PortfolioRange range, PortfolioRange.Window window,
                                                       List<OffsetDateTime> timeline, BigDecimal[] values) {
        List<PortfolioHistoryResponse.Point> points = new ArrayList<>(timeline.size());
        for (int k = 0; k < timeline.size(); k++) {
            BigDecimal value = values[k] != null ? values[k] : BigDecimal.ZERO.setScale(2);
            points.add(new PortfolioHistoryResponse.Point(timeline.get(k), value));
        }
        BigDecimal start = points.get(0).value();
        BigDecimal end = points.get(points.size() - 1).value();
        BigDecimal change = end.subtract(start);
        BigDecimal changePercent = start.signum() == 0 ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(start.abs(), 2, RoundingMode.HALF_UP);
        return new PortfolioHistoryResponse(range.code(), window.intervalSeconds(), points,
                start, end, change, changePercent);
    }
}

