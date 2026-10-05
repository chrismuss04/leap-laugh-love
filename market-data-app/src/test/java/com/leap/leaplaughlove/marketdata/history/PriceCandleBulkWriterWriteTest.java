package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PriceCandleBulkWriter write paths")
class PriceCandleBulkWriterWriteTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PriceCandleRepository repository = mock(PriceCandleRepository.class);
    private final PriceCandleBulkWriter writer = new PriceCandleBulkWriter(dataSource, repository,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private final List<PriceCandle> candles = List.of(new PriceCandle(
            new SimulatedInstrument(UUID.randomUUID(), "AAPL", "Apple Inc.", new BigDecimal("100"),
                    new BigDecimal("0.05"), new BigDecimal("0.2"), 1L, true),
            OffsetDateTime.parse("2026-01-01T10:00:00Z"), 60, new BigDecimal("1"), new BigDecimal("2"),
            new BigDecimal("0.5"), new BigDecimal("1.5")));

    @BeforeEach
    void connect() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
    }

    @Test
    @DisplayName("falls back to the repository when the database does not support COPY")
    void savesThroughTheRepositoryWithoutCopy() throws SQLException {
        when(connection.isWrapperFor(PGConnection.class)).thenReturn(false);

        writer.write(candles);

        verify(repository).saveAll(candles);
    }

    @Test
    @DisplayName("streams the candles with COPY on a PostgreSQL connection, relaxing the commit wait")
    void copiesOnPostgres() throws Exception {
        PGConnection postgres = mock(PGConnection.class);
        CopyManager copyManager = mock(CopyManager.class);
        Statement statement = mock(Statement.class);
        when(connection.isWrapperFor(PGConnection.class)).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.unwrap(PGConnection.class)).thenReturn(postgres);
        when(postgres.getCopyAPI()).thenReturn(copyManager);

        writer.write(candles);

        verify(statement).execute("SET LOCAL synchronous_commit TO OFF");
        verify(copyManager).copyIn(anyString(), any(Reader.class));
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("reports an I/O failure while copying as an unchecked exception")
    void copyIoFailure() throws Exception {
        PGConnection postgres = mock(PGConnection.class);
        CopyManager copyManager = mock(CopyManager.class);
        when(connection.isWrapperFor(PGConnection.class)).thenReturn(true);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.unwrap(PGConnection.class)).thenReturn(postgres);
        when(postgres.getCopyAPI()).thenReturn(copyManager);
        when(copyManager.copyIn(anyString(), any(Reader.class))).thenThrow(new IOException("pipe closed"));

        assertThrows(UncheckedIOException.class, () -> writer.write(candles));
    }

    @Test
    @DisplayName("reports a SQL failure with how many candles were being written")
    void sqlFailure() throws SQLException {
        when(connection.isWrapperFor(PGConnection.class)).thenThrow(new SQLException("connection lost"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> writer.write(candles));

        assertEquals("Failed to write 1 candles", ex.getMessage());
    }
}
