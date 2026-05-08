package com.coffeepoint.domain.menu.repository;

import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.entity.MenuStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findAllByStatus(MenuStatus status);
}
