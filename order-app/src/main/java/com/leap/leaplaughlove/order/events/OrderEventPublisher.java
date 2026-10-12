package com.leap.leaplaughlove.order.events;

import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Publishes an {@link OrderCompletedEvent} to Kafka once an order reaches its final status.
 *
 * Callers publish only after the transaction that finished the order has committed, so an
 * order that rolled back is never reported, and they pass the order as it now stands. Nothing
 * here throws: the trade is already committed and must still succeed, so a failure to publish
 * (broker down, account not found) is logged and the event is lost. The send waits at most
 * {@code max.block.ms} for the broker, so a Kafka outage can't hold up an order response for long.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;
    private final AccountRepository accountRepository;
    private final boolean enabled;
    private final String topic;

    /**
     * Constructs an OrderEventPublisher.
     * @param kafkaTemplate sends the events
     * @param accountRepository looks up the client who owns the order's account
     * @param enabled false turns publishing off (tests, or a stack without Kafka)
     * @param topic the topic to publish to
     */
    public OrderEventPublisher(KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate,
                               AccountRepository accountRepository,
                               @Value("${reporting.events.enabled:true}") boolean enabled,
                               @Value("${reporting.events.topic:order-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.accountRepository = accountRepository;
        this.enabled = enabled;
        this.topic = topic;
    }

    /**
     * Publishes a FILLED order.
     * @param order the order, marked FILLED, with its instrument loaded
     * @param execution its FILLED execution, which carries the fill price
     */
    public void publishFilled(Order order, Execution execution) {
        publish(order, execution.getFillPrice(), order.getFilledAt());
    }

    /**
     * Publishes an order rejected after its execution, i.e. refused at settlement. Orders
     * rejected by validation never executed and are not published.
     * @param order the order, marked REJECTED, with its instrument loaded
     */
    public void publishRejected(Order order) {
        publish(order, null, order.getRejectedAt());
    }

    private void publish(Order order, BigDecimal fillPrice, OffsetDateTime completedAt) {
        if (!enabled) {
            return;
        }
        UUID orderId = order.getOrderId();
        try {
            OrderCompletedEvent event = new OrderCompletedEvent(
                    UUID.randomUUID(),
                    orderId,
                    order.getAccountId(),
                    clientIdOf(order),
                    order.getInstrument().getInstrumentId(),
                    order.getInstrument().getSymbol(),
                    order.getSide().name(),
                    order.getQuantity(),
                    order.getStatus().name(),
                    fillPrice,
                    order.getRejectionReason(),
                    order.getSubmittedAt(),
                    completedAt);
            // Keyed by order, so any later event for the same order lands on the same partition.
            kafkaTemplate.send(topic, orderId.toString(), event)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.warn("Order event for order {} was not delivered: {}", orderId, failure.getMessage());
                        }
                    });
        } catch (RuntimeException ex) {
            log.warn("Could not publish order event for order {}: {}", orderId, ex.getMessage());
        }
    }

    /**
     * The client who owns the order's account.
     * @param order the order
     * @return its account's client ID
     */
    private UUID clientIdOf(Order order) {
        return accountRepository.findById(order.getAccountId())
                .map(Account::getClientId)
                .orElseThrow(() -> new IllegalStateException("Account not found: " + order.getAccountId()));
    }
}
