package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    protected Quote() {
    }

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

    public UUID getQuoteId() { return quoteId; }
    public SimulatedInstrument getInstrument() { return instrument; }
    public BigDecimal getBidPrice() { return bidPrice; }
    public long getBidSize() { return bidSize; }
    public BigDecimal getAskPrice() { return askPrice; }
    public long getAskSize() { return askSize; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public long getLastSize() { return lastSize; }
    public String getExchange() { return exchange; }
    public long getSequenceNumber() { return sequenceNumber; }
    public OffsetDateTime getQuoteTimestamp() { return quoteTimestamp; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
