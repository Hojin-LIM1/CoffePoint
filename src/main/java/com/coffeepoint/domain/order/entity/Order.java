package com.coffeepoint.domain.order.entity;

import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_menu_created", columnList = "menu_id, createdAt"),
        @Index(name = "idx_orders_user_created", columnList = "user_id, createdAt")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    /**
     * 주문 시점의 가격 스냅샷
     * 메뉴 가격이 변경되더라도 주문 당시 결제 금액은 보존된다
     */
    @Column(nullable = false)
    private long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Order(User user, Menu menu, long price) {
        this.user = user;
        this.menu = menu;
        this.price = price;
        this.status = OrderStatus.COMPLETED;
        this.createdAt = LocalDateTime.now(); // JPA Auditing이 persist 시 덮어쓰지만, 단위 테스트에서 null 방지
    }
}
