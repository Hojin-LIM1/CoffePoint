package com.coffeepoint.domain.order.dto;

import com.coffeepoint.domain.order.entity.Order;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderResponse {

    private Long orderId;
    private String menuName;
    private long price;
    private long remainBalance;

    public static OrderResponse of(Order order, long remainBalance) {
        return OrderResponse.builder()
                .orderId(order.getId())
                .menuName(order.getMenu().getName())
                .price(order.getPrice())
                .remainBalance(remainBalance)
                .build();
    }
}
