package com.leap.leaplaughlove.order.working;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.submission.OrderSubmissionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Market hours end to end: which orders can be placed and which execute in each session. The
 * session is pinned by mocking the {@link MarketClock}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/order_submission_test_setup.sql")
@DisplayName("Market hours: orders follow the market session")
class MarketHoursIntegrationTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AAPL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orderRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private OrderSubmissionService orderSubmissionService;
    @Autowired private OpenOrderService openOrderService;
    @Autowired private OrderUpdateBroadcaster broadcaster;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    @MockBean private AccountClient accountClient;
    @MockBean private CurrentQuoteService currentQuoteService;
    @MockBean private MarketClock marketClock;

    private OrderTriggerEngine engine;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerToken = sessionToken();
        engine = new OrderTriggerEngine(orderRepository, accountRepository, currentQuoteService,
                orderSubmissionService, openOrderService, jwtService, transactionTemplate, broadcaster, marketClock);
        when(marketClock.mode()).thenReturn(MarketClock.Mode.REAL);

        // AAPL: bid 149.50, ask 150.00. The owner has $10,000 and 25 shares.
        QuoteSnapshot quote = new QuoteSnapshot("AAPL", new BigDecimal("149.50"), 100L, new BigDecimal("150.00"), 100L,
                new BigDecimal("149.50"), 100L, "NASDAQ", OffsetDateTime.now());
        when(currentQuoteService.getCurrentQuote(eq("AAPL"))).thenReturn(quote);
        when(currentQuoteService.getFreshQuotes(anyString())).thenReturn(Map.of("AAPL", quote));
        AccountValidationDto owner = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L,
                "USD", "ACC-OWNER-USD", null);
        when(accountClient.getValidationData(eq(OWNER_ACCOUNT), eq(AAPL))).thenReturn(owner);
        when(accountClient.getValidationDataAs(eq(OWNER_ACCOUNT), eq(AAPL), anyString())).thenReturn(owner);
        when(accountClient.settleOrder(eq(OWNER_ACCOUNT), any(SettlementRequest.class))).thenReturn(
                new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 35L, new BigDecimal("150.00")));
        when(accountClient.settleOrderAs(eq(OWNER_ACCOUNT), any(SettlementRequest.class), anyString())).thenReturn(
                new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 35L, new BigDecimal("150.00")));
    }

    private void session(MarketSession session) {
        when(marketClock.session()).thenReturn(session);
    }

    // ---- Market orders ----

    @Test
    @DisplayName("a market order outside regular hours is rejected with the reason, and nothing settles")
    void marketOrderOutsideRegularHoursIsRejected() throws Exception {
        session(MarketSession.AFTER_HOURS);

        place("{\"side\":\"BUY\",\"quantity\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(org.hamcrest.Matchers.containsString("regular market hours")));

        verify(accountClient, never()).settleOrder(any(), any());
    }

    @Test
    @DisplayName("a market order in regular hours fills as before")
    void marketOrderInRegularHoursFills() throws Exception {
        session(MarketSession.REGULAR);

        place("{\"side\":\"BUY\",\"quantity\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.extendedHours").value(false));
    }

    // ---- Limit orders ----

    @Test
    @DisplayName("a marketable limit in pre-market waits OPEN unless it is for extended hours")
    void regularHoursLimitWaitsInPreMarket() throws Exception {
        session(MarketSession.PRE_MARKET);

        UUID orderId = openOrder("{\"side\":\"BUY\",\"quantity\":5,\"orderType\":\"LIMIT\",\"limitPrice\":151.00}");

        Order stored = reload(orderId);
        assertEquals(Order.Status.OPEN, stored.getStatus());
        assertFalse(stored.isExtendedHours());
        verify(accountClient, never()).settleOrder(any(), any());

        // The engine leaves it alone until regular trading opens.
        assertEquals(0, engine.evaluateOpenOrders());
        assertEquals(Order.Status.OPEN, reload(orderId).getStatus());

        session(MarketSession.REGULAR);
        assertEquals(1, engine.evaluateOpenOrders());
        assertEquals(Order.Status.FILLED, reload(orderId).getStatus());
    }

    @Test
    @DisplayName("an extended-hours limit fills in after-hours at once when marketable")
    void extendedHoursLimitFillsAfterHours() throws Exception {
        session(MarketSession.AFTER_HOURS);

        place("{\"side\":\"BUY\",\"quantity\":5,\"orderType\":\"LIMIT\",\"limitPrice\":151.00,\"extendedHours\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.extendedHours").value(true));
    }

    @Test
    @DisplayName("an extended-hours limit waiting overnight fills in pre-market once its price is reached")
    void extendedHoursLimitWaitsWhileClosed() throws Exception {
        session(MarketSession.CLOSED);
        UUID orderId = openOrder("{\"side\":\"BUY\",\"quantity\":5,\"orderType\":\"LIMIT\",\"limitPrice\":151.00,"
                + "\"extendedHours\":true,\"timeInForce\":\"GTC\"}");

        assertEquals(0, engine.evaluateOpenOrders());

        session(MarketSession.PRE_MARKET);
        assertEquals(1, engine.evaluateOpenOrders());
        assertEquals(Order.Status.FILLED, reload(orderId).getStatus());
    }

    @Test
    @DisplayName("extendedHours is refused on anything but a limit order")
    void extendedHoursIsForLimitOrdersOnly() throws Exception {
        session(MarketSession.REGULAR);

        place("{\"side\":\"SELL\",\"quantity\":5,\"orderType\":\"STOP\",\"stopPrice\":140.00,\"extendedHours\":true}")
                .andExpect(status().isBadRequest());
        place("{\"side\":\"BUY\",\"quantity\":5,\"extendedHours\":true}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a DAY limit placed after the close is good for the next trading day")
    void dayOrderPlacedAfterTheCloseLastsUntilTheNextClose() throws Exception {
        session(MarketSession.AFTER_HOURS);

        UUID orderId = openOrder("{\"side\":\"BUY\",\"quantity\":5,\"orderType\":\"LIMIT\",\"limitPrice\":140.00}");

        OffsetDateTime expiresAt = reload(orderId).getExpiresAt();
        assertTrue(expiresAt.isAfter(OffsetDateTime.now()));
        assertEquals(0, expiresAt.atZoneSameInstant(java.time.ZoneId.of("America/New_York")).getMinute(),
                "a DAY order ends at a close, on the hour");
    }

    // ---- Stops ----

    @Test
    @DisplayName("a stop doesn't trigger while the market is closed, and does once it opens")
    void stopsOnlyTriggerInRegularHours() throws Exception {
        session(MarketSession.REGULAR);
        UUID orderId = openOrder("{\"side\":\"SELL\",\"quantity\":5,\"orderType\":\"STOP\",\"stopPrice\":145.00,"
                + "\"timeInForce\":\"GTC\"}");

        // The bid falls through the stop overnight.
        QuoteSnapshot lower = new QuoteSnapshot("AAPL", new BigDecimal("140.00"), 100L, new BigDecimal("140.50"), 100L,
                new BigDecimal("140.00"), 100L, "NASDAQ", OffsetDateTime.now());
        when(currentQuoteService.getFreshQuotes(anyString())).thenReturn(Map.of("AAPL", lower));
        session(MarketSession.AFTER_HOURS);

        assertEquals(0, engine.evaluateOpenOrders());
        Order waiting = reload(orderId);
        assertEquals(Order.Status.OPEN, waiting.getStatus());
        assertNull(waiting.getTriggeredAt());

        session(MarketSession.REGULAR);
        assertEquals(1, engine.evaluateOpenOrders());
        assertEquals(Order.Status.FILLED, reload(orderId).getStatus());
    }

    // ---- Helpers ----

    private ResultActions place(String fields) throws Exception {
        String body = "{\"accountId\":\"" + OWNER_ACCOUNT + "\",\"symbol\":\"AAPL\"," + fields.substring(1);
        return mockMvc.perform(post("/api/order/orders").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID openOrder(String fields) throws Exception {
        String body = place(fields)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("orderId").asText());
    }

    private Order reload(UUID orderId) {
        entityManager.flush();
        entityManager.clear();
        return orderRepository.findById(orderId).orElseThrow();
    }

    private String sessionToken() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS iam");
        jdbc.execute("CREATE TABLE IF NOT EXISTS iam.client_sessions (session_id UUID PRIMARY KEY, "
                + "client_id UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
        UUID sid = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(3600);
        jdbc.update("INSERT INTO iam.client_sessions VALUES (?, ?, ?, ?, ?, NULL)",
                sid, OWNER, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), expiry.atOffset(ZoneOffset.UTC));
        return jwtService.generateToken(OWNER, "owner@example.com", sid, now, expiry);
    }
}
