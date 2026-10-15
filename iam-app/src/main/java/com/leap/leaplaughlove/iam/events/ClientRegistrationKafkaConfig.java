package com.leap.leaplaughlove.iam.events;

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

/** Uses ISO-8601 timestamps and no Java type headers for the Python ETL consumer. */
@Configuration
public class ClientRegistrationKafkaConfig {
    /**
     * Creates the producer factory for registration events.
     * @param properties the Spring Kafka producer settings
     * @param mapper the application's object mapper, copied so its own settings are untouched
     * @return a factory with string keys and JSON values
     */
    @Bean
    public ProducerFactory<String, ClientRegistrationEvent> registrationProducerFactory(
            KafkaProperties properties, ObjectMapper mapper) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null),
                new StringSerializer(), valueSerializer(mapper));
    }

    /**
     * Creates the template that sends registration events.
     * @param registrationProducerFactory the factory to send with
     * @return the Kafka template
     */
    @Bean
    public KafkaTemplate<String, ClientRegistrationEvent> registrationKafkaTemplate(
            ProducerFactory<String, ClientRegistrationEvent> registrationProducerFactory) {
        return new KafkaTemplate<>(registrationProducerFactory);
    }

    static JsonSerializer<ClientRegistrationEvent> valueSerializer(ObjectMapper mapper) {
        return new JsonSerializer<ClientRegistrationEvent>(mapper.copy().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).noTypeInfo();
    }
}
