import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ============================================================
// ☕ 커피포인트 부하 테스트
//
// 실행 방법:
//   k6 run load-test/load-test.js
//   k6 run --vus 50 --duration 30s load-test/load-test.js
//   docker-compose --profile loadtest up
// ============================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 커스텀 메트릭
const orderSuccess = new Counter('order_success');
const orderConflict = new Counter('order_conflict_409');
const pointSuccess = new Counter('point_charge_success');
const pointConflict = new Counter('point_charge_conflict_409');
const orderDuration = new Trend('order_duration');
const failRate = new Rate('fail_rate');

// ============================================================
// 시나리오 설정
// ============================================================
export const options = {
  scenarios: {
    // 1단계: 메뉴 조회 (읽기 부하, 캐시 효과 확인)
    menu_read: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
      exec: 'menuReadScenario',
      startTime: '0s',
      tags: { scenario: 'menu_read' },
    },

    // 2단계: 포인트 충전 (동시성, 낙관적 락 + 재시도 검증)
    point_charge: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 30 },  // 30명까지 증가
        { duration: '20s', target: 30 },  // 30명 유지
        { duration: '5s', target: 0 },    // 종료
      ],
      exec: 'pointChargeScenario',
      startTime: '5s',
      tags: { scenario: 'point_charge' },
    },

    // 3단계: 주문/결제 (핵심 시나리오 — 동시성 + 잔액 정합성)
    order: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },  // 50명까지 증가
        { duration: '30s', target: 50 },  // 50명 유지 (피크)
        { duration: '10s', target: 0 },   // 종료
      ],
      exec: 'orderScenario',
      startTime: '10s',
      tags: { scenario: 'order' },
    },

    // 4단계: 인기 메뉴 조회 (Redis 캐시 부하, Cache Stampede 방어 검증)
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
    // 성공률 95% 이상
    'http_req_failed': ['rate<0.05'],
    // p95 응답 시간 500ms 이하
    'http_req_duration': ['p(95)<500'],
    // 주문 p95 응답 시간 1초 이하
    'order_duration': ['p(95)<1000'],
  },
};

// ============================================================
// Setup: 테스트 데이터 준비
// ============================================================
export function setup() {
  console.log('🚀 부하 테스트 시작 - 테스트 데이터 준비');

  // 사용자 1~2번은 DataInitializer에서 이미 생성됨
  // 부하 테스트용으로 사용자 1번에 대량 포인트 충전
  const chargeRes = http.patch(
    `${BASE_URL}/api/points/1/charge`,
    JSON.stringify({ amount: 1000000 }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  console.log(`초기 충전 결과: ${chargeRes.status}, balance: ${chargeRes.json().balance || 'N/A'}`);

  // 메뉴 목록 확인
  const menuRes = http.get(`${BASE_URL}/api/menus`);
  const menus = menuRes.json();
  console.log(`메뉴 수: ${menus.length}`);

  return {
    userId: 1,
    userId2: 2,
    menus: menus,
  };
}

// ============================================================
// 시나리오 1: 메뉴 목록 조회 (Caffeine 캐시 효과 측정)
// ============================================================
export function menuReadScenario() {
  group('메뉴 목록 조회', () => {
    const res = http.get(`${BASE_URL}/api/menus`);

    check(res, {
      '200 OK': (r) => r.status === 200,
      '메뉴 1개 이상': (r) => r.json().length > 0,
    });

    failRate.add(res.status !== 200);
  });

  sleep(0.1); // 100ms 간격
}

// ============================================================
// 시나리오 2: 포인트 충전 (낙관적 락 + @Retryable 검증)
//
// 검증 포인트:
// - 동일 사용자에 대한 동시 충전 시 409 발생 여부
// - @Retryable 덕분에 대부분 성공하는지 확인
// - 최종 잔액 정합성
// ============================================================
export function pointChargeScenario() {
  group('포인트 충전', () => {
    // 사용자 1번에게 동시 충전 → 낙관적 락 충돌 유발
    const res = http.patch(
      `${BASE_URL}/api/points/1/charge`,
      JSON.stringify({ amount: 1000 }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    check(res, {
      '충전 성공 (200)': (r) => r.status === 200,
    });

    if (res.status === 200) {
      pointSuccess.add(1);
    } else if (res.status === 409) {
      pointConflict.add(1);
    }

    failRate.add(res.status !== 200 && res.status !== 409);
  });

  sleep(Math.random() * 0.2); // 0~200ms 랜덤 간격
}

// ============================================================
// 시나리오 3: 주문/결제 (핵심 — 동시성 + 잔액 정합성)
//
// 검증 포인트:
// - 동시 주문 시 음수 잔액 미발생
// - 낙관적 락 충돌 후 재시도로 성공
// - 잔액 부족 시 400 정상 반환
// - point.balance == SUM(point_history) 유지
// ============================================================
export function orderScenario(data) {
  group('주문/결제', () => {
    // 랜덤 메뉴 선택 (1~5번)
    const menuId = Math.floor(Math.random() * 5) + 1;

    const start = Date.now();
    const res = http.post(
      `${BASE_URL}/api/orders`,
      JSON.stringify({ userId: 1, menuId: menuId }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    const duration = Date.now() - start;
    orderDuration.add(duration);

    check(res, {
      '주문 성공 (201)': (r) => r.status === 201,
    });

    if (res.status === 201) {
      orderSuccess.add(1);
    } else if (res.status === 409) {
      orderConflict.add(1);
    }

    // 400(잔액 부족), 409(락 충돌)는 정상 동작
    failRate.add(res.status !== 201 && res.status !== 400 && res.status !== 409);
  });

  sleep(Math.random() * 0.3); // 0~300ms 랜덤 간격
}

// ============================================================
// 시나리오 4: 인기 메뉴 조회 (Redis 캐시 + Stampede 방어)
//
// 검증 포인트:
// - 동시 30명이 조회해도 DB 풀스캔 안 일어남 (캐시 HIT)
// - TTL 만료 시 SETNX 락으로 1개 스레드만 DB 조회
// - 응답 시간이 일정하게 유지되는지
// ============================================================
export function popularMenuScenario() {
  group('인기 메뉴 조회', () => {
    const res = http.get(`${BASE_URL}/api/menus/popular`);

    check(res, {
      '200 OK': (r) => r.status === 200,
      '3개 이하': (r) => r.json().length <= 3,
    });

    failRate.add(res.status !== 200);
  });

  sleep(0.2); // 200ms 간격
}

// ============================================================
// Teardown: 정합성 최종 검증
// ============================================================
export function teardown(data) {
  console.log('\n========================================');
  console.log('🔍 부하 테스트 완료 — 정합성 검증');
  console.log('========================================');

  // 잔액 확인
  const pointRes = http.get(`${BASE_URL}/api/points/1`);
  if (pointRes.status === 200) {
    const balance = pointRes.json().balance;
    console.log(`✅ 사용자 1 최종 잔액: ${balance}P`);

    if (balance < 0) {
      console.log('❌ 음수 잔액 발생! 동시성 제어 실패');
    } else {
      console.log('✅ 음수 잔액 없음 — 동시성 제어 정상');
    }
  }

  console.log('\n📊 확인 사항:');
  console.log('  1. point.balance == SUM(point_history) 인지 DB에서 직접 확인');
  console.log('  2. orders 건수 == point_history(type=USE) 건수 일치 확인');
  console.log('  3. 409 응답이 과도하지 않은지 확인 (@Retryable 효과)');
  console.log('========================================\n');
}
