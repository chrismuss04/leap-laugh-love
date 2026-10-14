package com.leap.leaplaughlove.order.ops;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A trade's timeline as CSV: one row per step, times in UTC.
 */
public final class TradeTimelineCsv {

    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private TradeTimelineCsv() {
    }

    public static String write(TradeTimeline timeline) {
        StringBuilder csv = new StringBuilder("order_id,time_utc,step,description\r\n");
        String orderId = timeline.trade().orderId().toString();
        for (TimelineStep step : timeline.steps()) {
            csv.append(orderId).append(',')
                    .append(step.at().withOffsetSameInstant(ZoneOffset.UTC).format(UTC_TIME)).append(',')
                    .append(step.type().name()).append(',')
                    .append(cell(step.description())).append("\r\n");
        }
        return csv.toString();
    }

    // RFC 4180 quoting. A leading = + - @ would run as a spreadsheet formula, and reasons can
    // come from other services, so those cells get an apostrophe prefix.
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
