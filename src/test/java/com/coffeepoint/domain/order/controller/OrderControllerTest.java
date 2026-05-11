package com.coffeepoint.domain.order.controller;

import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.repository.InventoryRepository;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.outbox.entity.OutboxStatus;
import com.coffeepoint.domain.outbox.repository.OutboxRepository;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.point.service.PointService;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private PointService pointService;

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

        Menu menu = menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        menuId = menu.getId();

        // 재고 50개
        inventoryRepository.save(Inventory.builder()
                .menuId(menuId)
                .quantity(50)
                .receivedDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30))
                .build());

        // 10,000P 충전
        pointService.charge(userId, 10_000);
    }

    @Test
    @DisplayName("POST /api/orders - 정상 주문/결제 시 201 응답")
    void orderSuccess() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userId", userId, "menuId", menuId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.menuName").value("아메리카노"))
                .andExpect(jsonPath("$.price").value(4500))
                .andExpect(jsonPath("$.remainBalance").value(5500));

        // 주문 확인
        assertThat(orderRepository.findAllByUserId(userId)).hasSize(1);

        // Outbox 이벤트 저장 확인 (Transactional Outbox)
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);

        // 포인트 정합성 확인
        Point point = pointRepository.findByUserId(userId).orElseThrow();
        long historySum = pointHistoryRepository.calculateBalanceByUserId(userId);
        assertThat(point.getBalance()).isEqualTo(historySum);
    }

    @Test
    @DisplayName("POST /api/orders - 잔액 부족 시 400 응답")
    void orderInsufficientBalance() throws Exception {
        // 5,500P 잔액 남기기 (이미 10,000P 충전됨)
        // 6,000P 메뉴 주문 시도
        Menu expensiveMenu = menuRepository.save(
                Menu.builder().name("프리미엄라떼").price(15_000).build());

        //  [추가된 코드] 비싼 메뉴에 대한 재고 추가
        inventoryRepository.save(Inventory.builder()
                .menuId(expensiveMenu.getId())
                .quantity(10)
                .receivedDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30))
                .build());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userId", userId, "menuId", expensiveMenu.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_001"));
    }

    @Test
    @DisplayName("POST /api/orders - 존재하지 않는 메뉴면 404 응답")
    void orderMenuNotFound() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userId", userId, "menuId", 999))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_002"));
    }

    @Test
    @DisplayName("POST /api/orders - 존재하지 않는 사용자면 404 응답")
    void orderUserNotFound() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userId", 999, "menuId", menuId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_004"));
    }

    @Test
    @DisplayName("충전 → 주문 → 잔액 확인 전체 플로우가 정상 동작한다")
    void fullFlowTest() throws Exception {
        // 1. 추가 충전 (기존 10,000 + 50,000 = 60,000)
        pointService.charge(userId, 50_000);

        // 2. 주문 3건
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("userId", userId, "menuId", menuId))))
                    .andExpect(status().isCreated());
        }

        // 3. 잔액 확인: 60,000 - (4,500 × 3) = 46,500
        Point point = pointRepository.findByUserId(userId).orElseThrow();
        assertThat(point.getBalance()).isEqualTo(46_500);

        // 4. 주문 3건 확인
        assertThat(orderRepository.findAllByUserId(userId)).hasSize(3);

        // 5. 포인트 정합성
        long historySum = pointHistoryRepository.calculateBalanceByUserId(userId);
        assertThat(point.getBalance()).isEqualTo(historySum);

        // 6. Outbox 이벤트 3건 저장 확인
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(3);

        // 7. 재고 차감 확인 (50 - 3 = 47)
        int remainingStock = inventoryRepository.getTotalAvailableQuantity(menuId, LocalDate.now());
        assertThat(remainingStock).isEqualTo(47);
    }
}
