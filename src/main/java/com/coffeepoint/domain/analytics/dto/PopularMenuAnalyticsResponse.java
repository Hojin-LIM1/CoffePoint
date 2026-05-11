package com.coffeepoint.domain.analytics.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PopularMenuAnalyticsResponse {
    private int rank;
    private Long menuId;
    private String menuName;
    private long orderCount;
    private long totalRevenue;
}
