package com.coffeepoint.domain.point.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 포인트 이력 — Append-Only Log
 * UPDATE/DELETE 없이 INSERT만 발생하며,
 * 감사(Audit) 및 정합성 검증 (point.balance = SUM(point_history))의 기준이 된다.
 */
@Entity
@Table(name = "point_history", indexes = {
        @Index(name = "idx_ph_user_created", columnList = "userId, createdAt")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointHistoryType type;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long balanceAfter;

    @Column(length = 200)
    private String description;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public PointHistory(Long userId, PointHistoryType type, long amount,
                        long balanceAfter, String description) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
    }

    public static PointHistory ofCharge(Long userId, long amount, long balanceAfter) {
        return PointHistory.builder()
                .userId(userId)
                .type(PointHistoryType.CHARGE)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .description("포인트 충전")
                .build();
    }

    public static PointHistory ofUse(Long userId, long amount, long balanceAfter, Long orderId) {
        return PointHistory.builder()
                .userId(userId)
                .type(PointHistoryType.USE)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .description("주문 결제 (주문번호: " + orderId + ")")
                .build();
    }
}
