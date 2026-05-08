package com.coffeepoint.domain.point.repository;

import com.coffeepoint.domain.point.entity.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    List<PointHistory> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 정합성 검증용: 충전 합계 - 사용 합계
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN ph.type = 'CHARGE' THEN ph.amount ELSE -ph.amount END), 0) " +
            "FROM PointHistory ph WHERE ph.userId = :userId")
    long calculateBalanceByUserId(@Param("userId") Long userId);
}
