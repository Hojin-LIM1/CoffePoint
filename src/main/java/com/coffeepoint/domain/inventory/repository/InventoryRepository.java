package com.coffeepoint.domain.inventory.repository;

import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.entity.InventoryStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * FIFO 재고 조회 (비관적 락)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i " +
            "WHERE i.menuId = :menuId " +
            "AND i.status = 'AVAILABLE' " +
            "AND i.expirationDate > :today " +
            "AND i.quantity > 0 " +
            "ORDER BY i.expirationDate ASC")
    List<Inventory> findAvailableByMenuIdWithLock(
            @Param("menuId") Long menuId,
            @Param("today") LocalDate today);

    /**
     * 메뉴별 총 가용 재고 수량 (락 없이 조회용)
     */
    @Query("SELECT COALESCE(SUM(i.quantity), 0) FROM Inventory i " +
            "WHERE i.menuId = :menuId " +
            "AND i.status = 'AVAILABLE' " +
            "AND i.expirationDate > :today")
    int getTotalAvailableQuantity(@Param("menuId") Long menuId, @Param("today") LocalDate today);

    /**
     * 유통기한 만료 재고 조회 (SKIP LOCKED — 멀티 인스턴스 중복 방지)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT i FROM Inventory i " +
            "WHERE i.status = :status AND i.expirationDate < :today")
    List<Inventory> findExpiredWithLock(
            @Param("status") InventoryStatus status,
            @Param("today") LocalDate today);

    List<Inventory> findByStatusAndExpirationDateBefore(InventoryStatus status, LocalDate date);

    List<Inventory> findByMenuIdAndStatusOrderByExpirationDateAsc(Long menuId, InventoryStatus status);
}
