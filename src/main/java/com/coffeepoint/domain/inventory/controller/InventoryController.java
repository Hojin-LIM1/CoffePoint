package com.coffeepoint.domain.inventory.controller;

import com.coffeepoint.domain.inventory.dto.InventoryAddRequest;
import com.coffeepoint.domain.inventory.dto.InventoryResponse;
import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Inventory", description = "재고 관리 API")
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(summary = "재고 입고")
    @PostMapping
    public ResponseEntity<InventoryResponse> addStock(@RequestBody @Valid InventoryAddRequest request) {
        Inventory inventory = inventoryService.addStock(
                request.getMenuId(), request.getQuantity(),
                request.getReceivedDate(), request.getExpirationDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(InventoryResponse.from(inventory));
    }

    @Operation(summary = "메뉴별 가용 재고 조회")
    @GetMapping("/{menuId}")
    public ResponseEntity<Map<String, Object>> getStock(@PathVariable Long menuId) {
        int quantity = inventoryService.getAvailableQuantity(menuId);
        return ResponseEntity.ok(Map.of("menuId", menuId, "availableQuantity", quantity));
    }

    @Operation(summary = "메뉴별 재고 상세 (유통기한순)")
    @GetMapping("/{menuId}/detail")
    public ResponseEntity<List<InventoryResponse>> getStockDetail(@PathVariable Long menuId) {
        List<Inventory> stocks = inventoryService.getStockDetail(menuId);
        return ResponseEntity.ok(stocks.stream().map(InventoryResponse::from).toList());
    }
}
