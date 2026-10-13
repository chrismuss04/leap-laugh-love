package com.leap.leaplaughlove.order.working;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bracket orders end to end. Not @Transactional: the exit orders are opened when the buy's fill
 * commits, and the sibling is cancelled after the other leg's fill commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/order_submission_test_setup.sql")
@DisplayName("Bracket orders: take-profit and stop-loss exits that cancel each other")
class BracketOrderIntegrationTest {

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
    @Autowired private OrderModificationService modificationService;
    @Autowired private OrderUpdateBroadcaster broadcaster;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockBean private AccountClient accountClient;
    @MockBean private CurrentQuoteService currentQuoteService;

    private OrderTriggerEngine engine;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerToken = sessionToken();
        engine = new OrderTriggerEngine(orderRepository, accountRepository, currentQuoteService,
                orderSubmissionService, openOrderService, jwtService, transactionTemplate, broadcaster);

        // AAPL: bid 149.50, ask 150.00. The owner has $10,000 and 25 shares.
        when(currentQuoteService.getCurrentQuote(eq("AAPL"))).thenReturn(quote("149.50", "150.00"));
        AccountValidationDto owner = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L,
                "USD", "ACC-OWNER-USD", null);
        when(accountClient.getValidationData(eq(OWNER_ACCOUNT), eq(AAPL))).thenReturn(owner);
        when(accountClient.getValidationDataAs(eq(OWNER_ACCOUNT), eq(AAPL), anyString())).thenReturn(owner);
        SettlementResponse settled = new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 35L,
                new BigDecimal("150.00"));
        when(accountClient.settleOrder(eq(OWNER_ACCOUNT), any(SettlementRequest.class))).thenReturn(settled);
        when(accountClient.settleOrderAs(eq(OWNER_ACCOUNT), any(SettlementRequest.class), anyString())).thenReturn(settled);
    }

    // ---- Validation ----

    @Test
    @DisplayName("a bracket on a sell, with only one price, or with the take-profit below the stop-loss is refused")
    void invalidBracketsAreRefused() throws Exception {
        place("SELL", 5, "\"bracketTakeProfitPrice\":160,\"bracketStopLossPrice\":140").andExpect(status().isBadRequest());
        place("BUY", 5, "\"bracketTakeProfitPrice\":160").andExpect(status().isBadRequest());
        place("BUY", 5, "\"bracketTakeProfitPrice\":140,\"bracketStopLossPrice\":160").andExpect(status().isBadRequest());
        assertEquals(0, orderRepository.count());
    }

    @Test
    @DisplayName("a take-profit at or below the entry, or a stop-loss at or above it, is rejected")
    void exitsMustBracketTheEntry() throws Exception {
        // Market entry is the ask, 150.00.
        place("BUY", 5, "\"bracketTakeProfitPrice\":149,\"bracketStopLossPrice\":140")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(
                        "Take-profit $149.00 must be above the buy's entry price of $150.00 - order rejected"));
        place("BUY", 5, "\"bracketTakeProfitPrice\":160,\"bracketStopLossPrice\":150.5")
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value(
                        "Stop-loss $150.50 must be below the buy's entry price of $150.00 - order rejected"));
        // A limit entry is its limit price.
        place("BUY", 5, "\"orderType\":\"LIMIT\",\"limitPrice\":145,\"bracketTakeProfitPrice\":160,\"bracketStopLossPrice\":150")
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ---- Activation ----

    @Test
    @DisplayName("when the buy fills, a take-profit limit and a stop-loss stop open as linked, good-till-cancelled sells")
    void exitsOpenWhenTheBuyFills() throws Exception {
        UUID parent = fillBracketBuy(10);

        List<Order> exits = exitsOf(parent);
        assertEquals(2, exits.size());
        Order limit = exit(exits, Order.OrderType.LIMIT);
        Order stop = exit(exits, Order.OrderType.STOP);
        assertEquals(0, new BigDecimal("160.00").compareTo(limit.getLimitPrice()));
        assertEquals(0, new BigDecimal("140.00").compareTo(stop.getStopPrice()));
        for (Order leg : exits) {
            assertEquals(Order.Status.OPEN, leg.getStatus());
            assertEquals(Order.Side.SELL, leg.getSide());
            assertEquals(10L, leg.getQuantity());
            assertEquals(Order.TimeInForce.GTC, leg.getTimeInForce());
            assertEquals(parent, leg.getParentOrderId());
            assertNotNull(leg.getOcoGroupId());
        }
        assertEquals(limit.getOcoGroupId(), stop.getOcoGroupId());
    }

    @Test
    @DisplayName("a buy that waits OPEN opens its exits only when the engine fills it")
    void exitsOpenAfterABackgroundFill() throws Exception {
        UUID parent = openOrder("BUY", 10, "\"orderType\":\"LIMIT\",\"limitPrice\":145,"
                + "\"bracketTakeProfitPrice\":160,\"bracketStopLossPrice\":140");
        assertTrue(exitsOf(parent).isEmpty());

        quotes("144.40", "144.90");
        engine.evaluateOpenOrders();

        assertEquals(Order.Status.FILLED, orderRepository.findById(parent).orElseThrow().getStatus());
        assertEquals(2, exitsOf(parent).size());
    }

    @Test
    @DisplayName("an order that was refused opens no exits")
    void noExitsForARejectedBuy() throws Exception {
        UUID parent = UUID.fromString(json(place("BUY", 5000, "\"bracketTakeProfitPrice\":160,\"bracketStopLossPrice\":140")
                .andExpect(jsonPath("$.status").value("REJECTED")).andReturn().getResponse().getContentAsString())
                .get("orderId").asText());
        assertTrue(exitsOf(parent).isEmpty());
    }

    // ---- Holds ----

    @Test
    @DisplayName("both exits sell the same shares, so they hold them once")
    void holdsAreCountedOncePerGroup() throws Exception {
        UUID parent = fillBracketBuy(10);
        UUID stop = exit(exitsOf(parent), Order.OrderType.STOP).getOrderId();

        assertEquals(10L, orderRepository.sumOpenSellQuantity(OWNER_ACCOUNT, AAPL, UUID.fromString("00000000-0000-0000-0000-000000000000")));
        // Filling a leg isn't held back by its own sibling.
        assertEquals(0L, orderRepository.sumOpenSellQuantity(OWNER_ACCOUNT, AAPL, stop));

        // 25 owned less 10 held leaves 15 free: 15 can be sold, 16 can't.
        place("SELL", 15, "\"orderType\":\"LIMIT\",\"limitPrice\":170").andExpect(jsonPath("$.status").value("OPEN"));
        place("SELL", 1, "\"orderType\":\"LIMIT\",\"limitPrice\":171").andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ---- One cancels the other ----

    @Test
    @DisplayName("when the stop-loss fills, the take-profit is cancelled")
    void stopFillCancelsTakeProfit() throws Exception {
        UUID parent = fillBracketBuy(10);

        quotes("139.20", "139.70");
        engine.evaluateOpenOrders();

        List<Order> exits = exitsOf(parent);
        assertEquals(Order.Status.FILLED, exit(exits, Order.OrderType.STOP).getStatus());
        Order takeProfit = exit(exits, Order.OrderType.LIMIT);
        assertEquals(Order.Status.CANCELLED, takeProfit.getStatus());
        assertNotNull(takeProfit.getCancelledAt());
    }

    @Test
    @DisplayName("when the take-profit fills, the stop-loss is cancelled")
    void takeProfitFillCancelsStop() throws Exception {
        UUID parent = fillBracketBuy(10);

        quotes("161.00", "161.50");
        engine.evaluateOpenOrders();

        List<Order> exits = exitsOf(parent);
        assertEquals(Order.Status.FILLED, exit(exits, Order.OrderType.LIMIT).getStatus());
        assertEquals(Order.Status.CANCELLED, exit(exits, Order.OrderType.STOP).getStatus());
    }

    @Test
    @DisplayName("if the price has already fallen through the stop when the exits open, the stop sells on the engine's next pass")
    void gapThroughTheStopSellsAtOnce() throws Exception {
        UUID parent = fillBracketBuy(10);

        // The price gapped far below the stop-loss before the engine's next pass. Activation never
        // rejects a stop the price has passed; the engine fills it.
        quotes("120.00", "120.50");
        assertEquals(1, engine.evaluateOpenOrders());

        List<Order> exits = exitsOf(parent);
        Order stop = exit(exits, Order.OrderType.STOP);
        assertEquals(Order.Status.FILLED, stop.getStatus());
        assertEquals(Order.Status.CANCELLED, exit(exits, Order.OrderType.LIMIT).getStatus());
    }

    @Test
    @DisplayName("a leg isn't filled once its sibling has executed: the first one wins")
    void secondLegCannotFillAfterTheFirst() throws Exception {
        UUID parent = fillBracketBuy(10);
        List<Order> exits = exitsOf(parent);
        UUID limit = exit(exits, Order.OrderType.LIMIT).getOrderId();
        UUID stop = exit(exits, Order.OrderType.STOP).getOrderId();
        // The take-profit has executed and its cancel of the stop hasn't happened yet.
        jdbc.update("UPDATE trading.orders SET status = 'ACCEPTED' WHERE order_id = ?", limit);

        quotes("139.20", "139.70");
        assertEquals(0, engine.evaluateOpenOrders());

        assertEquals(Order.Status.CANCELLED, orderRepository.findById(stop).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("cancelling one leg by hand leaves the other working as a normal order")
    void manualCancelLeavesTheOtherLeg() throws Exception {
        UUID parent = fillBracketBuy(10);
        List<Order> exits = exitsOf(parent);
        UUID stop = exit(exits, Order.OrderType.STOP).getOrderId();
        UUID limit = exit(exits, Order.OrderType.LIMIT).getOrderId();

        openOrderService.cancel(OWNER, stop);

        assertEquals(Order.Status.CANCELLED, orderRepository.findById(stop).orElseThrow().getStatus());
        assertEquals(Order.Status.OPEN, orderRepository.findById(limit).orElseThrow().getStatus());
        // The remaining leg holds its shares on its own.
        assertEquals(10L, orderRepository.sumOpenSellQuantity(OWNER_ACCOUNT, AAPL, UUID.fromString("00000000-0000-0000-0000-000000000000")));

        // ... and still executes.
        quotes("161.00", "161.50");
        engine.evaluateOpenOrders();
        assertEquals(Order.Status.FILLED, orderRepository.findById(limit).orElseThrow().getStatus());
    }

    // ---- Editing ----

    @Test
    @DisplayName("a quantity change to one leg applies to both")
    void quantityEditAppliesToBothLegs() throws Exception {
        UUID parent = fillBracketBuy(10);
        UUID limit = exit(exitsOf(parent), Order.OrderType.LIMIT).getOrderId();

        modificationService.modify(OWNER, limit, new ModifyOrderRequest(6L, null, null, null, null, null));

        for (Order leg : exitsOf(parent)) {
            assertEquals(6L, leg.getQuantity());
        }
        assertEquals(6L, orderRepository.sumOpenSellQuantity(OWNER_ACCOUNT, AAPL, UUID.fromString("00000000-0000-0000-0000-000000000000")));
    }

    @Test
    @DisplayName("a quantity change beyond the shares owned is refused for both legs")
    void quantityEditIsValidatedOnce() throws Exception {
        UUID parent = fillBracketBuy(10);
        UUID limit = exit(exitsOf(parent), Order.OrderType.LIMIT).getOrderId();

        assertThrows(ResponseStatusException.class,
                () -> modificationService.modify(OWNER, limit, new ModifyOrderRequest(26L, null, null, null, null, null)));

        for (Order leg : exitsOf(parent)) {
            assertEquals(10L, leg.getQuantity());
        }
    }

    // ---- Helpers ----

    private UUID fillBracketBuy(long quantity) throws Exception {
        String body = place("BUY", quantity, "\"bracketTakeProfitPrice\":160,\"bracketStopLossPrice\":140")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json(body).get("orderId").asText());
    }

    private List<Order> exitsOf(UUID parent) {
        return orderRepository.findAll().stream().filter(o -> parent.equals(o.getParentOrderId())).toList();
    }

    private static Order exit(List<Order> exits, Order.OrderType type) {
        return exits.stream().filter(o -> o.getOrderType() == type).findFirst().orElseThrow();
    }

    private ResultActions place(String side, long quantity, String extra) throws Exception {
        return mockMvc.perform(post("/api/order/orders").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + OWNER_ACCOUNT + "\",\"symbol\":\"AAPL\",\"side\":\"" + side
                        + "\",\"quantity\":" + quantity + "," + extra + "}"));
    }

    private UUID openOrder(String side, long quantity, String extra) throws Exception {
        String body = place(side, quantity, extra).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json(body).get("orderId").asText());
    }

    private void quotes(String bid, String ask) {
        when(currentQuoteService.getFreshQuotes(anyString())).thenReturn(Map.of("AAPL", quote(bid, ask)));
    }

    private static QuoteSnapshot quote(String bid, String ask) {
        return new QuoteSnapshot("AAPL", new BigDecimal(bid), 100L, new BigDecimal(ask), 100L,
                new BigDecimal(bid), 100L, "NASDAQ", OffsetDateTime.now());
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
