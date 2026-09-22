package com.leap.leaplaughlove.trading.portfolio;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountRepository;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.order.InstrumentRepository;
import com.leap.leaplaughlove.trading.position.PositionMovement;
import com.leap.leaplaughlove.trading.position.PositionMovementRepository;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import com.leap.leaplaughlove.trading.quote.PriceHistoryClient;
import com.leap.leaplaughlove.trading.quote.PriceHistoryClient.CandleClose;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioHistoryService Unit Tests")
class PortfolioHistoryServiceTest {

    private static final UUID CLIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AAPL_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-10T12:00:00Z");
    private static final OffsetDateTime BUY_TIME = OffsetDateTime.parse("2026-01-10T00:00:00Z");

    @Mock private AccountRepository accountRepository;
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
        service = new PortfolioHistoryService(accountRepository, positionRepository, positionMovementRepository,
                cashLedgerRepository, instrumentRepository, priceHistoryClient,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
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

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_DAY);

        assertEquals("1D", response.range());
        assertEquals(300, response.intervalSeconds());
        // 5-minute bucket ends across the day, plus "now" as the final point.
        assertEquals(288, response.points().size());
        assertEquals(NOW, response.points().get(response.points().size() - 1).timestamp());
        // Before the buy: all cash, nothing held.
        assertEquals(new BigDecimal("2000.00"), valueAt(response, "2026-01-09T12:05:00Z"));
        // The buy itself: cash moved into 10 shares at the carried-forward 100 close.
        assertEquals(new BigDecimal("2000.00"), valueAt(response, "2026-01-10T00:00:00Z"));
        // The 06:00 candle only counts once its bucket has ended.
        assertEquals(new BigDecimal("2000.00"), valueAt(response, "2026-01-10T06:00:00Z"));
        assertEquals(new BigDecimal("2100.00"), valueAt(response, "2026-01-10T06:05:00Z"));
        // Now: priced at the live price.
        assertEquals(new BigDecimal("2200.00"), response.endValue());
        assertEquals(new BigDecimal("2000.00"), response.startValue());
        assertEquals(new BigDecimal("200.00"), response.change());
        assertEquals(new BigDecimal("10.00"), response.changePercent());
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

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_WEEK);

        assertEquals(3600, response.intervalSeconds());
        response.points().forEach(point -> assertEquals(new BigDecimal("250.00"), point.value()));
        assertEquals(new BigDecimal("0.00"), response.change());
    }

    @Test
    @DisplayName("returns a flat zero series with no percentage for a client without accounts")
    void emptyForClientWithoutAccounts() {
        when(accountRepository.findByClientIdAndStatus(CLIENT_ID, "ACTIVE")).thenReturn(List.of());

        PortfolioHistoryResponse response = service.getHistory(PortfolioRange.ONE_MONTH);

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
    @DisplayName("rejects an unknown range code with 400")
    void rejectsUnknownRange() {
        assertEquals(PortfolioRange.THREE_MONTHS, PortfolioRange.fromCode("3m"));
        assertThrows(ResponseStatusException.class, () -> PortfolioRange.fromCode("5Y"));
    }
}
