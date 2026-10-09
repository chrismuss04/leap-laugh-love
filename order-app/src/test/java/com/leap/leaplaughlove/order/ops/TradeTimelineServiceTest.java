package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.order.execution.ExecutionQuote;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.CashEntryRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.ExecutionRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.OrderRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.PositionMovementRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.TradeSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradeTimelineService Tests")
class TradeTimelineServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("3f2a0000-0000-0000-0000-000000000001");
    private static final UUID EXECUTION_ID = UUID.fromString("e1000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime SUBMITTED = OffsetDateTime.parse("2026-10-08T14:02:05.100Z");
    private static final OffsetDateTime QUOTED = OffsetDateTime.parse("2026-10-08T14:02:05.000Z");
    private static final OffsetDateTime ACCEPTED = OffsetDateTime.parse("2026-10-08T14:02:05.200Z");
    private static final OffsetDateTime EXECUTED = OffsetDateTime.parse("2026-10-08T14:02:05.300Z");
    private static final OffsetDateTime SETTLED = OffsetDateTime.parse("2026-10-08T14:02:05.400Z");
    private static final OffsetDateTime REJECTED = OffsetDateTime.parse("2026-10-08T14:02:05.500Z");

    @Mock
    private TradeOpsRepository repository;

    private TradeTimelineService service;

    @BeforeEach
    void setUp() {
        service = new TradeTimelineService(repository);
        lenient().when(repository.findExecutions(ORDER_ID)).thenReturn(List.of());
        lenient().when(repository.findCashEntries(ORDER_ID)).thenReturn(List.of());
        lenient().when(repository.findPositionMovements(ORDER_ID)).thenReturn(List.of());
    }

    private static TradeSummary summary(String status) {
        return new TradeSummary(ORDER_ID, SUBMITTED, "alice.johnson@leap.com", "ACC-1001", "AAPL", "BUY", 10, status);
    }

    private void givenOrder(OrderRecord order) {
        when(repository.findOrder(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private static ExecutionQuote quote(String bid, String ask) {
        return new ExecutionQuote(new BigDecimal(bid), new BigDecimal(ask), new BigDecimal("150.00"), QUOTED, "NASDAQ");
    }

    private static List<TimelineStepType> types(TradeTimeline timeline) {
        return timeline.steps().stream().map(TimelineStep::type).toList();
    }

    private static String description(TradeTimeline timeline, TimelineStepType type) {
        return timeline.steps().stream().filter(s -> s.type() == type).findFirst().orElseThrow().description();
    }

    @Test
    @DisplayName("A filled order reads placement, pricing, acceptance, execution, settlement and fill in order")
    void filledOrder() {
        givenOrder(new OrderRecord(summary("FILLED"), ACCEPTED, null, EXECUTED, null,
                new BigDecimal("150.00"), new BigDecimal("1.00")));
        when(repository.findExecutions(ORDER_ID)).thenReturn(List.of(new ExecutionRecord(EXECUTION_ID, "FILLED", 10L,
                new BigDecimal("150.050000"), EXECUTED, "Executed at market price", quote("149.95", "150.05"))));
        when(repository.findCashEntries(ORDER_ID)).thenReturn(List.of(
                new CashEntryRecord("BUY_SETTLEMENT", new BigDecimal("-1500.50"), "USD", SETTLED)));
        when(repository.findPositionMovements(ORDER_ID)).thenReturn(List.of(
                new PositionMovementRecord("BUY_FILL", 10, new BigDecimal("1500.50"), EXECUTED)));

        TradeTimeline timeline = service.getTimeline(ORDER_ID);

        assertEquals(List.of(TimelineStepType.PRICED, TimelineStepType.SUBMITTED, TimelineStepType.ACCEPTED,
                TimelineStepType.EXECUTED, TimelineStepType.POSITION_UPDATED, TimelineStepType.FILLED,
                TimelineStepType.CASH_SETTLED), types(timeline));
        assertEquals("BUY 10 AAPL placed in account ACC-1001 by alice.johnson@leap.com; client was quoted $150.00"
                + " with a 1.00% price tolerance", description(timeline, TimelineStepType.SUBMITTED));
        assertEquals("Market quote from NASDAQ: bid $149.95, ask $150.05, last $150.00; a BUY prices at $150.05,"
                + " 0.03% from the client's quoted $150.00, within the 1.00% tolerance",
                description(timeline, TimelineStepType.PRICED));
        assertEquals(QUOTED, timeline.steps().get(0).at());
        assertEquals("Executed 10 at $150.05 (execution " + EXECUTION_ID + ")",
                description(timeline, TimelineStepType.EXECUTED));
        assertEquals("Cash ledger BUY_SETTLEMENT: -$1500.50 USD", description(timeline, TimelineStepType.CASH_SETTLED));
        assertEquals("Holding BUY_FILL: +10 AAPL, cost +$1500.50", description(timeline, TimelineStepType.POSITION_UPDATED));
        assertEquals(new BigDecimal("150.00"), timeline.quotedPrice());
        assertEquals(summary("FILLED"), timeline.trade());
    }

    @Test
    @DisplayName("A price-tolerance rejection shows the quote that broke the tolerance, once")
    void toleranceRejection() {
        String reason = "Price moved 1.33% from your quoted $150.00 to $152.00, beyond your 1.00% tolerance - order rejected";
        givenOrder(new OrderRecord(summary("REJECTED"), null, REJECTED, null, reason,
                new BigDecimal("150.00"), new BigDecimal("1.00")));
        when(repository.findExecutions(ORDER_ID)).thenReturn(List.of(
                new ExecutionRecord(EXECUTION_ID, "REJECTED", null, null, REJECTED, reason, quote("151.90", "152.00"))));

        TradeTimeline timeline = service.getTimeline(ORDER_ID);

        assertEquals(List.of(TimelineStepType.PRICED, TimelineStepType.SUBMITTED, TimelineStepType.REJECTED), types(timeline));
        assertTrue(description(timeline, TimelineStepType.PRICED)
                .endsWith("a BUY prices at $152.00, 1.33% from the client's quoted $150.00, beyond the 1.00% tolerance"));
        assertEquals("Order rejected: " + reason, description(timeline, TimelineStepType.REJECTED));
    }

    @Test
    @DisplayName("A settlement refusal shows the execution, then the rejection with account-app's reason")
    void settlementRefusal() {
        String reason = "Settlement failed: Cannot settle sell: insufficient position quantity";
        givenOrder(new OrderRecord(summary("REJECTED"), ACCEPTED, REJECTED, null, reason, null, null));
        when(repository.findExecutions(ORDER_ID)).thenReturn(List.of(new ExecutionRecord(EXECUTION_ID, "FILLED", 10L,
                new BigDecimal("150.05"), EXECUTED, "Executed at market price", quote("149.95", "150.05"))));

        TradeTimeline timeline = service.getTimeline(ORDER_ID);

        assertEquals(List.of(TimelineStepType.PRICED, TimelineStepType.SUBMITTED, TimelineStepType.ACCEPTED,
                TimelineStepType.EXECUTED, TimelineStepType.REJECTED), types(timeline));
        assertTrue(description(timeline, TimelineStepType.PRICED).endsWith("a BUY prices at $150.05"),
                "no slippage is shown when the client had no quoted price");
    }

    @Test
    @DisplayName("A rejected execution whose reason differs from the order's is shown on its own")
    void separateExecutionRejection() {
        givenOrder(new OrderRecord(summary("REJECTED"), null, REJECTED, null, "Market quote unavailable", null, null));
        when(repository.findExecutions(ORDER_ID)).thenReturn(List.of(
                new ExecutionRecord(EXECUTION_ID, "REJECTED", null, null, REJECTED, "Venue closed", null)));

        TradeTimeline timeline = service.getTimeline(ORDER_ID);

        assertEquals("Execution rejected: Venue closed", description(timeline, TimelineStepType.EXECUTION_REJECTED));
        assertFalse(types(timeline).contains(TimelineStepType.PRICED), "a rejection that used no quote has no pricing step");
    }

    @Test
    @DisplayName("A fill with no stored quote says the quote wasn't recorded")
    void fillWithoutQuote() {
        givenOrder(new OrderRecord(summary("FILLED"), ACCEPTED, null, EXECUTED, null, null, null));
        when(repository.findExecutions(ORDER_ID)).thenReturn(List.of(new ExecutionRecord(EXECUTION_ID, "FILLED", 10L,
                new BigDecimal("150.0000"), EXECUTED, "Executed at market price", null)));

        TradeTimeline timeline = service.getTimeline(ORDER_ID);

        assertTrue(description(timeline, TimelineStepType.PRICED).startsWith("Market quote not recorded"));
        assertEquals(EXECUTED, timeline.steps().stream()
                .filter(s -> s.type() == TimelineStepType.PRICED).findFirst().orElseThrow().at());
        assertEquals("Executed 10 at $150.00 (execution " + EXECUTION_ID + ")",
                description(timeline, TimelineStepType.EXECUTED));
    }


    @Test
    @DisplayName("An unknown order is a 404")
    void unknownOrder() {
        when(repository.findOrder(ORDER_ID)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getTimeline(ORDER_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("Search passes trimmed criteria and turns UTC days into a half-open range")
    void searchCriteria() {
        when(repository.search(any())).thenReturn(List.of(summary("FILLED")));

        List<TradeSummary> results = service.search(null, "  alice.johnson@leap.com ", " ", "aapl",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8));

        assertEquals(1, results.size());
        ArgumentCaptor<TradeSearch> captor = ArgumentCaptor.forClass(TradeSearch.class);
        verify(repository).search(captor.capture());
        TradeSearch search = captor.getValue();
        assertEquals("alice.johnson@leap.com", search.clientEmail());
        assertNull(search.accountNumber());
        assertEquals("aapl", search.symbol());
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00Z"), search.from());
        assertEquals(OffsetDateTime.parse("2026-10-09T00:00:00Z"), search.to());
    }

    @Test
    @DisplayName("Search needs at least one criterion")
    void searchNeedsCriterion() {
        assertThrows(IllegalArgumentException.class, () -> service.search(null, " ", null, "", null, null));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Search rejects from after to")
    void searchDatesWrongWayRound() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 1)));
        verifyNoInteractions(repository);
    }
}
