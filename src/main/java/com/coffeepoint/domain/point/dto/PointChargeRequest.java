package com.coffeepoint.domain.point.dto;

import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PointChargeRequest {

    @Positive(message = "충전 금액은 양수여야 합니다")
    private long amount;
}
