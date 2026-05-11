package com.coffeepoint.domain.outbox.entity;

public enum OutboxStatus {
    PENDING,     // 전송 대기
    PROCESSING,  // 전송 중 (스케줄러가 선점)
    SENT,        // 전송 완료
    DEAD         // 5회 재시도 후 실패 → Dead Letter
}
