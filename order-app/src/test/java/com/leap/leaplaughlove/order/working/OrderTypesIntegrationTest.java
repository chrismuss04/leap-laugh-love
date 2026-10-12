package com.leap.leaplaughlove.order.working;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/order_submission_test_setup.sql")
@DisplayName("Order types: limit, stop and stop-limit orders end to end")
class OrderTypesIntegrationTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AAPL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private PositionMovementRepository positionMovementRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private OrderSubmissionService orderSubmissionService;
    @Autowired private OpenOrderService openOrderService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    @MockBean private AccountClient accountClient;
    @MockBean private CurrentQuoteService currentQuoteService;

    private OrderTriggerEngine engine;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        ownerToken = sessionToken(OWNER, "owner@example.com");
        otherToken = sessionToken(OTHER, "other@example.com");
        // Disabled on a schedule in tests, so driven directly.
        engine = new OrderTriggerEngine(orderRepository, accountRepository, currentQuoteService,
                orderSubmissionService, openOrderService, jwtService, transactionTemplate);

        // AAPL: bid 149.50, ask 150.00. The owner has $10,000 and 25 shares.
        when(currentQuoteService.getCurrentQuote(eq("AAPL"))).thenReturn(aaplQuote("149.50", "150.00"));
        AccountValidationDto owner = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L,
                "USD", "ACC-OWNER-USD", null);
        when(accountClient.getValidationData(eq(OWNER_ACCOUNT), eq(AAPL))).thenReturn(owner);
        when(accountClient.getValidationDataAs(eq(OWNER_ACCOUNT), eq(AAPL), anyString())).thenReturn(owner);
        when(accountClient.settleOrder(eq(OWNER_ACCOUNT), any(SettlementRequest.class))).thenReturn(
                new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 35L, new BigDecimal("150.00")));
        when(accountClient.settleOrderAs(eq(OWNER_ACCOUNT), any(SettlementRequest.class), anyString())).thenReturn(
                new SettlementResponse(UUID.randomUUID(), new BigDecimal("8550.00"), 35L, new BigDecimal("145.00")));
    }

    // ---- Placing ----

    @Test
    @DisplayName("a limit buy below the ask is stored OPEN, holding its cost, with no execution")
    void limitBuyBelowTheMarketWaits() throws Exception {
        JsonNode response = place("LIMIT", "BUY", 10, "\"limitPrice\":145.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.orderType").value("LIMIT"))
                .andExpect(jsonPath("$.timeInForce").value("DAY"))
                .andExpect(jsonPath("$.reservedCash").value(1450.00))
                .andExpect(jsonPath("$.execution").doesNotExist())
                .andReturn().getResponse().getContentAsString().transform(this::json);

        UUID orderId = UUID.fromString(response.get("orderId").asText());
        Order stored = orderRepository.findById(orderId).orElseThrow();
        assertEquals(Order.Status.OPEN, stored.getStatus());
        assertTrue(stored.getExpiresAt().isAfter(OffsetDateTime.now()), "a DAY order expires later today");
        assertTrue(executionRepository.findByOrder_OrderIdIn(java.util.List.of(orderId)).isEmpty());
        verify(accountClient, never()).settleOrder(any(), any());
    }

    @Test
    @DisplayName("a limit buy at or above the ask fills at once, at the ask")
    void marketableLimitFillsNow() throws Exception {
        place("LIMIT", "BUY", 10, "\"limitPrice\":151.00,\"timeInForce\":\"GTC\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.execution.fillPrice").value(150.00))
                .andExpect(jsonPath("$.execution.reason").value("Executed at limit price or better"));
    }

    @Test
    @DisplayName("a sell stop above the bid is rejected, as it would trigger at once")
    void sellStopAboveTheMarketIsRejected() throws Exception {
        place("STOP", "SELL", 5, "\"stopPrice\":152.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(
                        "Stop price $152.00 must be below the current bid of $149.50 - order rejected"));
    }

    @Test
    @DisplayName("a limit order without a limit price is refused before anything is stored")
    void limitWithoutAPriceIsInvalid() throws Exception {
        place("LIMIT", "BUY", 10, "\"stopPrice\":145.00").andExpect(status().isBadRequest());
        assertEquals(0, orderRepository.count());
    }

    @Test
    @DisplayName("price tolerance is refused on a limit order")
    void toleranceIsForMarketOrdersOnly() throws Exception {
        place("LIMIT", "BUY", 10, "\"limitPrice\":145.00,\"quotedPrice\":150.00,\"maxSlippagePercent\":1")
                .andExpect(status().isBadRequest());
    }

    // ---- Holds ----

    @Test
    @DisplayName("cash an open buy holds can't be spent by another order")
    void openBuysHoldCash() throws Exception {
        place("LIMIT", "BUY", 60, "\"limitPrice\":140.00").andExpect(jsonPath("$.status").value("OPEN"));

        // $10,000 less $8,400 held leaves $1,600: not enough for 20 at $150.
        mockMvc.perform(post("/api/order/orders").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + OWNER_ACCOUNT + "\",\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":20}"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(
                        "Insufficient funds - order rejected ($8400.00 is held for your open orders)"));
    }

    @Test
    @DisplayName("shares an open sell holds can't be sold twice")
    void openSellsHoldShares() throws Exception {
        place("STOP", "SELL", 20, "\"stopPrice\":140.00").andExpect(jsonPath("$.status").value("OPEN"));

        place("LIMIT", "SELL", 10, "\"limitPrice\":160.00")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(
                        "Insufficient position quantity - order rejected (20 shares are held for your open sell orders)"));
    }

    // ---- Filling ----

    @Test
    @DisplayName("the engine fills an open limit buy once the ask reaches its limit, settling as the owner")
    void engineFillsALimitBuy() throws Exception {
        UUID orderId = openOrder("LIMIT", "BUY", 10, "\"limitPrice\":145.00");

        quotes("149.00", "145.10");
        assertEquals(0, engine.evaluateOpenOrders(), "145.10 is above the limit");
        assertEquals(Order.Status.OPEN, reload(orderId).getStatus());

        quotes("144.40", "144.90");
        assertEquals(1, engine.evaluateOpenOrders());

        Order filled = reload(orderId);
        assertEquals(Order.Status.FILLED, filled.getStatus());
        Execution execution = executionRepository.findFirstByOrder_OrderIdAndStatus(orderId, Execution.Status.FILLED)
                .orElseThrow();
        assertEquals(0, new BigDecimal("144.90").compareTo(execution.getFillPrice()));
        assertTrue(positionMovementRepository.existsByOrderId(orderId));
        verify(accountClient).settleOrderAs(eq(OWNER_ACCOUNT), any(SettlementRequest.class), anyString());
    }

    @Test
    @DisplayName("the engine fills a sell stop at the bid once the bid falls to the stop")
    void engineFillsASellStop() throws Exception {
        UUID orderId = openOrder("STOP", "SELL", 5, "\"stopPrice\":140.00");

        quotes("139.20", "139.70");
        engine.evaluateOpenOrders();

        Order filled = reload(orderId);
        assertEquals(Order.Status.FILLED, filled.getStatus());
        assertNotNull(filled.getTriggeredAt());
        assertEquals(0, new BigDecimal("139.20").compareTo(executionRepository
                .findFirstByOrder_OrderIdAndStatus(orderId, Execution.Status.FILLED).orElseThrow().getFillPrice()));
    }

    @Test
    @DisplayName("a stop-limit whose price gaps past its limit is triggered but keeps waiting")
    void stopLimitGapWaits() throws Exception {
        UUID orderId = openOrder("STOP_LIMIT", "SELL", 5, "\"stopPrice\":140.00,\"limitPrice\":139.00");

        quotes("138.50", "139.00");
        assertEquals(0, engine.evaluateOpenOrders());

        Order waiting = reload(orderId);
        assertEquals(Order.Status.OPEN, waiting.getStatus());
        assertNotNull(waiting.getTriggeredAt(), "now working as a limit order");

        quotes("139.10", "139.60");
        assertEquals(1, engine.evaluateOpenOrders(), "back within its limit");
        assertEquals(Order.Status.FILLED, reload(orderId).getStatus());
    }

    @Test
    @DisplayName("an open order the account can no longer cover is rejected when its price is reached")
    void revalidatesWhenFilling() throws Exception {
        UUID orderId = openOrder("LIMIT", "BUY", 10, "\"limitPrice\":145.00");
        // The cash was withdrawn in the meantime.
        when(accountClient.getValidationDataAs(eq(OWNER_ACCOUNT), eq(AAPL), anyString())).thenReturn(
                new AccountValidationDto(true, true, new BigDecimal("100.00"), 25L, "USD", "ACC-OWNER-USD", null));

        quotes("144.40", "144.90");
        engine.evaluateOpenOrders();

        Order rejected = reload(orderId);
        assertEquals(Order.Status.REJECTED, rejected.getStatus());
        assertEquals("Insufficient funds - order rejected", rejected.getRejectionReason());
        verify(accountClient, never()).settleOrderAs(any(), any(), anyString());
    }

    @Test
    @DisplayName("an open order stays OPEN when account-app can't be reached, to be tried again")
    void keepsWaitingWhenAccountAppIsDown() throws Exception {
        UUID orderId = openOrder("LIMIT", "BUY", 10, "\"limitPrice\":145.00");
        when(accountClient.getValidationDataAs(eq(OWNER_ACCOUNT), eq(AAPL), anyString()))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        quotes("144.40", "144.90");
        engine.evaluateOpenOrders();

        assertEquals(Order.Status.OPEN, reload(orderId).getStatus());
    }

    // ---- Listing, cancelling, expiring ----

    @Test
    @DisplayName("lists the client's open orders, and only theirs")
    void listsOpenOrders() throws Exception {
        UUID orderId = openOrder("LIMIT", "BUY", 10, "\"limitPrice\":145.00");

        mockMvc.perform(get("/api/order/orders/open").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].limitPrice").value(145.00));
        mockMvc.perform(get("/api/order/orders/open").header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("cancelling releases the order; it can't be cancelled twice, or by another client")
    void cancelsOpenOrders() throws Exception {
        UUID orderId = openOrder("LIMIT", "BUY", 10, "\"limitPrice\":145.00");

        mockMvc.perform(post("/api/order/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/order/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/order/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());

        quotes("144.40", "144.90");
        assertEquals(0, engine.evaluateOpenOrders(), "a cancelled order never fills");
        assertEquals(Order.Status.CANCELLED, reload(orderId).getStatus());
    }

    @Test
    @DisplayName("an order past its time in force expires")
    void expiresOrders() throws Exception {
        UUID orderId = openOrder("LIMIT", "BUY", 10, "\"limitPrice\":145.00");
        jdbc.update("UPDATE trading.orders SET expires_at = ? WHERE order_id = ?",
                OffsetDateTime.now().minusMinutes(1), orderId);
        entityManager.clear();

        assertEquals(1, openOrderService.expireDue());

        Order expired = reload(orderId);
        assertEquals(Order.Status.EXPIRED, expired.getStatus());
        assertNotNull(expired.getExpiredAt());
    }

    @Test
    @DisplayName("history shows an order's type and prices")
    void historyShowsTheType() throws Exception {
        openOrder("STOP_LIMIT", "SELL", 5, "\"stopPrice\":140.00,\"limitPrice\":139.00");

        mockMvc.perform(get("/api/order/orders/history").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].orderType").value("STOP_LIMIT"))
                .andExpect(jsonPath("$.content[0].stopPrice").value(140.00))
                .andExpect(jsonPath("$.content[0].limitPrice").value(139.00));
    }

    @Test
    @DisplayName("history lists orders by when they filled, not when they were placed")
    void historyIsOrderedByFillTime() throws Exception {
        UUID limitId = openOrder("LIMIT", "BUY", 1, "\"limitPrice\":145.00");
        // Placed after the limit order, filled at once.
        String marketBody = mockMvc.perform(post("/api/order/orders").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + OWNER_ACCOUNT + "\",\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":1}"))
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andReturn().getResponse().getContentAsString();
        String marketId = json(marketBody).get("orderId").asText();
        // The limit order fills later.
        jdbc.update("UPDATE trading.orders SET submitted_at = ? WHERE order_id = ?",
                OffsetDateTime.now().minusHours(1), limitId);
        entityManager.clear();
        quotes("144.40", "144.90");
        engine.evaluateOpenOrders();

        mockMvc.perform(get("/api/order/orders/history").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.content[0].orderId").value(limitId.toString()))
                .andExpect(jsonPath("$.content[0].completedAt").exists())
                .andExpect(jsonPath("$.content[1].orderId").value(marketId));
    }

    // ---- Helpers ----

    private ResultActions place(String type, String side, long quantity, String extra) throws Exception {
        return mockMvc.perform(post("/api/order/orders").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + OWNER_ACCOUNT + "\",\"symbol\":\"AAPL\",\"side\":\"" + side
                        + "\",\"quantity\":" + quantity + ",\"orderType\":\"" + type + "\"," + extra + "}"));
    }

    private UUID openOrder(String type, String side, long quantity, String extra) throws Exception {
        String body = place(type, side, quantity, extra)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json(body).get("orderId").asText());
    }

    private void quotes(String bid, String ask) {
        when(currentQuoteService.getFreshQuotes(anyString())).thenReturn(Map.of("AAPL", aaplQuote(bid, ask)));
    }

    private static QuoteSnapshot aaplQuote(String bid, String ask) {
        return new QuoteSnapshot("AAPL", new BigDecimal(bid), 100L, new BigDecimal(ask), 100L,
                new BigDecimal(bid), 100L, "NASDAQ", OffsetDateTime.now());
    }

    private Order reload(UUID orderId) {
        entityManager.flush();
        entityManager.clear();
        return orderRepository.findById(orderId).orElseThrow();
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String sessionToken(UUID clientId, String email) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS iam");
        jdbc.execute("CREATE TABLE IF NOT EXISTS iam.client_sessions (session_id UUID PRIMARY KEY, "
                + "client_id UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
        UUID sid = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(3600);
        jdbc.update("INSERT INTO iam.client_sessions VALUES (?, ?, ?, ?, ?, NULL)",
                sid, clientId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), expiry.atOffset(ZoneOffset.UTC));
        return jwtService.generateToken(clientId, email, sid, now, expiry);
    }
}
