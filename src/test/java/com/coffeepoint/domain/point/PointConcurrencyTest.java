package com.coffeepoint.domain.point;

import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.inventory.repository.InventoryRepository;
import com.coffeepoint.domain.outbox.repository.OutboxRepository;
import com.coffeepoint.domain.menu.repository.MenuRepository;
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
class PointConcurrencyTest {

    @Autowired private PointService pointService;
    @Autowired private UserRepository userRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private OutboxRepository outboxRepository;

    private Long userId;

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
    }

    @Test
    @DisplayName("동시에 10건의 충전 요청이 들어와도 포인트 정합성이 보장된다 (@Retryable 재시도)")
    void concurrentChargeTest() throws InterruptedException {
        // given
        int threadCount = 10;
        long chargeAmount = 1_000L;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: 10개 스레드가 동시에 1,000P 충전
        // @Retryable 덕분에 낙관적 락 충돌 시 자동 재시도 → 성공률 향상
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    pointService.charge(userId, chargeAmount);
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

        // 1. 잔액 = 성공 건수 × 충전 금액
        assertThat(point.getBalance()).isEqualTo(successCount.get() * chargeAmount);

        // 2. 성공 + 실패 = 전체 요청 수
        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);

        // 3. point.balance == SUM(point_history) 정합성
        long historyBalance = pointHistoryRepository.calculateBalanceByUserId(userId);
        assertThat(point.getBalance()).isEqualTo(historyBalance);

        System.out.printf("=== 동시 충전 테스트 결과 (@Retryable) ===%n");
        System.out.printf("성공: %d건, 실패(3회 재시도 후에도 실패): %d건%n", successCount.get(), failCount.get());
        System.out.printf("최종 잔액: %dP, 이력 합계: %dP%n", point.getBalance(), historyBalance);
    }
}
