package com.coffeepoint.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topic.order-completed:order-completed}")
    private String orderCompletedTopic;

    @Bean
    public NewTopic orderCompletedTopic() {
        return TopicBuilder.name(orderCompletedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
