package com.coffeepoint.common.config;

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

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final MenuRepository menuRepository;
    private final PointRepository pointRepository;

    @Override
    public void run(String... args) {
        // 사용자
        User user1 = userRepository.save(User.builder().name("홍길동").build());
        User user2 = userRepository.save(User.builder().name("김영희").build());

        // 포인트
        pointRepository.save(Point.builder().userId(user1.getId()).build());
        pointRepository.save(Point.builder().userId(user2.getId()).build());

        // 메뉴
        menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        menuRepository.save(Menu.builder().name("카페라떼").price(5000).build());
        menuRepository.save(Menu.builder().name("카푸치노").price(5500).build());
        menuRepository.save(Menu.builder().name("바닐라라떼").price(5500).build());
        menuRepository.save(Menu.builder().name("카라멜마끼아또").price(6000).build());

        log.info("=== 초기 데이터 로딩 완료 ===");
        log.info("사용자: {}, {}", user1.getName(), user2.getName());
        log.info("메뉴: 아메리카노(4500), 카페라떼(5000), 카푸치노(5500), 바닐라라떼(5500), 카라멜마끼아또(6000)");
    }
}
