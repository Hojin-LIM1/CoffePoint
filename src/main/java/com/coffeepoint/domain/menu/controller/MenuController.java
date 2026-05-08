package com.coffeepoint.domain.menu.controller;

import com.coffeepoint.domain.menu.dto.MenuResponse;
import com.coffeepoint.domain.menu.dto.PopularMenuResponse;
import com.coffeepoint.domain.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Menu", description = "메뉴 API")
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @Operation(summary = "메뉴 목록 조회", description = "판매 중인 커피 메뉴 목록을 조회합니다")
    @GetMapping
    public ResponseEntity<List<MenuResponse>> getMenus() {
        return ResponseEntity.ok(menuService.getMenus());
    }

    @Operation(summary = "인기 메뉴 조회", description = "최근 7일간 주문 수 상위 3개 메뉴를 조회합니다")
    @GetMapping("/popular")
    public ResponseEntity<List<PopularMenuResponse>> getPopularMenus() {
        return ResponseEntity.ok(menuService.getPopularMenus());
    }
}
