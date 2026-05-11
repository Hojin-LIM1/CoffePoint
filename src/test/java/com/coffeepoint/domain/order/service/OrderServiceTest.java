package com.coffeepoint.domain.order.service;

import com.coffeepoint.common.exception.CustomException;
import com.coffeepoint.common.exception.ErrorCode;
import com.coffeepoint.domain.inventory.service.InventoryService;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.dto.OrderResponse;
import com.coffeepoint.domain.order.entity.Order;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.outbox.repository.OutboxRepository;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @InjectMocks
    private OrderTransactionService transactionService;

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private MenuRepository menuRepository;
    @Mock private PointRepository pointRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;
    @Mock private InventoryService inventoryService;
    @Mock private OutboxRepository outboxRepository;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    private Menu createMenu(String name, long price) {
        return Menu.builder().name(name).price(price).build();
    }

    private Point createPointWithBalance(Long userId, long balance) {
        Point point = Point.builder().userId(userId).build();
        if (balance > 0) point.charge(balance);
        return point;
    }

    @Nested
    @DisplayName("주문/결제")
    class OrderTest {

        @Test
        @DisplayName("정상 주문 시 재고 차감 + 포인트 차감 + Outbox 저장")
        void orderSuccess() {
            Long userId = 1L;
            Long menuId = 1L;
            User user = User.builder().name("테스트").build();
            Menu menu = createMenu("아메리카노", 4500L);
            Point point = createPointWithBalance(userId, 10_000L);

            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));
            given(menuRepository.findById(menuId)).willReturn(Optional.of(menu));
            given(userRepository.getReferenceById(userId)).willReturn(user);
            given(orderRepository.saveAndFlush(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(pointHistoryRepository.save(any())).willReturn(null);
            given(outboxRepository.save(any())).willReturn(null);

            OrderResponse response = transactionService.execute(userId, menuId);

            assertThat(response.getPrice()).isEqualTo(4500L);
            assertThat(response.getRemainBalance()).isEqualTo(5500L);

            // 재고 차감 호출 확인
            verify(inventoryService, times(1)).deductStock(eq(menuId), eq(1));
            // Outbox 저장 확인 (이벤트 유실 방지)
            verify(outboxRepository, times(1)).save(any());
            // 포인트 이력 저장 확인
            verify(pointHistoryRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("재고 부족 시 포인트는 차감되지 않는다")
        void orderInventoryInsufficientDoesNotDeductPoint() {
            Long userId = 1L;
            Long menuId = 1L;
            Menu menu = createMenu("아메리카노", 4500L);
            Point point = createPointWithBalance(userId, 10_000L);

            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));
            given(menuRepository.findById(menuId)).willReturn(Optional.of(menu));
            // 재고 차감에서 예외 발생
            doThrow(new CustomException(ErrorCode.INVENTORY_INSUFFICIENT))
                    .when(inventoryService).deductStock(eq(menuId), eq(1));

            assertThatThrownBy(() -> transactionService.execute(userId, menuId))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVENTORY_INSUFFICIENT);

            // 포인트는 차감되지 않아야 함 (재고 차감이 먼저)
            assertThat(point.getBalance()).isEqualTo(10_000L);
            verify(orderRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("잔액 부족하면 ORDER_001 예외")
        void orderInsufficientBalanceFail() {
            Long userId = 1L;
            Long menuId = 1L;
            Menu menu = createMenu("아메리카노", 4500L);
            Point point = createPointWithBalance(userId, 3000L);

            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));
            given(menuRepository.findById(menuId)).willReturn(Optional.of(menu));
            // 재고는 충분

            assertThatThrownBy(() -> transactionService.execute(userId, menuId))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_INSUFFICIENT_BALANCE);

            verify(orderRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("존재하지 않는 메뉴면 ORDER_002 예외")
        void orderMenuNotFoundFail() {
            Long userId = 1L;
            Point point = createPointWithBalance(userId, 10_000L);

            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));
            given(menuRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.execute(userId, 999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_MENU_NOT_FOUND);
        }

        @Test
        @DisplayName("비활성 메뉴면 ORDER_003 예외")
        void orderInactiveMenuFail() {
            Long userId = 1L;
            Long menuId = 1L;
            Point point = createPointWithBalance(userId, 10_000L);
            Menu menu = createMenu("품절메뉴", 5000L);
            Menu spyMenu = spy(menu);
            given(spyMenu.isActive()).willReturn(false);

            given(pointRepository.findByUserId(userId)).willReturn(Optional.of(point));
            given(menuRepository.findById(menuId)).willReturn(Optional.of(spyMenu));

            assertThatThrownBy(() -> transactionService.execute(userId, menuId))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_MENU_INACTIVE);
        }

        @Test
        @DisplayName("포인트 없는 사용자면 ORDER_004 예외")
        void orderUserNotFoundFail() {
            given(pointRepository.findByUserId(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.execute(999L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_USER_NOT_FOUND);
        }
    }
}
