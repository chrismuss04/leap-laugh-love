package com.leap.leaplaughlove.marketdata.ingestion;

/**
 * Thrown when a raw quote feed line is structurally malformed and cannot be parsed.
 */
public class QuoteParseException extends RuntimeException {

    private final String rawLine;

    /**
     * Creates a new QuoteParseException for the given raw line and reason.
     * @param rawLine the raw feed line that failed to parse
     * @param reason a human-readable explanation of why parsing failed
     */
    public QuoteParseException(String rawLine, String reason) {
        super(reason);
        this.rawLine = rawLine;
    }

    /**
     * Gets the raw feed line that failed to parse.
     * @return the rawLine that caused this exception
     */
    public String getRawLine() {
        return rawLine;
    }
}
