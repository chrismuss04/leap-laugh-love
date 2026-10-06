package com.leap.leaplaughlove.order.events;

import org.apache.kafka.common.PartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Connects the order-events producer to the broker at startup, before the first order completes.
 *
 * <p>A producer's first send has to connect and fetch the topic's metadata, which can take longer
 * than the {@code max.block.ms} a send is allowed to wait, so without this the first event after
 * every start was lost even though the topic existed. Fetching the topic's partitions here does
 * that work up front: once it succeeds, sends only wait on the broker when it is actually down.
 *
 * <p>Runs ahead of the other ready listeners, so seeded fills start publishing afterwards. Each
 * attempt is itself capped at {@code max.block.ms}, so it retries until the warmup time is up,
 * then logs and carries on: the app still starts without Kafka, and sends retry the connection.
 */
@Component
public class OrderEventsProducerWarmup {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsProducerWarmup.class);

    private static final long RETRY_PAUSE_MS = 500;

    private final KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;
    private final boolean enabled;
    private final String topic;
    private final Duration timeout;

    /**
     * Constructs an OrderEventsProducerWarmup.
     * @param kafkaTemplate the template OrderEventPublisher sends with
     * @param enabled false when publishing is off, which skips the warmup
     * @param topic the topic events are published to
     * @param timeoutSeconds how long to keep trying to reach the broker
     */
    public OrderEventsProducerWarmup(KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate,
                                     @Value("${reporting.events.enabled:true}") boolean enabled,
                                     @Value("${reporting.events.topic:order-events}") String topic,
                                     @Value("${reporting.events.warmup-seconds:30}") long timeoutSeconds) {
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
        this.topic = topic;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /**
     * Warms up the producer once the app is ready, ahead of the other ready listeners.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onApplicationReady() {
        connect();
    }

    /**
     * Fetches the topic's partitions until it succeeds or the warmup time is up. Never throws.
     * @return true if the producer reached the broker and found the topic
     */
    boolean connect() {
        if (!enabled) {
            return false;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                List<PartitionInfo> partitions = kafkaTemplate.partitionsFor(topic);
                if (partitions != null && !partitions.isEmpty()) {
                    log.info("Order events producer connected: {} has {} partition(s)", topic, partitions.size());
                    return true;
                }
            } catch (RuntimeException ex) {
                if (System.nanoTime() - deadline >= 0) {
                    log.warn("Order events producer could not reach {} after {} attempt(s); events will be "
                            + "lost until it can: {}", topic, attempts, ex.getMessage());
                    return false;
                }
                log.debug("Order events producer not connected yet (attempt {}): {}", attempts, ex.getMessage());
            }
            if (System.nanoTime() - deadline >= 0) {
                log.warn("Order events topic {} has no partitions after {} attempt(s)", topic, attempts);
                return false;
            }
            try {
                Thread.sleep(RETRY_PAUSE_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
