package com.coffeepoint.domain.point.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.point.dto.PointResponse;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.entity.PointHistory;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 트랜잭션 서비스 — 실제 충전 로직
 *
 * @Transactional만 담당. retry는 PointService가 처리.
 * retry할 때마다 이 메서드가 새 트랜잭션으로 실행된다.
 *
 * 트랜잭션 경계:
 * ┌─ @Transactional ─────────────────────────────┐
 * │  1. 포인트 조회 (findOrCreate)                 │
 * │  2. 충전 금액 유효성 검증 (엔티티 내부)            │
 * │  3. 잔액 증가 (@Version 낙관적 락)             │
 * │  4. 이력 저장 (Append-Only)                    │
 * │  5. 커밋                                       │
 * └────────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointTransactionService {

    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final UserRepository userRepository;

    @Transactional
    public PointResponse charge(Long userId, long amount) {
        // findOrCreate 패턴 — user는 있는데 point row가 없는 케이스 방어
        Point point = findOrCreatePoint(userId);

        // 충전 (유효성 검증 + 잔액 증가, 엔티티 도메인 로직)
        point.charge(amount);

        // 이력 저장 (Append-Only)
        pointHistoryRepository.save(
                PointHistory.ofCharge(userId, amount, point.getBalance())
        );

        log.info("[포인트 충전] userId={}, amount={}, balance={}", userId, amount, point.getBalance());
        return PointResponse.from(point);
    }

    /**
     * Point 자동 생성 (findOrCreate 패턴)
     *
     * user는 있는데 point row가 없는 상황 대비.
     * (회원가입 시 point 생성 누락, 데이터 마이그레이션 등)
     */
    private Point findOrCreatePoint(Long userId) {
        return pointRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new CustomException(ErrorCode.POINT_USER_NOT_FOUND));

                    log.info("[포인트 자동 생성] userId={}", user.getId());
                    return pointRepository.save(Point.builder().userId(userId).build());
                });
    }
}
