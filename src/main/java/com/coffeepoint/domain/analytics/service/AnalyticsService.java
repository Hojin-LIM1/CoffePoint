package com.coffeepoint.domain.analytics.service;

import com.coffeepoint.domain.analytics.dto.DailyRevenueResponse;
import com.coffeepoint.domain.analytics.dto.HourlyDistributionResponse;
import com.coffeepoint.domain.analytics.dto.PopularMenuAnalyticsResponse;
import com.coffeepoint.domain.analytics.repository.OrderAnalyticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    private final OrderAnalyticsRepository analyticsRepository;

    /** 기간별 인기 메뉴 (매출 포함) */
    public List<PopularMenuAnalyticsResponse> getPopularMenus(LocalDate from, LocalDate to) {
        List<Object[]> results = analyticsRepository.findPopularMenusByPeriod(from, to);
        List<PopularMenuAnalyticsResponse> response = new ArrayList<>();
        int rank = 1;
        for (Object[] row : results) {
            response.add(PopularMenuAnalyticsResponse.builder()
                    .rank(rank++)
                    .menuId((Long) row[0])
                    .menuName((String) row[1])
                    .orderCount((long) row[2])
                    .totalRevenue((long) row[3])
                    .build());
        }
        return response;
    }

    /** 시간대별 주문 분포 */
    public List<HourlyDistributionResponse> getHourlyDistribution(LocalDate from, LocalDate to) {
        List<Object[]> results = analyticsRepository.findOrderDistributionByHour(from, to);
        return results.stream()
                .map(row -> HourlyDistributionResponse.builder()
                        .hour((int) row[0])
                        .orderCount((long) row[1])
                        .build())
                .toList();
    }

    /** 일별 매출 추이 */
    public List<DailyRevenueResponse> getDailyRevenue(LocalDate from, LocalDate to) {
        List<Object[]> results = analyticsRepository.findDailyRevenue(from, to);
        return results.stream()
                .map(row -> DailyRevenueResponse.builder()
                        .date((LocalDate) row[0])
                        .revenue((long) row[1])
                        .build())
                .toList();
    }
}
