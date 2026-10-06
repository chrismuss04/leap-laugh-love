package com.leap.leaplaughlove.order.events;

import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderEventsProducerWarmup Tests")
class OrderEventsProducerWarmupTest {

    private static final List<PartitionInfo> PARTITIONS = List.of(
            new PartitionInfo("order-events", 0, null, null, null));

    @Mock private KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;

    private OrderEventsProducerWarmup warmup(boolean enabled, long timeoutSeconds) {
        return new OrderEventsProducerWarmup(kafkaTemplate, enabled, "order-events", timeoutSeconds);
    }

    @Test
    @DisplayName("connects on the first attempt when the broker answers")
    void connectsFirstTime() {
        when(kafkaTemplate.partitionsFor("order-events")).thenReturn(PARTITIONS);

        assertTrue(warmup(true, 30).connect());
        verify(kafkaTemplate, times(1)).partitionsFor("order-events");
    }

    @Test
    @DisplayName("retries a cold-start metadata timeout until the topic is found")
    void retriesUntilConnected() {
        when(kafkaTemplate.partitionsFor("order-events"))
                .thenThrow(new TimeoutException("Topic order-events not present in metadata after 2000 ms."))
                .thenReturn(PARTITIONS);

        assertTrue(warmup(true, 30).connect());
        verify(kafkaTemplate, times(2)).partitionsFor("order-events");
    }

    @Test
    @DisplayName("gives up without throwing once the warmup time is up")
    void givesUpWhenBrokerUnreachable() {
        when(kafkaTemplate.partitionsFor("order-events"))
                .thenThrow(new TimeoutException("Topic order-events not present in metadata after 2000 ms."));

        OrderEventsProducerWarmup warmup = warmup(true, 0);
        assertFalse(assertDoesNotThrow(warmup::connect));
        verify(kafkaTemplate, atLeastOnce()).partitionsFor("order-events");
    }

    @Test
    @DisplayName("does nothing when publishing is off")
    void skippedWhenDisabled() {
        warmup(false, 30).onApplicationReady();

        verifyNoInteractions(kafkaTemplate);
    }
}
