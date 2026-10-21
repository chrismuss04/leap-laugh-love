package com.leap.leaplaughlove.account.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Account numbering ("Brokerage 1", "Brokerage 2", ...) against the database: each client's
 * sequence is its own, so other clients opening accounts never shift or block it.
 * Not @Transactional, so each createAccount commits as it does in production.
 */
@SpringBootTest
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("Account numbering")
class AccountSeqIntegrationTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private AccountService accountService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void numberSeededAccounts() {
        // Each seeded client already has one account: their Brokerage 1.
        jdbc.update("UPDATE trading.accounts SET account_seq = 1");
    }

    private Account openAs(UUID clientId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(clientId, null, List.of()));
        try {
            return accountService.createAccount(new CreateAccountRequest("USD"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("numbers each client's accounts on their own, so two clients can both have a Brokerage 2")
    void numbersArePerClient() {
        assertThat(openAs(OWNER).getAccountSeq()).isEqualTo(2);
        assertThat(openAs(OTHER).getAccountSeq()).isEqualTo(2);
        assertThat(openAs(OWNER).getAccountSeq()).isEqualTo(3);
    }

    @Test
    @DisplayName("two clients opening their second account at the same moment both get Brokerage 2")
    void simultaneousOpensByDifferentClients() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Account> owner = pool.submit(() -> { start.await(); return openAs(OWNER); });
            Future<Account> other = pool.submit(() -> { start.await(); return openAs(OTHER); });
            start.countDown();

            assertThat(owner.get(10, TimeUnit.SECONDS).getAccountSeq()).isEqualTo(2);
            assertThat(other.get(10, TimeUnit.SECONDS).getAccountSeq()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList(
                "SELECT account_seq FROM trading.accounts WHERE account_seq = 2", Integer.class)).hasSize(2);
    }
}
