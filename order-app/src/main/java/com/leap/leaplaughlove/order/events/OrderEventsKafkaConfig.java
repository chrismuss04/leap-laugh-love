package com.leap.leaplaughlove.order.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * The Kafka producer for order events. Connection and timeout settings come from
 * {@code spring.kafka.*} in application.yml; the serializers are set here so the JSON matches
 * the event contract reporting-etl reads.
 */
@Configuration
public class OrderEventsKafkaConfig {

    /**
     * The producer factory for order events, closed on shutdown so buffered events are sent.
     * @param kafkaProperties the spring.kafka settings
     * @param objectMapper the application's JSON mapper
     * @return the producer factory
     */
    @Bean
    public ProducerFactory<String, OrderCompletedEvent> orderEventsProducerFactory(KafkaProperties kafkaProperties,
                                                                                  ObjectMapper objectMapper) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null),
                new StringSerializer(), valueSerializer(objectMapper));
    }

    /**
     * The template OrderEventPublisher sends with.
     * @param orderEventsProducerFactory the producer factory
     * @return the template
     */
    @Bean
    public KafkaTemplate<String, OrderCompletedEvent> orderEventsKafkaTemplate(
            ProducerFactory<String, OrderCompletedEvent> orderEventsProducerFactory) {
        return new KafkaTemplate<>(orderEventsProducerFactory);
    }

    /**
     * Serializes events with a copy of the application's ObjectMapper that always writes
     * timestamps as ISO-8601 strings: spring-kafka's default mapper writes them as epoch numbers,
     * which reporting-etl rejects, and the copy keeps a change to the app-wide Jackson settings
     * from changing the event contract. No type headers: the consumer has no use for Java class
     * names.
     * @param objectMapper the application's JSON mapper
     * @return the value serializer
     */
    static JsonSerializer<OrderCompletedEvent> valueSerializer(ObjectMapper objectMapper) {
        ObjectMapper eventMapper = objectMapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new JsonSerializer<OrderCompletedEvent>(eventMapper).noTypeInfo();
    }
}
