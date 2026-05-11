import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// ============================================================
// ☕ v2 동시성 스트레스 테스트
//
// 목적: 재고(비관적 락) + 포인트(낙관적 락) 동시 동작에서 정합성 검증
// 실행: k6 run load-test/concurrency-test.js
// ============================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const success = new Counter('order_success');
const conflict = new Counter('order_conflict_409');
const insufficientBalance = new Counter('insufficient_balance');
const insufficientInventory = new Counter('insufficient_inventory');

export const options = {
  vus: 10,
  duration: '20s',
  thresholds: { 'http_req_duration': ['p(95)<2000'] },
};

export function setup() {
  console.log('💰 포인트 충전 + 재고 확인...');

  for (let i = 0; i < 10; i++) {
    http.patch(`${BASE_URL}/api/points/1/charge`,
      JSON.stringify({ amount: 1000000 }),
      { headers: { 'Content-Type': 'application/json' } });
  }

  const pointRes = http.get(`${BASE_URL}/api/points/1`);
  const balance = pointRes.json().balance;

  const invRes = http.get(`${BASE_URL}/api/inventory/1`);
  const stock = invRes.status === 200 ? invRes.json().availableQuantity : 'N/A';

  console.log(`✅ 잔액: ${balance}P / 메뉴1 재고: ${stock}개`);
  return { startBalance: balance, startStock: stock };
}

export default function () {
  const res = http.post(`${BASE_URL}/api/orders`,
    JSON.stringify({ userId: 1, menuId: 1 }),
    { headers: { 'Content-Type': 'application/json' } });

  if (res.status === 201) success.add(1);
  else if (res.status === 409) conflict.add(1);
  else if (res.status === 400) {
    try {
      const code = res.json().code;
      if (code === 'INVENTORY_001') insufficientInventory.add(1);
      else insufficientBalance.add(1);
    } catch (e) { insufficientBalance.add(1); }
  }

  check(res, { '정상 응답': (r) => [201, 400, 409].includes(r.status) });
  sleep(Math.random() * 0.5);
}

export function teardown(data) {
  const pointRes = http.get(`${BASE_URL}/api/points/1`);
  const endBalance = pointRes.json().balance;

  const invRes = http.get(`${BASE_URL}/api/inventory/1`);
  const endStock = invRes.status === 200 ? invRes.json().availableQuantity : 'N/A';

  console.log('');
  console.log('╔══════════════════════════════════════╗');
  console.log('║   v2 동시성 스트레스 테스트 결과       ║');
  console.log('╠══════════════════════════════════════╣');
  console.log(`║ 시작 잔액:  ${String(data.startBalance).padStart(12)}P  ║`);
  console.log(`║ 최종 잔액:  ${String(endBalance).padStart(12)}P  ║`);
  console.log(`║ 시작 재고:  ${String(data.startStock).padStart(12)}개  ║`);
  console.log(`║ 최종 재고:  ${String(endStock).padStart(12)}개  ║`);
  console.log(`║ 음수 잔액:  ${endBalance < 0 ? '    ❌ 발생!    ' : '   ✅ 없음      '}║`);
  console.log(`║ 음수 재고:  ${endStock < 0 ? '    ❌ 발생!    ' : '   ✅ 없음      '}║`);
  console.log('╚══════════════════════════════════════╝');
  console.log('');
  console.log('📌 DB 검증: load-test/verify-v2.sql');
}
