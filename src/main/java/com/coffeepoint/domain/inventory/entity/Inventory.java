package com.coffeepoint.domain.inventory.entity;

import com.coffeepoint.common.config.BaseEntity;
import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 재고 엔티티
 *
 * 동시성 전략: 비관적 락 (Pessimistic Lock)
 * - 재고 차감은 동일 메뉴에 대한 동시 요청이 빈번 (여러 사용자가 같은 메뉴 주문)
 * - 포인트(사용자별 1개 row)와 달리 재고(메뉴별 공유 row)는 충돌 빈도가 높음
 * - 따라서 낙관적 락 대신 비관적 락이 적합
 */
@Entity
@Table(name = "inventory", indexes = {
        @Index(name = "idx_inventory_menu_status_exp", columnList = "menu_id, status, expirationDate"),
        @Index(name = "idx_inventory_status_exp", columnList = "status, expirationDate")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "menu_id", nullable = false)
    private Long menuId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private LocalDate receivedDate;

    @Column(nullable = false)
    private LocalDate expirationDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InventoryStatus status;

    @Builder
    public Inventory(Long menuId, int quantity, LocalDate receivedDate, LocalDate expirationDate) {
        this.menuId = menuId;
        this.quantity = quantity;
        this.receivedDate = receivedDate;
        this.expirationDate = expirationDate;
        this.status = InventoryStatus.AVAILABLE;
    }

    public void deduct(int amount) {
        if (this.quantity < amount) {
            throw new CustomException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        this.quantity -= amount;
    }

    public void dispose() {
        this.status = InventoryStatus.DISPOSED;
        this.quantity = 0;
    }

    public boolean isExpired() {
        return LocalDate.now().isAfter(this.expirationDate);
    }

    public boolean isAvailable() {
        return this.status == InventoryStatus.AVAILABLE && !isExpired() && this.quantity > 0;
    }
}
