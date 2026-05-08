package com.coffeepoint.domain.order.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 주문 완료 이벤트
 * 트랜잭션 커밋 이후 발행되어 데이터 수집 플랫폼에 전송된다.
 */
@Getter
@AllArgsConstructor
public class OrderCompletedEvent {

    private final Long userId;
    private final Long menuId;
    private final long price;
    private final LocalDateTime orderedAt;
}
