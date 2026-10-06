// Password Recovery: exercise the reset-link queries against the real table.
package com.leap.leaplaughlove.iam.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Sql(scripts = {"/db/client_registration_test_setup.sql", "/db/client_profile_test_setup.sql"})
class PasswordResetTokenRepositoryTest {
    private static final UUID CLIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CLIENT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired private PasswordResetTokenRepository tokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private Instant now;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // The database keeps microseconds, so compare at a precision it stores exactly.
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?, 'carol@example.com', 'ACTIVE')",
                OTHER_CLIENT);
    }

    private void issue(UUID clientId, String hash, Instant expiresAt) {
        tx.executeWithoutResult(status ->
                tokens.create(UUID.randomUUID(), clientId, hash, now.minusSeconds(60), expiresAt));
    }

    private Optional<UUID> consume(String hash) {
        return tx.execute(status -> tokens.consume(hash, now));
    }

    @Test void consumeReturnsTheClientAndSpendsTheLink() {
        issue(CLIENT, "hash-a", now.plusSeconds(600));

        assertEquals(Optional.of(CLIENT), consume("hash-a"));
        assertEquals(Optional.empty(), consume("hash-a"));
    }

    @Test void consumeRejectsUnknownAndExpiredLinks() {
        issue(CLIENT, "hash-expired", now);

        assertEquals(Optional.empty(), consume("hash-unknown"));
        assertEquals(Optional.empty(), consume("hash-expired"));
        assertNull(jdbc.queryForObject(
                "SELECT used_at FROM iam.password_reset_tokens WHERE token_hash = 'hash-expired'",
                OffsetDateTime.class));
    }

    @Test void invalidateAllSpendsOnlyThatClientsLinks() {
        issue(CLIENT, "hash-a", now.plusSeconds(600));
        issue(CLIENT, "hash-b", now.plusSeconds(600));
        issue(OTHER_CLIENT, "hash-c", now.plusSeconds(600));

        tx.executeWithoutResult(status -> tokens.invalidateAll(CLIENT, now));

        assertEquals(Optional.empty(), consume("hash-a"));
        assertEquals(Optional.empty(), consume("hash-b"));
        assertEquals(Optional.of(OTHER_CLIENT), consume("hash-c"));
    }

    @Test void isUsableTracksTheLinkWithoutSpendingIt() {
        issue(CLIENT, "hash-a", now.plusSeconds(600));
        issue(CLIENT, "hash-expired", now);

        assertTrue(tokens.isUsable("hash-a", now));
        assertTrue(tokens.isUsable("hash-a", now));
        assertFalse(tokens.isUsable("hash-expired", now));
        assertFalse(tokens.isUsable("hash-unknown", now));

        assertEquals(Optional.of(CLIENT), consume("hash-a"));
        assertFalse(tokens.isUsable("hash-a", now));
    }

    @Test void writesRequireACallerTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> tokens.create(UUID.randomUUID(), CLIENT, "hash-a", now, now.plusSeconds(600)));
        assertThrows(IllegalTransactionStateException.class, () -> tokens.consume("hash-a", now));
    }
}
