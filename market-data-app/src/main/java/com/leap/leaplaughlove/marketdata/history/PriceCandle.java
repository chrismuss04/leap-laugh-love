package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "price_candles", schema = "marketdata")
public class PriceCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "candle_id")
    private UUID candleId;

    @ManyToOne
    @JoinColumn(name = "instrument_id", nullable = false)
    private SimulatedInstrument instrument;

    @Column(name = "bucket_start", nullable = false)
    private OffsetDateTime bucketStart;

    @Column(name = "open", nullable = false, precision = 18, scale = 6)
    private BigDecimal open;

    @Column(name = "high", nullable = false, precision = 18, scale = 6)
    private BigDecimal high;

    @Column(name = "low", nullable = false, precision = 18, scale = 6)
    private BigDecimal low;

    @Column(name = "close", nullable = false, precision = 18, scale = 6)
    private BigDecimal close;

    protected PriceCandle() {
    }

    public PriceCandle(SimulatedInstrument instrument, OffsetDateTime bucketStart,
                        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        this.instrument = instrument;
        this.bucketStart = bucketStart;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }

    public UUID getCandleId() { return candleId; }
    public SimulatedInstrument getInstrument() { return instrument; }
    public OffsetDateTime getBucketStart() { return bucketStart; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
}
