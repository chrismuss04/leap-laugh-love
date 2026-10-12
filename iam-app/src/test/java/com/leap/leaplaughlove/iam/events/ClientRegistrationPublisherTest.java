package com.leap.leaplaughlove.iam.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientRegistrationPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ClientRegistrationEvent> kafka = mock(KafkaTemplate.class);
    private final ClientRegistrationEvent event = new ClientRegistrationEvent(UUID.randomUUID(),
            OffsetDateTime.parse("2026-10-06T12:00:00Z"));

    // Verify registration events reach Kafka only after commit, never on rollback or outside a transaction.
    @Test
    void committedOnly() {
        when(kafka.send("client-register", event.clientId().toString(), event))
                .thenReturn(CompletableFuture.completedFuture(null));
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:registration-events");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(ClientRegistrationPublisher.class,
                    () -> new ClientRegistrationPublisher(kafka, true, "client-register"));
            context.refresh();
            context.publishEvent(event);
            transaction.executeWithoutResult(status -> {
                context.publishEvent(event);
                status.setRollbackOnly();
            });
            verifyNoInteractions(kafka);
            transaction.executeWithoutResult(status -> {
                context.publishEvent(event);
                verifyNoInteractions(kafka);
            });
            verify(kafka).send("client-register", event.clientId().toString(), event);
        }
    }

    // Verify disabled reporting never contacts Kafka.
    @Test
    void disabled() {
        new ClientRegistrationPublisher(kafka, false, "client-register").publish(event);
        verifyNoInteractions(kafka);
    }

    // Verify synchronous and asynchronous broker failures do not escape into registration.
    @Test
    void brokerFailure() {
        var publisher = new ClientRegistrationPublisher(kafka, true, "client-register");
        when(kafka.send("client-register", event.clientId().toString(), event))
                .thenThrow(new IllegalStateException("broker unavailable"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("delivery failed")));
        assertDoesNotThrow(() -> publisher.publish(event));
        assertDoesNotThrow(() -> publisher.publish(event));
    }

    // Verify the JSON contract contains exactly the two required fields and an ISO timestamp.
    @Test
    void jsonContract() throws Exception {
        var mapper = new ObjectMapper();
        var headers = new RecordHeaders();
        try (var serializer = ClientRegistrationKafkaConfig.valueSerializer(mapper)) {
            var json = mapper.readTree(serializer.serialize("client-register", headers, event));
            assertEquals(2, json.size());
            assertEquals(event.clientId().toString(), json.get("clientId").asText());
            assertEquals(event.registeredAt(), OffsetDateTime.parse(json.get("registered_at").asText()));
            assertEquals(0, headers.toArray().length);
        }
    }
}
