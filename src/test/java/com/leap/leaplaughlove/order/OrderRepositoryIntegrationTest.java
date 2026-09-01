package com.leap.leaplaughlove.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

// Exercises the real derived query/index against Postgres instead of a mocked repository.
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class OrderRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("db/leap_laugh_love_schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void returnsOrdersForClientNewestFirstWithStableTieBreak() {
        UUID clientId = insertClient("client@example.com");
        Account account = persistAccount(clientId, "ACC-001");
        Instrument instrument = persistInstrument("AAPL");

        OffsetDateTime sameInstant = OffsetDateTime.parse("2026-08-01T10:00:00Z");
        Order older = persistOrder(account, instrument, sameInstant.minusDays(1));
        Order sameTimestampFirst = persistOrder(account, instrument, sameInstant);
        Order sameTimestampSecond = persistOrder(account, instrument, sameInstant);
        entityManager.flush();

        Page<Order> page = orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(
                clientId, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        Order expectedFirst = sameTimestampFirst.getOrderId().compareTo(sameTimestampSecond.getOrderId()) > 0
                ? sameTimestampFirst : sameTimestampSecond;
        assertThat(page.getContent().get(0).getOrderId()).isEqualTo(expectedFirst.getOrderId());
        assertThat(page.getContent()).extracting(Order::getOrderId).doesNotContain(older.getOrderId());
    }

    @Test
    void returnsEmptyPageForUnknownClient() {
        Page<Order> page = orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(
                UUID.randomUUID(), PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    private UUID insertClient(String email) {
        UUID clientId = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO iam.clients (client_id, email) VALUES (?1, ?2)")
                .setParameter(1, clientId)
                .setParameter(2, email)
                .executeUpdate();
        return clientId;
    }

    private Account persistAccount(UUID clientId, String accountNumber) {
        Account account = new Account();
        account.setClientId(clientId);
        account.setAccountNumber(accountNumber);
        account.setStatus("ACTIVE");
        account.setBaseCurrency("USD");
        account.setTradingEnabled(true);
        ReflectionTestUtils.setField(account, "createdAt", OffsetDateTime.now());
        return entityManager.persist(account);
    }

    private Instrument persistInstrument(String symbol) {
        Instrument instrument = new Instrument();
        instrument.setSymbol(symbol);
        instrument.setInstrumentName(symbol + " Inc.");
        instrument.setAssetClass("EQUITY");
        instrument.setMarket("NASDAQ");
        instrument.setCurrency("USD");
        instrument.setTradable(true);
        ReflectionTestUtils.setField(instrument, "createdAt", OffsetDateTime.now());
        return entityManager.persist(instrument);
    }

    private Order persistOrder(Account account, Instrument instrument, OffsetDateTime submittedAt) {
        Order order = new Order();
        order.setAccount(account);
        order.setInstrument(instrument);
        order.setSide(Order.Side.BUY);
        order.setQuantity(10);
        order.setStatus(Order.Status.SUBMITTED);
        ReflectionTestUtils.setField(order, "submittedAt", submittedAt);
        return entityManager.persist(order);
    }
}
