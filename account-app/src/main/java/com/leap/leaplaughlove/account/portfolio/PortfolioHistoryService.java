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
import com.leap.leaplaughlove.common.marketdata.MarketDataPriceClient.CandleClose;
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
 * <p>
 * The walk runs backwards from what is known exactly - today's holdings and
 * cash - undoing
 * each position movement and cash ledger entry as the timeline passes it.
 * Walking back rather
 * than replaying forward from zero means holdings and cash that predate the
 * ledgers (seeded
 * positions, opening balances) are still counted, as held for the whole range.
 * Each holding is
 * priced from market data candles at that point, carrying the last close
 * forward over gaps; the
 * final point is "now", priced at the live price so the chart ends on the value
 * the rest of the
 * dashboard shows.
 *
 * <p>
 * Deposits and withdrawals move the value without being a gain or loss, so the
 * change over the range leaves them out.
 */
@Service
public class PortfolioHistoryService {

    /** Cash ledger entries that move money in or out of an account, rather than earn it. */
    private static final Set<String> CONTRIBUTION_TYPES = Set.of("DEPOSIT", "WITHDRAWAL");

    private final AccountRepository accountRepository;
    private final AccountAuthorizationService accountAuthorizationService;
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
            AccountAuthorizationService accountAuthorizationService,
            PositionRepository positionRepository,
            PositionMovementRepository positionMovementRepository,
            CashLedgerRepository cashLedgerRepository,
            InstrumentRepository instrumentRepository,
            PriceHistoryClient priceHistoryClient) {
        this(accountRepository, accountAuthorizationService, positionRepository, positionMovementRepository, cashLedgerRepository,
                instrumentRepository, priceHistoryClient, Clock.systemUTC());
    }

    /**
     * Constructs a PortfolioHistoryService with a given clock
     * 
     * @param accountRepository          The repository for accessing account data.
     * @param accountAuthorizationService Checks a single charted account belongs to
     *                                   the client.
     * @param positionRepository         The repository for accessing position data.
     * @param positionMovementRepository The repository for accessing position
     *                                   movement data.
     * @param cashLedgerRepository       The repository for accessing cash ledger
     *                                   entries.
     * @param instrumentRepository       The repository for accessing instrument
     *                                   data.
     * @param priceHistoryClient         The client for accessing price history
     *                                   data.
     * @param clock                      The clock to use for time-based operations.
     */
    PortfolioHistoryService(AccountRepository accountRepository,
            AccountAuthorizationService accountAuthorizationService,
            PositionRepository positionRepository,
            PositionMovementRepository positionMovementRepository,
            CashLedgerRepository cashLedgerRepository,
            InstrumentRepository instrumentRepository,
            PriceHistoryClient priceHistoryClient,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.positionRepository = positionRepository;
        this.positionMovementRepository = positionMovementRepository;
        this.cashLedgerRepository = cashLedgerRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceHistoryClient = priceHistoryClient;
        this.clock = clock;
    }

    /**
     * Values one of the authenticated client's accounts across the range, or every
     * active account combined when no account is given. Accounts are summed in their
     * base currencies without conversion.
     * 
     * @param range     the range to chart
     * @param accountId the account to chart, or null for all of them
     * @return the value at each point of the range
     * @throws org.springframework.web.server.ResponseStatusException 404 if the
     *         account isn't the client's, 400 if it isn't active
     * @throws QuoteUnavailableException if market data could not be reached
     */
    public PortfolioHistoryResponse getHistory(PortfolioRange range, UUID accountId) {
        List<Account> accounts = accountId != null
                ? List.of(accountAuthorizationService.getAuthorizedAccount(accountId))
                : accountRepository.findByClientIdAndStatus(
                        SecurityUtils.getAuthenticatedClientId(), AccountAuthorizationService.ACTIVE_STATUS);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime earliestOpen = accounts.stream()
                .map(Account::getCreatedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        PortfolioRange.Window window = range.window(now, earliestOpen);
        List<OffsetDateTime> timeline = timeline(window, now);

        if (accounts.isEmpty()) {
            return toResponse(range, window, timeline, new BigDecimal[timeline.size()],
                    new BigDecimal[timeline.size()]);
        }

        Map<UUID, String> baseCurrencies = new HashMap<>();
        accounts.forEach(account -> baseCurrencies.put(account.getAccountId(), account.getBaseCurrency()));
        List<UUID> accountIds = List.copyOf(baseCurrencies.keySet());

        // Today's holdings and cash: the known end state the walk starts from.
        Map<UUID, Long> quantities = new HashMap<>();
        Map<UUID, String> symbols = new HashMap<>();
        loadHoldings(accountIds, quantities, symbols);
        BigDecimal cash = currentCash(accountIds, baseCurrencies);

        // Everything that happened inside the range, newest first, to undo on the way
        // back.
        List<PositionMovement> movements = new ArrayList<>(
                positionMovementRepository.findByAccountIdInAndCreatedAtAfter(accountIds, window.from()));
        movements.sort(Comparator.comparing(PositionMovement::getCreatedAt).reversed());
        List<CashLedgerEntry> entries = new ArrayList<>(
                cashLedgerRepository.findByAccountIdInAndCreatedAtAfter(accountIds, window.from()));
        entries.removeIf(entry -> !entry.getCurrency().equals(baseCurrencies.get(entry.getAccountId())));
        entries.sort(Comparator.comparing(CashLedgerEntry::getCreatedAt).reversed());

        Map<UUID, BigDecimal[]> prices = priceHeldInstruments(quantities, symbols, movements, window, timeline);
        BigDecimal[] contributions = contributionsAt(timeline, entries);
        BigDecimal[] values = walkBack(timeline, quantities, cash, movements, entries, prices);
        return toResponse(range, window, timeline, values, contributions);
    }

    /**
     * Money deposited less money withdrawn between the first point and each point, so a point's
     * gain or loss can leave it out. Between accounts that are charted together a transfer's two
     * sides cancel out; for one account it counts.
     */
    private static BigDecimal[] contributionsAt(List<OffsetDateTime> timeline, List<CashLedgerEntry> entries) {
        OffsetDateTime start = timeline.get(0);
        BigDecimal[] totals = new BigDecimal[timeline.size()];
        for (int k = 0; k < timeline.size(); k++) {
            OffsetDateTime at = timeline.get(k);
            totals[k] = entries.stream()
                    .filter(entry -> CONTRIBUTION_TYPES.contains(entry.getEntryType()))
                    .filter(entry -> entry.getCreatedAt().isAfter(start) && !entry.getCreatedAt().isAfter(at))
                    .map(CashLedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return totals;
    }

    /** Today's quantity and symbol of every instrument held across the accounts. */
    private void loadHoldings(List<UUID> accountIds, Map<UUID, Long> quantities, Map<UUID, String> symbols) {
        for (UUID accountId : accountIds) {
            positionRepository.findPositionsByAccountId(accountId).forEach(row -> {
                UUID instrumentId = UUID.fromString(row.getInstrumentId());
                quantities.merge(instrumentId, row.getQuantity(), Long::sum);
                symbols.put(instrumentId, row.getSymbol());
            });
        }
    }

    /** Today's cash across the accounts, counting only each account's base currency. */
    private BigDecimal currentCash(List<UUID> accountIds, Map<UUID, String> baseCurrencies) {
        return cashLedgerRepository.sumAmountsByAccountIds(accountIds).stream()
                .filter(total -> total.getCurrency().equals(baseCurrencies.get(total.getAccountId())))
                .map(CashLedgerRepository.AccountTotal::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Prices every instrument held at some point in the range; the rest need none. */
    private Map<UUID, BigDecimal[]> priceHeldInstruments(Map<UUID, Long> quantities, Map<UUID, String> symbols,
            List<PositionMovement> movements, PortfolioRange.Window window, List<OffsetDateTime> timeline) {
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
        return prices;
    }

    /** Walks the timeline back from today, undoing movements and cash entries to value each point. */
    private static BigDecimal[] walkBack(List<OffsetDateTime> timeline, Map<UUID, Long> quantities,
            BigDecimal cash, List<PositionMovement> movements, List<CashLedgerEntry> entries,
            Map<UUID, BigDecimal[]> prices) {
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
            values[k] = valueAt(cash, quantities, prices, k).setScale(2, RoundingMode.HALF_UP);
        }
        return values;
    }

    private static BigDecimal valueAt(BigDecimal cash, Map<UUID, Long> quantities,
            Map<UUID, BigDecimal[]> prices, int k) {
        BigDecimal value = cash;
        for (Map.Entry<UUID, BigDecimal[]> price : prices.entrySet()) {
            long quantity = quantities.getOrDefault(price.getKey(), 0L);
            BigDecimal unitPrice = price.getValue()[k];
            if (quantity != 0 && unitPrice != null) {
                value = value.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            }
        }
        return value;
    }

    /**
     * Builds the chart's timestamps: the end of each candle bucket from the range
     * start up to now, with "now" itself as the final point. Buckets are aligned to
     * the epoch the same way market data aligns candles, so point k lines up with
     * the candle starting one width earlier. A range that starts on a bucket
     * boundary - 1D at the previous close - gets a point there, valued at the close before it.
     * 
     * @param window The price history window.
     * @param now    The current time.
     * @return A list of timestamps for each point in the history.
     */
    static List<OffsetDateTime> timeline(PortfolioRange.Window window, OffsetDateTime now) {
        long width = window.intervalSeconds();
        long nowSeconds = now.toEpochSecond();
        long firstEnd = -Math.floorDiv(-window.from().toEpochSecond(), width) * width;
        List<OffsetDateTime> points = new ArrayList<>();
        for (long end = firstEnd; end < nowSeconds; end += width) {
            points.add(OffsetDateTime.ofInstant(Instant.ofEpochSecond(end), ZoneOffset.UTC));
        }
        points.add(now);
        return points;
    }

    /**
     * Prices one symbol at every timeline point: the close of the latest candle
     * that ended at or before the point, carried forward over gaps. Points before
     * the first candle take that candle's close, so a symbol with thin history
     * doesn't drop out of the early chart. The final point uses the live price when
     * the instrument is currently held.
     * 
     * @param symbol        The instrument symbol to fetch prices for.
     * @param window        The price history window.
     * @param timeline      The timeline of timestamps to generate prices for.
     * @param currentlyHeld Whether the instrument is currently held.
     * @return An array of prices corresponding to each timestamp in the timeline.
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
        // No candles at all: the live price (if any) is the best estimate for every
        // point.
        if (closes.isEmpty() && series[series.length - 1] != null) {
            Arrays.fill(series, series[series.length - 1]);
        }
        return series;
    }

    /**
     * Converts the timeline and values into the response DTO.
     * 
     * @param range    The original range requested by the client.
     * @param window   The effective window used for the calculation.
     * @param timeline The timestamps for each point in the history.
     * @param values   The calculated portfolio values at each timestamp.
     * @param contributions Deposits less withdrawals from the first point to each one.
     * @return The PortfolioHistoryResponse containing the history data.
     */
    private static PortfolioHistoryResponse toResponse(PortfolioRange range, PortfolioRange.Window window,
            List<OffsetDateTime> timeline, BigDecimal[] values, BigDecimal[] contributions) {
        List<PortfolioHistoryResponse.Point> points = new ArrayList<>(timeline.size());
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        for (int k = 0; k < timeline.size(); k++) {
            points.add(new PortfolioHistoryResponse.Point(timeline.get(k),
                    values[k] != null ? values[k] : zero,
                    contributions[k] != null ? contributions[k] : zero));
        }
        BigDecimal start = points.get(0).value();
        BigDecimal end = points.get(points.size() - 1).value();
        BigDecimal netContributions = points.get(points.size() - 1).contributions();
        BigDecimal change = end.subtract(start).subtract(netContributions);
        // Measured against everything put in, so a range that starts empty and is funded part way
        // through still has a percentage.
        BigDecimal invested = start.add(netContributions);
        BigDecimal changePercent = invested.signum() <= 0 ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(invested, 2, RoundingMode.HALF_UP);
        return new PortfolioHistoryResponse(range.code(), window.intervalSeconds(), points,
                start, end, netContributions, change, changePercent);
    }
}
