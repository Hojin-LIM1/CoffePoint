package com.coffeepoint.domain.menu.service;

import com.coffeepoint.domain.menu.dto.MenuResponse;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.entity.MenuStatus;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @InjectMocks
    private MenuService menuService;

    @Mock private MenuRepository menuRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("ACTIVE 상태의 메뉴만 조회된다")
    void getMenusOnlyActive() {
        // given
        Menu menu1 = Menu.builder().name("아메리카노").price(4500).build();
        Menu menu2 = Menu.builder().name("카페라떼").price(5000).build();

        given(menuRepository.findAllByStatus(MenuStatus.ACTIVE))
                .willReturn(List.of(menu1, menu2));

        // when
        List<MenuResponse> result = menuService.getMenus();

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("아메리카노");
        assertThat(result.get(1).getPrice()).isEqualTo(5000);
    }

    @Test
    @DisplayName("메뉴가 없으면 빈 리스트를 반환한다")
    void getMenusEmpty() {
        // given
        given(menuRepository.findAllByStatus(MenuStatus.ACTIVE))
                .willReturn(List.of());

        // when
        List<MenuResponse> result = menuService.getMenus();

        // then
        assertThat(result).isEmpty();
    }
}
