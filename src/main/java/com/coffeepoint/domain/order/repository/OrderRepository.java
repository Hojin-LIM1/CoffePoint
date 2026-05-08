package com.coffeepoint.domain.order.repository;

import com.coffeepoint.domain.order.entity.Order;
import com.coffeepoint.domain.order.entity.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 인기 메뉴 집계 쿼리
     * 최근 7일간 COMPLETED 주문을 menu별로 COUNT, 상위 N개
     * 동률 시 menu.id 오름차순
     * JPQL은 LIMIT를 지원하지 않으므로 Pageable로 제한
     */
    @Query("SELECT o.menu, COUNT(o) as orderCount " +
            "FROM Order o " +
            "WHERE o.status = 'COMPLETED' " +
            "AND o.createdAt >= :since " +
            "GROUP BY o.menu " +
            "ORDER BY orderCount DESC, o.menu.id ASC")
    List<Object[]> findPopularMenus(@Param("since") LocalDateTime since, Pageable pageable);

    long countByMenuIdAndStatusAndCreatedAtAfter(Long menuId, OrderStatus status, LocalDateTime since);

    List<Order> findAllByUserId(Long userId);
}
