package com.coffeepoint.common.config;

import com.coffeepoint.domain.inventory.entity.Inventory;
import com.coffeepoint.domain.inventory.repository.InventoryRepository;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final MenuRepository menuRepository;
    private final PointRepository pointRepository;
    private final InventoryRepository inventoryRepository;

    @Override
    public void run(String... args) {
        User user1 = userRepository.save(User.builder().name("홍길동").build());
        User user2 = userRepository.save(User.builder().name("김영희").build());

        pointRepository.save(Point.builder().userId(user1.getId()).build());
        pointRepository.save(Point.builder().userId(user2.getId()).build());

        Menu m1 = menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        Menu m2 = menuRepository.save(Menu.builder().name("카페라떼").price(5000).build());
        Menu m3 = menuRepository.save(Menu.builder().name("카푸치노").price(5500).build());
        Menu m4 = menuRepository.save(Menu.builder().name("바닐라라떼").price(5500).build());
        Menu m5 = menuRepository.save(Menu.builder().name("카라멜마끼아또").price(6000).build());

        // 재고 입고 (각 메뉴 100개씩, 유통기한 30일)
        LocalDate today = LocalDate.now();
        for (Menu menu : new Menu[]{m1, m2, m3, m4, m5}) {
            inventoryRepository.save(Inventory.builder()
                    .menuId(menu.getId())
                    .quantity(100)
                    .receivedDate(today)
                    .expirationDate(today.plusDays(30))
                    .build());
        }

        log.info("=== v2 초기 데이터 로딩 완료 ===");
        log.info("사용자 2명, 메뉴 5개, 재고 각 100개");
    }
}
