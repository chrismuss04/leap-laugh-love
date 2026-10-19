package com.leap.leaplaughlove.reporting.recap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and reads clients' recaps in reporting.client_recaps, through the recap write pool: the
 * only place the reporting service writes. Each recap is one row of JSON, so opening a recap is
 * one primary key lookup.
 */
@Repository
public class RecapSnapshotRepository {

    // A rerun of a month replaces its rows: the latest prices and rankings win.
    private static final String UPSERT_SQL = """
            INSERT INTO reporting.client_recaps (client_id, month, schema_version, payload, generated_at)
            VALUES (:clientId, :month, :schemaVersion, CAST(:payload AS JSONB), :generatedAt)
            ON CONFLICT (client_id, month) DO UPDATE
                SET schema_version = EXCLUDED.schema_version,
                    payload = EXCLUDED.payload,
                    generated_at = EXCLUDED.generated_at
            """;

    private static final String FIND_SQL = """
            SELECT schema_version, payload::text AS payload, generated_at
            FROM reporting.client_recaps
            WHERE client_id = :clientId AND month = :month
            """;

    // A month's recaps are out of date when any is in an older JSON shape (RecapService won't serve
    // those), or when the ETL loaded a fill after they were built (it was down, or still catching
    // up, when the job ran). The slack covers an ETL transaction that stamped loaded_at just before
    // the build read the fills but committed just after.
    private static final String IS_UP_TO_DATE_SQL = """
            WITH built AS (
                SELECT MIN(generated_at) AS generated_at, BOOL_AND(schema_version = :schemaVersion) AS current
                FROM reporting.client_recaps
                WHERE month = :month
            )
            SELECT built.generated_at IS NOT NULL AND built.current AND NOT EXISTS (
                SELECT 1 FROM reporting.orders
                WHERE status = 'FILLED' AND completed_at < :before
                  AND loaded_at > built.generated_at - INTERVAL '1 minute')
            FROM built
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    /**
     * Creates the repository. Its JdbcTemplate and transactions are its own, not beans, so the
     * reports keep Spring Boot's, on the read-only pool.
     * @param recapWriteDataSource the recap write pool
     * @param objectMapper writes and reads the recap JSON
     */
    public RecapSnapshotRepository(@Qualifier("recapWriteDataSource") DataSource recapWriteDataSource,
                                   ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(recapWriteDataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(recapWriteDataSource));
        this.objectMapper = objectMapper;
    }

    /**
     * Stores a month's recaps in one transaction, replacing any already stored for the same
     * clients, so a reader never sees half a month.
     * @param month the month the recaps cover
     * @param recaps each client's recap
     * @param generatedAt when they were built
     */
    public void saveMonth(YearMonth month, Map<UUID, RecapPayload> recaps, OffsetDateTime generatedAt) {
        MapSqlParameterSource[] rows = recaps.entrySet().stream()
                .map(entry -> new MapSqlParameterSource()
                        .addValue("clientId", entry.getKey())
                        .addValue("month", month.atDay(1))
                        .addValue("schemaVersion", RecapCalculator.SCHEMA_VERSION)
                        .addValue("payload", toJson(entry.getValue()))
                        .addValue("generatedAt", generatedAt))
                .toArray(MapSqlParameterSource[]::new);
        transaction.executeWithoutResult(status -> jdbc.batchUpdate(UPSERT_SQL, rows));
    }

    /**
     * A client's stored recap of a month.
     * @param clientId the client
     * @param month the month
     * @return the recap, or empty when none is stored
     */
    public Optional<StoredRecap> find(UUID clientId, YearMonth month) {
        List<StoredRecap> found = jdbc.query(FIND_SQL,
                new MapSqlParameterSource("clientId", clientId).addValue("month", month.atDay(1)),
                (rs, row) -> new StoredRecap(month, rs.getInt("schema_version"), rs.getString("payload"),
                        rs.getObject("generated_at", OffsetDateTime.class)));
        return found.stream().findFirst();
    }

    /**
     * Whether a month's recaps are stored in the current JSON shape and no fill they cover has been
     * loaded since they were built.
     * @param month the month
     * @return false when the month has no recaps, any is in an older shape, or a fill up to its end
     *         arrived after them
     */
    public boolean isUpToDate(YearMonth month) {
        return Boolean.TRUE.equals(jdbc.queryForObject(IS_UP_TO_DATE_SQL,
                new MapSqlParameterSource("month", month.atDay(1))
                        .addValue("schemaVersion", RecapCalculator.SCHEMA_VERSION)
                        .addValue("before", month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC)),
                Boolean.class));
    }

    /**
     * Reads a stored recap's JSON.
     * @param stored the stored recap
     * @return the recap
     */
    public RecapPayload payload(StoredRecap stored) {
        try {
            return objectMapper.readValue(stored.payloadJson(), RecapPayload.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Stored recap of " + stored.month() + " can't be read", e);
        }
    }

    private String toJson(RecapPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Recap of " + payload.month() + " can't be written", e);
        }
    }

    /**
     * A recap as stored.
     * @param month the month it covers
     * @param schemaVersion the version of the JSON's shape
     * @param payloadJson the recap, as JSON
     * @param generatedAt when it was built
     */
    public record StoredRecap(YearMonth month, int schemaVersion, String payloadJson, OffsetDateTime generatedAt) {}
}
