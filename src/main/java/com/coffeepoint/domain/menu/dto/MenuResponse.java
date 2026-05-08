package com.coffeepoint.domain.menu.dto;

import com.coffeepoint.domain.menu.entity.Menu;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MenuResponse {

    private Long id;
    private String name;
    private long price;

    public static MenuResponse from(Menu menu) {
        return MenuResponse.builder()
                .id(menu.getId())
                .name(menu.getName())
                .price(menu.getPrice())
                .build();
    }
}
