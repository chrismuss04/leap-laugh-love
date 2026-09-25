package com.leap.leaplaughlove.marketdata.history;

import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Writes a large set of new candles in one transaction, as fast as the database allows. Used by
 * the history backfill, which writes a few million rows on a fresh database before the service
 * is ready.
 *
 * <p>On Postgres it streams the rows with {@code COPY}, which loads them without per-row
 * statement, JPA entity or index-lookup overhead - several times faster than even batched
 * {@code INSERT}s, which matters most on the small VMs the stack is often brought up on. Any
 * other database (the H2 the tests run against) falls back to {@code saveAll}.
 */
@Component
public class PriceCandleBulkWriter {

    private static final String COPY_SQL = "COPY marketdata.price_candles "
            + "(instrument_id, bucket_seconds, bucket_start, open, high, low, close) FROM STDIN";

    private final DataSource dataSource;
    private final PriceCandleRepository candleRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates a new PriceCandleBulkWriter.
     * @param dataSource the data source whose transaction-bound connection the COPY runs on
     * @param candleRepository repository used when the database doesn't support COPY
     * @param transactionTemplate template used to write each set of candles in one transaction
     */
    public PriceCandleBulkWriter(DataSource dataSource,
                                 PriceCandleRepository candleRepository,
                                 TransactionTemplate transactionTemplate) {
        this.dataSource = dataSource;
        this.candleRepository = candleRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Inserts the candles in one transaction: either all of them are stored or none are.
     * @param candles new candles, none of which may already exist
     */
    public void write(List<PriceCandle> candles) {
        transactionTemplate.executeWithoutResult(status -> {
            // The transaction's own connection, so the COPY commits or rolls back with it.
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                if (connection.isWrapperFor(PGConnection.class)) {
                    copy(connection, candles);
                } else {
                    candleRepository.saveAll(candles);
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("Failed to write " + candles.size() + " candles", ex);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private static void copy(Connection connection, List<PriceCandle> candles) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            // Skips waiting for this transaction's commit to reach disk - on a slow VM disk that
            // wait is a large share of a small transaction. Still atomic: a crash can only lose
            // the most recent commits whole, which leaves those instruments with no candles, and
            // the backfill regenerates those on the next start.
            statement.execute("SET LOCAL synchronous_commit TO OFF");
        }
        connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY_SQL, new StringReader(toCopyText(candles)));
    }

    /**
     * Renders candles in COPY's default text format: tab-separated columns, one row per line.
     * No value can contain a tab, newline or backslash, so none needs escaping.
     */
    static String toCopyText(List<PriceCandle> candles) {
        StringBuilder rows = new StringBuilder(candles.size() * 128);
        for (PriceCandle candle : candles) {
            rows.append(candle.getInstrument().getInstrumentId()).append('\t')
                    .append(candle.getBucketSeconds()).append('\t')
                    .append(candle.getBucketStart().toInstant()).append('\t')
                    .append(candle.getOpen().toPlainString()).append('\t')
                    .append(candle.getHigh().toPlainString()).append('\t')
                    .append(candle.getLow().toPlainString()).append('\t')
                    .append(candle.getClose().toPlainString()).append('\n');
        }
        return rows.toString();
    }
}
