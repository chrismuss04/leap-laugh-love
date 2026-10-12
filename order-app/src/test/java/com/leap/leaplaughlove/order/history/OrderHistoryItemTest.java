package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.order.execution.ExecutionItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OrderHistoryItem Unit Tests")
class OrderHistoryItemTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-01-01T10:00:00Z");

    private static OrderHistoryItem item(List<ExecutionItem> executions) {
        return new OrderHistoryItem(UUID.randomUUID(), "AAPL", "BUY", 3L, "FILLED", AT, AT, executions);
    }

    private static ExecutionItem execution(long quantity) {
        return new ExecutionItem(UUID.randomUUID(), quantity, new BigDecimal("10"), AT);
    }

    @Test
    @DisplayName("exposes its first execution as the single execution")
    void firstExecution() {
        ExecutionItem first = execution(1);

        assertEquals(first, item(List.of(first, execution(2))).execution());
    }

    @Test
    @DisplayName("has no execution when there are none, or when the list is missing")
    void noExecution() {
        assertNull(item(List.of()).execution());
        assertNull(item(null).execution());
        assertTrue(item(null).executions().isEmpty());
    }

    @Test
    @DisplayName("keeps an immutable copy of its executions")
    void copiesExecutions() {
        List<ExecutionItem> source = new ArrayList<>(List.of(execution(1)));
        OrderHistoryItem item = item(source);

        source.add(execution(2));

        assertEquals(1, item.executions().size());
        assertThrows(UnsupportedOperationException.class, () -> item.executions().add(execution(3)));
    }
}
