package com.coffeepoint.domain.analytics.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주문 분석 데이터
 *
 * Kafka Consumer가 주문 이벤트를 수신하여 집계 데이터를 저장한다.
 * 메뉴별 + 날짜별 주문 횟수와 매출을 집계한다.
 *
 * 활용:
 * - 시간대별 인기 메뉴
 * - 일별/월별 매출 추이
 * - 계절별 메뉴 추천
 */
@Entity
@Table(name = "order_analytics", indexes = {
        @Index(name = "idx_analytics_date_menu", columnList = "orderDate, menuId"),
        @Index(name = "idx_analytics_date", columnList = "orderDate")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_analytics_menu_date_hour", columnNames = {"menu_id", "order_date", "order_hour"})
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long menuId;

    @Column(nullable = false, length = 100)
    private String menuName;

    @Column(nullable = false)
    private LocalDate orderDate;

    @Column(nullable = false)
    private int orderHour;

    @Column(nullable = false)
    private long orderCount;

    @Column(nullable = false)
    private long totalRevenue;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUpdatedAt;

    @Builder
    public OrderAnalytics(Long menuId, String menuName, LocalDate orderDate, int orderHour) {
        this.menuId = menuId;
        this.menuName = menuName;
        this.orderDate = orderDate;
        this.orderHour = orderHour;
        this.orderCount = 0;
        this.totalRevenue = 0;
    }

    public void addOrder(long price) {
        this.orderCount++;
        this.totalRevenue += price;
        this.lastUpdatedAt = LocalDateTime.now();
    }
}
