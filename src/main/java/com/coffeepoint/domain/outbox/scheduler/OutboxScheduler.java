package com.coffeepoint.domain.outbox.scheduler;

import com.coffeepoint.domain.outbox.entity.EventOutbox;
import com.coffeepoint.domain.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 스케줄러 v2.1
 *
 * 아키텍처: 3-Phase 처리 (DB 커넥션 장기 점유 방지)
 *
 * Phase 1: [짧은 TX] PENDING → PROCESSING 상태 변경 + SKIP LOCKED
 * Phase 2: [TX 없음] Kafka 동기 전송 (DB 커넥션 미점유)
 * Phase 3: [짧은 TX] 전송 결과에 따라 SENT or PENDING 복원
 *
 * 이전 문제:
 *   단일 @Transactional 안에서 Kafka send().get() 호출
 *   → 50건 × 10초 = 최대 500초 DB 커넥션 + 락 점유
 *   → 커넥션 풀 고갈 위험
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxScheduler {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.scheduler.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedRateString = "${outbox.scheduler.rate:5000}")
    public void processOutbox() {
        // Phase 1: PENDING 이벤트 조회 + PROCESSING으로 전환 (짧은 TX)
        List<EventOutbox> events = claimPendingEvents();
        if (events.isEmpty()) return;

        // Phase 2: Kafka 전송 (TX 밖 — DB 커넥션 미점유)
        List<Long> sentIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();

        for (EventOutbox event : events) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
                sentIds.add(event.getId());
            } catch (Exception e) {
                failedIds.add(event.getId());
                log.error("[Outbox] Kafka 전송 실패: id={}, error={}",
                        event.getId(), e.getMessage());
            }
        }

        // Phase 3: 결과 반영 (짧은 TX)
        if (!sentIds.isEmpty()) markAsSent(sentIds);
        if (!failedIds.isEmpty()) markAsFailed(failedIds);

        log.info("[Outbox] 처리 완료: 성공={}, 실패={}", sentIds.size(), failedIds.size());
    }

    /**
     * Phase 1: SKIP LOCKED로 PENDING 이벤트를 선점하여 PROCESSING으로 전환
     * + 5분 이상 PROCESSING 상태 = 서버 크래시로 고착된 이벤트 → 재처리
     */
    @Transactional
    public List<EventOutbox> claimPendingEvents() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(5);
        List<EventOutbox> events = outboxRepository.findPendingWithLock(
                staleThreshold, PageRequest.of(0, batchSize));
        for (EventOutbox event : events) {
            event.markProcessing();
        }
        return events;
    }

    /** Phase 3: 전송 성공한 이벤트를 SENT로 변경 */
    @Transactional
    public void markAsSent(List<Long> ids) {
        outboxRepository.bulkUpdateStatus(ids, "SENT");
    }

    /** Phase 3: 전송 실패한 이벤트를 PENDING으로 복원 (또는 retryCount 증가) */
    @Transactional
    public void markAsFailed(List<Long> ids) {
        List<EventOutbox> events = outboxRepository.findAllById(ids);
        for (EventOutbox event : events) {
            event.markFailed(); // retryCount++ → 5회 초과 시 DEAD
        }
    }

    /** SENT 이벤트 정리 (7일 보관 후 삭제, 매일 새벽 2시) */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupSentEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        int deleted = outboxRepository.deleteSentBefore(threshold);
        if (deleted > 0) {
            log.info("[Outbox] SENT 이벤트 정리: {}건 삭제", deleted);
        }
    }
}
