package com.coffeepoint.domain.menu.controller;

import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.entity.Order;
import com.coffeepoint.domain.order.repository.OrderRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MenuControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        menuRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/menus - 활성 메뉴 목록을 조회한다")
    void getMenus() throws Exception {
        // given
        menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        menuRepository.save(Menu.builder().name("카페라떼").price(5000).build());

        // when & then
        mockMvc.perform(get("/api/menus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("아메리카노"))
                .andExpect(jsonPath("$[0].price").value(4500))
                .andExpect(jsonPath("$[1].name").value("카페라떼"))
                .andExpect(jsonPath("$[1].price").value(5000));
    }

    @Test
    @DisplayName("GET /api/menus/popular - 최근 7일 인기 메뉴 상위 3개를 조회한다")
    void getPopularMenus() throws Exception {
        // given
        User user = userRepository.save(User.builder().name("테스트").build());
        Menu menu1 = menuRepository.save(Menu.builder().name("아메리카노").price(4500).build());
        Menu menu2 = menuRepository.save(Menu.builder().name("카페라떼").price(5000).build());
        Menu menu3 = menuRepository.save(Menu.builder().name("카푸치노").price(5500).build());

        // 아메리카노 3건, 카페라떼 2건, 카푸치노 1건
        for (int i = 0; i < 3; i++) {
            orderRepository.save(Order.builder().user(user).menu(menu1).price(menu1.getPrice()).build());
        }
        for (int i = 0; i < 2; i++) {
            orderRepository.save(Order.builder().user(user).menu(menu2).price(menu2.getPrice()).build());
        }
        orderRepository.save(Order.builder().user(user).menu(menu3).price(menu3.getPrice()).build());

        // when & then
        mockMvc.perform(get("/api/menus/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].name").value("아메리카노"))
                .andExpect(jsonPath("$[0].orderCount").value(3))
                .andExpect(jsonPath("$[1].rank").value(2))
                .andExpect(jsonPath("$[1].name").value("카페라떼"))
                .andExpect(jsonPath("$[1].orderCount").value(2))
                .andExpect(jsonPath("$[2].rank").value(3))
                .andExpect(jsonPath("$[2].name").value("카푸치노"))
                .andExpect(jsonPath("$[2].orderCount").value(1));
    }
}
