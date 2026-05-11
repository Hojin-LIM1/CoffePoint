package com.coffeepoint.domain.analytics;

import com.coffeepoint.domain.analytics.consumer.OrderAnalyticsConsumer;
import com.coffeepoint.domain.analytics.entity.OrderAnalytics;
import com.coffeepoint.domain.analytics.repository.OrderAnalyticsRepository;
import com.coffeepoint.domain.order.event.OrderCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderAnalyticsConsumer 통합 테스트
 *
 * Kafka 없이 Consumer 로직만 직접 호출하여 검증.
 * 실제 Kafka 연동은 E2E 테스트에서 수행.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnalyticsConsumerTest {

    @Autowired
    private OrderAnalyticsConsumer consumer;

    @Autowired
    private OrderAnalyticsRepository analyticsRepository;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        analyticsRepository.deleteAll();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("주문 이벤트를 수신하면 메뉴별+시간대별 집계가 생성된다")
    void consumeCreatesAnalytics() throws Exception {
        // given
        LocalDateTime now = LocalDateTime.of(2025, 5, 8, 14, 30);
        OrderCompletedEvent event = new OrderCompletedEvent(
                1L, 1L, 1L, "아메리카노", 4500L, now);
        String message = objectMapper.writeValueAsString(event);

        // when
        consumer.consume(message);

        // then
        Optional<OrderAnalytics> result = analyticsRepository
                .findByMenuIdAndOrderDateAndOrderHour(1L, now.toLocalDate(), 14);

        assertThat(result).isPresent();
        assertThat(result.get().getOrderCount()).isEqualTo(1);
        assertThat(result.get().getTotalRevenue()).isEqualTo(4500L);
        assertThat(result.get().getMenuName()).isEqualTo("아메리카노");
    }

    @Test
    @DisplayName("같은 메뉴+시간대의 이벤트가 누적된다")
    void consumeAccumulatesAnalytics() throws Exception {
        // given
        LocalDateTime now = LocalDateTime.of(2025, 5, 8, 14, 30);

        // when: 3건 수신
        for (int i = 0; i < 3; i++) {
            OrderCompletedEvent event = new OrderCompletedEvent(
                    (long) (i + 1), 1L, 1L, "아메리카노", 4500L, now);
            consumer.consume(objectMapper.writeValueAsString(event));
        }

        // then
        OrderAnalytics analytics = analyticsRepository
                .findByMenuIdAndOrderDateAndOrderHour(1L, now.toLocalDate(), 14)
                .orElseThrow();

        assertThat(analytics.getOrderCount()).isEqualTo(3);
        assertThat(analytics.getTotalRevenue()).isEqualTo(13500L);
    }

    @Test
    @DisplayName("다른 시간대의 이벤트는 별도로 집계된다")
    void consumeSeparatesByHour() throws Exception {
        // given
        LocalDateTime morning = LocalDateTime.of(2025, 5, 8, 9, 0);
        LocalDateTime afternoon = LocalDateTime.of(2025, 5, 8, 14, 0);

        // when
        consumer.consume(objectMapper.writeValueAsString(
                new OrderCompletedEvent(1L, 1L, 1L, "아메리카노", 4500L, morning)));
        consumer.consume(objectMapper.writeValueAsString(
                new OrderCompletedEvent(2L, 1L, 1L, "아메리카노", 4500L, afternoon)));

        // then
        assertThat(analyticsRepository.findAll()).hasSize(2);

        OrderAnalytics morningData = analyticsRepository
                .findByMenuIdAndOrderDateAndOrderHour(1L, morning.toLocalDate(), 9)
                .orElseThrow();
        assertThat(morningData.getOrderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 메시지가 와도 예외 없이 처리된다")
    void consumeInvalidMessageDoesNotThrow() {
        // when & then: 예외 발생하지 않음
        consumer.consume("{ invalid json }");
        consumer.consume("");
    }
}
