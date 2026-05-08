package com.coffeepoint.domain.order.service;

import com.coffeepoint.domain.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 주문 서비스 — Retry Wrapper
 *
 * @Retryable과 @Transactional을 분리한 이유:
 *
 * 문제:
 *   @Transactional + @Retryable이 같은 메서드에 있으면,
 *   OptimisticLockException 발생 시 트랜잭션이 rollback-only로 마킹됨.
 *   같은 트랜잭션 안에서 retry해도 이미 죽은 트랜잭션이라 이상 동작 발생.
 *
 * 해결:
 *   @Retryable (외부) → @Transactional (내부) 분리.
 *   retry할 때마다 새로운 트랜잭션이 열린다.
 *
 * 구조:
 *   OrderService (@Retryable)
 *     └→ OrderTransactionService (@Transactional)
 *          └→ 실제 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderTransactionService transactionService;

    /**
     * 주문/결제 — 낙관적 락 충돌 시 최대 3회 재시도 (50ms 간격)
     *
     * retry할 때마다 transactionService.execute()가 새 트랜잭션을 열어
     * rollback-only 문제가 발생하지 않는다.
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50)
    )
    public OrderResponse order(Long userId, Long menuId) {
        return transactionService.execute(userId, menuId);
    }
}
