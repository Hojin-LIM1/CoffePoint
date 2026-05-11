import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ============================================================
// ☕ 커피포인트 v2 부하 테스트
//
// v2 추가 검증:
//   - 재고 차감 동시성 (비관적 락)
//   - Outbox 이벤트 정합성
//   - 재고 소진 시 주문 거부
//
// 실행: k6 run load-test/load-test-v2.js
// ============================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const orderSuccess = new Counter('order_success');
const orderFail400 = new Counter('order_fail_400');
const orderConflict = new Counter('order_conflict_409');
const chargeSuccess = new Counter('charge_success');
const inventoryInsufficient = new Counter('inventory_insufficient');
const orderDuration = new Trend('order_duration');
const failRate = new Rate('fail_rate');

export const options = {
  scenarios: {
    menu_read: {
      executor: 'constant-vus',
      vus: 20, duration: '30s',
      exec: 'menuReadScenario', startTime: '0s',
    },
    point_charge: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '20s', target: 20 },
        { duration: '5s', target: 0 },
      ],
      exec: 'pointChargeScenario', startTime: '5s',
    },
    order: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '10s', target: 0 },
      ],
      exec: 'orderScenario', startTime: '10s',
    },
    popular_menu: {
      executor: 'constant-vus',
      vus: 30, duration: '30s',
      exec: 'popularMenuScenario', startTime: '15s',
    },
    inventory_check: {
      executor: 'constant-vus',
      vus: 5, duration: '50s',
      exec: 'inventoryCheckScenario', startTime: '10s',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.10'],
    'http_req_duration': ['p(95)<1000'],
    'order_duration': ['p(95)<1500'],
  },
};

export function setup() {
  console.log('🚀 v2 부하 테스트 시작');

  for (const userId of [1, 2]) {
    for (let i = 0; i < 5; i++) {
      http.patch(`${BASE_URL}/api/points/${userId}/charge`,
        JSON.stringify({ amount: 1000000 }),
        { headers: { 'Content-Type': 'application/json' } });
    }
    const res = http.get(`${BASE_URL}/api/points/${userId}`);
    if (res.status === 200) console.log(`  사용자 ${userId} 잔액: ${res.json().balance}P`);
  }

  const menus = http.get(`${BASE_URL}/api/menus`).json();
  console.log(`  메뉴 수: ${menus.length}`);
  for (const m of menus) {
    const inv = http.get(`${BASE_URL}/api/inventory/${m.id}`);
    if (inv.status === 200) console.log(`  메뉴 ${m.id}(${m.name}) 재고: ${inv.json().availableQuantity}개`);
  }

  return { userIds: [1, 2], menuIds: menus.map(m => m.id) };
}

export function menuReadScenario() {
  const res = http.get(`${BASE_URL}/api/menus`);
  check(res, { '메뉴 200': (r) => r.status === 200 });
  failRate.add(res.status !== 200);
  sleep(0.1);
}

export function pointChargeScenario() {
  const userId = (__VU % 2) + 1;
  const res = http.patch(`${BASE_URL}/api/points/${userId}/charge`,
    JSON.stringify({ amount: 1000 }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status === 200) chargeSuccess.add(1);
  failRate.add(res.status >= 500);
  sleep(Math.random() * 0.3);
}

export function orderScenario() {
  const userId = (__VU % 2) + 1;
  const menuId = Math.floor(Math.random() * 5) + 1;

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/orders`,
    JSON.stringify({ userId, menuId }),
    { headers: { 'Content-Type': 'application/json' } });
  orderDuration.add(Date.now() - start);

  check(res, { '주문 정상 응답': (r) => [201, 400, 409].includes(r.status) });

  if (res.status === 201) orderSuccess.add(1);
  if (res.status === 400) {
    orderFail400.add(1);
    try { if (res.json().code === 'INVENTORY_001') inventoryInsufficient.add(1); } catch (e) {}
  }
  if (res.status === 409) orderConflict.add(1);
  failRate.add(res.status >= 500);
  sleep(Math.random() * 0.3);
}

export function popularMenuScenario() {
  const res = http.get(`${BASE_URL}/api/menus/popular`);
  check(res, { '인기메뉴 200': (r) => r.status === 200 });
  failRate.add(res.status !== 200);
  sleep(0.2);
}

export function inventoryCheckScenario() {
  for (let id = 1; id <= 5; id++) http.get(`${BASE_URL}/api/inventory/${id}`);
  sleep(2);
}

export function teardown(data) {
  console.log('\n╔══════════════════════════════════════════╗');
  console.log('║     v2 부하 테스트 완료 — 정합성 검증      ║');
  console.log('╠══════════════════════════════════════════╣');

  for (const userId of data.userIds) {
    const res = http.get(`${BASE_URL}/api/points/${userId}`);
    if (res.status === 200) {
      const b = res.json().balance;
      console.log(`║ ${b >= 0 ? '✅' : '❌'} 사용자 ${userId} 잔액: ${String(b).padStart(10)}P     ║`);
    }
  }

  console.log('╠──────────────────────────────────────────╣');
  for (const menuId of data.menuIds) {
    const res = http.get(`${BASE_URL}/api/inventory/${menuId}`);
    if (res.status === 200) {
      console.log(`║ 📦 메뉴 ${menuId} 잔여 재고: ${String(res.json().availableQuantity).padStart(5)}개          ║`);
    }
  }

  console.log('╠──────────────────────────────────────────╣');
  console.log('║ 📊 DB 검증: load-test/verify-v2.sql      ║');
  console.log('╚══════════════════════════════════════════╝');
}
