package com.coffeepoint.domain.point.dto;

import com.coffeepoint.domain.point.entity.Point;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PointResponse {

    private Long userId;
    private long balance;

    public static PointResponse from(Point point) {
        return PointResponse.builder()
                .userId(point.getUserId())
                .balance(point.getBalance())
                .build();
    }
}
