# ☕ 커피포인트 — 커피숍 주문 시스템

> 다수 서버 환경에서도 안정적으로 동작하는 포인트 기반 커피 주문 시스템

---

## 📌 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [기술 스택](#2-기술-스택)
3. [ERD](#3-erd)
4. [API 명세](#4-api-명세)
5. [정책 정의](#5-정책-정의)
6. [에러 코드 정의](#6-에러-코드-정의)
7. [트랜잭션 경계](#7-트랜잭션-경계)
8. [동시성 제어 전략](#8-동시성-제어-전략)
9. [멀티 인스턴스 대응](#9-멀티-인스턴스-대응)
10. [캐싱 전략](#10-캐싱-전략)
11. [데이터 수집 플랫폼 연동](#11-데이터-수집-플랫폼-연동)
12. [테스트 전략](#12-테스트-전략)
13. [확장 가능성](#13-확장-가능성)
14. [실행 방법](#14-실행-방법)

---

## 1. 프로젝트 개요

커피 메뉴를 조회하고, 충전한 포인트로 주문·결제하며, 주문 데이터를 기반으로 인기 메뉴를 추천하는 시스템입니다.

### 핵심 기능

| 기능 | 설명 |
|------|------|
| 메뉴 조회 | 판매 중인 커피 메뉴 목록을 조회합니다 |
| 포인트 충전 | 사용자가 포인트를 충전합니다 (1원 = 1P) |
| 주문/결제 | 메뉴를 선택하고 포인트로 결제합니다 |
| 인기 메뉴 조회 | 최근 7일간 주문 수 상위 3개 메뉴를 조회합니다 |

### 설계 핵심 관심사

| 관점 | 핵심 질문 | 해결 전략 |
|------|-----------|-----------|
| **동시성 제어** | 동일 사용자의 동시 충전/결제 시 잔액이 꼬이지 않는가? | JPA 낙관적 락 (`@Version`) |
| **데이터 일관성** | 포인트 잔액과 이력이 항상 일치하는가? | 단일 트랜잭션 + Append-Only 이력 |
| **멀티 인스턴스** | 서버가 여러 대여도 기능에 문제가 없는가? | DB = Single Source of Truth, Redis 공유 캐시 |
| **장애 격리** | 외부 시스템 장애가 주문에 영향을 주는가? | 커밋 후 이벤트 기반 비동기 전송 |

---

## 2. 기술 스택

| 구분 | 기술 | 선택 이유 |
|------|------|-----------|
| Language | Java 17 | LTS 버전, Record 등 모던 문법 |
| Framework | Spring Boot 3.x | `@TransactionalEventListener` 네이티브 지원 |
| ORM | JPA (Hibernate) | `@Version` 기반 낙관적 락 네이티브 지원 |
| DB | MySQL 8.x | 트랜잭션 격리 수준 설정, 인덱스 최적화 |
| Cache (공유) | Redis | 멀티 인스턴스 간 인기메뉴 캐시 공유 |
| Cache (로컬) | Caffeine | 메뉴 목록 등 저빈도 변경 데이터 캐싱 |
| API Docs | Swagger (SpringDoc) | API 명세 자동 생성 |
| Infra | Docker | 멀티 인스턴스 환경 시뮬레이션 |
| CI/CD | GitHub Actions | PR 단위 자동 빌드/테스트 |

---

## 3. ERD

### 테이블 관계

```
user (1) ──── (1) point            사용자 당 포인트 잔액 1건 (Current State)
user (1) ──── (N) point_history    포인트 변동 이력 (Append-Only Log)
user (1) ──── (N) orders           주문 내역
menu (1) ──── (N) orders           메뉴별 주문
```

### user

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 사용자 식별값 |
| name | VARCHAR(50) | NOT NULL | 사용자명 |
| created_at | DATETIME | NOT NULL | 가입일시 |
| updated_at | DATETIME | NOT NULL | 수정일시 |

### menu

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 메뉴 ID |
| name | VARCHAR(100) | NOT NULL | 메뉴명 |
| price | BIGINT | NOT NULL | 가격 (원) |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'ACTIVE' | ACTIVE / INACTIVE |
| created_at | DATETIME | NOT NULL | 등록일시 |
| updated_at | DATETIME | NOT NULL | 수정일시 |

### point — Current State

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | PK |
| user_id | BIGINT | FK, UNIQUE, NOT NULL | 사용자 FK (1:1) |
| balance | BIGINT | NOT NULL, DEFAULT 0 | 현재 잔액 |
| version | INT | NOT NULL, DEFAULT 0 | **낙관적 락 버전** |
| created_at | DATETIME | NOT NULL | 생성일시 |
| updated_at | DATETIME | NOT NULL | 수정일시 |

> point 테이블은 **조회 성능을 위한 현재 상태(Current State) 저장소**입니다. 잔액 조회 시 이력을 매번 SUM하지 않고, 단일 row 조회로 O(1) 응답합니다.

### point_history — Append-Only Log

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | PK |
| user_id | BIGINT | FK, NOT NULL | 사용자 FK |
| type | VARCHAR(20) | NOT NULL | CHARGE / USE |
| amount | BIGINT | NOT NULL | 변동 금액 |
| balance_after | INT | NOT NULL | 변동 후 잔액 |
| description | VARCHAR(200) | | 비고 (주문번호 등) |
| created_at | DATETIME | NOT NULL | 발생일시 |

> point_history는 **감사(Audit) 및 정합성 검증을 위한 Append-Only 로그**입니다. UPDATE/DELETE 없이 INSERT만 발생하며, CS 대응과 `point.balance = SUM(point_history)` 정합성 검증의 기준이 됩니다.

### orders

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 주문 ID |
| user_id | BIGINT | FK, NOT NULL | 주문자 FK |
| menu_id | BIGINT | FK, NOT NULL | 메뉴 FK |
| price | BIGINT | NOT NULL | **결제 금액 (주문 시점 스냅샷)** |
| status | VARCHAR(20) | NOT NULL | COMPLETED / CANCELLED |
| created_at | DATETIME | NOT NULL | 주문일시 |

> `price`를 별도 저장하는 이유: 메뉴 가격이 변경되더라도 **주문 당시 결제 금액이 보존**되어야 합니다. 커머스 시스템에서는 주문 시점의 가격 스냅샷을 반드시 보관합니다.

### 인덱스 설계

| 테이블 | 인덱스 | 용도 |
|--------|--------|------|
| orders | `idx_orders_menu_created` (menu_id, created_at) | 인기메뉴 집계 쿼리 최적화 |
| orders | `idx_orders_user_created` (user_id, created_at) | 사용자별 주문 조회 |
| point_history | `idx_ph_user_created` (user_id, created_at) | 포인트 이력 조회 |

> 인기메뉴 API는 `최근 7일 + menu_id별 COUNT` 집계 쿼리를 수행합니다. 복합 인덱스가 없으면 데이터 증가에 따라 풀스캔이 발생합니다.

---

## 4. API 명세

### 4-1. 커피 메뉴 목록 조회

```
GET /api/menus
```

**Response 200**

```json
[
  { "id": 1, "name": "아메리카노", "price": 4500 },
  { "id": 2, "name": "카페라떼", "price": 5000 },
  { "id": 3, "name": "카푸치노", "price": 5500 }
]
```

### 4-2. 포인트 충전

```
PATCH /api/points/{userId}/charge
```

**Request**

```json
{ "amount": 10000 }
```

**Response 200**

```json
{ "userId": 1, "balance": 15000 }
```

**에러 케이스**: POINT_001 ~ POINT_005 → [에러 코드 상세](#6-에러-코드-정의)

### 4-3. 커피 주문/결제

```
POST /api/orders
```

**Request**

```json
{ "userId": 1, "menuId": 3 }
```

**Response 201**

```json
{
  "orderId": 101,
  "menuName": "카푸치노",
  "price": 5500,
  "remainBalance": 9500
}
```

**에러 케이스**: ORDER_001 ~ ORDER_005 → [에러 코드 상세](#6-에러-코드-정의)

**후처리**: 커밋 이후 이벤트 발행 → 비동기 데이터 전송 → [상세](#11-데이터-수집-플랫폼-연동)

### 4-4. 인기 메뉴 목록 조회

```
GET /api/menus/popular
```

**Response 200**

```json
[
  { "rank": 1, "id": 3, "name": "카푸치노", "price": 5500, "orderCount": 142 },
  { "rank": 2, "id": 1, "name": "아메리카노", "price": 4500, "orderCount": 128 },
  { "rank": 3, "id": 2, "name": "카페라떼", "price": 5000, "orderCount": 95 }
]
```

---

## 5. 정책 정의

### 포인트 정책

| 항목 | 내용 |
|------|------|
| 충전 단위 | 1원 = 1P |
| 최소 충전 | 1,000P |
| 최대 충전 (1회) | 1,000,000P |
| 최대 보유 한도 | 10,000,000P |
| 유효기간 | 없음 (무기한) |
| 이력 | 모든 변동은 point_history에 Append-Only 기록 |
| 정합성 | `point.balance = SUM(point_history)` 항상 성립 |

### 주문 정책

| 항목 | 내용 |
|------|------|
| 결제 수단 | 포인트만 가능 |
| 주문 단위 | 1건당 1메뉴 |
| 잔액 부족 | 주문 거부 (400) |
| 비활성 메뉴 | 주문 거부 (409 — 요청 형식은 정상이나 리소스 상태와 충돌) |
| 전송 실패 | **주문은 성공 유지** — 외부 장애가 핵심 비즈니스를 중단시키지 않는다 |

### 메뉴 정책

| 항목 | 내용 |
|------|------|
| 상태 | ACTIVE / INACTIVE |
| 조회 대상 | ACTIVE만 노출 |
| 가격 | 0원 초과 양의 정수 |

### 인기 메뉴 정책

| 항목 | 내용 |
|------|------|
| 집계 기간 | 최근 7일 |
| 집계 대상 | COMPLETED 주문만 |
| 조회 수 | 상위 3개 |
| 동률 처리 | 메뉴 ID 오름차순 |
| 일관성 수준 | **최대 5분의 Eventual Consistency 허용** |

---

## 6. 에러 코드 정의

### 공통 에러

| HTTP | 코드 | 메시지 |
|------|------|--------|
| 400 | COMMON_001 | 잘못된 요청입니다 |
| 404 | COMMON_002 | 리소스를 찾을 수 없습니다 |
| 500 | COMMON_003 | 서버 내부 오류입니다 |
| 503 | COMMON_004 | 현재 서비스를 이용할 수 없습니다 |

### 포인트 에러

| HTTP | 코드 | 메시지 | 상황 |
|------|------|--------|------|
| 400 | POINT_001 | 충전 금액은 1,000원 이상이어야 합니다 | 최소 금액 미달 |
| 400 | POINT_002 | 1회 최대 충전 금액을 초과하였습니다 | 1회 100만원 초과 |
| 400 | POINT_003 | 포인트 보유 한도를 초과합니다 | 한도 초과 |
| 404 | POINT_004 | 사용자를 찾을 수 없습니다 | userId 없음 |
| 409 | POINT_005 | 포인트 처리 중 충돌이 발생했습니다. 다시 시도해주세요 | 낙관적 락 충돌 |

### 주문 에러

| HTTP | 코드 | 메시지 | 상황 |
|------|------|--------|------|
| 400 | ORDER_001 | 잔액이 부족합니다 | 포인트 < 메뉴 가격 |
| 404 | ORDER_002 | 해당 메뉴를 찾을 수 없습니다 | menuId 없음 |
| 409 | ORDER_003 | 현재 주문할 수 없는 메뉴입니다 | INACTIVE 메뉴 |
| 404 | ORDER_004 | 사용자를 찾을 수 없습니다 | userId 없음 |
| 409 | ORDER_005 | 결제 처리 중 충돌이 발생했습니다. 다시 시도해주세요 | 동시 결제 충돌 |

### 메뉴 에러

| HTTP | 코드 | 메시지 | 상황 |
|------|------|--------|------|
| 404 | MENU_001 | 메뉴를 찾을 수 없습니다 | 존재하지 않는 메뉴 |

### 에러 응답 포맷

```json
{
  "code": "ORDER_001",
  "message": "잔액이 부족합니다",
  "timestamp": "2025-05-07T14:30:00"
}
```

---

## 7. 트랜잭션 경계

### 주문/결제 트랜잭션

하나의 주문 요청은 **단일 DB 트랜잭션**으로 처리됩니다.

```
@Transactional ──────────────────────────────────────────────┐
│                                                            │
│  1. 사용자 조회 (user)                                      │
│  2. 메뉴 조회 + 상태 검증 (menu.status == ACTIVE)            │
│  3. 포인트 잔액 검증 (point.balance >= menu.price)           │
│  4. 포인트 차감 (point.balance -= price, version 증가)       │
│  5. 포인트 이력 저장 (point_history INSERT)                  │
│  6. 주문 생성 (orders INSERT, price 스냅샷 저장)             │
│  7. 트랜잭션 커밋                                            │
│                                                            │
└────────────────────────────────────────────────────────────┘
                         │
                         ▼ AFTER_COMMIT
              ┌──────────────────────┐
              │ OrderCompletedEvent   │
              │ → 데이터 수집 플랫폼   │
              │ 실패해도 주문 무관     │
              └──────────────────────┘
```

**보장 사항**

- 포인트 차감 + 이력 저장 + 주문 생성은 **원자적**으로 처리됩니다.
- 주문 저장 실패 시 포인트 차감도 롤백됩니다.
- 외부 전송은 **커밋 이후**에만 실행되므로 트랜잭션에 영향을 주지 않습니다.
- 롤백된 주문에 대해서는 이벤트가 **발행되지 않습니다**.

### 포인트 충전 트랜잭션

```
@Transactional ──────────────────────────────────────┐
│                                                    │
│  1. 사용자 조회 (user)                              │
│  2. 충전 금액 유효성 검증 (최소/최대/한도)             │
│  3. 포인트 잔액 증가 (point.balance += amount)       │
│  4. 포인트 이력 저장 (point_history INSERT)           │
│  5. 트랜잭션 커밋                                    │
│                                                    │
└────────────────────────────────────────────────────┘
```

---

## 8. 동시성 제어 전략

### 문제 상황

동일 사용자에 대한 동시 요청 시 Check-then-Act 레이스 컨디션이 발생할 수 있습니다.

```
[Thread A] 잔액 조회: 10,000P (version=1)
[Thread B] 잔액 조회: 10,000P (version=1)
[Thread A] 5,000P 차감 → UPDATE ... WHERE version=1 → 성공 (version=2)
[Thread B] 5,000P 차감 → UPDATE ... WHERE version=1 → 실패 (OptimisticLockException)
→ 정합성 보장됨
```

### 선택: 낙관적 락 + 자동 재시도 (Optimistic Lock + @Retryable)

```java
@Entity
public class Point {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private long balance;   // long: overflow 안전성, 금액류 통일

    @Version
    private int version;    // 동시 수정 감지
}
```

```java
@Transactional
@Retryable(
    retryFor = ObjectOptimisticLockingFailureException.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 50)
)
public PointResponse charge(Long userId, long amount) { ... }
```

**낙관적 락만으로는 부족한 이유**: version 충돌 시 `OptimisticLockException`이 발생하면 그냥 실패로 끝납니다. 사용자 입장에서는 "다시 시도해주세요"를 반복하게 되어 경험이 나빠집니다. `@Retryable`을 통해 50ms 간격으로 최대 3회 자동 재시도하면, 대부분의 충돌이 사용자 모르게 해결됩니다.

### 대안 비교 분석

| 전략 | 장점 | 단점 | 적합한 상황 |
|------|------|------|-------------|
| **낙관적 락 ✅** | 읽기 성능 우수, 커넥션 점유 없음, 데드락 없음 | 충돌 시 재시도 필요 | 충돌 빈도 낮은 환경 |
| 비관적 락 | 충돌 방지 보장 | 커넥션 점유, 데드락 위험, 처리량 저하 | 충돌 빈번 환경 |
| Redis 분산 락 | 멀티 인스턴스에서 강력한 제어 | 인프라 의존, 구현 복잡, 락 해제 실패 위험 | 분산 + 높은 충돌 빈도 |

**선택 근거**

1. 커피 주문 시스템에서 **동일 사용자**가 동시에 결제하는 빈도는 낮습니다.
2. DB 레벨에서 동작하므로 **멀티 인스턴스에서도 정합성을 보장**합니다. (DB = Single Source of Truth)
3. 비관적 락 대비 데드락 위험이 없고, 읽기 성능이 우수합니다.
4. 충돌 빈도가 높아질 경우, Redis 분산 락으로 전환 가능하도록 서비스 레이어를 추상화했습니다.

---

## 9. 멀티 인스턴스 대응

| 관심사 | 전략 | 이유 |
|--------|------|------|
| 상태 관리 | Stateless | 인스턴스 간 세션 공유 불필요 |
| 포인트 정합성 | DB 레벨 낙관적 락 | DB가 단일 진실의 원천 — 어떤 인스턴스에서 요청해도 동일 결과 |
| 인기 메뉴 캐시 | Redis (공유) | 인스턴스 간 동일 데이터 보장 |
| 메뉴 목록 캐시 | Caffeine (로컬) | 변경 빈도 낮음, Redis 네트워크 비용 절감 |
| 데이터 전송 | 인스턴스별 독립 비동기 | 각 인스턴스가 커밋 후 독립적으로 전송 |

> **왜 로컬 캐시가 아닌 Redis인가? (인기 메뉴)**  
> 인스턴스 A에서 새 주문이 발생해도, 인스턴스 B의 로컬 캐시에는 반영되지 않습니다. 인기 메뉴처럼 **인스턴스 간 일관된 결과**가 필요한 데이터는 Redis 공유 캐시가 필수입니다.

> **왜 Redis가 아닌 로컬 캐시인가? (메뉴 목록)**  
> 메뉴 목록은 변경 빈도가 매우 낮고 강한 정합성 요구가 없으므로, Caffeine 로컬 캐시를 사용하여 Redis 네트워크 비용을 절감합니다.

---

## 10. 캐싱 전략

### 캐시 분리

| 대상 | 저장소 | TTL | 패턴 | 무효화 |
|------|--------|-----|------|--------|
| 인기 메뉴 | Redis (ZSET) | 5분 | Cache-Aside + 분산 락 | TTL 만료 |
| 메뉴 목록 | Caffeine (Local) | 10분 | Cache-Aside | `@CacheEvict` |

### 인기 메뉴 — Redis ZSET 저장 구조

```
ZSET  popular:menus
  member = "1"     score = 150    (menuId=1, 주문 150건)
  member = "3"     score = 128    (menuId=3, 주문 128건)
  member = "2"     score = 95     (menuId=2, 주문 95건)
```

**ZSET에 menuId만 저장하는 이유**

- 메뉴명에 특수문자(`:` 등)가 포함되어도 안전합니다.
- 타입 안정성이 확보됩니다. (문자열 split 파싱 제거)
- 메뉴 상세 정보는 DB 또는 Caffeine 캐시에서 조회합니다.

### 캐시 갱신 플로우

```
[요청] → Redis ZSET 확인
         ├── HIT  → menuId 추출 → DB에서 메뉴 정보 조회 → 응답
         └── MISS → SETNX 락 획득 시도
                    ├── 락 성공 → Double-check → DB 집계 → temp key에 적재
                    │              → RENAME 원자적 교체 → 응답
                    └── 락 실패 → DB 직접 조회 → 응답 (캐시 갱신은 락 획득 스레드에 위임)
```

**갱신 정책**

- 캐시 미스 시 DB에서 조회 후 Redis에 저장합니다.
- TTL(5분) 만료 시 자동으로 재계산됩니다.
- **주문 발생 시 즉시 갱신하지 않습니다.**

> **왜 실시간 갱신하지 않는가?**  
> 인기 메뉴는 **실시간 정확도보다 조회 성능이 우선**입니다. 주문 건마다 캐시를 무효화하면 쓰기 시 Redis 부하가 급증하며, 인기 메뉴 특성상 5분 내 순위 변동 가능성은 매우 낮습니다. 최대 5분의 **Eventual Consistency**를 허용하는 것이 합리적인 트레이드오프입니다.

### 원자적 캐시 교체 (RENAME)

기존 방식의 문제: `DELETE → ADD` 사이에 다른 요청이 오면 빈 캐시를 조회합니다.

```
개선: 임시 key(popular:menus:temp)에 데이터를 완성한 후
      RENAME으로 원자적 교체 → 조회 중단 없음
```

### Cache Stampede 방어 (SETNX 분산 락)

TTL 만료 시 동시에 여러 인스턴스가 DB를 조회하는 것(stampede)을 방지합니다.

```
SETNX popular:menus:lock "1" EX 10
```

- 락을 획득한 하나의 스레드만 DB 조회 + 캐시 갱신을 수행합니다.
- 락을 못 잡은 스레드는 DB에서 직접 조회하여 응답합니다.
- Double-check 패턴으로 락 획득 후에도 캐시를 재확인합니다.

### 메뉴 목록 캐시 무효화 (@CacheEvict)

메뉴 생성/수정/삭제 시 `@CacheEvict(value = "menuList", allEntries = true)`로 Caffeine 캐시를 즉시 무효화합니다. 다음 조회 시 DB에서 재로딩됩니다.

---

## 11. 데이터 수집 플랫폼 연동

### 전송 데이터

```json
{
  "userId": 1,
  "menuId": 3,
  "price": 5500,
  "orderedAt": "2025-05-07T14:30:00"
}
```

### 이벤트 기반 비동기 처리

```
OrderService                              DataPlatformEventListener
    │                                              │
    ├── 주문 트랜잭션 처리                            │
    ├── ApplicationEventPublisher.publish()          │
    ├── 트랜잭션 커밋                                 │
    │                                              │
    └──────── AFTER_COMMIT ───────────────────────▶ │
                                                   ├── Mock API 전송 시도
                                                   ├── 성공 → 완료
                                                   └── 실패 → 로그 기록
```

**구현 방식**

- `ApplicationEventPublisher`를 통해 `OrderCompletedEvent` 발행
- `@TransactionalEventListener(phase = AFTER_COMMIT)` 기반 비동기 처리
- 전송 대상은 Mock 구현 (로그 출력)

**@TransactionalEventListener(AFTER_COMMIT)을 선택한 이유**

1. **커밋 성공 이후에만 이벤트가 실행됩니다.** 롤백된 주문의 이벤트 발행을 원천 차단합니다.
2. 트랜잭션과 후처리가 명확히 분리됩니다.
3. 이벤트 리스너 추가만으로 후처리 확장이 용이합니다. (OCP 준수)

> `@Async`만으로는 커밋 전에 비동기 스레드가 실행될 수 있어, 아직 커밋되지 않은 (또는 롤백될) 주문 데이터를 전송하는 문제가 발생할 수 있습니다.

---

## 12. 테스트 전략

### 단위 테스트

| 대상 | 검증 항목 |
|------|-----------|
| PointService | 충전 금액 유효성, 잔액 부족 예외, 한도 초과 예외 |
| OrderService | 비활성 메뉴 거부, 가격 스냅샷 정확성, 포인트 차감 정확성 |
| PopularMenuService | 7일 집계 정확성, 상위 3개 정렬, 동률 처리 |

### 통합 테스트

| 시나리오 | 검증 항목 |
|----------|-----------|
| 포인트 충전 | point.balance 증가 + point_history INSERT 확인 |
| 충전 → 주문 → 차감 | 트랜잭션 원자성, 잔액 정확성, 이벤트 발행 확인 |
| 주문 N건 → 인기 메뉴 | 집계 정확성, 정렬 검증 |

### 동시성 테스트

**목적**: 동일 사용자에 대한 동시 포인트 차감 시 정합성 보장 검증

```java
@Test
void 동시에_10건의_주문이_들어와도_포인트_정합성이_보장된다() throws Exception {
    // given: 10,000P 충전된 사용자, 1,000P짜리 메뉴
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    // when: 10개 스레드가 동시에 주문
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            try {
                orderService.order(userId, menuId);
                successCount.incrementAndGet();
            } catch (OptimisticLockException e) {
                failCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }
    latch.await();

    // then
    Point point = pointRepository.findByUserId(userId);

    // 1. 음수 잔액 미발생
    assertThat(point.getBalance()).isGreaterThanOrEqualTo(0);

    // 2. 잔액 = 초기값 - (성공 건수 × 메뉴 가격)
    assertThat(point.getBalance())
        .isEqualTo(10000 - (successCount.get() * 1000));

    // 3. point.balance == SUM(point_history) 정합성 유지
    int historySum = pointHistoryRepository.sumByUserId(userId);
    assertThat(point.getBalance()).isEqualTo(historySum);
}
```

**검증 항목**

| 항목 | 기대 결과 |
|------|-----------|
| 음수 잔액 | 미발생 |
| 중복 차감 | 미발생 |
| balance == SUM(history) | 항상 성립 |
| 성공 + 실패 == 요청 수 | 항상 성립 |

---

## 13. 확장 가능성

현재 과제 범위에는 포함하지 않았으나, 아래 확장을 고려한 구조로 설계했습니다.

| 확장 방향 | 현재 설계에서의 대응 | 도입 시 변경 사항 |
|-----------|---------------------|-------------------|
| **Kafka 이벤트 브로커** | EventPublisher로 추상화됨 | 리스너에 KafkaTemplate 주입으로 교체 (OrderService 수정 0건) |
| **Transactional Outbox** | AFTER_COMMIT + @Async (At-Most-Once) | EventOutbox 테이블 + 스케줄러로 At-Least-Once 보장 |
| **Redis 분산 락 (포인트)** | 서비스 레이어에서 락 전략 분리 | AOP 기반 분산 락 어노테이션 적용 |
| **인기 메뉴 스케줄러** | TTL + Cache-Aside + SETNX 락 | `@Scheduled(fixedRate=5min)` 사전 계산 → API는 Redis only |
| **주문 다건 처리** | 단건 주문 구조 | 주문-주문상세 테이블 분리 |
| **추천 시스템** | 인기 메뉴 집계 기반 | 별도 추천 서비스 분리 |
| **CQRS** | 단일 DB 구조 | 읽기 전용 레플리카 or Materialized View |

### 기술적 한계 및 의도적 트레이드오프

**이벤트 유실 가능성 (At-Most-Once)**

현재 데이터 수집 플랫폼 전송은 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`로 처리됩니다. 주문 트랜잭션이 커밋된 이후 비동기로 전송하므로, 전송 시점에 서버가 종료되거나 외부 시스템이 다운되면 해당 이벤트는 유실될 수 있습니다.

이는 과제 범위에서 **의도적으로 허용한 트레이드오프**입니다. 주문(핵심 비즈니스)의 안정성을 데이터 전송(부가 기능)보다 우선했으며, 전송 실패가 주문을 롤백시키지 않도록 장애 격리를 적용했습니다.

완벽한 메시지 전달 보장(At-Least-Once)이 필요한 경우, **Transactional Outbox Pattern**을 도입할 수 있습니다.

```
[현재] 주문 커밋 → AFTER_COMMIT → @Async 전송 (유실 가능)

[Outbox] 주문 커밋 + EventOutbox INSERT (같은 트랜잭션)
         → 스케줄러가 Outbox 테이블 폴링 → Kafka 전송 → 전송 완료 시 상태 업데이트
         → 유실 불가 (DB에 이미 저장됨)
```

---

## 14. 실행 방법

### 사전 요구사항

- Java 17+
- Docker & Docker Compose

### 실행

```bash
# 인프라 실행 (MySQL, Redis)
docker-compose up -d

# 애플리케이션 실행
./gradlew bootRun

# 테스트 실행
./gradlew test
```

### API 문서

```
http://localhost:8080/swagger-ui/index.html
```
