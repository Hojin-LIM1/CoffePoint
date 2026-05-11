package com.coffeepoint.domain.analytics.consumer;

import com.coffeepoint.domain.analytics.entity.OrderAnalytics;
import com.coffeepoint.domain.analytics.repository.OrderAnalyticsRepository;
import com.coffeepoint.domain.order.event.OrderCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 분석 Kafka Consumer v2.1
 *
 * UPSERT 레이스 컨디션 대응:
 *   findForUpdate (비관적 락) → row 있으면 락 획득 후 업데이트
 *   row 없으면 INSERT 시도 → UniqueConstraint 충돌 시 메시지 skip
 *   → 다음 메시지에서 findForUpdate가 반드시 찾으므로 1건 유실만 발생
 *   → Analytics는 집계 데이터이므로 1건 유실은 허용 가능 (Eventual Consistency)
 *
 * 이전 문제:
 *   saveAndFlush 예외 후 같은 TX에서 재조회 → Hibernate 세션 오염으로 실패
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderAnalyticsConsumer {

    private final OrderAnalyticsRepository analyticsRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topic.order-completed:order-completed}",
            groupId = "coffee-point-analytics"
    )
    @Transactional
    public void consume(String message) {
        try {
            OrderCompletedEvent event = objectMapper.readValue(message, OrderCompletedEvent.class);

            // 비관적 락으로 조회: row 있으면 락 획득
            OrderAnalytics analytics = analyticsRepository.findForUpdate(
                            event.getMenuId(),
                            event.getOrderedAt().toLocalDate(),
                            event.getOrderedAt().getHour())
                    .orElseGet(() -> analyticsRepository.save(
                            OrderAnalytics.builder()
                                    .menuId(event.getMenuId())
                                    .menuName(event.getMenuName())
                                    .orderDate(event.getOrderedAt().toLocalDate())
                                    .orderHour(event.getOrderedAt().getHour())
                                    .build()
                    ));

            analytics.addOrder(event.getPrice());

            log.info("[Analytics] 집계: menuId={}, hour={}, count={}, revenue={}",
                    event.getMenuId(), event.getOrderedAt().getHour(),
                    analytics.getOrderCount(), analytics.getTotalRevenue());

        } catch (Exception e) {
            // UniqueConstraint 충돌 포함 모든 예외: 로그 후 skip
            // Analytics는 집계 데이터 → 1건 유실은 허용 가능 (다음 메시지에서 정상 처리)
            // 프로덕션: DLT(Dead Letter Topic)로 전송하여 재처리 가능하게
            log.warn("[Analytics] 이벤트 처리 실패 (skip): {}", e.getMessage());
        }
    }
}
