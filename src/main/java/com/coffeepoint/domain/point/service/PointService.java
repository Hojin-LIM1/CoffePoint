package com.coffeepoint.domain.point.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.point.dto.PointResponse;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 서비스 — Retry Wrapper + 조회
 *
 * @Retryable 대상:
 *   - ObjectOptimisticLockingFailureException: 낙관적 락 충돌 (동시 충전/차감)
 *   - DataIntegrityViolationException: findOrCreate 동시 INSERT 충돌
 *
 * retry할 때마다 새 트랜잭션이 열림 → rollback-only 문제 없음
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    private final PointTransactionService transactionService;
    private final PointRepository pointRepository;

    @Retryable(
            retryFor = {
                    ObjectOptimisticLockingFailureException.class,
                    DataIntegrityViolationException.class
            },
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
