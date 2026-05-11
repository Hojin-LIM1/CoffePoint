package com.coffeepoint.domain.inventory.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @InjectMocks
    private InventoryService inventoryService;

    @Mock
    private InventoryRepository inventoryRepository;

    @Test
    @DisplayName("재고가 충분하면 FIFO로 차감한다")
    void deductStockFifo() {
        // given: 유통기한 임박 순서로 2개 재고
        Inventory stock1 = Inventory.builder()
                .menuId(1L).quantity(3)
                .receivedDate(LocalDate.now().minusDays(10))
                .expirationDate(LocalDate.now().plusDays(5))  // 임박
                .build();
        Inventory stock2 = Inventory.builder()
                .menuId(1L).quantity(10)
                .receivedDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30)) // 여유
                .build();

        given(inventoryRepository.findAvailableByMenuIdWithLock(eq(1L), any()))
                .willReturn(List.of(stock1, stock2));

        // when: 5개 차감
        inventoryService.deductStock(1L, 5);

        // then: FIFO로 stock1(3개) 먼저 소진 → stock2에서 2개 차감
        assertThat(stock1.getQuantity()).isEqualTo(0);
        assertThat(stock2.getQuantity()).isEqualTo(8);
    }

    @Test
    @DisplayName("재고 부족 시 INVENTORY_001 예외가 발생한다")
    void deductStockInsufficient() {
        // given
        Inventory stock = Inventory.builder()
                .menuId(1L).quantity(2)
                .receivedDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30))
                .build();

        given(inventoryRepository.findAvailableByMenuIdWithLock(eq(1L), any()))
                .willReturn(List.of(stock));

        // when & then
        assertThatThrownBy(() -> inventoryService.deductStock(1L, 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVENTORY_INSUFFICIENT);
    }

    @Test
    @DisplayName("가용 재고가 없으면 예외가 발생한다")
    void deductStockEmpty() {
        given(inventoryRepository.findAvailableByMenuIdWithLock(eq(1L), any()))
                .willReturn(List.of());

        assertThatThrownBy(() -> inventoryService.deductStock(1L, 1))
                .isInstanceOf(CustomException.class);
    }
}
