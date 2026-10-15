package com.leap.leaplaughlove.order.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradeOpsReportService Tests")
class TradeOpsReportServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-08T14:00:00Z");
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-10-08T00:00:00Z");

    @Mock
    private TradeOpsRepository repository;

    private TradeOpsReportService service;

    @BeforeEach
    void setUp() {
        service = new TradeOpsReportService(repository, Duration.ofMinutes(5),
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Rejections by reason cover the period given")
    void rejectionsByReason() {
        List<RejectionCount> rejections = List.of(new RejectionCount("Insufficient position", 3, NOW));
        when(repository.countRejectionsByReason(FROM, TO)).thenReturn(rejections);

        assertEquals(rejections, service.rejectionsByReason(FROM, TO));
    }

    @Test
    @DisplayName("Rejections by reason need a period that starts before it ends")
    void rejectionsNeedPeriod() {
        assertEquals("Give a time period to report on", assertThrows(IllegalArgumentException.class,
                () -> service.rejectionsByReason(null, TO)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.rejectionsByReason(FROM, null));
        assertEquals("from must be before to", assertThrows(IllegalArgumentException.class,
                () -> service.rejectionsByReason(TO, FROM)).getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Stuck orders are those still waiting to execute longer than the threshold after submission")
    void stuckOrders() {
        List<TradeSummary> stuck = List.of(new TradeSummary(UUID.randomUUID(), NOW.minusHours(1),
                "alice.johnson@leap.com", "ACC-1001", "AAPL", "BUY", 10, "SUBMITTED"));
        when(repository.findAwaitingExecutionSince(OffsetDateTime.parse("2026-10-08T13:55:00Z"))).thenReturn(stuck);

        assertEquals(stuck, service.stuckOrders());
    }
}
