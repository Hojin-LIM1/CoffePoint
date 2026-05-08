package com.coffeepoint.domain.menu.entity;

import com.coffeepoint.common.config.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MenuStatus status;

    @Builder
    public Menu(String name, long price) {
        this.name = name;
        this.price = price;
        this.status = MenuStatus.ACTIVE;
    }

    public boolean isActive() {
        return this.status == MenuStatus.ACTIVE;
    }
}
