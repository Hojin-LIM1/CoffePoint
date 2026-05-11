# ☕ 커피포인트 — 커피숍 주문 시스템

> **다수 서버 환경에서 안정적으로 동작하는 커피숍 주문/결제 시스템**
>
> 동시성 제어, 데이터 정합성, 이벤트 기반 아키텍처를 고려한 백엔드 서비스

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [기술 스택](#2-기술-스택)
3. [아키텍처](#3-아키텍처)
4. [ERD](#4-erd)
5. [API 명세](#5-api-명세)
6. [동시성 제어 전략](#6-동시성-제어-전략)
7. [Transactional Outbox Pattern](#7-transactional-outbox-pattern)
8. [재고 관리 (FIFO)](#8-재고-관리-fifo)
9. [캐싱 전략](#9-캐싱-전략)
10. [멀티 인스턴스 대응](#10-멀티-인스턴스-대응)
11. [테스트 전략](#11-테스트-전략)
12. [와이어프레임](#12-와이어프레임)
13. [기술적 한계 및 확장 방향](#13-기술적-한계-및-확장-방향)
14. [실행 방법](#14-실행-방법)
15. [CI/CD](#15-cicd)

---

## 1. 프로젝트 개요

### 요구사항

4개 필수 API를 기반으로, 재고 관리, 실시간 데이터 수집, 분석 기능까지 확장한 커피숍 주문 시스템입니다.

| API | 메서드 | 엔드포인트 | 핵심 기능 |
|-----|--------|-----------|-----------|
| 메뉴 목록 조회 | GET | `/api/menus` | Caffeine 로컬 캐시 |
| 포인트 충전 | PATCH | `/api/points/{userId}/charge` | 낙관적 락 + @Retryable |
| 주문/결제 | POST | `/api/orders` | 재고 차감 + 포인트 차감 + Outbox |
| 인기 메뉴 조회 | GET | `/api/menus/popular` | Redis ZSET + SETNX Stampede 방어 |

### 확장 기능

| 기능 | 설명 |
|------|------|
| 재고 관리 | 비관적 락 + FIFO 차감(유통기한 임박순) + 자동 폐기 스케줄러 |
| Transactional Outbox | 이벤트를 주문과 같은 TX로 저장 → At-Least-Once 보장 |
| Kafka 이벤트 스트림 | Outbox → Kafka → Analytics Consumer |
| 데이터 분석 | 메뉴별·시간대별 집계, 매출 추이 API |

---

## 2. 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.3.5, Spring Data JPA, Spring Kafka |
| Database | MySQL 8.0 (프로덕션), H2 (테스트) |
| Cache | Redis 7 (인기 메뉴), Caffeine (메뉴 목록) |
| Messaging | Apache Kafka (Outbox → Analytics) |
| Retry | Spring Retry (@Retryable) |
| Infra | Docker, AWS ECS Fargate, ECR, RDS, ElastiCache, MSK |
| CI/CD | GitHub Actions |
| API Docs | Swagger (springdoc-openapi) |
| Test | JUnit 5, Mockito, k6 (부하 테스트) |

---

## 3. 아키텍처

### 주문 처리 흐름

```
클라이언트 → OrderService (@Retryable)
               └→ OrderTransactionService (@Transactional)
                    ├→ 재고 차감 (비관적 락, SELECT FOR UPDATE, FIFO)
                    ├→ 포인트 차감 (낙관적 락, @Version)
                    ├→ 주문 생성 (saveAndFlush, 가격 스냅샷)
                    ├→ 포인트 이력 저장 (Append-Only)
                    └→ Outbox INSERT (같은 TX → 유실 불가)
```

### Outbox → Kafka → Analytics

```
OutboxScheduler (3-Phase):
  Phase 1: [짧은 TX] PENDING/고착PROCESSING → PROCESSING (SKIP LOCKED)
  Phase 2: [TX 없음] Kafka send().get(10초) — DB 커넥션 미점유
  Phase 3: [짧은 TX] → SENT or → PENDING 복원(retryCount++)

Kafka Consumer:
  order-completed 토픽 수신 → OrderAnalytics UPSERT (비관적 락)
```

### AWS 배포 아키텍처

```
ALB ──┬──▶ ECS Task 1 ──┐
      ├──▶ ECS Task 2 ──┼──▶ RDS MySQL (락으로 정합성 보장)
      └──▶ ECS Task 3 ──┤   ElastiCache Redis (공유 캐시)
                        └──▶ MSK Kafka (이벤트 스트림)
```

---

## 4. ERD

> **dbdiagram.io에서 렌더링**: 아래 DBML 코드를 [dbdiagram.io](https://dbdiagram.io)에 붙여넣으세요.

```dbml
Table users {
  id bigint [pk, increment]
  name varchar(50) [not null]
  created_at datetime [not null]
  updated_at datetime [not null]
}

Table menu {
  id bigint [pk, increment]
  name varchar(100) [not null]
  price bigint [not null]
  status varchar(20) [not null, default: 'ACTIVE', note: 'ACTIVE / INACTIVE']
  created_at datetime [not null]
  updated_at datetime [not null]
}

Table point {
  id bigint [pk, increment]
  user_id bigint [unique, not null]
  balance bigint [not null, default: 0]
  version int [not null, default: 0, note: '낙관적 락']
  created_at datetime [not null]
  updated_at datetime [not null]
}

Table point_history {
  id bigint [pk, increment]
  user_id bigint [not null]
  type varchar(20) [not null, note: 'CHARGE / USE']
  amount bigint [not null]
  balance_after bigint [not null]
  description varchar(200)
  created_at datetime [not null]
  indexes { (user_id, created_at) [name: 'idx_ph_user_created'] }
  note: 'Append-Only'
}

Table orders {
  id bigint [pk, increment]
  user_id bigint [not null]
  menu_id bigint [not null]
  price bigint [not null, note: '주문 시점 스냅샷']
  status varchar(20) [not null]
  created_at datetime [not null]
  indexes {
    (status, created_at, menu_id) [name: 'idx_orders_status_created_menu']
    (user_id, created_at) [name: 'idx_orders_user_created']
  }
}

Table inventory {
  id bigint [pk, increment]
  menu_id bigint [not null]
  quantity int [not null]
  received_date date [not null]
  expiration_date date [not null]
  status varchar(20) [not null, default: 'AVAILABLE']
  created_at datetime [not null]
  updated_at datetime [not null]
  indexes {
    (menu_id, status, expiration_date) [name: 'idx_inv_menu_status_exp']
    (status, expiration_date) [name: 'idx_inv_status_exp']
  }
  note: '비관적 락 + FIFO'
}

Table event_outbox {
  id bigint [pk, increment]
  topic varchar(100) [not null]
  partition_key varchar(100) [not null]
  payload text [not null]
  status varchar(20) [not null, default: 'PENDING', note: 'PENDING/PROCESSING/SENT/DEAD']
  retry_count int [not null, default: 0]
  created_at datetime [not null]
  processed_at datetime
  indexes {
    (status, created_at) [name: 'idx_outbox_status_created']
    (status, processed_at) [name: 'idx_outbox_status_processed']
  }
  note: 'Transactional Outbox'
}

Table order_analytics {
  id bigint [pk, increment]
  menu_id bigint [not null]
  menu_name varchar(100) [not null]
  order_date date [not null]
  order_hour int [not null]
  order_count bigint [not null, default: 0]
  total_revenue bigint [not null, default: 0]
  created_at datetime [not null]
  last_updated_at datetime
  indexes {
    (order_date, menu_id) [name: 'idx_analytics_date_menu']
    (order_date) [name: 'idx_analytics_date']
    (menu_id, order_date, order_hour) [unique, name: 'uk_analytics_menu_date_hour']
  }
  note: 'Kafka Consumer 집계'
}

Ref: point.user_id - users.id
Ref: point_history.user_id > users.id
Ref: orders.user_id > users.id
Ref: orders.menu_id > menu.id
Ref: inventory.menu_id > menu.id
Ref: order_analytics.menu_id > menu.id
```

---

## 5. API 명세

### 핵심 API

| API | 메서드 | 엔드포인트 | 요청 | 응답 |
|-----|--------|-----------|------|------|
| 메뉴 조회 | GET | `/api/menus` | - | `[{id, name, price}]` |
| 인기 메뉴 | GET | `/api/menus/popular` | - | `[{rank, id, name, price, orderCount}]` |
| 포인트 충전 | PATCH | `/api/points/{userId}/charge` | `{amount}` | `{userId, balance}` |
| 잔액 조회 | GET | `/api/points/{userId}` | - | `{userId, balance}` |
| 주문/결제 | POST | `/api/orders` | `{userId, menuId}` | `{orderId, menuName, price, remainBalance}` |

### 재고 API

| API | 메서드 | 엔드포인트 | 설명 |
|-----|--------|-----------|------|
| 재고 조회 | GET | `/api/inventory/{menuId}` | 가용 재고 수량 |
| 재고 상세 | GET | `/api/inventory/{menuId}/detail` | 유통기한별 상세 목록 |
| 재고 입고 | POST | `/api/inventory` | 신규 재고 입고 |

### 분석 API

| API | 메서드 | 엔드포인트 | 설명 |
|-----|--------|-----------|------|
| 인기 메뉴 분석 | GET | `/api/analytics/popular-menus?from=&to=` | 기간별 메뉴 매출 |
| 시간대별 분포 | GET | `/api/analytics/hourly?from=&to=` | 시간대별 주문 수 |
| 일별 매출 | GET | `/api/analytics/daily-revenue?from=&to=` | 일별 매출 추이 |

### 에러 코드

| 코드 | HTTP | 설명 |
|------|------|------|
| ORDER_001 | 400 | 잔액 부족 |
| ORDER_002 | 404 | 메뉴 없음 |
| ORDER_003 | 400 | 비활성 메뉴 |
| ORDER_004 | 404 | 사용자 없음 |
| INVENTORY_001 | 400 | 재고 부족 |
| POINT_001 | 400 | 최소 금액 미달 (1,000P) |
| POINT_002 | 400 | 최대 금액 초과 (1,000,000P) |
| POINT_003 | 400 | 보유 한도 초과 (10,000,000P) |

---

## 6. 동시성 제어 전략

### 포인트: 낙관적 락 + @Retryable

```java
@Entity
public class Point {
    private long balance;
    @Version
    private int version;  // 동시 수정 감지
}

// retry 계층 (OrderService)
@Retryable(
    retryFor = {ObjectOptimisticLockingFailureException.class,
                DataIntegrityViolationException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 50)
)
public OrderResponse order(Long userId, Long menuId) {
    return transactionService.execute(userId, menuId);
}

// 트랜잭션 계층 (OrderTransactionService)
@Transactional
public OrderResponse execute(Long userId, Long menuId) { ... }
```

**@Retryable과 @Transactional을 분리한 이유**: 같은 메서드에 두면 OptimisticLockException 발생 시 트랜잭션이 rollback-only로 마킹되어, 같은 트랜잭션 안에서 retry해도 정상 동작하지 않습니다. 분리하면 retry할 때마다 새 트랜잭션이 열립니다.

### 재고: 비관적 락 (SELECT FOR UPDATE)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT i FROM Inventory i WHERE i.menuId = :menuId AND i.status = 'AVAILABLE' ...")
List<Inventory> findAvailableByMenuIdWithLock(Long menuId, LocalDate today);
```

**포인트(낙관적)와 재고(비관적)의 선택 근거 차이**: 포인트는 같은 사용자에 대한 동시 충전이 드물지만(충돌 빈도 낮음), 재고는 같은 메뉴에 대한 동시 주문이 빈번합니다(충돌 빈도 높음). 비관적 락은 대기 후 즉시 처리하므로 재시도 오버헤드가 없습니다.

### 쿼리-인덱스 매칭 전수 검증

| 쿼리 | WHERE 조건 | 인덱스 | 커버링 |
|------|-----------|--------|--------|
| 재고 FIFO 조회 | `menu_id, status, expirationDate` | `(menu_id, status, expirationDate)` | ✅ |
| 인기 메뉴 집계 | `status, createdAt >= ?` GROUP BY `menu_id` | `(status, createdAt, menu_id)` | ✅ |
| Outbox 폴링 | `status` ORDER BY `createdAt` | `(status, createdAt)` | ✅ |
| Outbox 정리 | `status='SENT', processedAt` | `(status, processedAt)` | ✅ |
| Analytics 집계 | `orderDate BETWEEN` GROUP BY `menuId` | `(orderDate, menuId)` | ✅ |

---

## 7. Transactional Outbox Pattern

### v1 → v2 전환 이유

```
v1: 주문 커밋 → AFTER_COMMIT → @Async 전송
    → 서버 죽으면 이벤트 유실 (At-Most-Once)

v2: 주문 + Outbox INSERT (같은 TX)
    → 스케줄러가 Kafka 전송 → 유실 불가 (At-Least-Once)
```

### 3-Phase 아키텍처 (DB 커넥션 장기 점유 방지)

```
Phase 1: [짧은 TX ~수ms]
  SELECT FOR UPDATE SKIP LOCKED → PROCESSING 마킹 → 커밋 → 락 해제

Phase 2: [TX 없음 — DB 커넥션 미점유]
  Kafka send().get(10초) — 동기 전송 확인

Phase 3: [짧은 TX ~수ms]
  성공 → SENT / 실패 → PENDING 복원(retryCount++) / 5회 초과 → DEAD
```

단일 트랜잭션에서 Kafka 전송을 하면 50건 × 10초 = 최대 500초 DB 커넥션 점유 → 커넥션 풀 고갈 위험. 3-Phase로 분리하여 DB 커넥션은 수 밀리초만 점유합니다.

### PROCESSING 고착 복구

서버 크래시로 PROCESSING 상태에서 멈춘 이벤트는 5분 후 자동 재처리됩니다.

```sql
-- Phase 1 쿼리
WHERE status = 'PENDING'
   OR (status = 'PROCESSING' AND created_at < 5분 전)
```

---

## 8. 재고 관리 (FIFO)

### FIFO 차감 (유통기한 임박순)

```java
// 유통기한 오름차순으로 비관적 락 조회
List<Inventory> stocks = findAvailableByMenuIdWithLock(menuId, today);

// 임박한 것부터 차감
int remaining = quantity;
for (Inventory stock : stocks) {
    if (remaining <= 0) break;
    int deductAmount = Math.min(stock.getQuantity(), remaining);
    stock.deduct(deductAmount);
    remaining -= deductAmount;
}
```

### 주문 내 처리 순서 (재고 → 포인트)

```
1. 재고 차감 (비관적 락) ← 실패 시 포인트 차감 전에 종료
2. 포인트 차감 (낙관적 락)
3. 주문 생성
4. Outbox INSERT
5. 커밋
```

재고가 없으면 포인트는 차감되지 않습니다 (트랜잭션 원자성).

### 유통기한 만료 자동 폐기

```java
@Scheduled(cron = "0 0 1 * * *")  // 매일 새벽 1시
public void disposeExpiredStock() {
    // SKIP LOCKED: 멀티 인스턴스 중복 폐기 방지
    List<Inventory> expired = findExpiredWithLock(AVAILABLE, today);
    expired.forEach(Inventory::dispose);
}
```

---

## 9. 캐싱 전략

| 대상 | 캐시 | TTL | 이유 |
|------|------|-----|------|
| 메뉴 목록 | Caffeine (로컬) | 10분 | 변경 빈도 낮음, 네트워크 비용 절감 |
| 인기 메뉴 | Redis ZSET | 5분 | 인스턴스 간 동일 결과 보장 |

### 인기 메뉴 캐시 구조

```
Redis Key: popular:menus (ZSET, menuId만 저장)

Cache Miss:
  1. SETNX popular:lock → Stampede 방어 (1개 스레드만 DB 조회)
  2. DB 집계 쿼리 실행
  3. ZADD popular:menus:temp → RENAME popular:menus (원자적 교체)
  4. TTL 5분 설정

Cache Hit:
  1. ZREVRANGE popular:menus → menuId 목록
  2. menuId로 Menu 엔티티 조회 (Caffeine 캐시에서)
```

---

## 10. 멀티 인스턴스 대응

| 관심사 | 전략 | 멀티 인스턴스 안전 근거 |
|--------|------|----------------------|
| 포인트 정합성 | DB 낙관적 락 (@Version) | RDS가 Single Source of Truth |
| 재고 정합성 | DB 비관적 락 (SELECT FOR UPDATE) | 행 잠금 → 인스턴스 무관 |
| 인기 메뉴 캐시 | ElastiCache Redis | 공유 캐시 → 모든 인스턴스 동일 |
| 이벤트 전달 | Outbox + SKIP LOCKED | 각 인스턴스가 서로 다른 이벤트 처리 |
| 재고 폐기 | SKIP LOCKED | 중복 폐기 방지 |
| 세션 | Stateless | 인스턴스 간 상태 공유 불필요 |

---

## 11. 테스트 전략

### 단위 테스트

| 테스트 | 검증 내용 |
|--------|-----------|
| PointServiceTest | 충전 성공, findOrCreate 자동 생성, 금액 유효성 (최소/최대/한도) |
| OrderServiceTest | 주문 성공 시 재고+포인트+Outbox, 재고 부족 시 포인트 미차감 |
| InventoryServiceTest | FIFO 차감 순서, 재고 부족 예외 |

### 동시성 테스트

| 테스트 | 시나리오 | 검증 |
|--------|----------|------|
| PointConcurrencyTest | 10스레드 동시 충전 | balance == SUM(history), 음수 잔액 0건 |
| OrderConcurrencyTest | 10스레드 동시 주문 | 음수 잔액 0건, 주문 수 == USE 이력 수 |
| InventoryConcurrencyTest | 20스레드 동시 차감 (재고 10개) | 성공 10건, 실패 10건, 음수 재고 0건 |

### 통합 테스트

| 테스트 | 검증 |
|--------|------|
| OutboxIntegrationTest | 주문 성공 → Outbox PENDING / 주문 실패 → Outbox 롤백 |
| AnalyticsConsumerTest | 메시지 수신 → 집계 누적, 시간대 분리, 잘못된 메시지 처리 |
| OrderControllerTest | E2E 주문 흐름 + 재고 차감 + Outbox 이벤트 확인 |

### 부하 테스트 (k6)

```bash
# 전체 시나리오 (메뉴조회 20VU + 충전 20VU + 주문 50VU + 인기메뉴 30VU + 재고조회 5VU)
k6 run load-test/load-test-v2.js

# 동시성 스트레스 (단일 유저 집중)
k6 run load-test/concurrency-test.js

# 정합성 검증 (10개 쿼리)
mysql -u coffee -pcoffee1234 coffee_point < load-test/verify-v2.sql
```

**부하 테스트 성공 기준**

| 항목 | 기준 |
|------|------|
| 음수 잔액 | **0건** |
| 음수 재고 | **0건** |
| balance == SUM(history) | **항상 일치** |
| 주문 수 == Outbox 이벤트 수 | **항상 일치** |
| HTTP 500 에러 | **0건** |

**부하 테스트 결과 (50VU 극한 스트레스)**

```
✅ 음수 잔액: 0건
✅ 500 에러: 0건
✅ checks 성공률: 100% (201/400/409 모두 정상 응답)
```

409(낙관적 락 충돌)는 동시성 제어가 정합성을 정상적으로 보호하고 있다는 의미입니다.

---

## 12. 와이어프레임

### 메뉴 목록 / 주문하기

```
┌──────────────────────────────────────────────────────────┐
│  ☕ 커피포인트 v2                    👤 홍길동  50,000P  │
│  ───────────────────────────────────────────────────────  │
│  [☕ 메뉴] [🛒 주문] [🔥 인기] [💰 포인트] [📦 재고] [📊 분석]│
├──────────────────────────────────────────────────────────┤
│                                                          │
│  ☕ 메뉴 목록  Caffeine 캐시 (TTL 10분)                   │
│                                                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │    ☕    │ │    🥛    │ │    🍵    │ │    🧋    │   │
│  │아메리카노│ │ 카페라떼 │ │ 카푸치노 │ │바닐라라떼│   │
│  │ 4,500원  │ │ 5,000원  │ │ 5,500원  │ │ 5,500원  │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│                                                          │
│  GET /api/menus — Caffeine 로컬 캐시 (TTL 10분)          │
└──────────────────────────────────────────────────────────┘
```

### 주문하기 (재고 표시)

```
┌──────────────────────────────────────────────────────────┐
│  🛒 주문하기  비관적 락(재고) + 낙관적 락(포인트)         │
│                                                          │
│  ┌────────────┐ ┌──────────┐ ┌──────────┐               │
│  │  ☕ [선택] │ │    🥛    │ │    🍵    │               │
│  │ 아메리카노 │ │ 카페라떼 │ │ 카푸치노 │               │
│  │  4,500원   │ │ 5,000원  │ │ 5,500원  │               │
│  │ 재고 87개  │ │ 재고 95개│ │  ⛔ 품절  │               │
│  └────────────┘ └──────────┘ └──────────┘               │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │ ☕ 아메리카노  4,500P 차감           [주문하기]   │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │ ✅ 아메리카노 주문 완료! 잔액: 45,500P            │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  POST /api/orders                                        │
│  재고: SELECT FOR UPDATE (비관적)                         │
│  포인트: @Version (낙관적) + @Retryable                  │
│  이벤트: Outbox INSERT (같은 TX)                          │
└──────────────────────────────────────────────────────────┘
```

### 인기 메뉴 / 포인트 충전

```
┌─────────────────────────┐  ┌─────────────────────────┐
│ 🔥 인기 메뉴 TOP 3       │  │ 💰 포인트 충전            │
│ Redis ZSET + SETNX       │  │ @Version + @Retryable   │
│                          │  │                         │
│ 🥇 아메리카노   127 주문  │  │ ┌───────────────────┐   │
│    4,500원               │  │ │    현재 잔액        │   │
│                          │  │ │   50,000 P         │   │
│ 🥈 카페라떼      98 주문  │  │ └───────────────────┘   │
│    5,000원               │  │                         │
│                          │  │ [1천][5천][1만][5만][10만]│
│ 🥉 카라멜마끼아또 73 주문  │  │                         │
│    6,000원               │  │ [  10,000  ] [충전하기]  │
└─────────────────────────┘  └─────────────────────────┘
```

### 재고 관리 (v2 신규)

```
┌──────────────────────────────────────────────────────────┐
│  📦 재고 관리  비관적 락(SELECT FOR UPDATE) + FIFO 차감   │
│                                                          │
│  ☕ 아메리카노  ████████████████████░░░░  87개  [상세][+50]│
│  🥛 카페라떼    █████████████████████░░░  95개  [상세][+50]│
│  🍵 카푸치노    ░░░░░░░░░░░░░░░░░░░░░░░   0개  [상세][+50]│
│  🧋 바닐라라떼  ████████████████████████ 100개  [상세][+50]│
│                                                          │
│  ┌─────────────────────────────────────────────────┐     │
│  │ 📋 재고 상세 (FIFO — 유통기한순)                  │     │
│  │ ┌──────────┬──────────────┬──────┬────────┐     │     │
│  │ │ 입고일    │ 유통기한      │ 수량 │ 상태   │     │     │
│  │ ├──────────┼──────────────┼──────┼────────┤     │     │
│  │ │ 05-01    │ 05-20 (D-11) │ 37개 │ 사용가능│     │     │
│  │ │ 05-08    │ 06-07        │ 50개 │ 사용가능│     │     │
│  │ └──────────┴──────────────┴──────┴────────┘     │     │
│  └─────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────┘
```

### 데이터 분석 (v2 신규)

```
┌──────────────────────────────────────────────────────────┐
│  📊 데이터 분석  Kafka Consumer → order_analytics 집계    │
│                                                          │
│  🏆 메뉴별 매출                                           │
│  아메리카노  █████████████████████████  571,500원          │
│  카페라떼    ████████████████████       490,000원          │
│  카라멜마끼  ████████████████           438,000원          │
│                                                          │
│  ⏰ 시간대별 주문                                         │
│       ▄                                                  │
│    ▄  █  ▄                                               │
│  ▄ █  █  █ ▄                                             │
│  █ █  █  █ █ ▄                                           │
│  9  10  11  12  13  14  15 시                             │
│                                                          │
│  📈 일별 매출 추이                                        │
│  05-05  ████████████████████████  312,000원               │
│  05-06  ███████████████████       267,500원               │
│  05-07  ██████████████████████    298,000원               │
└──────────────────────────────────────────────────────────┘
```

---

## 13. 기술적 한계 및 확장 방향

### 의도적 트레이드오프

**Analytics Consumer 집계 유실 가능성 (Eventual Consistency)**

UPSERT 시 UniqueConstraint 충돌이 발생하면 해당 메시지 1건을 skip합니다. Analytics는 집계 데이터이므로 1건 유실은 허용 가능하며, 다음 메시지부터 정상 처리됩니다. 완벽한 정확성이 필요하면 DB UPSERT(`INSERT ON DUPLICATE KEY UPDATE`)로 전환할 수 있습니다.

### 확장 방향

| 확장 | 현재 설계에서의 대응 | 도입 시 변경 |
|------|---------------------|-------------|
| Outbox 스케줄러 분산 락 | SKIP LOCKED | ShedLock (DB 기반 분산 스케줄러 락) |
| Kafka DLT | 실패 시 skip + 로그 | Dead Letter Topic으로 전송 → 재처리 |
| CQRS | 단일 DB | 읽기 전용 레플리카 또는 Materialized View |
| Redis 분산 락 | DB 낙관적 락 | AOP 기반 분산 락 어노테이션 (Redisson) |
| DB 마이그레이션 | `ddl-auto: create` | Flyway 도입 → `ddl-auto: validate` |

---

## 14. 실행 방법

### 사전 요구사항

- Java 17+
- Docker, Docker Compose

### 실행

```bash
# 1. 인프라 (MySQL + Redis + Kafka)
docker-compose up -d

# 2. 앱 실행
./gradlew bootRun

# 3. Swagger UI
open http://localhost:8080/swagger-ui.html

# 4. 부하 테스트 (k6 설치 필요)
k6 run load-test/load-test-v2.js

# 5. 정합성 검증
mysql -u coffee -pcoffee1234 coffee_point < load-test/verify-v2.sql
```

---

## 15. CI/CD

### 파이프라인

```
PR 생성
  └→ 🧪 Test (MySQL + Redis + Kafka)
       ├→ 단위 테스트
       ├→ 동시성 테스트
       └→ 통합 테스트

main push
  └→ 🧪 Test → 🐳 Docker Build → ECR Push → 🚀 ECS 롤링 배포 (3인스턴스)
```

### GitHub Secrets

| Secret | 값 |
|--------|-----|
| `AWS_ACCESS_KEY_ID` | IAM Access Key |
| `AWS_SECRET_ACCESS_KEY` | IAM Secret Key |
| `AWS_REGION` | `ap-northeast-2` |
| `ECR_REPOSITORY` | `coffee-point` |
| `ECS_CLUSTER` | `coffee-point-cluster` |
| `ECS_SERVICE` | `coffee-point-service` |

### 프로젝트 구조

```
coffee-point-v2/
├── src/main/java/com/coffeepoint/
│   ├── common/
│   │   ├── config/          # JPA, Redis, Kafka, Cache, Swagger
│   │   ├── exception/       # ErrorCode, CustomException, GlobalHandler
│   │   └── response/        # ApiResponse
│   └── domain/
│       ├── user/            # User 엔티티
│       ├── menu/            # 메뉴 CRUD + 인기 메뉴 (Redis)
│       ├── point/           # 포인트 충전 (낙관적 락 + Retryable)
│       ├── order/           # 주문/결제 (3-Layer: Service→TX→Repository)
│       ├── inventory/       # 재고 관리 (비관적 락 + FIFO)
│       ├── outbox/          # Transactional Outbox (3-Phase 스케줄러)
│       └── analytics/       # Kafka Consumer + 분석 API
├── src/test/                # 단위 5 + 통합 4 + 동시성 3 + Outbox 1
├── load-test/               # k6 부하 테스트 + 정합성 검증 SQL
├── infra/                   # ECS Task Definition + AWS 가이드
├── frontend/                # React JSX (6탭 카페 UI)
├── .github/workflows/       # CI/CD (GitHub Actions)
├── Dockerfile               # 멀티스테이지 빌드
└── docker-compose.yml       # MySQL + Redis + Kafka
```
