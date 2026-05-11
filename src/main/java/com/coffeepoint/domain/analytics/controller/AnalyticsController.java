package com.coffeepoint.domain.analytics.controller;

import com.coffeepoint.domain.analytics.dto.DailyRevenueResponse;
import com.coffeepoint.domain.analytics.dto.HourlyDistributionResponse;
import com.coffeepoint.domain.analytics.dto.PopularMenuAnalyticsResponse;
import com.coffeepoint.domain.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Analytics", description = "데이터 분석 API")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "인기 메뉴 분석", description = "기간별 인기 메뉴와 매출 데이터를 조회합니다")
    @GetMapping("/popular-menus")
    public ResponseEntity<List<PopularMenuAnalyticsResponse>> getPopularMenus(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(analyticsService.getPopularMenus(from, to));
    }

    @Operation(summary = "시간대별 주문 분포", description = "기간 내 시간대별 주문 수를 조회합니다")
    @GetMapping("/hourly")
    public ResponseEntity<List<HourlyDistributionResponse>> getHourlyDistribution(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(analyticsService.getHourlyDistribution(from, to));
    }

    @Operation(summary = "일별 매출 추이", description = "기간 내 일별 매출을 조회합니다")
    @GetMapping("/daily-revenue")
    public ResponseEntity<List<DailyRevenueResponse>> getDailyRevenue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(analyticsService.getDailyRevenue(from, to));
    }
}
