package com.coffeepoint.domain.point.entity;

import com.coffeepoint.common.config.BaseEntity;
import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "point")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Point extends BaseEntity {

    public static final long MIN_CHARGE_AMOUNT = 1_000L;
    public static final long MAX_CHARGE_AMOUNT = 1_000_000L;
    public static final long MAX_BALANCE = 10_000_000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private long balance;

    @Version
    private int version;

    @Builder
    public Point(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }

    /**
     * 포인트 충전
     */
    public void charge(long amount) {
        validateChargeAmount(amount);
        this.balance += amount;
    }

    /**
     * 포인트 사용 (차감)
     */
    public void use(long amount) {
        if (this.balance < amount) {
            throw new CustomException(ErrorCode.ORDER_INSUFFICIENT_BALANCE);
        }
        this.balance -= amount;
    }

    private void validateChargeAmount(long amount) {
        if (amount < MIN_CHARGE_AMOUNT) {
            throw new CustomException(ErrorCode.POINT_MINIMUM_AMOUNT);
        }
        if (amount > MAX_CHARGE_AMOUNT) {
            throw new CustomException(ErrorCode.POINT_MAXIMUM_AMOUNT);
        }
        if (this.balance + amount > MAX_BALANCE) {
            throw new CustomException(ErrorCode.POINT_BALANCE_LIMIT);
        }
    }
}
