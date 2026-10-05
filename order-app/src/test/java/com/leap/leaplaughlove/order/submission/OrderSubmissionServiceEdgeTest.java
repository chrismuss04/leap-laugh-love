package com.leap.leaplaughlove.order.submission;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.history.FillRecorder;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.instrument.InstrumentRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.order.validation.TradeValidationResult;
import com.leap.leaplaughlove.order.validation.TradeValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderSubmissionService edge cases")
class OrderSubmissionServiceEdgeTest {

    private final AccountClient accountClient = mock(AccountClient.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ExecutionRepository executions = mock(ExecutionRepository.class);
    private final PositionMovementRepository movements = mock(PositionMovementRepository.class);
    private final TradeValidationService validation = mock(TradeValidationService.class);
    private final CurrentQuoteService quotes = mock(CurrentQuoteService.class);

    private OrderSubmissionService service;
    private UUID accountId;
    private Instrument instrument;
    private AccountValidationDto account;
    private Order stored;

    @BeforeEach
    void setUp() {
        service = new OrderSubmissionService(accountClient, instruments, orders, executions,
                new FillRecorder(executions, movements, accountClient), validation, quotes,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        accountId = UUID.randomUUID();
        instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        account = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 0L, "USD", "ACC-1", null);
        when(instruments.findBySymbol("AAPL")).thenReturn(Optional.of(instrument));
        when(accountClient.getValidationData(any(), any())).thenReturn(account);
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            return stored;
        });
        when(orders.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(stored));
        when(executions.saveAndFlush(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validation.validateTrade(any(), any(), any(), anyLong(), any())).thenReturn(TradeValidationResult.accepted());
    }

    private OrderSubmissionRequest buy(String symbol, UUID instrumentId, BigDecimal quoted, BigDecimal slippage) {
        return new OrderSubmissionRequest(accountId, symbol, instrumentId, Order.Side.BUY, 10, quoted, slippage);
    }

    private OrderSubmissionRequest sell() {
        return new OrderSubmissionRequest(accountId, "AAPL", null, Order.Side.SELL, 10, null, null);
    }

    private void quote(String bid, String ask, String last) {
        when(quotes.getCurrentQuote("AAPL")).thenReturn(new QuoteSnapshot("AAPL",
                bid == null ? null : new BigDecimal(bid), 1, ask == null ? null : new BigDecimal(ask), 1,
                last == null ? null : new BigDecimal(last), 1, "NASDAQ", OffsetDateTime.now()));
    }

    private ResponseStatusException rejected(Runnable call) {
        return assertThrows(ResponseStatusException.class, call::run);
    }

