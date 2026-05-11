package com.coffeepoint.domain.outbox.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Transactional Outbox Pattern
 *
 * 이벤트를 주문과 같은 트랜잭션으로 DB에 저장한 후,
 * 별도 스케줄러가 폴링하여 Kafka로 전송한다.
 *
 * 장점:
 * - 주문 커밋과 이벤트 저장이 원자적 → 이벤트 유실 불가
 * - Kafka 장애 시에도 이벤트가 DB에 보존됨
 * - At-Least-Once 전달 보장
 */
@Entity
@Table(name = "event_outbox", indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, createdAt"),
        @Index(name = "idx_outbox_status_processed", columnList = "status, processedAt")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 100)
    private String partitionKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    private int retryCount;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    @Builder
    public EventOutbox(String topic, String partitionKey, String payload) {
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
    }

    public void markProcessing() {
        this.status = OutboxStatus.PROCESSING;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.processedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.retryCount++;
        if (this.retryCount >= 5) {
            this.status = OutboxStatus.DEAD;
        } else {
            this.status = OutboxStatus.PENDING; // PROCESSING → PENDING 복원 (재시도 대상)
        }
    }
}
