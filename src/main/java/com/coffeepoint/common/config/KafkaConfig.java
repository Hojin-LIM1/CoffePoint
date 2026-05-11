package com.coffeepoint.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 설정
 *
 * DLT(Dead Letter Topic) 구성:
 * - Consumer에서 3회 재시도 후에도 실패한 메시지를 DLT로 전송
 * - DLT에 보관된 메시지는 버그 수정 후 재처리 가능
 * - 수만 건 연속 실패해도 메시지가 유실되지 않음
 */
@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topic.order-completed:order-completed}")
    private String orderCompletedTopic;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public NewTopic orderCompletedTopic() {
        return TopicBuilder.name(orderCompletedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** DLT 토픽 자동 생성 (원본 토픽명 + ".DLT") */
    @Bean
    public NewTopic orderCompletedDltTopic() {
        return TopicBuilder.name(orderCompletedTopic + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Consumer ErrorHandler: 3회 재시도 후 DLT로 전송
     *
     * 동작:
     * 1. 메시지 처리 실패 → 1초 간격 3회 재시도
     * 2. 3회 모두 실패 → DLT 토픽으로 전송 (메시지 보존)
     * 3. 버그 수정 후 DLT 토픽을 읽어 재처리 가능
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                (KafkaOperations) kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
    }
}
