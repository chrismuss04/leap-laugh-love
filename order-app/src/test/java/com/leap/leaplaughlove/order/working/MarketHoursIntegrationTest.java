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
    @Autowired private OrderModificationService orderModificationService;
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
    @DisplayName("a market buy after hours is queued for the open, holding cash at its 5% collar")
    void marketOrderOutsideRegularHoursIsQueued() throws Exception {
        session(MarketSession.AFTER_HOURS);

        UUID orderId = UUID.fromString(objectMapper.readTree(place("{\"side\":\"BUY\",\"quantity\":5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.orderType").value("MARKET"))
                .andExpect(jsonPath("$.timeInForce").value("DAY"))
                // The ask is $150.00, so the collar is $157.50 and five shares hold $787.50.
                .andExpect(jsonPath("$.collarPrice").value(157.50))
                .andExpect(jsonPath("$.reservedCash").value(787.50))
                .andExpect(jsonPath("$.execution").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("orderId").asText());

        verify(accountClient, never()).settleOrder(any(), any());
        OpenOrderItem listed = openOrderService.listOpen(OWNER).stream()
                .filter(item -> item.orderId().equals(orderId)).findFirst().orElseThrow();
        assertTrue(listed.queued());
        assertEquals(0, new BigDecimal("157.50").compareTo(listed.collarPrice()));
    }

    @Test
    @DisplayName("a queued market order must be affordable at its collar")
    void queuedMarketOrderNeedsCashForItsCollar() throws Exception {
        session(MarketSession.CLOSED);

        // 64 x $150.00 = $9,600 fits the $10,000 balance, but 64 x $157.50 = $10,080 doesn't.
        place("{\"side\":\"BUY\",\"quantity\":64}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(org.hamcrest.Matchers.startsWith("Insufficient funds")));
    }

    @Test
    @DisplayName("a queued market order ignores the account's saved price tolerance")
    void queuedMarketOrderNeedsNoQuotedPrice() throws Exception {
        session(MarketSession.PRE_MARKET);
        AccountValidationDto withTolerance = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L,
                "USD", "ACC-OWNER-USD", new BigDecimal("0.50"));
        when(accountClient.getValidationData(eq(OWNER_ACCOUNT), eq(AAPL))).thenReturn(withTolerance);

        place("{\"side\":\"BUY\",\"quantity\":5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("a queued market buy fills at the open, at the opening ask")
    void queuedMarketOrderFillsAtTheOpen() throws Exception {
        session(MarketSession.CLOSED);
        UUID orderId = queue("{\"side\":\"BUY\",\"quantity\":5}");

        assertEquals(0, engine.evaluateOpenOrders(), "nothing fills while the market is closed");
        session(MarketSession.PRE_MARKET);
        assertEquals(0, engine.evaluateOpenOrders(), "nor in pre-market");

        // The market opens 2% higher: within the collar.
        openingQuote("152.50", "153.00");
        session(MarketSession.REGULAR);
        assertEquals(1, engine.evaluateOpenOrders());

        Order filled = reload(orderId);
        assertEquals(Order.Status.FILLED, filled.getStatus());
        verify(accountClient).settleOrderAs(eq(OWNER_ACCOUNT), any(SettlementRequest.class), anyString());
    }

    @Test
    @DisplayName("a queued market buy is refused at the open when the price gaps beyond its collar")
    void queuedMarketOrderBeyondItsCollarIsRefused() throws Exception {
        session(MarketSession.AFTER_HOURS);
        UUID orderId = queue("{\"side\":\"BUY\",\"quantity\":5}");

        // Opens 8% higher: past the $157.50 collar.
        openingQuote("161.50", "162.00");
        session(MarketSession.REGULAR);
        engine.evaluateOpenOrders();

        Order refused = reload(orderId);
        assertEquals(Order.Status.REJECTED, refused.getStatus());
        assertTrue(refused.getRejectionReason().contains("price collar of $157.50"), refused.getRejectionReason());
        assertTrue(refused.getRejectionReason().contains("nothing was charged"));
        verify(accountClient, never()).settleOrderAs(any(), any(), anyString());
    }

    @Test
    @DisplayName("a queued market sell is refused at the open when the price gaps below its collar")
    void queuedMarketSellBelowItsCollarIsRefused() throws Exception {
        session(MarketSession.CLOSED);
        UUID orderId = queue("{\"side\":\"SELL\",\"quantity\":5}");
        // The bid is $149.50, so the collar is $142.03 (rounded for display).
        assertEquals(0, new BigDecimal("142.025").compareTo(reload(orderId).getCollarPrice()));

        openingQuote("140.00", "140.50");
        session(MarketSession.REGULAR);
        engine.evaluateOpenOrders();

        Order refused = reload(orderId);
        assertEquals(Order.Status.REJECTED, refused.getStatus());
        assertTrue(refused.getRejectionReason().contains("no shares were sold"), refused.getRejectionReason());
    }

    @Test
    @DisplayName("cancelling a queued market order releases what it held")
    void cancellingAQueuedOrderReleasesItsHold() throws Exception {
        session(MarketSession.CLOSED);
        UUID orderId = queue("{\"side\":\"BUY\",\"quantity\":60}");

        // 60 x $157.50 = $9,450 is held, so a second order for $787.50 more can't be afforded...
        place("{\"side\":\"BUY\",\"quantity\":5}")
                .andExpect(jsonPath("$.status").value("REJECTED"));

        openOrderService.cancel(OWNER, orderId);
        entityManager.flush();

        // ...until the first is cancelled.
        place("{\"side\":\"BUY\",\"quantity\":5}")
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("a queued market order's quantity can change, re-holding at its collar, but not its time in force")
    void modifyingAQueuedOrder() throws Exception {
        session(MarketSession.CLOSED);
        UUID orderId = queue("{\"side\":\"BUY\",\"quantity\":5}");

        OpenOrderItem changed = orderModificationService.modify(OWNER, orderId,
                new ModifyOrderRequest(10L, null, null, null, null, null));
        assertEquals(0, new BigDecimal("1575.00").compareTo(changed.reservedCash()));

        org.springframework.web.server.ResponseStatusException refused = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> orderModificationService.modify(OWNER, orderId,
                        new ModifyOrderRequest(null, null, null, null, null, Order.TimeInForce.GTC)));
        assertTrue(refused.getReason().contains("good for that trading day only"));
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

    private UUID queue(String fields) throws Exception {
        String body = place(fields)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.orderType").value("MARKET"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("orderId").asText());
    }

    private void openingQuote(String bid, String ask) {
        QuoteSnapshot opening = new QuoteSnapshot("AAPL", new BigDecimal(bid), 100L, new BigDecimal(ask), 100L,
                new BigDecimal(bid), 100L, "NASDAQ", OffsetDateTime.now());
        when(currentQuoteService.getFreshQuotes(anyString())).thenReturn(Map.of("AAPL", opening));
    }

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
