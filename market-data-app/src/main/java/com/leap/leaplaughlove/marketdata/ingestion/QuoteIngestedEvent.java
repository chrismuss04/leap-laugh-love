package com.leap.leaplaughlove.marketdata.ingestion;

/**
 * Published by {@link QuoteIngestionService} after a quote is parsed, validated, and
 * persisted, so other components can react to newly ingested quotes.
 * @param quoteState the snapshot of the accepted quote
 */
public record QuoteIngestedEvent(QuoteState quoteState) {
}
