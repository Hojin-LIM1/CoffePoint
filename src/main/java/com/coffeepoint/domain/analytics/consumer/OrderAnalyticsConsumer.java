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
 * 주문 분석 Kafka Consumer v2.2
 *
 * 에러 처리:
 *   1차: 비관적 락 findForUpdate → 동시 접근 안전
 *   2차: 처리 실패 시 DefaultErrorHandler가 3회 재시도
 *   3차: 3회 모두 실패 → DLT(Dead Letter Topic)로 전송 → 재처리 가능
 *
 * 이전 문제:
 *   try-catch로 skip → 수만 건 실패 시 추적 불가
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
        // 역직렬화 실패 시 예외 → DefaultErrorHandler가 재시도 후 DLT로 전송
        OrderCompletedEvent event = deserialize(message);

        // 비관적 락으로 조회 → 없으면 생성
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
    }

    private OrderCompletedEvent deserialize(String message) {
        try {
            return objectMapper.readValue(message, OrderCompletedEvent.class);
        } catch (Exception e) {
            throw new RuntimeException("[Analytics] 메시지 역직렬화 실패", e);
        }
    }
}
