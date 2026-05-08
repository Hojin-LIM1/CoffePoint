package com.coffeepoint.domain.user.repository;

import com.coffeepoint.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
