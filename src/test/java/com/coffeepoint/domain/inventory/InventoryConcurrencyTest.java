package com.coffeepoint.domain.inventory;

import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.repository.InventoryRepository;
import com.coffeepoint.domain.inventory.service.InventoryService;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.outbox.repository.OutboxRepository;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class InventoryConcurrencyTest {

    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private UserRepository userRepository;

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

        Menu menu = menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        menuId = menu.getId();

        // 재고 10개
        inventoryRepository.save(Inventory.builder()
                .menuId(menuId)
                .quantity(10)
                .receivedDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30))
                .build());
    }

    @Test
    @DisplayName("20개 동시 재고 차감 요청 시 10개만 성공하고 음수 재고가 발생하지 않는다")
    void concurrentDeductTest() throws InterruptedException {
        // given: 재고 10개, 20개 동시 차감 요청
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    inventoryService.deductStock(menuId, 1);
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
        int remainingStock = inventoryRepository.getTotalAvailableQuantity(menuId, LocalDate.now());

        // 1. 재고가 음수가 아님
        assertThat(remainingStock).isGreaterThanOrEqualTo(0);

        // 2. 성공 건수 = 10 (재고 10개)
        assertThat(successCount.get()).isEqualTo(10);

        // 3. 실패 건수 = 10 (재고 부족)
        assertThat(failCount.get()).isEqualTo(10);

        // 4. 최종 재고 = 0
        assertThat(remainingStock).isEqualTo(0);

        System.out.printf("=== 재고 동시성 테스트 (비관적 락) ===%n");
        System.out.printf("성공: %d건, 실패: %d건, 잔여 재고: %d%n",
                successCount.get(), failCount.get(), remainingStock);
    }
}
