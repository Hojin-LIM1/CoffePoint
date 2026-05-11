package com.coffeepoint.domain.order;

import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.repository.InventoryRepository;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.order.service.OrderService;
import com.coffeepoint.domain.outbox.entity.EventOutbox;
import com.coffeepoint.domain.outbox.entity.OutboxStatus;
import com.coffeepoint.domain.outbox.repository.OutboxRepository;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.point.service.PointService;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transactional Outbox Pattern 통합 테스트
 *
 * 검증:
 * - 주문 성공 시 Outbox 이벤트가 같은 트랜잭션으로 저장되는지
 * - 주문 실패 시 Outbox 이벤트도 롤백되는지
 * - 주문 건수 == Outbox PENDING 건수
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private PointService pointService;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private InventoryRepository inventoryRepository;

    private Long userId;
    private Long menuId;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
        pointHistoryRepository.deleteAll();
        pointRepository.deleteAll();
        inventoryRepository.deleteAll();
        menuRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder().name("테스트유저").build());
        userId = user.getId();
        pointRepository.save(Point.builder().userId(userId).build());

        Menu menu = menuRepository.save(Menu.builder().name("아메리카노").price(1_000).build());
        menuId = menu.getId();

        inventoryRepository.save(Inventory.builder()
                .menuId(menuId).quantity(50)
                .receivedDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30))
                .build());

        pointService.charge(userId, 10_000);
    }

    @Test
    @DisplayName("주문 성공 시 Outbox 이벤트가 PENDING 상태로 저장된다")
    void orderCreatesOutboxEvent() {
        // when
        orderService.order(userId, menuId);

        // then
        List<EventOutbox> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(events.get(0).getTopic()).isEqualTo("order-completed");
        assertThat(events.get(0).getPayload()).contains(String.valueOf(userId));
    }

    @Test
    @DisplayName("3건 주문 시 Outbox 이벤트도 3건 저장된다")
    void multipleOrdersCreateMultipleOutboxEvents() {
        // when
        orderService.order(userId, menuId);
        orderService.order(userId, menuId);
        orderService.order(userId, menuId);

        // then
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(3);
        assertThat(orderRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("주문 실패(잔액 부족) 시 Outbox 이벤트도 저장되지 않는다")
    void failedOrderDoesNotCreateOutboxEvent() {
        // given: 비싼 메뉴 등록 (잔액 초과)
        Menu expensiveMenu = menuRepository.save(
                Menu.builder().name("프리미엄").price(50_000).build());
        inventoryRepository.save(Inventory.builder()
                .menuId(expensiveMenu.getId()).quantity(10)
                .receivedDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30))
                .build());

        // when & then
        try {
            orderService.order(userId, expensiveMenu.getId());
        } catch (Exception ignored) {}

        // Outbox에 이벤트 없어야 함 (롤백)
        assertThat(outboxRepository.count()).isEqualTo(0);
        assertThat(orderRepository.count()).isEqualTo(0);
    }
}
