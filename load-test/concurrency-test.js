import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// ============================================================
// ☕ 동시성 스트레스 테스트 — 단일 사용자에 집중 부하
//
// 목적: 낙관적 락 + @Retryable이 극한 경쟁에서도 정합성을 지키는지 검증
// 이건 "실제 트래픽 시뮬레이션"이 아니라 "동시성 제어 스트레스 테스트"
//
// 실행: k6 run load-test/concurrency-test.js
// ============================================================

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const success = new Counter('order_success');
const conflict = new Counter('order_conflict_409');
const insufficientBalance = new Counter('insufficient_balance_400');
const serverError = new Counter('server_error_500');

export const options = {
  // 10명이 20초간 — 극단적이지 않지만 충분한 경쟁
  vus: 10,
  duration: '20s',

  thresholds: {
    'http_req_duration': ['p(95)<2000'],
  },
};

// 테스트 전: 충분한 포인트 확보 (잔액 부족으로 인한 실패 제거)
export function setup() {
  console.log('💰 포인트 대량 충전 중...');

  // 10회 × 100만P = 1000만P 충전
  for (let i = 0; i < 10; i++) {
    const res = http.patch(
      `${BASE_URL}/api/points/1/charge`,
      JSON.stringify({ amount: 1000000 }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status !== 200) {
      console.log(`충전 ${i+1}회 실패: ${res.status} ${res.body}`);
    }
  }

  const res = http.get(`${BASE_URL}/api/points/1`);
  const balance = res.json().balance;
  console.log(`✅ 초기 잔액: ${balance}P`);
  console.log(`🎯 10 VU × 20초 = 약 200~400건 주문 예상`);
  console.log(`   메뉴 최대 6,000P × 400건 = 240만P 필요 → ${balance}P 충분`);

  return { startBalance: balance };
}

// 메인: 동일 사용자에게 동시 주문 (1번 메뉴 고정 = 4500P)
export default function () {
  const res = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ userId: 1, menuId: 1 }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (res.status === 201) success.add(1);
  else if (res.status === 409) conflict.add(1);
  else if (res.status === 400) insufficientBalance.add(1);
  else if (res.status >= 500) serverError.add(1);

  check(res, {
    '정상 응답 (201/400/409)': (r) => [201, 400, 409].includes(r.status),
  });

  sleep(Math.random() * 0.5); // 0~500ms 랜덤 간격
}

// 테스트 후: 정합성 검증
export function teardown(data) {
  const res = http.get(`${BASE_URL}/api/points/1`);
  const endBalance = res.json().balance;

  console.log('');
  console.log('╔══════════════════════════════════════╗');
  console.log('║      동시성 스트레스 테스트 결과       ║');
  console.log('╠══════════════════════════════════════╣');
  console.log(`║ 시작 잔액:  ${String(data.startBalance).padStart(12)}P  ║`);
  console.log(`║ 최종 잔액:  ${String(endBalance).padStart(12)}P  ║`);
  console.log(`║ 차감 금액:  ${String(data.startBalance - endBalance).padStart(12)}P  ║`);
  console.log(`║ 음수 잔액:  ${endBalance < 0 ? '    ❌ 발생!    ' : '   ✅ 없음      '}║`);
  console.log('╚══════════════════════════════════════╝');
  console.log('');
  console.log('📌 DB 정합성 최종 확인:');
  console.log('  mysql -u coffee -pcoffee1234 coffee_point < load-test/verify.sql');
  console.log('');
}