    @Test
    @DisplayName("rejects a missing request or a non-positive quantity outright")
    void rejectsMalformedRequests() {
        assertEquals(HttpStatus.BAD_REQUEST, rejected(() -> service.submitOrder(null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected(() -> service.submitOrder(
                new OrderSubmissionRequest(accountId, "AAPL", null, Order.Side.BUY, 0, null, null))).getStatusCode());
        verify(orders, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("resolves an instrument by id, and 404s for an unknown id")
    void resolvesInstrumentById() {
        when(instruments.findById(instrument.getInstrumentId())).thenReturn(Optional.of(instrument));
        quote("149", "150", "149.5");
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500"), 10L, new BigDecimal("150")));

        assertEquals("FILLED", service.submitOrder(buy(null, instrument.getInstrumentId(), null, null)).status());

        UUID unknown = UUID.randomUUID();
        when(instruments.findById(unknown)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, rejected(() -> service.submitOrder(buy(null, unknown, null, null))).getStatusCode());
    }

    @Test
    @DisplayName("404s for an unknown symbol and 400s when neither id nor symbol is given")
    void unknownOrMissingInstrument() {
        when(instruments.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, rejected(() -> service.submitOrder(buy("nope", null, null, null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected(() -> service.submitOrder(buy(null, null, null, null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rejected(() -> service.submitOrder(buy("  ", null, null, null))).getStatusCode());
    }

    @Test
    @DisplayName("passes an account-app status error through, and turns any other failure into a 400")
    void accountValidationFailures() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "not yours"))
                .when(accountClient).getValidationData(any(), any());
        assertEquals(HttpStatus.FORBIDDEN, rejected(() -> service.submitOrder(buy("AAPL", null, null, null))).getStatusCode());

        doThrow(new IllegalStateException("boom")).when(accountClient).getValidationData(any(), any());
        ResponseStatusException ex = rejected(() -> service.submitOrder(buy("AAPL", null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Failed to validate account: boom", ex.getReason());
    }

    @Test
    @DisplayName("requires a quoted price when the account has a saved price tolerance")
    void savedToleranceNeedsQuotedPrice() {
        when(accountClient.getValidationData(any(), any())).thenReturn(new AccountValidationDto(
                true, true, new BigDecimal("10000.00"), 0L, "USD", "ACC-1", new BigDecimal("2.00")));

        ResponseStatusException ex = rejected(() -> service.submitOrder(buy("AAPL", null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("rejects the order when the market has no quote, or the quote service fails")
    void rejectsWithoutAQuote() {
        when(quotes.getCurrentQuote("AAPL")).thenReturn(null);
        assertEquals("REJECTED", service.submitOrder(buy("AAPL", null, null, null)).status());

        when(quotes.getCurrentQuote("AAPL")).thenThrow(new QuoteUnavailableException(null));
        OrderSubmissionResponse response = service.submitOrder(buy("AAPL", null, null, null));
        assertEquals("REJECTED", response.status());
        assertEquals("Market quote unavailable", response.rejectionReason());
    }

    @Test
    @DisplayName("fills a buy at the last price when the ask is missing or zero")
    void buyFallsBackToLastPrice() {
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500"), 10L, new BigDecimal("149.5")));

        quote("149", null, "149.5");
        assertEquals(new BigDecimal("149.5000"), service.submitOrder(buy("AAPL", null, null, null)).execution().fillPrice());

        quote("149", "0", "149.5");
        assertEquals(new BigDecimal("149.5000"), service.submitOrder(buy("AAPL", null, null, null)).execution().fillPrice());
    }

    @Test
    @DisplayName("fills a sell at the last price when the bid is missing or zero")
    void sellFallsBackToLastPrice() {
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500"), 0L, new BigDecimal("0")));

        quote(null, "150", "149.5");
        assertEquals(new BigDecimal("149.5000"), service.submitOrder(sell()).execution().fillPrice());

        quote("0", "150", "149.5");
        assertEquals(new BigDecimal("149.5000"), service.submitOrder(sell()).execution().fillPrice());
    }

    @Test
    @DisplayName("rejects the order when no executable price can be found at all")
    void rejectsWithoutAnyPrice() {
        quote("0", "0", null);
        assertEquals("REJECTED", service.submitOrder(buy("AAPL", null, null, null)).status());

        quote("0", "0", "0");
        assertEquals("REJECTED", service.submitOrder(sell()).status());
    }

    @Test
    @DisplayName("rejects the order when trade validation refuses it, without settling")
    void rejectsWhenValidationRefuses() {
        quote("149", "150", "149.5");
        when(validation.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.rejected("Insufficient buying power"));

        OrderSubmissionResponse response = service.submitOrder(buy("AAPL", null, null, null));

        assertEquals("REJECTED", response.status());
        assertEquals("Insufficient buying power", response.rejectionReason());
        assertNotNull(response.execution());
        verify(accountClient, never()).settleOrder(any(), any());
    }

    @Test
    @DisplayName("reports a zero balance when a rejection has no account data")
    void rejectionWithoutAccountDataReportsZero() {
        when(accountClient.getValidationData(any(), any())).thenReturn(null);
        quote("149", "150", "149.5");
        when(quotes.getCurrentQuote("AAPL")).thenThrow(new QuoteUnavailableException("down"));

        // With no validation data the service cannot even read the account number.
        assertThrows(RuntimeException.class, () -> service.submitOrder(buy("AAPL", null, null, null)));
    }
}
