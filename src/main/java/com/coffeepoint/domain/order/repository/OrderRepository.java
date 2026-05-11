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
     * 인기 메뉴 집계 쿼리 (DTO Projection — N+1 방지)
     *
     * 수정: GROUP BY에 엔티티 대신 스칼라 값 사용
     * - 이전: SELECT o.menu → Hibernate가 Menu 엔티티 개별 SELECT 발생 가능
     * - 이후: SELECT o.menu.id, o.menu.name, o.menu.price → 단일 쿼리로 해결
     */
    @Query("SELECT o.menu.id, o.menu.name, o.menu.price, COUNT(o) as orderCount " +
            "FROM Order o " +
            "WHERE o.status = 'COMPLETED' " +
            "AND o.createdAt >= :since " +
            "GROUP BY o.menu.id, o.menu.name, o.menu.price " +
            "ORDER BY orderCount DESC, o.menu.id ASC")
    List<Object[]> findPopularMenus(@Param("since") LocalDateTime since, Pageable pageable);

    long countByMenuIdAndStatusAndCreatedAtAfter(Long menuId, OrderStatus status, LocalDateTime since);

    List<Order> findAllByUserId(Long userId);
}
