# v2 부하 테스트 가이드

## 테스트 종류

| 파일 | 목적 | VU | 검증 포인트 |
|------|------|-----|-------------|
| `load-test-v2.js` | **전체 시나리오** | 50명 | 재고+포인트 동시 차감, Outbox 정합성, 재고 소진 |
| `concurrency-test.js` | **동시성 스트레스** | 10명 | 단일 유저 집중 → 비관적+낙관적 락 동시 동작 |

## 실행 순서

```bash
# 1. 인프라 (MySQL + Redis + Kafka)
docker-compose up -d

# 2. 앱 실행
./gradlew bootRun

# 3-A. 전체 부하 테스트
k6 run load-test/load-test-v2.js

# 3-B. 동시성 스트레스
k6 run load-test/concurrency-test.js

# 3-C. Docker로 실행 (k6 미설치)
docker-compose --profile loadtest run k6

# 4. DB 정합성 검증 (10개 쿼리)
mysql -u coffee -pcoffee1234 coffee_point < load-test/verify-v2.sql
```

## 성공 기준

| 항목 | 기준 | 비고 |
|------|------|------|
| 음수 잔액 | **0건** | 낙관적 락 검증 |
| 음수 재고 | **0건** | 비관적 락 검증 |
| balance == SUM(history) | **항상 일치** | 포인트 정합성 |
| 주문 수 == Outbox 수 | **항상 일치** | 이벤트 유실 없음 |
| 주문 수 == USE 이력 수 | **항상 일치** | 트랜잭션 원자성 |
| HTTP 500 에러 | **0건** | 서버 안정성 |

## verify-v2.sql 검증 항목 (10개)

1. 포인트 잔액 정합성 (balance == SUM(history))
2. 음수 잔액 체크
3. 음수 재고 체크
4. 주문 수 == 포인트 USE 이력 수
5. **주문 수 == Outbox 이벤트 수** (v2 신규)
6. **Outbox 상태 분포** (PENDING/SENT/DEAD)
7. **메뉴별 재고 잔량 + 판매 수** (v2 신규)
8. 인기 메뉴 TOP 3
9. **시간대별 분석 데이터** (v2 신규)
10. 통계 요약
