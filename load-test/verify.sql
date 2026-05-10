-- ============================================================
-- ☕ 부하 테스트 후 정합성 검증 쿼리
-- 
-- 사용법:
--   mysql -u coffee -pcoffee1234 coffee_point < load-test/verify.sql
-- ============================================================

SELECT '========== 1. 포인트 잔액 정합성 ==========' AS '';

-- point.balance == SUM(point_history) 인지 확인
-- is_consistent가 1이면 정합성 유지
SELECT 
    p.user_id,
    p.balance AS current_balance,
    COALESCE(SUM(CASE 
        WHEN ph.type = 'CHARGE' THEN ph.amount 
        ELSE -ph.amount 
    END), 0) AS history_calculated,
    p.balance = COALESCE(SUM(CASE 
        WHEN ph.type = 'CHARGE' THEN ph.amount 
        ELSE -ph.amount 
    END), 0) AS is_consistent
FROM point p
LEFT JOIN point_history ph ON p.user_id = ph.user_id
GROUP BY p.user_id, p.balance;


SELECT '========== 2. 음수 잔액 체크 ==========' AS '';

-- 음수 잔액이 있으면 동시성 제어 실패
SELECT user_id, balance 
FROM point 
WHERE balance < 0;

SELECT IF(COUNT(*) = 0, '✅ 음수 잔액 없음', '❌ 음수 잔액 발생!') AS result
FROM point WHERE balance < 0;


SELECT '========== 3. 주문 건수 vs 포인트 사용 건수 ==========' AS '';

-- 주문 수와 포인트 USE 이력 수가 일치해야 함
SELECT 
    (SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED') AS order_count,
    (SELECT COUNT(*) FROM point_history WHERE type = 'USE') AS use_history_count,
    (SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED') = 
    (SELECT COUNT(*) FROM point_history WHERE type = 'USE') AS is_matched;


SELECT '========== 4. 주문 가격 스냅샷 검증 ==========' AS '';

-- 주문 가격과 메뉴 현재 가격 비교 (스냅샷 검증)
SELECT 
    o.id AS order_id,
    o.price AS order_price,
    m.price AS current_menu_price,
    o.price = m.price AS price_matches
FROM orders o 
JOIN menu m ON o.menu_id = m.id
LIMIT 10;


SELECT '========== 5. 통계 요약 ==========' AS '';

SELECT 
    (SELECT COUNT(*) FROM orders) AS total_orders,
    (SELECT COUNT(*) FROM point_history WHERE type = 'CHARGE') AS total_charges,
    (SELECT COUNT(*) FROM point_history WHERE type = 'USE') AS total_uses,
    (SELECT SUM(balance) FROM point) AS total_balance;


SELECT '========== 6. 인기 메뉴 TOP 3 (7일) ==========' AS '';

SELECT 
    m.id,
    m.name,
    COUNT(o.id) AS order_count
FROM orders o
JOIN menu m ON o.menu_id = m.id
WHERE o.status = 'COMPLETED'
  AND o.created_at >= NOW() - INTERVAL 7 DAY
GROUP BY m.id, m.name
ORDER BY order_count DESC, m.id ASC
LIMIT 3;
