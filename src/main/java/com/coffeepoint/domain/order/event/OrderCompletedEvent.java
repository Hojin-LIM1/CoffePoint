package com.coffeepoint.domain.order.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 주문 완료 이벤트 — Kafka 메시지 페이로드
 *
 * Outbox 테이블에 JSON으로 직렬화되어 저장되고,
 * 스케줄러가 Kafka로 전송한다.
 *
 * NoArgsConstructor: Jackson 역직렬화에 필요
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrderCompletedEvent {

    private Long orderId;
    private Long userId;
    private Long menuId;
    private String menuName;
    private long price;
    private LocalDateTime orderedAt;
}
