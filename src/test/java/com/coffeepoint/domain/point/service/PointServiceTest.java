package com.coffeepoint.domain.point.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.point.dto.PointResponse;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * PointTransactionService 단위 테스트
 * 실제 비즈니스 로직을 직접 테스트한다.
 * retry 동작은 통합 테스트(PointConcurrencyTest)에서 검증.
 */
@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    @InjectMocks
    private PointTransactionService transactionService;

    @Mock private PointRepository pointRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;
    @Mock private UserRepository userRepository;

    @Nested
    @DisplayName("포인트 충전")
    class ChargeTest {

        @Test
        @DisplayName("정상 충전 시 잔액이 증가하고 이력이 저장된다")
        void chargeSuccess() {
            Long userId = 1L;
            Point point = Point.builder().userId(userId).build();

            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));
            given(pointHistoryRepository.save(any())).willReturn(null);

            PointResponse response = transactionService.charge(userId, 10_000L);

            assertThat(response.getBalance()).isEqualTo(10_000L);
            verify(pointHistoryRepository, times(1)).save(any());
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("point가 없으면 자동 생성 후 충전된다 (findOrCreate)")
        void chargeWithAutoCreate() {
            Long userId = 1L;
            User user = User.builder().name("테스트").build();
            Point newPoint = Point.builder().userId(userId).build();

            given(pointRepository.findByUserId(userId)).willReturn(Optional.empty());
            given(userRepository.findById(userId)).willReturn(Optional.of(user));
            given(pointRepository.save(any(Point.class))).willReturn(newPoint);
            given(pointHistoryRepository.save(any())).willReturn(null);

            PointResponse response = transactionService.charge(userId, 5_000L);

            assertThat(response.getBalance()).isEqualTo(5_000L);
            verify(pointRepository, times(1)).save(any(Point.class));
        }

        @Test
        @DisplayName("최소 충전 금액(1,000P) 미만이면 예외")
        void chargeMinimumAmountFail() {
            Long userId = 1L;
            Point point = Point.builder().userId(userId).build();
            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));

            assertThatThrownBy(() -> transactionService.charge(userId, 500L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_MINIMUM_AMOUNT);
        }

        @Test
        @DisplayName("1회 최대 충전 금액(1,000,000P) 초과시 예외")
        void chargeMaximumAmountFail() {
            Long userId = 1L;
            Point point = Point.builder().userId(userId).build();
            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));

            assertThatThrownBy(() -> transactionService.charge(userId, 1_500_000L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_MAXIMUM_AMOUNT);
        }

        @Test
        @DisplayName("보유 한도(10,000,000P) 초과시 예외")
        void chargeBalanceLimitFail() {
            Long userId = 1L;
            Point point = Point.builder().userId(userId).build();
            for (int i = 0; i < 9; i++) point.charge(1_000_000L);
            point.charge(500_000L);

            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));

            assertThatThrownBy(() -> transactionService.charge(userId, 600_000L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_BALANCE_LIMIT);
        }

        @Test
        @DisplayName("point도 없고 user도 없으면 예외")
        void chargeUserNotFoundFail() {
            given(pointRepository.findByUserId(999L)).willReturn(Optional.empty());
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.charge(999L, 10_000L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_USER_NOT_FOUND);
        }
    }
}
