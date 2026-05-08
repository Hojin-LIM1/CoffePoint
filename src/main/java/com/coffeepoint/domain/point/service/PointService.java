package com.coffeepoint.domain.point.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.point.dto.PointResponse;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 서비스 — Retry Wrapper + 조회
 *
 * @Retryable과 @Transactional을 분리한 이유:
 *   OptimisticLockException 발생 시 트랜잭션이 rollback-only 상태가 됨.
 *   같은 트랜잭션에서 retry하면 이상 동작 발생.
 *   retry할 때마다 새 트랜잭션이 열려야 정상 동작.
 *
 * 구조:
 *   PointService (@Retryable)
 *     └→ PointTransactionService (@Transactional)
 *          └→ 실제 충전 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    private final PointTransactionService transactionService;
    private final PointRepository pointRepository;

    /**
     * 포인트 충전 — 낙관적 락 충돌 시 최대 3회 재시도 (50ms 간격)
     *
     * retry할 때마다 transactionService.charge()가 새 트랜잭션을 열어
     * rollback-only 문제가 발생하지 않는다.
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50)
    )
    public PointResponse charge(Long userId, long amount) {
        return transactionService.charge(userId, amount);
    }

    @Transactional(readOnly = true)
    public PointResponse getBalance(Long userId) {
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.POINT_USER_NOT_FOUND));

        return PointResponse.from(point);
    }
}
