package com.coffeepoint.domain.order.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * v2: 이 리스너는 더 이상 사용되지 않음.
 *
 * v1: @TransactionalEventListener(AFTER_COMMIT) + @Async → At-Most-Once
 * v2: Transactional Outbox Pattern → At-Least-Once
 *
 * 이벤트는 OrderTransactionService에서 event_outbox 테이블에 직접 저장되고,
 * OutboxScheduler가 주기적으로 폴링하여 Kafka로 전송한다.
 *
 * 이 클래스는 참조용으로 남겨둠 (v1 → v2 마이그레이션 이력)
 */
@Slf4j
@Component
public class OrderEventListener {
    // v2에서는 Outbox Pattern으로 대체됨.
    // OutboxScheduler → Kafka → OrderAnalyticsConsumer 흐름 참조.
}
