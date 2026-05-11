package com.coffeepoint.domain.inventory.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAddRequest {

    @NotNull(message = "메뉴 ID는 필수입니다")
    private Long menuId;

    @Positive(message = "수량은 1 이상이어야 합니다")
    private int quantity;

    @NotNull(message = "입고일은 필수입니다")
    private LocalDate receivedDate;

    @NotNull(message = "유통기한은 필수입니다")
    @Future(message = "유통기한은 미래 날짜여야 합니다")
    private LocalDate expirationDate;
}
