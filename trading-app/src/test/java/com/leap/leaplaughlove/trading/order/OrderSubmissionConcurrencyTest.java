package com.leap.leaplaughlove.trading.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionId;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import com.leap.leaplaughlove.trading.quote.CurrentQuoteService;
import com.leap.leaplaughlove.trading.quote.QuoteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Not @Transactional: each order submission needs its own DB transaction so the two
 * requests genuinely race against each other rather than sharing a single test transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/order_submission_test_setup.sql")
@DisplayName("Order Submission Concurrency Tests")
class OrderSubmissionConcurrencyTest {

    private static final UUID CLIENT_OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String CLIENT_OWNER_EMAIL = "owner@example.com";
    private static final UUID ACCOUNT_OWNER_USD_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INSTRUMENT_AAPL_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private CashLedgerRepository cashLedgerRepository;
    @Autowired private PositionRepository positionRepository;

    @MockBean private CurrentQuoteService currentQuoteService;

    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerToken = jwtService.generateToken(CLIENT_OWNER_ID, CLIENT_OWNER_EMAIL);

        cashLedgerRepository.save(new CashLedgerEntry(
                ACCOUNT_OWNER_USD_ID, "DEPOSIT", new BigDecimal("10000.00"), "USD",
                OffsetDateTime.now(), "Initial test funding"));

        when(currentQuoteService.getCurrentQuote(eq("AAPL"))).thenReturn(new QuoteSnapshot(
                "AAPL", new BigDecimal("149.50"), 100L, new BigDecimal("150.00"), 100L,
                new BigDecimal("149.75"), 100L, "NASDAQ", OffsetDateTime.now()));
    }

    @Test
    @DisplayName("Two concurrent BUY orders on the same account/instrument both apply without losing an update")
    void concurrentBuyOrders_bothApplyToFinalPosition() throws Exception {
        // Seeded starting position for this account/instrument is 25 shares.
        OrderSubmissionRequest requestA = new OrderSubmissionRequest(
                ACCOUNT_OWNER_USD_ID, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"));
        OrderSubmissionRequest requestB = new OrderSubmissionRequest(
                ACCOUNT_OWNER_USD_ID, "AAPL", null, Order.Side.BUY, 5, new BigDecimal("150.00"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<MvcResult> submitA = () -> {
            ready.countDown();
            go.await();
            return submitOrder(requestA);
        };
        Callable<MvcResult> submitB = () -> {
            ready.countDown();
            go.await();
            return submitOrder(requestB);
        };

        Future<MvcResult> futureA = executor.submit(submitA);
        Future<MvcResult> futureB = executor.submit(submitB);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();

        MvcResult resultA = futureA.get(10, TimeUnit.SECONDS);
        MvcResult resultB = futureB.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(200, resultA.getResponse().getStatus());
        assertEquals(200, resultB.getResponse().getStatus());

        Position position = positionRepository.findById(
                new PositionId(ACCOUNT_OWNER_USD_ID, INSTRUMENT_AAPL_ID)).orElseThrow();
        assertEquals(25L + 10L + 5L, position.getQuantity());
    }

    private MvcResult submitOrder(OrderSubmissionRequest request) throws Exception {
        return mockMvc.perform(post("/api/trading/orders")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
    }
}
