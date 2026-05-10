# 부하 테스트 가이드

## 테스트 종류

| 파일 | 목적 | VU | 사용자 분산 |
|------|------|-----|-------------|
| `load-test-v2.js` | **현실적 부하 테스트** (전체 API) | 50명 | ✅ 여러 사용자 |
| `concurrency-test.js` | **동시성 스트레스 테스트** (단일 유저) | 10명 | ❌ userId=1 집중 |
| `load-test.js` | 극한 스트레스 (참고용) | 130명 | ❌ userId=1 집중 |

> **v1 → v2 변경 이유**: v1은 50명 VU가 전부 userId=1에 몰려 단일 row에 80개 동시 요청이 발생. 실제 서비스에서는 일어나지 않는 극단적 상황으로, 낙관적 락 충돌률이 비현실적으로 높았음 (성공률 5%). v2는 사용자를 분산시켜 현실적인 부하 패턴을 재현.

## 사전 준비

```bash
# k6 설치 (택 1)

# macOS
brew install k6

# Windows (choco)
choco install k6

# Docker (설치 없이 실행)
# → docker-compose 방식 사용
```

## 실행 순서

```bash
# 1. 인프라 + 앱 실행
docker-compose up -d
./gradlew bootRun

# 2-A. 현실적 부하 테스트 (추천)
k6 run load-test/load-test-v2.js

# 2-B. 동시성 스트레스 테스트 (단일 유저 집중)
k6 run load-test/concurrency-test.js

# 2-C. Docker로 실행 (k6 미설치)
docker-compose --profile loadtest run k6

# 3. 부하 테스트 후 정합성 검증
mysql -u coffee -pcoffee1234 coffee_point < load-test/verify.sql
```

## 테스트 시나리오 상세

### load-test-v2.js (현실적 부하)

| 시나리오 | VU | 시간 | 사용자 | 검증 포인트 |
|----------|-----|------|--------|-------------|
| 메뉴 조회 | 20명 | 30초 | - | Caffeine 캐시 HIT율 |
| 포인트 충전 | 0→20명 | 35초 | 분산 | @Retryable 재시도 성공률 |
| 주문/결제 | 0→50명 | 50초 | 분산 | TPS, 응답 시간, 정합성 |
| 인기 메뉴 | 30명 | 30초 | - | Redis 캐시, Stampede 방어 |

### concurrency-test.js (동시성 집중)

10명 VU × 20초 → 동일 userId=1에 집중 → 낙관적 락 스트레스 테스트

## 성공 기준

| 테스트 | 항목 | 기준 |
|--------|------|------|
| **v2 (현실적)** | HTTP 실패율 | 10% 미만 |
| | p95 응답 시간 | 1초 이하 |
| | 주문 p95 | 1.5초 이하 |
| **동시성** | 음수 잔액 | **0건 (필수)** |
| | balance == SUM(history) | **항상 일치 (필수)** |
| | 주문 수 == USE 이력 수 | **항상 일치 (필수)** |

> 동시성 테스트에서 409 응답은 정상 동작입니다. @Retryable 3회 재시도 후에도 충돌이 해소되지 않은 경우이며, 중요한 건 **정합성이 깨지지 않는 것**입니다.

## 결과 확인

k6 실행 후 콘솔에 아래와 같은 결과가 표시됩니다:

```
✓ 주문 성공 (201)
✓ 정상 응답

checks.........................: 97.23% ✓ 4812  ✗ 137
http_req_duration..............: avg=45ms  p(95)=189ms
order_duration.................: avg=52ms  p(95)=234ms
order_success..................: 4200
order_conflict_409.............: 137       ← @Retryable 3회 후에도 실패한 건
```
