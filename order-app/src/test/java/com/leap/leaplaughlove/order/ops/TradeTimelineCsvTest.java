package com.leap.leaplaughlove.order.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TradeTimelineCsv Tests")
class TradeTimelineCsvTest {

    private static final UUID ORDER_ID = UUID.fromString("3f2a0000-0000-0000-0000-000000000001");

    private static TradeTimeline timeline(TimelineStep... steps) {
        TradeSummary trade = new TradeSummary(ORDER_ID, OffsetDateTime.parse("2026-10-08T14:02:05Z"),
                "alice.johnson@leap.com", "ACC-1001", "AAPL", "BUY", 10, "FILLED");
        return new TradeTimeline(trade, new BigDecimal("150.00"), null, List.of(steps));
    }

    @Test
    @DisplayName("Writes a header and one row per step, with times in UTC")
    void rows() {
        String csv = TradeTimelineCsv.write(timeline(
                new TimelineStep(OffsetDateTime.parse("2026-10-08T16:02:05.1+02:00"), TimelineStepType.SUBMITTED,
                        "BUY 10 AAPL placed"),
                new TimelineStep(OffsetDateTime.parse("2026-10-08T14:02:06Z"), TimelineStepType.FILLED, "Order filled")));

        assertEquals("order_id,time_utc,step,description\r\n"
                + ORDER_ID + ",2026-10-08T14:02:05.100Z,SUBMITTED,BUY 10 AAPL placed\r\n"
                + ORDER_ID + ",2026-10-08T14:02:06.000Z,FILLED,Order filled\r\n", csv);
    }

    @Test
    @DisplayName("A timeline with no steps is just the header")
    void noSteps() {
        assertEquals("order_id,time_utc,step,description\r\n", TradeTimelineCsv.write(timeline()));
    }

    @Test
    @DisplayName("Quotes cells with commas, quotes or line breaks")
    void quoting() {
        assertEquals("\"bid $1, ask $2\"", TradeTimelineCsv.cell("bid $1, ask $2"));
        assertEquals("\"the \"\"quoted\"\" price\"", TradeTimelineCsv.cell("the \"quoted\" price"));
        assertEquals("\"line one\nline two\"", TradeTimelineCsv.cell("line one\nline two"));
        assertEquals("plain", TradeTimelineCsv.cell("plain"));
        assertEquals("", TradeTimelineCsv.cell(null));
    }

    @Test
    @DisplayName("Defuses text a spreadsheet would run as a formula")
    void formulaInjection() {
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", TradeTimelineCsv.cell("=HYPERLINK(\"x\")"));
        assertEquals("'+1", TradeTimelineCsv.cell("+1"));
        assertEquals("'-1", TradeTimelineCsv.cell("-1"));
        assertEquals("'@SUM(A1)", TradeTimelineCsv.cell("@SUM(A1)"));
        assertEquals("Order rejected: =1+1", TradeTimelineCsv.cell("Order rejected: =1+1"));
    }
}
