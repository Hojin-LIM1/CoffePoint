package com.coffeepoint.domain.analytics.repository;

import com.coffeepoint.domain.analytics.entity.OrderAnalytics;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OrderAnalyticsRepository extends JpaRepository<OrderAnalytics, Long> {

    Optional<OrderAnalytics> findByMenuIdAndOrderDateAndOrderHour(Long menuId, LocalDate orderDate, int orderHour);

    /**
     * 비관적 락 기반 조회 (UPSERT 레이스 컨디션 방지)
     * 동시에 같은 (menuId, date, hour) 집계 row에 접근 시 직렬화
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM OrderAnalytics a " +
            "WHERE a.menuId = :menuId AND a.orderDate = :orderDate AND a.orderHour = :orderHour")
    Optional<OrderAnalytics> findForUpdate(
            @Param("menuId") Long menuId,
            @Param("orderDate") LocalDate orderDate,
            @Param("orderHour") int orderHour);

    /** 기간별 메뉴 인기 순위 */
    @Query("SELECT a.menuId, a.menuName, SUM(a.orderCount) as total, SUM(a.totalRevenue) as revenue " +
            "FROM OrderAnalytics a " +
            "WHERE a.orderDate BETWEEN :from AND :to " +
            "GROUP BY a.menuId, a.menuName " +
            "ORDER BY total DESC")
    List<Object[]> findPopularMenusByPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 시간대별 주문 분포 */
    @Query("SELECT a.orderHour, SUM(a.orderCount) " +
            "FROM OrderAnalytics a " +
            "WHERE a.orderDate BETWEEN :from AND :to " +
            "GROUP BY a.orderHour " +
            "ORDER BY a.orderHour")
    List<Object[]> findOrderDistributionByHour(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 일별 매출 추이 */
    @Query("SELECT a.orderDate, SUM(a.totalRevenue) " +
            "FROM OrderAnalytics a " +
            "WHERE a.orderDate BETWEEN :from AND :to " +
            "GROUP BY a.orderDate " +
            "ORDER BY a.orderDate")
    List<Object[]> findDailyRevenue(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
