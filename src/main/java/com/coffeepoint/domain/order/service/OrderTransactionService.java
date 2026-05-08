package com.coffeepoint.domain.order.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.dto.OrderResponse;
import com.coffeepoint.domain.order.entity.Order;
import com.coffeepoint.domain.order.event.OrderCompletedEvent;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.entity.PointHistory;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 트랜잭션 서비스 — 실제 비즈니스 로직
 *
 * @Transactional만 담당. retry는 OrderService가 처리.
 * retry할 때마다 이 메서드가 새 트랜잭션으로 실행된다.
 *
 * 트랜잭션 경계:
 * ┌─ @Transactional ─────────────────────────────────────┐
 * │  1. 포인트 조회 (= 사용자 존재 검증)                    │
 * │  2. 메뉴 조회 + 상태 검증 (ACTIVE)                     │
 * │  3. 가격 스냅샷 추출                                   │
 * │  4. 포인트 차감 (@Version 낙관적 락)                   │
 * │  5. 주문 생성 (saveAndFlush → createdAt 즉시 확정)     │
 * │  6. 포인트 이력 저장 (Append-Only)                     │
 * │  7. 커밋                                              │
 * └───────────────────────────────────────────────────────┘
 *                          │
 *                          ▼ AFTER_COMMIT
 *               이벤트 발행 → 비동기 데이터 전송
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
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public OrderResponse execute(Long userId, Long menuId) {
        // 1. 포인트 조회 (= 사용자 존재 검증, point 있으면 user 존재)
        Point point = pointRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_USER_NOT_FOUND));

        // 2. 메뉴 조회 + 상태 검증
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_MENU_NOT_FOUND));

        if (!menu.isActive()) {
            throw new CustomException(ErrorCode.ORDER_MENU_INACTIVE);
        }

        // 3. 주문 시점의 가격 스냅샷 (1회 추출, 일관성 보장)
        long orderPrice = menu.getPrice();

        // 4. 포인트 차감 (낙관적 락 — 충돌 시 예외 발생 → OrderService가 재시도)
        point.use(orderPrice);

        // 5. 주문 생성 (saveAndFlush → @CreatedDate가 즉시 확정)
        //    User는 getReferenceById 프록시로 FK만 설정 (SELECT 없음)
        User userRef = userRepository.getReferenceById(userId);
        Order order = orderRepository.saveAndFlush(
                Order.builder()
                        .user(userRef)
                        .menu(menu)
                        .price(orderPrice)
                        .build()
        );

        // 6. 포인트 이력 저장 (Append-Only)
        pointHistoryRepository.save(
                PointHistory.ofUse(userId, orderPrice, point.getBalance(), order.getId())
        );

        // 7. 이벤트 발행 (AFTER_COMMIT에서 비동기 실행)
        //    orderedAt은 flush 후 확정된 Order의 createdAt 사용
        eventPublisher.publishEvent(
                new OrderCompletedEvent(userId, menuId, orderPrice, order.getCreatedAt())
        );

        return OrderResponse.of(order, point.getBalance());
    }
}
