package com.leap.leaplaughlove.marketdata.ingestion;

/** Thrown when a raw quote feed line is structurally malformed and cannot be parsed. */
public class QuoteParseException extends RuntimeException {

    private final String rawLine;

    public QuoteParseException(String rawLine, String reason) {
        super(reason);
        this.rawLine = rawLine;
    }

    public String getRawLine() {
        return rawLine;
    }
}
