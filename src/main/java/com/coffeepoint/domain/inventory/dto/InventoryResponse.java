package com.coffeepoint.domain.inventory.dto;

import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.entity.InventoryStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class InventoryResponse {

    private Long id;
    private Long menuId;
    private int quantity;
    private LocalDate receivedDate;
    private LocalDate expirationDate;
    private InventoryStatus status;

    public static InventoryResponse from(Inventory inventory) {
        return InventoryResponse.builder()
                .id(inventory.getId())
                .menuId(inventory.getMenuId())
                .quantity(inventory.getQuantity())
                .receivedDate(inventory.getReceivedDate())
                .expirationDate(inventory.getExpirationDate())
                .status(inventory.getStatus())
                .build();
    }
}
