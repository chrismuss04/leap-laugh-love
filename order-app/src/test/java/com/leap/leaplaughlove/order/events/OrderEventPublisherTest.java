package com.leap.leaplaughlove.order.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderEventPublisher Tests")
class OrderEventPublisherTest {

    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.of(2026, 10, 5, 14, 32, 7, 118_000_000, ZoneOffset.UTC);
    private static final OffsetDateTime COMPLETED_AT = SUBMITTED_AT.plusNanos(284_000_000);

    @Mock private KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;
    @Mock private AccountRepository accountRepository;

    private OrderEventPublisher publisher;
    private Account account;
    private Instrument aapl;

    @BeforeEach
    void setUp() {
        publisher = new OrderEventPublisher(kafkaTemplate, accountRepository, true, "order-events");
        account = new Account(UUID.randomUUID(), UUID.randomUUID(), "ACC-TEST-01", "ACTIVE", "USD", true,
                SUBMITTED_AT.minusDays(30));
        aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        when(accountRepository.findById(account.getAccountId())).thenReturn(Optional.of(account));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, OrderCompletedEvent>) null));
    }

    private Order filledOrder() {
        return new Order(UUID.randomUUID(), account, aapl, Order.Side.BUY, 10L, Order.Status.FILLED,
                SUBMITTED_AT, SUBMITTED_AT, null, COMPLETED_AT, null);
    }

    private Order rejectedOrder() {
        Order order = new Order(UUID.randomUUID(), account, aapl, Order.Side.SELL, 5L, Order.Status.ACCEPTED,
                SUBMITTED_AT, SUBMITTED_AT, null, null, null);
        order.markRejected("Settlement failed: insufficient position quantity", COMPLETED_AT);
        return order;
    }

    private OrderCompletedEvent sentEvent(String expectedKey) {
        ArgumentCaptor<OrderCompletedEvent> event = ArgumentCaptor.forClass(OrderCompletedEvent.class);
        verify(kafkaTemplate).send(eq("order-events"), eq(expectedKey), event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("publishes a FILLED order keyed by its id, with its owner and fill price")
    void publishesFilledOrder() {
        Order order = filledOrder();
        Execution execution = new Execution(order, 10L, new BigDecimal("187.4200"), Execution.Status.FILLED,
                "Executed at market price", COMPLETED_AT);

        publisher.publishFilled(order, execution);

        OrderCompletedEvent event = sentEvent(order.getOrderId().toString());
        assertNotNull(event.eventId());
        assertEquals(order.getOrderId(), event.orderId());
        assertEquals(account.getAccountId(), event.accountId());
        assertEquals(account.getClientId(), event.clientId());
        assertEquals(aapl.getInstrumentId(), event.instrumentId());
        assertEquals("AAPL", event.symbol());
        assertEquals("BUY", event.side());
        assertEquals(10L, event.quantity());
        assertEquals("FILLED", event.status());
        assertEquals(new BigDecimal("187.4200"), event.fillPrice());
        assertNull(event.rejectionReason());
        assertEquals(SUBMITTED_AT, event.submittedAt());
        assertEquals(COMPLETED_AT, event.completedAt());
    }

    @Test
    @DisplayName("publishes a REJECTED order with its reason, rejection time and no price")
    void publishesRejectedOrder() {
        Order order = rejectedOrder();

        publisher.publishRejected(order);

        OrderCompletedEvent event = sentEvent(order.getOrderId().toString());
        assertEquals("REJECTED", event.status());
        assertEquals("SELL", event.side());
        assertNull(event.fillPrice());
        assertEquals("Settlement failed: insufficient position quantity", event.rejectionReason());
        assertEquals(COMPLETED_AT, event.completedAt());
    }

    @Test
    @DisplayName("each message gets its own event id")
    void eventIdsAreUnique() {
        Order order = rejectedOrder();

        publisher.publishRejected(order);
        publisher.publishRejected(order);

        ArgumentCaptor<OrderCompletedEvent> events = ArgumentCaptor.forClass(OrderCompletedEvent.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(anyString(), anyString(), events.capture());
        assertFalse(events.getAllValues().get(0).eventId().equals(events.getAllValues().get(1).eventId()));
    }

    @Test
    @DisplayName("does nothing when publishing is turned off")
    void disabledPublishesNothing() {
        publisher = new OrderEventPublisher(kafkaTemplate, accountRepository, false, "order-events");

        publisher.publishRejected(rejectedOrder());

        verifyNoInteractions(kafkaTemplate, accountRepository);
    }

    @Test
    @DisplayName("a send that throws is logged, never thrown: the order is already committed")
    void sendFailureDoesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("Topic order-events not present in metadata"));

        assertDoesNotThrow(() -> publisher.publishRejected(rejectedOrder()));
    }

    @Test
    @DisplayName("a delivery that fails later is logged, never thrown")
    void deliveryFailureDoesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertDoesNotThrow(() -> publisher.publishRejected(rejectedOrder()));
    }

    @Test
    @DisplayName("an account that can't be found means no event, and no exception")
    void missingAccountSkipsEvent() {
        when(accountRepository.findById(any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> publisher.publishRejected(rejectedOrder()));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("serializes to the plain JSON reporting-etl reads: ISO timestamps, exact price, no type headers")
    void serializesToTheEventContract() throws Exception {
        Order order = filledOrder();
        OrderCompletedEvent event = new OrderCompletedEvent(UUID.randomUUID(), order.getOrderId(),
                account.getAccountId(), account.getClientId(), aapl.getInstrumentId(), "AAPL", "BUY", 10L,
                "FILLED", new BigDecimal("187.4200"), null, SUBMITTED_AT, COMPLETED_AT);
        RecordHeaders headers = new RecordHeaders();

        // The producer's own serializer. Given a mapper that still writes dates as timestamps
        // (Jackson's default), so this also proves the serializer doesn't depend on Boot's setting.
        ObjectMapper appMapper = Jackson2ObjectMapperBuilder.json().build();
        try (JsonSerializer<OrderCompletedEvent> serializer = OrderEventsKafkaConfig.valueSerializer(appMapper)) {
            byte[] bytes = serializer.serialize("order-events", headers, event);

            JsonNode json = new ObjectMapper().readTree(bytes);
            assertEquals(order.getOrderId().toString(), json.get("orderId").asText());
            assertEquals(account.getClientId().toString(), json.get("clientId").asText());
            assertEquals("FILLED", json.get("status").asText());
            assertEquals(10, json.get("quantity").asInt());
            assertTrue(json.get("fillPrice").isNumber());
            // Written as the exact decimal, not via a double: the ETL parses it straight into Decimal.
            assertTrue(new String(bytes).contains("\"fillPrice\":187.4200"), new String(bytes));
            assertTrue(json.get("rejectionReason").isNull());
            // ISO-8601 with an offset, which Python's datetime.fromisoformat reads.
            assertTrue(json.get("submittedAt").isTextual());
            assertEquals(SUBMITTED_AT.toInstant(), OffsetDateTime.parse(json.get("submittedAt").asText()).toInstant());
            assertEquals(COMPLETED_AT.toInstant(), OffsetDateTime.parse(json.get("completedAt").asText()).toInstant());
            assertFalse(headers.iterator().hasNext(), "no __TypeId__ header");
        }
    }
}
