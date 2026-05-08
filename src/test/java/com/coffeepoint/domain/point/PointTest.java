package com.coffeepoint.domain.point;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.point.entity.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointTest {

    @Nested
    @DisplayName("포인트 충전")
    class ChargeTest {

        @Test
        @DisplayName("정상 충전 시 잔액이 증가한다")
        void chargeSuccess() {
            Point point = Point.builder().userId(1L).build();
            point.charge(10_000);
            assertThat(point.getBalance()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("여러 번 충전하면 누적된다")
        void chargeMultiple() {
            Point point = Point.builder().userId(1L).build();
            point.charge(5_000);
            point.charge(3_000);
            assertThat(point.getBalance()).isEqualTo(8_000);
        }

        @Test
        @DisplayName("최소 금액(1,000) 미만 충전 시 예외")
        void chargeMinFail() {
            Point point = Point.builder().userId(1L).build();
            assertThatThrownBy(() -> point.charge(999))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_MINIMUM_AMOUNT);
        }

        @Test
        @DisplayName("최대 금액(1,000,000) 초과 충전 시 예외")
        void chargeMaxFail() {
            Point point = Point.builder().userId(1L).build();
            assertThatThrownBy(() -> point.charge(1_000_001))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_MAXIMUM_AMOUNT);
        }

        @Test
        @DisplayName("보유 한도(10,000,000) 초과 시 예외")
        void chargeBalanceLimitFail() {
            Point point = Point.builder().userId(1L).build();
            // 9,500,000까지 충전
            for (int i = 0; i < 9; i++) {
                point.charge(1_000_000);
            }
            point.charge(500_000);

            // 600,000 추가 충전 → 10,100,000 → 한도 초과
            assertThatThrownBy(() -> point.charge(600_000))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_BALANCE_LIMIT);
        }
    }

    @Nested
    @DisplayName("포인트 사용")
    class UseTest {

        @Test
        @DisplayName("정상 사용 시 잔액이 차감된다")
        void useSuccess() {
            Point point = Point.builder().userId(1L).build();
            point.charge(10_000);
            point.use(4_500);
            assertThat(point.getBalance()).isEqualTo(5_500);
        }

        @Test
        @DisplayName("잔액 부족 시 예외")
        void useInsufficientFail() {
            Point point = Point.builder().userId(1L).build();
            point.charge(3_000);
            assertThatThrownBy(() -> point.use(5_000))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_INSUFFICIENT_BALANCE);
        }

        @Test
        @DisplayName("잔액이 정확히 0이 될 수 있다")
        void useExactBalance() {
            Point point = Point.builder().userId(1L).build();
            point.charge(5_000);
            point.use(5_000);
            assertThat(point.getBalance()).isEqualTo(0);
        }
    }
}
