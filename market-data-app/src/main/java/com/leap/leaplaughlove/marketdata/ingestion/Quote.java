package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a single quote accepted by the ingestion pipeline for an instrument.
 * Contains bid/ask/last price and size, the exchange it was quoted on, the feed's
 * sequence number, and both the feed's own timestamp and this backend's ingestion time.
 */
@Entity
@Table(name = "quotes", schema = "marketdata")
public class Quote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "quote_id")
    private UUID quoteId;

    @ManyToOne
    @JoinColumn(name = "instrument_id", nullable = false)
    private SimulatedInstrument instrument;

    @Column(name = "bid_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal bidPrice;

    @Column(name = "bid_size", nullable = false)
    private long bidSize;

    @Column(name = "ask_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal askPrice;

    @Column(name = "ask_size", nullable = false)
    private long askSize;

    @Column(name = "last_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "last_size", nullable = false)
    private long lastSize;

    @Column(name = "exchange", nullable = false)
    private String exchange;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "quote_timestamp", nullable = false)
    private OffsetDateTime quoteTimestamp;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Quote() {
    }

    /**
     * Creates a new Quote entity with the specified details.
     * @param instrument the instrument this quote is for
     * @param bidPrice the bid price of the quote
     * @param bidSize the bid size of the quote
     * @param askPrice the ask price of the quote
     * @param askSize the ask size of the quote
     * @param lastPrice the last-traded price of the quote
     * @param lastSize the last-traded size of the quote
     * @param exchange the exchange the quote was sourced from
     * @param sequenceNumber the feed's sequence number for this quote
     * @param quoteTimestamp the timestamp assigned by the feed
     * @param receivedAt the timestamp this backend ingested the quote
     */
    public Quote(SimulatedInstrument instrument, BigDecimal bidPrice, long bidSize,
                 BigDecimal askPrice, long askSize, BigDecimal lastPrice, long lastSize,
                 String exchange, long sequenceNumber, OffsetDateTime quoteTimestamp,
                 OffsetDateTime receivedAt) {
        this.instrument = instrument;
        this.bidPrice = bidPrice;
        this.bidSize = bidSize;
        this.askPrice = askPrice;
        this.askSize = askSize;
        this.lastPrice = lastPrice;
        this.lastSize = lastSize;
        this.exchange = exchange;
        this.sequenceNumber = sequenceNumber;
        this.quoteTimestamp = quoteTimestamp;
        this.receivedAt = receivedAt;
    }

    /**
     * Gets the unique identifier of the quote.
     * @return the quoteId of the quote
     */
    public UUID getQuoteId() { return quoteId; }
    /**
     * Gets the instrument this quote is for.
     * @return the instrument of the quote
     */
    public SimulatedInstrument getInstrument() { return instrument; }
    /**
     * Gets the bid price of the quote.
     * @return the bidPrice of the quote
     */
    public BigDecimal getBidPrice() { return bidPrice; }
    /**
     * Gets the bid size of the quote.
     * @return the bidSize of the quote
     */
    public long getBidSize() { return bidSize; }
    /**
     * Gets the ask price of the quote.
     * @return the askPrice of the quote
     */
    public BigDecimal getAskPrice() { return askPrice; }
    /**
     * Gets the ask size of the quote.
     * @return the askSize of the quote
     */
    public long getAskSize() { return askSize; }
    /**
     * Gets the last-traded price of the quote.
     * @return the lastPrice of the quote
     */
    public BigDecimal getLastPrice() { return lastPrice; }
    /**
     * Gets the last-traded size of the quote.
     * @return the lastSize of the quote
     */
    public long getLastSize() { return lastSize; }
    /**
     * Gets the exchange the quote was sourced from.
     * @return the exchange of the quote
     */
    public String getExchange() { return exchange; }
    /**
     * Gets the feed's sequence number for this quote.
     * @return the sequenceNumber of the quote
     */
    public long getSequenceNumber() { return sequenceNumber; }
    /**
     * Gets the timestamp assigned by the feed.
     * @return the quoteTimestamp of the quote
     */
    public OffsetDateTime getQuoteTimestamp() { return quoteTimestamp; }
    /**
     * Gets the timestamp this backend ingested the quote.
     * @return the receivedAt timestamp of the quote
     */
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
