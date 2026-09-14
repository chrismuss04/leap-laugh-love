package com.leap.leaplaughlove.marketdata.ingestion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Parses raw pipe-delimited quote feed lines into {@link QuoteFeedMessage}. Structural/type
 * validation only - business-rule validation (crossed quotes, known instruments, staleness,
 * sequencing) happens downstream in QuoteIngestionService, which has the context to judge it.
 */
public final class QuoteFeedMessageParser {

    private static final String DELIMITER = "\\|";
    private static final int FIELD_COUNT = 10;

    private QuoteFeedMessageParser() {
    }

    public static QuoteFeedMessage parse(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            throw new QuoteParseException(rawLine, "line is blank");
        }
        String[] fields = rawLine.split(DELIMITER);
        if (fields.length != FIELD_COUNT) {
            throw new QuoteParseException(rawLine,
                    "expected " + FIELD_COUNT + " fields but found " + fields.length);
        }

        String symbol = requireNonBlank(rawLine, fields[0], "symbol");
        BigDecimal bidPrice = parsePositivePrice(rawLine, fields[1], "bidPrice");
        long bidSize = parseNonNegativeSize(rawLine, fields[2], "bidSize");
        BigDecimal askPrice = parsePositivePrice(rawLine, fields[3], "askPrice");
        long askSize = parseNonNegativeSize(rawLine, fields[4], "askSize");
        BigDecimal lastPrice = parsePositivePrice(rawLine, fields[5], "lastPrice");
        long lastSize = parseNonNegativeSize(rawLine, fields[6], "lastSize");
        String exchange = requireNonBlank(rawLine, fields[7], "exchange");
        long sequenceNumber = parseSequenceNumber(rawLine, fields[8]);
        OffsetDateTime quoteTimestamp = parseTimestamp(rawLine, fields[9]);

        return new QuoteFeedMessage(symbol.toUpperCase(), bidPrice, bidSize, askPrice, askSize,
                lastPrice, lastSize, exchange, sequenceNumber, quoteTimestamp);
    }

    private static String requireNonBlank(String rawLine, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new QuoteParseException(rawLine, fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static BigDecimal parsePositivePrice(String rawLine, String value, String fieldName) {
        BigDecimal price;
        try {
            price = new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            throw new QuoteParseException(rawLine, fieldName + " is not a valid number: " + value);
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new QuoteParseException(rawLine, fieldName + " must be positive: " + value);
        }
        return price;
    }

    private static long parseNonNegativeSize(String rawLine, String value, String fieldName) {
        long size;
        try {
            size = Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new QuoteParseException(rawLine, fieldName + " is not a valid integer: " + value);
        }
        if (size < 0) {
            throw new QuoteParseException(rawLine, fieldName + " must not be negative: " + value);
        }
        return size;
    }

    private static long parseSequenceNumber(String rawLine, String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new QuoteParseException(rawLine, "sequenceNumber is not a valid integer: " + value);
        }
    }

    private static OffsetDateTime parseTimestamp(String rawLine, String value) {
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new QuoteParseException(rawLine,
                    "quoteTimestamp is not a valid ISO-8601 timestamp: " + value);
        }
    }
}
