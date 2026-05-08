package com.coffeepoint.domain.menu.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PopularMenuResponse {

    private int rank;
    private Long id;
    private String name;
    private long price;
    private long orderCount;
}
