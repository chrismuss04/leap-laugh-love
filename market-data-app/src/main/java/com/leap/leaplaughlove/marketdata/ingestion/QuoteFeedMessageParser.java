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

    /**
     * Parses a raw pipe-delimited quote feed line into a {@link QuoteFeedMessage}.
     * @param rawLine the raw feed line, in
     *     symbol|bidPrice|bidSize|askPrice|askSize|lastPrice|lastSize|exchange|sequenceNumber|quoteTimestamp
     *     format
     * @return the parsed, structurally-valid quote feed message
     * @throws QuoteParseException if the line is blank, has the wrong number of fields, or
     *     any field fails to parse or fails its structural validation
     */
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

    /**
     * Validates that a field is present and non-blank.
     * @param rawLine the raw feed line, for error reporting
     * @param value the raw field value
     * @param fieldName the field's name, for error reporting
     * @return the trimmed field value
     * @throws QuoteParseException if the value is null or blank
     */
    private static String requireNonBlank(String rawLine, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new QuoteParseException(rawLine, fieldName + " must not be blank");
        }
        return value.trim();
    }

    /**
     * Parses a price field, requiring it to be a positive number.
     * @param rawLine the raw feed line, for error reporting
     * @param value the raw field value
     * @param fieldName the field's name, for error reporting
     * @return the parsed price
     * @throws QuoteParseException if the value is not a number or is not positive
     */
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

    /**
     * Parses a size field, requiring it to be a non-negative integer.
     * @param rawLine the raw feed line, for error reporting
     * @param value the raw field value
     * @param fieldName the field's name, for error reporting
     * @return the parsed size
     * @throws QuoteParseException if the value is not an integer or is negative
     */
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

    /**
     * Parses the sequence number field.
     * @param rawLine the raw feed line, for error reporting
     * @param value the raw field value
     * @return the parsed sequence number
     * @throws QuoteParseException if the value is not an integer
     */
    private static long parseSequenceNumber(String rawLine, String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new QuoteParseException(rawLine, "sequenceNumber is not a valid integer: " + value);
        }
    }

    /**
     * Parses the quote timestamp field.
     * @param rawLine the raw feed line, for error reporting
     * @param value the raw field value, in ISO-8601 offset date-time format
     * @return the parsed timestamp
     * @throws QuoteParseException if the value is not a valid ISO-8601 timestamp
     */
    private static OffsetDateTime parseTimestamp(String rawLine, String value) {
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new QuoteParseException(rawLine,
                    "quoteTimestamp is not a valid ISO-8601 timestamp: " + value);
        }
    }
}
