package com.coffeepoint.domain.order.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 완료 이벤트 리스너
 *
 * @TransactionalEventListener(AFTER_COMMIT)을 사용하는 이유:
 * 1. 커밋 성공 이후에만 이벤트가 실행된다 → 롤백된 주문의 이벤트 발행을 원천 차단
 * 2. 트랜잭션과 후처리가 명확히 분리된다
 * 3. 이벤트 리스너 추가만으로 후처리 확장 가능 (OCP)
 */
@Slf4j
@Component
public class OrderEventListener {

    /**
     * 데이터 수집 플랫폼에 주문 정보를 비동기 전송 (Mock)
     *
     * 장애 격리: 전송 실패가 주문 트랜잭션에 영향을 주지 않는다.
     * 실패 시 로그를 남기고, 주문 자체는 성공 상태를 유지한다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCompleted(OrderCompletedEvent event) {
        try {
            // Mock: 데이터 수집 플랫폼 전송
            log.info("[데이터 수집 플랫폼 전송] userId={}, menuId={}, price={}, orderedAt={}",
                    event.getUserId(),
                    event.getMenuId(),
                    event.getPrice(),
                    event.getOrderedAt());

            // 실제 구현 시: RestTemplate 또는 WebClient로 외부 API 호출
            // dataPlatformClient.send(event);

        } catch (Exception e) {
            // 장애 격리: 전송 실패해도 주문은 이미 커밋 완료
            log.error("[데이터 수집 플랫폼 전송 실패] userId={}, menuId={}, error={}",
                    event.getUserId(),
                    event.getMenuId(),
                    e.getMessage());
        }
    }
}
