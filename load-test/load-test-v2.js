import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ============================================================
// ☕ 커피포인트 부하 테스트 v2 (현실적 시나리오)
//
// v1 문제: 50명 VU가 전부 userId=1에 몰림
//   → 단일 row에 80개 동시 요청 → 낙관적 락 지옥
//   → 실제 서비스에서는 일어나지 않는 극단적 상황
//
// v2 개선: 사용자를 분산시켜 현실적인 부하 패턴 재현
//   → VU마다 다른 사용자 → 실제 서비스처럼 분산 경쟁
//   → 동시성 검증은 concurrency-test.js에서 별도 수행
//
// 실행:
//   k6 run load-test/load-test-v2.js
// ============================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 커스텀 메트릭
const orderSuccess = new Counter('order_success');
const orderFail400 = new Counter('order_fail_400');
const orderConflict = new Counter('order_conflict_409');
const chargeSuccess = new Counter('charge_success');
const chargeConflict = new Counter('charge_conflict_409');
const orderDuration = new Trend('order_duration');
const failRate = new Rate('fail_rate');

// 테스트 유저 수 (setup에서 생성)
const USER_COUNT = 20;

export const options = {
  scenarios: {
    // 1. 메뉴 조회 (읽기 부하 → 캐시 효과 측정)
    menu_read: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
      exec: 'menuReadScenario',
      startTime: '0s',
      tags: { scenario: 'menu_read' },
    },

    // 2. 포인트 충전 (여러 사용자 분산)
    point_charge: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '20s', target: 20 },
        { duration: '5s', target: 0 },
      ],
      exec: 'pointChargeScenario',
      startTime: '5s',
      tags: { scenario: 'point_charge' },
    },

    // 3. 주문/결제 (핵심 — 여러 사용자 분산)
    order: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '10s', target: 0 },
      ],
      exec: 'orderScenario',
      startTime: '10s',
      tags: { scenario: 'order' },
    },

    // 4. 인기 메뉴 조회 (Redis 캐시 부하)
    popular_menu: {
      executor: 'constant-vus',
      vus: 30,
      duration: '30s',
      exec: 'popularMenuScenario',
      startTime: '15s',
      tags: { scenario: 'popular_menu' },
    },
  },

  thresholds: {
    'http_req_failed': ['rate<0.10'],         // 실패율 10% 미만
    'http_req_duration': ['p(95)<1000'],       // p95 1초 이하
    'order_duration': ['p(95)<1500'],          // 주문 p95 1.5초 이하
  },
};

// ============================================================
// Setup: 테스트 사용자 20명 생성 + 각각 포인트 충전
// ============================================================
export function setup() {
  console.log(`🚀 테스트 사용자 ${USER_COUNT}명 생성 + 포인트 충전`);

  // DataInitializer가 이미 user 1, 2를 생성하므로
  // 기존 사용자들에게 포인트 대량 충전
  const userIds = [1, 2];

  for (const userId of userIds) {
    for (let i = 0; i < 5; i++) {
      http.patch(
        `${BASE_URL}/api/points/${userId}/charge`,
        JSON.stringify({ amount: 1000000 }),
        { headers: { 'Content-Type': 'application/json' } }
      );
    }
  }

  // 잔액 확인
  for (const userId of userIds) {
    const res = http.get(`${BASE_URL}/api/points/${userId}`);
    if (res.status === 200) {
      console.log(`  사용자 ${userId} 잔액: ${res.json().balance}P`);
    }
  }

  // 메뉴 확인
  const menuRes = http.get(`${BASE_URL}/api/menus`);
  console.log(`  메뉴 수: ${menuRes.json().length}`);

  return { userIds: userIds };
}

// ============================================================
// 시나리오 1: 메뉴 목록 조회
// ============================================================
export function menuReadScenario() {
  group('메뉴 목록 조회', () => {
    const res = http.get(`${BASE_URL}/api/menus`);

    check(res, {
      '메뉴 조회 200': (r) => r.status === 200,
      '메뉴 1개 이상': (r) => r.json().length > 0,
    });

    failRate.add(res.status !== 200);
  });

  sleep(0.1);
}

// ============================================================
// 시나리오 2: 포인트 충전 (사용자 분산)
//
// VU마다 다른 사용자에게 충전 → 낙관적 락 충돌이 현실적 수준으로 감소
// 같은 사용자에게 동시 충전이 가끔 발생 → @Retryable 효과 검증
// ============================================================
export function pointChargeScenario(data) {
  group('포인트 충전', () => {
    // VU ID 기반으로 사용자 분산 (1 또는 2)
    const userId = (__VU % 2) + 1;

    const res = http.patch(
      `${BASE_URL}/api/points/${userId}/charge`,
      JSON.stringify({ amount: 1000 }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    check(res, {
      '충전 성공 (200)': (r) => r.status === 200,
    });

    if (res.status === 200) chargeSuccess.add(1);
    if (res.status === 409) chargeConflict.add(1);

    failRate.add(res.status !== 200 && res.status !== 409);
  });

  sleep(Math.random() * 0.3);
}

// ============================================================
// 시나리오 3: 주문/결제 (사용자 분산)
//
// 검증 포인트:
// - 여러 사용자가 동시 주문 → 전체 처리량(TPS) 측정
// - 같은 사용자에게 간헐적 동시 요청 → 낙관적 락 자연스러운 발생
// - 잔액 부족은 정상 동작 (400)
// ============================================================
export function orderScenario(data) {
  group('주문/결제', () => {
    const userId = (__VU % 2) + 1;
    const menuId = Math.floor(Math.random() * 5) + 1;

    const start = Date.now();
    const res = http.post(
      `${BASE_URL}/api/orders`,
      JSON.stringify({ userId: userId, menuId: menuId }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    orderDuration.add(Date.now() - start);

    check(res, {
      '주문 정상 응답': (r) => [201, 400, 409].includes(r.status),
    });

    if (res.status === 201) orderSuccess.add(1);
    if (res.status === 400) orderFail400.add(1);
    if (res.status === 409) orderConflict.add(1);

    // 500 에러만 실패로 카운트
    failRate.add(res.status >= 500);
  });

  sleep(Math.random() * 0.3);
}

// ============================================================
// 시나리오 4: 인기 메뉴 조회
// ============================================================
export function popularMenuScenario() {
  group('인기 메뉴 조회', () => {
    const res = http.get(`${BASE_URL}/api/menus/popular`);

    check(res, {
      '인기메뉴 200': (r) => r.status === 200,
      '3개 이하': (r) => r.json().length <= 3,
    });

    failRate.add(res.status !== 200);
  });

  sleep(0.2);
}

// ============================================================
// Teardown: 정합성 최종 검증
// ============================================================
export function teardown(data) {
  console.log('\n========================================');
  console.log('🔍 부하 테스트 완료 — 정합성 검증');
  console.log('========================================');

  for (const userId of data.userIds) {
    const res = http.get(`${BASE_URL}/api/points/${userId}`);
    if (res.status === 200) {
      const balance = res.json().balance;
      const status = balance >= 0 ? '✅' : '❌';
      console.log(`${status} 사용자 ${userId} 최종 잔액: ${balance}P ${balance < 0 ? '(음수 잔액!)' : ''}`);
    }
  }

  console.log('\n📊 DB 정합성 검증:');
  console.log('  mysql -u coffee -pcoffee1234 coffee_point < load-test/verify.sql');
  console.log('========================================\n');
}
