package com.coffeepoint.domain.order.controller;

import com.coffeepoint.domain.order.dto.OrderRequest;
import com.coffeepoint.domain.order.dto.OrderResponse;
import com.coffeepoint.domain.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order", description = "주문 API")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "커피 주문/결제", description = "메뉴를 선택하고 포인트로 결제합니다")
    @PostMapping
    public ResponseEntity<OrderResponse> order(@RequestBody @Valid OrderRequest request) {
        OrderResponse response = orderService.order(request.getUserId(), request.getMenuId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
