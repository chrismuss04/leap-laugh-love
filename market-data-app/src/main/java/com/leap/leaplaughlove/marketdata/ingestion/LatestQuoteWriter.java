package com.leap.leaplaughlove.marketdata.ingestion;

import org.postgresql.PGConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.UUID;

/**
 * Stores the latest quote per instrument: marketdata.quotes holds one row per instrument, replaced
 * in place, instead of a new row every tick. Nothing reads past quotes, and inserting one row per
 * instrument per tick kept the database busy and the table growing without limit.
 *
 * <p>
 * Each call writes every quote it's given as one batch: a single round trip, however many
 * instruments ticked. On Postgres that's an {@code INSERT ... ON CONFLICT} upsert; any other
 * database (the H2 the tests run against) uses H2's own {@code MERGE ... KEY}.
 */
@Component
public class LatestQuoteWriter {

    private static final String POSTGRES_UPSERT = """
            INSERT INTO marketdata.quotes (quote_id, instrument_id, bid_price, bid_size, ask_price,
                ask_size, last_price, last_size, exchange, sequence_number, quote_timestamp, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (instrument_id) DO UPDATE SET
                bid_price = EXCLUDED.bid_price, bid_size = EXCLUDED.bid_size,
                ask_price = EXCLUDED.ask_price, ask_size = EXCLUDED.ask_size,
                last_price = EXCLUDED.last_price, last_size = EXCLUDED.last_size,
                exchange = EXCLUDED.exchange, sequence_number = EXCLUDED.sequence_number,
                quote_timestamp = EXCLUDED.quote_timestamp, received_at = EXCLUDED.received_at
            """;

    private static final String H2_MERGE = """
            MERGE INTO marketdata.quotes (quote_id, instrument_id, bid_price, bid_size, ask_price,
                ask_size, last_price, last_size, exchange, sequence_number, quote_timestamp, received_at)
            KEY (instrument_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    /**
     * Creates a new LatestQuoteWriter.
     * @param jdbc access to marketdata.quotes
     */
    public LatestQuoteWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes each quote its instrument's stored quote, in one batch.
     * @param quotes at most one quote per instrument
     */
    public void upsert(Collection<Quote> quotes) {
        if (quotes.isEmpty()) {
            return;
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String sql = connection.isWrapperFor(PGConnection.class) ? POSTGRES_UPSERT : H2_MERGE;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (Quote quote : quotes) {
                    bind(statement, quote);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private static void bind(PreparedStatement statement, Quote quote) throws SQLException {
        // A new id for the instrument's first row; an existing row keeps its own.
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, quote.getInstrument().getInstrumentId());
        statement.setBigDecimal(3, quote.getBidPrice());
        statement.setLong(4, quote.getBidSize());
        statement.setBigDecimal(5, quote.getAskPrice());
        statement.setLong(6, quote.getAskSize());
        statement.setBigDecimal(7, quote.getLastPrice());
        statement.setLong(8, quote.getLastSize());
        statement.setString(9, quote.getExchange());
        statement.setLong(10, quote.getSequenceNumber());
        statement.setObject(11, quote.getQuoteTimestamp());
        statement.setObject(12, quote.getReceivedAt());
    }
}
