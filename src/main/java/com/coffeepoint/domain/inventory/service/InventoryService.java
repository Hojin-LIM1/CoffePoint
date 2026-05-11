package com.coffeepoint.domain.inventory.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.entity.InventoryStatus;
import com.coffeepoint.domain.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 재고 관리 서비스
 *
 * 동시성: 비관적 락 (SELECT FOR UPDATE)
 * 차감 전략: FIFO (유통기한 임박 순서로 차감)
 *
 * 포인트(낙관적 락)와 재고(비관적 락)의 선택 근거 차이:
 * - 포인트: 같은 사용자에 대한 동시 충전은 드묾 → 낙관적 락 + 재시도
 * - 재고: 같은 메뉴에 대한 동시 주문은 빈번 → 비관적 락으로 즉시 직렬화
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    /**
     * 재고 차감 (FIFO — 유통기한 임박 순서)
     *
     * 호출자(OrderTransactionService)의 트랜잭션에 참여하거나,
     * 직접 호출 시 자체 트랜잭션을 생성한다.
     * 비관적 락은 트랜잭션 종료 시 해제.
     */
    @Transactional
    public void deductStock(Long menuId, int quantity) {
        List<Inventory> stocks = inventoryRepository.findAvailableByMenuIdWithLock(
                menuId, LocalDate.now());

        int totalAvailable = stocks.stream().mapToInt(Inventory::getQuantity).sum();
        if (totalAvailable < quantity) {
            throw new CustomException(ErrorCode.INVENTORY_INSUFFICIENT);
        }

        // FIFO 차감: 유통기한 임박한 것부터 소진
        int remaining = quantity;
        for (Inventory stock : stocks) {
            if (remaining <= 0) break;

            int deductAmount = Math.min(stock.getQuantity(), remaining);
            stock.deduct(deductAmount);
            remaining -= deductAmount;
        }
    }

    /**
     * 메뉴별 가용 재고 수량 조회 (락 없음, 조회 전용)
     */
    @Transactional(readOnly = true)
    public int getAvailableQuantity(Long menuId) {
        return inventoryRepository.getTotalAvailableQuantity(menuId, LocalDate.now());
    }

    /**
     * 재고 입고
     */
    @Transactional
    public Inventory addStock(Long menuId, int quantity, LocalDate receivedDate, LocalDate expirationDate) {
        return inventoryRepository.save(
                Inventory.builder()
                        .menuId(menuId)
                        .quantity(quantity)
                        .receivedDate(receivedDate)
                        .expirationDate(expirationDate)
                        .build()
        );
    }

    /**
     * 유통기한 만료 재고 자동 폐기 (매일 새벽 1시)
     *
     * 멀티 인스턴스 대응: findExpiredWithLock (FOR UPDATE SKIP LOCKED)
     * → 다른 인스턴스가 처리 중인 row는 건너뜀
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void disposeExpiredStock() {
        List<Inventory> expired = inventoryRepository.findExpiredWithLock(
                InventoryStatus.AVAILABLE, LocalDate.now());

        for (Inventory stock : expired) {
            stock.dispose();
            log.info("[재고 폐기] menuId={}, quantity={}, expirationDate={}",
                    stock.getMenuId(), stock.getQuantity(), stock.getExpirationDate());
        }

        if (!expired.isEmpty()) {
            log.info("[재고 폐기 완료] {}건 처리", expired.size());
        }
    }

    /**
     * 메뉴별 재고 상세 조회 (유통기한 오름차순)
     */
    @Transactional(readOnly = true)
    public List<Inventory> getStockDetail(Long menuId) {
        return inventoryRepository.findByMenuIdAndStatusOrderByExpirationDateAsc(
                menuId, InventoryStatus.AVAILABLE);
    }
}
