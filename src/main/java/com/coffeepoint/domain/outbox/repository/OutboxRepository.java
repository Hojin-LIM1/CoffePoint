package com.coffeepoint.domain.outbox.repository;

import com.coffeepoint.domain.outbox.entity.EventOutbox;
import com.coffeepoint.domain.outbox.entity.OutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<EventOutbox, Long> {

    /**
     * PENDING + 고착된 PROCESSING 이벤트 조회 (SKIP LOCKED)
     *
     * SKIP_LOCKED: 다른 인스턴스가 처리 중인 row는 건너뜀
     * staleThreshold: 5분 이상 PROCESSING 상태 = 서버 크래시 → 재처리 대상
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM EventOutbox e " +
            "WHERE e.status = 'PENDING' " +
            "OR (e.status = 'PROCESSING' AND e.createdAt < :staleThreshold) " +
            "ORDER BY e.createdAt ASC")
    List<EventOutbox> findPendingWithLock(
            @Param("staleThreshold") LocalDateTime staleThreshold,
            Pageable pageable);

    List<EventOutbox> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);

    /**
     * 벌크 상태 업데이트 (Phase 3 — 전송 결과 반영)
     */
    @Modifying
    @Query("UPDATE EventOutbox e SET e.status = :status, e.processedAt = CURRENT_TIMESTAMP " +
            "WHERE e.id IN :ids")
    int bulkUpdateStatus(@Param("ids") List<Long> ids, @Param("status") String status);

    /**
     * SENT 이벤트 정리 (보관 기간 경과 후 삭제)
     */
    @Modifying
    @Query("DELETE FROM EventOutbox e WHERE e.status = 'SENT' AND e.processedAt < :threshold")
    int deleteSentBefore(@Param("threshold") LocalDateTime threshold);
}
