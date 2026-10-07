package com.leap.leaplaughlove.iam.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes committed registrations using the order publisher's best-effort convention.
 * Delivery failures are logged, not propagated to the already-committed registration.
 * There is no durable outbox: failures after Kafka retries can lose an event.
 */
@Component
public class ClientRegistrationPublisher {
    private static final Logger log = LoggerFactory.getLogger(ClientRegistrationPublisher.class);
    private final KafkaTemplate<String, ClientRegistrationEvent> kafka;
    private final boolean enabled;
    private final String topic;

    public ClientRegistrationPublisher(KafkaTemplate<String, ClientRegistrationEvent> kafka,
            @Value("${reporting.registrations.enabled:false}") boolean enabled,
            @Value("${reporting.registrations.topic:client-register}") String topic) {
        this.kafka = kafka;
        this.enabled = enabled;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ClientRegistrationEvent event) {
        if (!enabled) return;
        try {
            kafka.send(topic, event.clientId().toString(), event).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.warn("Registration event for client {} was not delivered: {}",
                            event.clientId(), failure.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Could not publish registration for client {}: {}", event.clientId(), ex.getMessage());
        }
    }
}
