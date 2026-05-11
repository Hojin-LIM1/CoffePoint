package com.coffeepoint.domain.order.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.inventory.service.InventoryService;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.dto.OrderResponse;
import com.coffeepoint.domain.order.entity.Order;
import com.coffeepoint.domain.order.event.OrderCompletedEvent;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.outbox.entity.EventOutbox;
import com.coffeepoint.domain.outbox.repository.OutboxRepository;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.entity.PointHistory;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 트랜잭션 서비스 v2
 *
 * v1 대비 변경:
 * - 재고 차감 추가 (비관적 락, FIFO)
 * - ApplicationEventPublisher → Transactional Outbox Pattern
 *   (이벤트를 주문과 같은 트랜잭션으로 DB에 저장 → 유실 불가)
 *
 * 트랜잭션 경계:
 * ┌─ @Transactional ──────────────────────────────────────────┐
 * │  1. 포인트 조회 (= 사용자 존재 검증)                         │
 * │  2. 메뉴 조회 + 상태 검증                                   │
 * │  3. 가격 스냅샷 추출                                        │
 * │  4. 재고 차감 (비관적 락, FIFO)                              │
 * │  5. 포인트 차감 (낙관적 락)                                  │
 * │  6. 주문 생성 (saveAndFlush)                                │
 * │  7. 포인트 이력 저장 (Append-Only)                           │
 * │  8. Outbox 이벤트 저장 (같은 트랜잭션 → 유실 불가)            │
 * │  9. 커밋                                                    │
 * └────────────────────────────────────────────────────────────┘
 *                          │
 *                          ▼ 스케줄러가 Outbox 폴링
 *               Kafka 전송 → Analytics Consumer가 수신
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final MenuRepository menuRepository;
    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final InventoryService inventoryService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topic.order-completed:order-completed}")
    private String orderCompletedTopic;

    @Transactional
    public OrderResponse execute(Long userId, Long menuId) {
        // 1. 포인트 조회 (= 사용자 존재 검증)
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_USER_NOT_FOUND));

        // 2. 메뉴 조회 + 상태 검증
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_MENU_NOT_FOUND));

        if (!menu.isActive()) {
            throw new CustomException(ErrorCode.ORDER_MENU_INACTIVE);
        }

        // 3. 주문 시점 스냅샷 (가격 + 메뉴명, 1회 추출)
        //    메뉴명·가격이 이후 변경되어도 주문 이력은 불변으로 보존
        long orderPrice = menu.getPrice();
        String orderMenuName = menu.getName();

        // 4. 재고 차감 (비관적 락 + FIFO)
        //    주문 1건 = 재고 1개 차감
        //    재고 부족 시 INVENTORY_001 예외 → 포인트 차감 전에 실패
        inventoryService.deductStock(menuId, 1);

        // 5. 포인트 차감 (낙관적 락)
        point.use(orderPrice);

        // 6. 주문 생성 (menuName + price 스냅샷 — 주문 데이터 불변성)
        User userRef = userRepository.getReferenceById(userId);
        Order order = orderRepository.saveAndFlush(
                Order.builder()
                        .user(userRef)
                        .menu(menu)
                        .menuName(orderMenuName)
                        .price(orderPrice)
                        .build()
        );

        // 7. 포인트 이력 저장
        pointHistoryRepository.save(
                PointHistory.ofUse(userId, orderPrice, point.getBalance(), order.getId())
        );

        // 8. Outbox 이벤트 저장 (같은 트랜잭션 → 유실 불가)
        saveOutboxEvent(order, userId, menuId, orderMenuName, orderPrice);

        return OrderResponse.of(order, point.getBalance());
    }

    /**
     * Transactional Outbox: 이벤트를 주문과 같은 트랜잭션으로 DB에 저장
     *
     * - 주문 커밋 시 이벤트도 함께 커밋 → 유실 불가
     * - 주문 롤백 시 이벤트도 롤백 → 유령 이벤트 불가
     * - 직렬화 실패 시에도 예외를 던져서 주문 전체 롤백
     *   (Outbox 패턴의 핵심: 주문과 이벤트의 원자성)
     */
    private void saveOutboxEvent(Order order, Long userId, Long menuId, String menuName, long price) {
        try {
            OrderCompletedEvent event = new OrderCompletedEvent(
                    order.getId(), userId, menuId, menuName, price, order.getCreatedAt());

            String payload = objectMapper.writeValueAsString(event);

            outboxRepository.save(EventOutbox.builder()
                    .topic(orderCompletedTopic)
                    .partitionKey(String.valueOf(userId))
                    .payload(payload)
                    .build());

        } catch (Exception e) {
            // 직렬화/저장 실패 시 주문도 함께 롤백 (Outbox 원자성 보장)
            throw new RuntimeException("[Outbox] 이벤트 저장 실패: orderId=" + order.getId(), e);
        }
    }
}
