package com.coffeepoint.domain.analytics.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class DailyRevenueResponse {
    private LocalDate date;
    private long revenue;
}
