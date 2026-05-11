package com.coffeepoint.domain.analytics.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class HourlyDistributionResponse {
    private int hour;
    private long orderCount;
}
