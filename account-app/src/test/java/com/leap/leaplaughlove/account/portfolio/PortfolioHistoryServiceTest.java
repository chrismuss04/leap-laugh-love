package com.leap.leaplaughlove.account.portfolio;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.account.AccountRepository;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.account.instrument.InstrumentRepository;
import com.leap.leaplaughlove.account.position.PositionMovement;
import com.leap.leaplaughlove.account.position.PositionMovementRepository;
import com.leap.leaplaughlove.account.position.PositionRepository;
import com.leap.leaplaughlove.account.quote.PriceHistoryClient;
import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import com.leap.leaplaughlove.common.marketdata.MarketDataPriceClient.CandleClose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioHistoryService Unit Tests")
class PortfolioHistoryServiceTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AAPL_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");
    /** A Saturday: 1D is still Friday's trading day, from Thursday's 16:00 ET (21:00 UTC) close. */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-10T12:00:00Z");
    private static final OffsetDateTime PREVIOUS_CLOSE = OffsetDateTime.parse("2026-01-08T21:00:00Z");
    private static final OffsetDateTime BUY_TIME = OffsetDateTime.parse("2026-01-10T03:00:00Z");

    @Mock private AccountRepository accountRepository;
    @Mock private AccountAuthorizationService accountAuthorizationService;
    @Mock private PositionRepository positionRepository;
    @Mock private PositionMovementRepository positionMovementRepository;
    @Mock private CashLedgerRepository cashLedgerRepository;
    @Mock private InstrumentRepository instrumentRepository;
    @Mock private PriceHistoryClient priceHistoryClient;

    private PortfolioHistoryService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CLIENT_ID, null, List.of()));
        // Market hours off, so the walk back is tested across every hour, as in e2e runs.
        service = serviceAt(NOW, MarketClock.Mode.ALWAYS_OPEN);
    }

    private PortfolioHistoryService serviceAt(OffsetDateTime now, MarketClock.Mode mode) {
        return new PortfolioHistoryService(accountRepository, accountAuthorizationService, positionRepository, positionMovementRepository,
                cashLedgerRepository, instrumentRepository, priceHistoryClient,
                new MarketClock(Clock.fixed(now.toInstant(), ZoneOffset.UTC), mode, null, null));
    }

    private static List<MarketSession> sessions(PortfolioHistoryResponse response) {
        return response.points().stream().map(PortfolioHistoryResponse.Point::session).toList();
    }

    private static boolean hasPointAt(PortfolioHistoryResponse response, String timestamp) {
        OffsetDateTime at = OffsetDateTime.parse(timestamp);
        return response.points().stream().anyMatch(point -> point.timestamp().isEqual(at));
    }

    /** n copies of a session, for comparing a run of points. */
    private static List<MarketSession> times(int n, MarketSession session) {
        return Collections.nCopies(n, session);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void givenAccount() {
        when(accountRepository.findByClientIdAndStatus(CLIENT_ID, "ACTIVE")).thenReturn(List.of(
                new Account(ACCOUNT_ID, CLIENT_ID, "ACC-1", "ACTIVE", "USD", true,
                        OffsetDateTime.parse("2025-01-01T00:00:00Z"))));
    }

    private void givenHolding(long quantity) {
        PositionRepository.PositionRow row = mock(PositionRepository.PositionRow.class);
        when(row.getInstrumentId()).thenReturn(AAPL_ID.toString());
        when(row.getSymbol()).thenReturn("AAPL");
        when(row.getQuantity()).thenReturn(quantity);
        when(positionRepository.findPositionsByAccountId(ACCOUNT_ID)).thenReturn(List.of(row));
    }

    private void givenCash(String amount) {
        CashLedgerRepository.AccountTotal total = mock(CashLedgerRepository.AccountTotal.class);
        when(total.getAccountId()).thenReturn(ACCOUNT_ID);
        when(total.getCurrency()).thenReturn("USD");
        when(total.getTotal()).thenReturn(new BigDecimal(amount));
        when(cashLedgerRepository.sumAmountsByAccountIds(anyList())).thenReturn(List.of(total));
    }

    private static BigDecimal valueAt(PortfolioHistoryResponse response, String timestamp) {
        OffsetDateTime at = OffsetDateTime.parse(timestamp);
        return response.points().stream()
                .filter(point -> point.timestamp().isEqual(at))
                .findFirst()
                .orElseThrow()
                .value();
    }

    @Test
    @DisplayName("walks holdings and cash back through a buy and prices each point from candles")
    void valuesPortfolioAcrossABuy() {
        givenAccount();
        givenHolding(10);
        givenCash("1000.00");
        when(positionMovementRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of(
                new PositionMovement(ACCOUNT_ID, AAPL_ID, null, null, "BUY_FILL", 10, new BigDecimal("1000.00"), BUY_TIME)));
        when(cashLedgerRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of(
                new CashLedgerEntry(ACCOUNT_ID, "BUY_SETTLEMENT", new BigDecimal("-1000.00"), "USD", BUY_TIME, null)));
        when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), any(), eq(300))).thenReturn(List.of(
                new CandleClose(OffsetDateTime.parse("2026-01-09T11:55:00Z"), new BigDecimal("100")),
                new CandleClose(OffsetDateTime.parse("2026-01-10T06:00:00Z"), new BigDecimal("110"))));
        when(priceHistoryClient.fetchLatestPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("120")));

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_DAY, null);

        assertEquals("1D", response.range());
        assertEquals(300, response.intervalSeconds());
        // 5-minute bucket ends from the previous close, plus "now" as the final point.
        assertEquals(39 * 12 + 1, response.points().size());
        assertEquals(PREVIOUS_CLOSE, response.points().get(0).timestamp());
        assertEquals(NOW, response.points().get(response.points().size() - 1).timestamp());
        // Market hours off: trading runs around the clock, so every point is in the regular session.
        assertTrue(sessions(response).stream().allMatch(MarketSession.REGULAR::equals));
        assertNull(response.hours());
        // Before the buy: all cash, nothing held.
        assertEquals(new BigDecimal("2000.00"), valueAt(response, "2026-01-10T00:00:00Z"));
        // The buy itself: cash moved into 10 shares at the carried-forward 100 close.
        assertEquals(new BigDecimal("2000.00"), valueAt(response, "2026-01-10T03:00:00Z"));
        // The 06:00 candle only counts once its bucket has ended.
        assertEquals(new BigDecimal("2000.00"), valueAt(response, "2026-01-10T06:00:00Z"));
        assertEquals(new BigDecimal("2100.00"), valueAt(response, "2026-01-10T06:05:00Z"));
        // Now: priced at the live price.
        assertEquals(new BigDecimal("2200.00"), response.endValue());
        assertEquals(new BigDecimal("2000.00"), response.startValue());
        assertEquals(new BigDecimal("0.00"), response.netContributions());
        assertEquals(new BigDecimal("200.00"), response.change());
        assertEquals(new BigDecimal("10.00"), response.changePercent());
    }

    @Test
    @DisplayName("leaves deposits and withdrawals out of the change")
    void excludesContributionsFromChange() {
        givenAccount();
        givenHolding(10);
        givenCash("1500.00");
        when(positionMovementRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of());
        when(cashLedgerRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of(
                new CashLedgerEntry(ACCOUNT_ID, "DEPOSIT", new BigDecimal("700.00"), "USD",
                        OffsetDateTime.parse("2026-01-10T06:00:00Z"), null),
                new CashLedgerEntry(ACCOUNT_ID, "WITHDRAWAL", new BigDecimal("-200.00"), "USD",
                        OffsetDateTime.parse("2026-01-10T07:00:00Z"), null)));
        when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), any(), eq(300))).thenReturn(List.of(
                new CandleClose(OffsetDateTime.parse("2026-01-09T23:55:00Z"), new BigDecimal("100"))));
        when(priceHistoryClient.fetchLatestPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("120")));

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_DAY, null);

        // 1,000 cash + 10 @ 100 at the previous close; 1,500 cash + 10 @ 120 now, 500 of it deposited.
        assertEquals(new BigDecimal("2000.00"), response.startValue());
        assertEquals(new BigDecimal("2700.00"), response.endValue());
        assertEquals(new BigDecimal("500.00"), response.netContributions());
        // Each point carries what had been put in by then, for a hovered point's own change.
        assertEquals(new BigDecimal("0.00"), response.points().get(0).contributions());
        assertEquals(new BigDecimal("700.00"),
                response.points().stream().filter(p -> p.timestamp().isEqual(OffsetDateTime.parse("2026-01-10T06:30:00Z")))
                        .findFirst().orElseThrow().contributions());
        assertEquals(new BigDecimal("200.00"), response.change());
        // Against the 2,500 put in by the end.
        assertEquals(new BigDecimal("8.00"), response.changePercent());
    }

    @Test
    @DisplayName("keeps a young account's week whole, leading in flat at the value it was funded with")
    void leadsInFlatBeforeAYoungAccountWasFunded() {
        // Opened at 8:30, funded at 10:03, a stock bought at 11:00.
        OffsetDateTime opened = OffsetDateTime.parse("2026-01-10T08:30:00Z");
        OffsetDateTime funded = OffsetDateTime.parse("2026-01-10T10:03:00Z");
        OffsetDateTime bought = OffsetDateTime.parse("2026-01-10T11:00:00Z");
        when(accountAuthorizationService.getAuthorizedAccount(ACCOUNT_ID)).thenReturn(
                new Account(ACCOUNT_ID, CLIENT_ID, "ACC-2", "ACTIVE", "USD", true, opened));
        givenHolding(10);
        givenCash("0.00");
        when(positionMovementRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of(
                new PositionMovement(ACCOUNT_ID, AAPL_ID, null, null, "BUY_FILL", 10, new BigDecimal("1000.00"), bought)));
        when(cashLedgerRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of(
                new CashLedgerEntry(ACCOUNT_ID, "DEPOSIT", new BigDecimal("1000.00"), "USD", funded, null),
                new CashLedgerEntry(ACCOUNT_ID, "BUY_SETTLEMENT", new BigDecimal("-1000.00"), "USD", bought, null)));
        // Up on the day, but slipped since the buy.
        when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), any(), eq(3600))).thenReturn(List.of(
                new CandleClose(OffsetDateTime.parse("2026-01-10T09:00:00Z"), new BigDecimal("90")),
                new CandleClose(OffsetDateTime.parse("2026-01-10T10:00:00Z"), new BigDecimal("100"))));
        when(priceHistoryClient.fetchLatestPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("99")));

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_WEEK, ACCOUNT_ID);

        verify(accountRepository, never()).findByClientIdAndStatus(any(), any());
        // The whole week, at the week's own width.
        assertEquals(3600, response.intervalSeconds());
        assertEquals(OffsetDateTime.parse("2026-01-03T12:00:00Z"), response.points().get(0).timestamp());
        // Before it was funded - including the hour it was open but empty - flat at what it was
        // funded with, flagged; from 11:00, its own history.
        PortfolioHistoryResponse.Point first = response.points().get(0);
        assertTrue(first.beforeFunding());
        assertEquals(new BigDecimal("1000.00"), first.value());
        assertTrue(response.points().stream()
                .filter(point -> point.timestamp().isEqual(OffsetDateTime.parse("2026-01-10T10:00:00Z")))
                .findFirst().orElseThrow().beforeFunding());
        assertFalse(response.points().stream()
                .filter(point -> point.timestamp().isEqual(bought))
                .findFirst().orElseThrow().beforeFunding());
        // The deposit is the baseline rather than a gain.
        assertEquals(new BigDecimal("1000.00"), response.startValue());
        assertEquals(new BigDecimal("0.00"), response.netContributions());
        assertEquals(new BigDecimal("990.00"), response.endValue());
        assertEquals(new BigDecimal("-10.00"), response.change());
        assertEquals(new BigDecimal("-1.00"), response.changePercent());
    }

    @Test
    @DisplayName("1D on a Monday morning draws from the pre-market open, tagging each point with its session")
    void oneDaySkipsClosedHours() {
        // Monday 12 January 2026, 10:00 ET (EST, UTC-5): the day runs from Friday's 16:00 ET close.
        service = serviceAt(OffsetDateTime.parse("2026-01-12T15:00:00Z"), MarketClock.Mode.REAL);
        givenAccount();
        givenCash("1000.00");

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_DAY, null);

        List<MarketSession> expected = new ArrayList<>();
        // Friday's close is the baseline, and closes the regular session.
        expected.add(MarketSession.REGULAR);
        // Friday's last hour of after-hours as a lead-in, then Monday 4:05-9:30 ET, 9:35-9:55 ET and
        // now: not the rest of Friday's after-hours, or the weekend.
        expected.addAll(times(12, MarketSession.AFTER_HOURS));
        expected.addAll(times(66, MarketSession.PRE_MARKET));
        expected.addAll(times(6, MarketSession.REGULAR));
        assertEquals(expected, sessions(response));
        assertEquals(OffsetDateTime.parse("2026-01-09T21:00:00Z"), response.points().get(0).timestamp());
        assertEquals(OffsetDateTime.parse("2026-01-10T00:05:00Z"), response.points().get(1).timestamp());
        assertEquals(OffsetDateTime.parse("2026-01-12T09:05:00Z"), response.points().get(13).timestamp());
        assertFalse(hasPointAt(response, "2026-01-09T23:00:00Z"));
        assertFalse(hasPointAt(response, "2026-01-10T12:00:00Z"));
        // Monday's hours, for the chart to frame the day by time.
        assertEquals(new PortfolioHistoryResponse.Hours(OffsetDateTime.parse("2026-01-12T09:00:00Z"),
                OffsetDateTime.parse("2026-01-12T14:30:00Z"), OffsetDateTime.parse("2026-01-12T21:00:00Z"),
                OffsetDateTime.parse("2026-01-13T01:00:00Z")), response.hours());
        // Skipping points doesn't change what the day is measured from.
        assertEquals(new BigDecimal("1000.00"), response.startValue());
        assertEquals(new BigDecimal("0.00"), response.change());
    }

    @Test
    @DisplayName("1D on a weekend charts Friday's whole day and ends on now, in the closed session")
    void oneDayOnAWeekendEndsClosed() {
        service = serviceAt(NOW, MarketClock.Mode.REAL);
        givenAccount();
        givenCash("1000.00");

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_DAY, null);

        List<MarketSession> sessions = sessions(response);
        // Thursday's close and last hour, then Friday's pre-market, regular day and after-hours, then now.
        assertEquals(1 + 12 + 66 + 78 + 48 + 1, sessions.size());
        assertEquals(OffsetDateTime.parse("2026-01-09T09:05:00Z"), response.points().get(13).timestamp());
        assertEquals(MarketSession.CLOSED, sessions.get(sessions.size() - 1));
        assertEquals(NOW, response.points().get(sessions.size() - 1).timestamp());
        assertEquals(OffsetDateTime.parse("2026-01-10T01:00:00Z"), response.points().get(sessions.size() - 2).timestamp());
        assertEquals(1, sessions.stream().filter(MarketSession.CLOSED::equals).count());
        // Friday's hours: the trading day under way on a Saturday.
        assertEquals(OffsetDateTime.parse("2026-01-09T14:30:00Z"), response.hours().regularOpen());
    }

    @Test
    @DisplayName("1D after a holiday skips the holiday as well as the weekend")
    void oneDaySkipsHolidays() {
        // Tuesday 20 January 2026, 10:00 ET, after Martin Luther King Jr. Day.
        service = serviceAt(OffsetDateTime.parse("2026-01-20T15:00:00Z"), MarketClock.Mode.REAL);
        givenAccount();
        givenCash("1000.00");

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_DAY, null);

        assertEquals(OffsetDateTime.parse("2026-01-16T21:00:00Z"), response.points().get(0).timestamp());
        assertEquals(1 + 12 + 66 + 6, response.points().size());
        assertEquals(OffsetDateTime.parse("2026-01-20T09:05:00Z"), response.points().get(13).timestamp());
        assertFalse(response.points().stream()
                .anyMatch(point -> point.timestamp().toLocalDate().equals(LocalDate.of(2026, 1, 19))));
    }

    /** A clock a test can move, so a pinned session can begin before now. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** A pre-market pinned at midnight ET after Tuesday 13 January 2026, an hour ago. */
    private PortfolioHistoryResponse pinnedPreMarketHistory(Duration pinFor) {
        MovableClock time = new MovableClock(Instant.parse("2026-01-14T05:00:00Z"));
        MarketClock pinned = new MarketClock(time, MarketClock.Mode.REAL, MarketSession.PRE_MARKET, pinFor);
        time.now = Instant.parse("2026-01-14T06:00:00Z");
        service = new PortfolioHistoryService(accountRepository, accountAuthorizationService, positionRepository,
                positionMovementRepository, cashLedgerRepository, instrumentRepository, priceHistoryClient, pinned);
        givenAccount();
        givenCash("1000.00");
        return service.getHistory(PortfolioRange.ONE_DAY, null);
    }

    @Test
    @DisplayName("a pinned pre-market is charted as a new day from the pin")
    void pinnedPreMarketIsANewDay() {
        PortfolioHistoryResponse response = pinnedPreMarketHistory(null);

        List<MarketSession> expected = new ArrayList<>();
        // The calendar's previous close is still the baseline (at 1 AM it's still Tuesday's trading
        // day, from Monday's close), so the chart agrees with today's return; then the pinned hour,
        // though the calendar says closed, led into by Tuesday's last hour of after-hours.
        expected.add(MarketSession.REGULAR);
        expected.addAll(times(12, MarketSession.AFTER_HOURS));
        expected.addAll(times(12, MarketSession.PRE_MARKET));
        assertEquals(expected, sessions(response));
        assertEquals(OffsetDateTime.parse("2026-01-12T21:00:00Z"), response.points().get(0).timestamp());
        assertEquals(OffsetDateTime.parse("2026-01-14T00:05:00Z"), response.points().get(1).timestamp());
        assertEquals(OffsetDateTime.parse("2026-01-14T05:05:00Z"), response.points().get(13).timestamp());
        // Laid out like a usual day from the pin: the open five and a half hours on.
        assertEquals(new PortfolioHistoryResponse.Hours(OffsetDateTime.parse("2026-01-14T05:00:00Z"),
                OffsetDateTime.parse("2026-01-14T10:30:00Z"), OffsetDateTime.parse("2026-01-14T17:00:00Z"),
                OffsetDateTime.parse("2026-01-14T21:00:00Z")), response.hours());
    }

    @Test
    @DisplayName("a pinned pre-market that ends opens the regular session then")
    void pinnedPreMarketOpens() {
        PortfolioHistoryResponse response = pinnedPreMarketHistory(Duration.ofMinutes(30));

        List<MarketSession> expected = new ArrayList<>();
        expected.add(MarketSession.REGULAR);
        expected.addAll(times(12, MarketSession.AFTER_HOURS));
        // 0:05-0:30 ET pinned pre-market, then regular hours to now.
        expected.addAll(times(6, MarketSession.PRE_MARKET));
        expected.addAll(times(6, MarketSession.REGULAR));
        assertEquals(expected, sessions(response));
        assertEquals(OffsetDateTime.parse("2026-01-14T05:30:00Z"), response.hours().regularOpen());
        assertEquals(OffsetDateTime.parse("2026-01-14T12:00:00Z"), response.hours().regularClose());
    }

    @Test
    @DisplayName("daily ranges keep weekends, tagged closed")
    void dailyRangesKeepClosedDays() {
        service = serviceAt(NOW, MarketClock.Mode.REAL);
        givenAccount();
        givenCash("1000.00");

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.THREE_MONTHS, null);

        assertEquals(86400, response.intervalSeconds());
        assertEquals(91, response.points().size());
        assertNull(response.hours());
        // Sunday 4 January 2026, 00:00 UTC closes Saturday.
        assertEquals(MarketSession.CLOSED, response.points().stream()
                .filter(point -> point.timestamp().isEqual(OffsetDateTime.parse("2026-01-04T00:00:00Z")))
                .findFirst().orElseThrow().session());
    }

    @Test
    @DisplayName("prices a holding with no candle history at its live price throughout")
    void fallsBackToLivePriceWithoutHistory() {
        givenAccount();
        givenHolding(5);
        givenCash("0.00");
        when(positionMovementRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of());
        when(cashLedgerRepository.findByAccountIdInAndCreatedAtAfter(anyList(), any())).thenReturn(List.of());
        when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), any(), anyInt())).thenReturn(List.of());
        when(priceHistoryClient.fetchLatestPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("50")));

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_WEEK, null);

        assertEquals(3600, response.intervalSeconds());
        response.points().forEach(point -> assertEquals(new BigDecimal("250.00"), point.value()));
        assertEquals(new BigDecimal("0.00"), response.change());
    }

    @Test
    @DisplayName("returns a flat zero series with no percentage for a client without accounts")
    void emptyForClientWithoutAccounts() {
        when(accountRepository.findByClientIdAndStatus(CLIENT_ID, "ACTIVE")).thenReturn(List.of());

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_MONTH, null);

        assertEquals(new BigDecimal("0.00"), response.endValue());
        assertNull(response.changePercent());
        verifyNoInteractions(priceHistoryClient);
    }

    @Test
    @DisplayName("ALL uses a finer candle width for a young account")
    void allRangeAdaptsToAccountAge() {
        PortfolioRange.Window young = PortfolioRange.ALL.window(NOW, NOW.minusDays(10));
        assertEquals(NOW.minusDays(10), young.from());
        assertEquals(3600, young.intervalSeconds());

        PortfolioRange.Window old = PortfolioRange.ALL.window(NOW, NOW.minusYears(5));
        assertEquals(NOW.minus(PortfolioRange.MAX_LOOKBACK), old.from());
        assertEquals(86400, old.intervalSeconds());
    }

    @Test
    @DisplayName("1D starts at the previous trading day's regular close")
    void oneDayStartsAtPreviousClose() {
        PortfolioRange.Window day = PortfolioRange.ONE_DAY.window(NOW, NOW.minusYears(1));
        assertEquals(PREVIOUS_CLOSE, day.from());
        assertEquals(300, day.intervalSeconds());
    }

    @Test
    @DisplayName("a range keeps its whole span and width, noting when a younger account opened")
    void rangesKeepTheirSpanForYoungAccounts() {
        PortfolioRange.Window month = PortfolioRange.ONE_MONTH.window(NOW, NOW.minusYears(1));
        assertEquals(NOW.minusDays(30), month.from());
        assertEquals(3600, month.intervalSeconds());
        assertNull(month.opened());

        PortfolioRange.Window youngYear = PortfolioRange.ONE_YEAR.window(NOW, NOW.minusDays(10));
        assertEquals(NOW.minusDays(365), youngYear.from());
        assertEquals(86400, youngYear.intervalSeconds());
        assertEquals(NOW.minusDays(10), youngYear.opened());

        PortfolioRange.Window youngWeek = PortfolioRange.ONE_WEEK.window(NOW, NOW.minusDays(5));
        assertEquals(NOW.minusDays(7), youngWeek.from());
        assertEquals(3600, youngWeek.intervalSeconds());
        assertEquals(NOW.minusDays(5), youngWeek.opened());
    }

    @Test
    @DisplayName("rejects an unknown range code with 400")
    void rejectsUnknownRange() {
        assertEquals(PortfolioRange.THREE_MONTHS, PortfolioRange.fromCode("3m"));
        assertThrows(ResponseStatusException.class, () -> PortfolioRange.fromCode("5Y"));
    }
}

