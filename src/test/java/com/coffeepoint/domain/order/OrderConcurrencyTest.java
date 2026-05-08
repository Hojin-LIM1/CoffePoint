package com.coffeepoint.domain.order;

import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.order.service.OrderService;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OrderConcurrencyTest {

    @Autowired private OrderService orderService;
    @Autowired private PointService pointService;
    @Autowired private UserRepository userRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;

    private Long userId;
    private Long menuId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        pointHistoryRepository.deleteAll();
        pointRepository.deleteAll();
        menuRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder().name("테스트유저").build());
        userId = user.getId();
        pointRepository.save(Point.builder().userId(userId).build());

        Menu menu = menuRepository.save(Menu.builder().name("아메리카노").price(1_000).build());
        menuId = menu.getId();

        // 10,000P 충전
        pointService.charge(userId, 10_000);
    }

    @Test
    @DisplayName("동시에 10건의 주문이 들어와도 포인트 정합성이 보장된다")
    void concurrentOrderTest() throws InterruptedException {
        // given
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: 10개 스레드가 동시에 1,000P 메뉴 주문
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    orderService.order(userId, menuId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // then
        Point point = pointRepository.findByUserId(userId).orElseThrow();

        // 1. 음수 잔액 미발생
        assertThat(point.getBalance()).isGreaterThanOrEqualTo(0);

        // 2. 잔액 = 초기값(10,000) - 충전(10,000) 후 차감분
        // 충전 10,000P 에서 성공 건수 × 1,000P 차감
        long expectedBalance = 10_000L - (successCount.get() * 1_000L);
        assertThat(point.getBalance()).isEqualTo(expectedBalance);

        // 3. point.balance == SUM(point_history) 정합성
        long historyBalance = pointHistoryRepository.calculateBalanceByUserId(userId);
        assertThat(point.getBalance()).isEqualTo(historyBalance);

        // 4. 성공 + 실패 == 전체 요청 수
        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);

        System.out.printf("=== 동시 주문 테스트 결과 ===%n");
        System.out.printf("성공: %d건, 실패(락 충돌): %d건%n", successCount.get(), failCount.get());
        System.out.printf("최종 잔액: %dP, 이력 합계: %dP%n", point.getBalance(), historyBalance);
        System.out.printf("주문 수: %d건%n", orderRepository.count());
    }

    @Test
    @DisplayName("잔액 부족 시 주문이 거부되고 음수 잔액이 발생하지 않는다")
    void insufficientBalanceTest() throws InterruptedException {
        // given: 잔액 10,000P, 메뉴 1,000P, 15건 동시 주문 → 최대 10건만 성공 가능
        int threadCount = 15;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    orderService.order(userId, menuId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 잔액 부족 또는 락 충돌
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // then
        Point point = pointRepository.findByUserId(userId).orElseThrow();

        // 1. 음수 잔액 절대 미발생
        assertThat(point.getBalance()).isGreaterThanOrEqualTo(0);

        // 2. 성공 건수는 최대 10건 (10,000P / 1,000P)
        assertThat(successCount.get()).isLessThanOrEqualTo(10);

        // 3. 정합성 유지
        long historyBalance = pointHistoryRepository.calculateBalanceByUserId(userId);
        assertThat(point.getBalance()).isEqualTo(historyBalance);

        System.out.printf("=== 잔액 부족 동시 주문 테스트 ===%n");
        System.out.printf("성공: %d건 (최대 10건), 잔액: %dP%n", successCount.get(), point.getBalance());
    }
}
