-- ============================================================
-- ☕ v2 부하 테스트 후 정합성 검증 쿼리
--
-- 사용법:
--   mysql -u coffee -pcoffee1234 coffee_point < load-test/verify-v2.sql
-- ============================================================

SELECT '========== 1. 포인트 잔액 정합성 ==========' AS '';

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

SELECT IF(COUNT(*) = 0, '✅ 음수 잔액 없음', '❌ 음수 잔액 발생!') AS result
FROM point WHERE balance < 0;


SELECT '========== 3. 음수 재고 체크 ==========' AS '';

SELECT IF(COUNT(*) = 0, '✅ 음수 재고 없음', '❌ 음수 재고 발생!') AS result
FROM inventory WHERE quantity < 0;


SELECT '========== 4. 주문 수 vs 포인트 USE 이력 수 ==========' AS '';

SELECT
    (SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED') AS order_count,
    (SELECT COUNT(*) FROM point_history WHERE type = 'USE') AS use_history_count,
    (SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED') =
    (SELECT COUNT(*) FROM point_history WHERE type = 'USE') AS is_matched;


SELECT '========== 5. 주문 수 vs Outbox 이벤트 수 ==========' AS '';

SELECT
    (SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED') AS order_count,
    (SELECT COUNT(*) FROM event_outbox WHERE status IN ('PENDING', 'PROCESSING', 'SENT')) AS outbox_count,
    (SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED') =
    (SELECT COUNT(*) FROM event_outbox WHERE status IN ('PENDING', 'PROCESSING', 'SENT')) AS is_matched;


SELECT '========== 6. Outbox 상태 분포 ==========' AS '';

SELECT status, COUNT(*) AS cnt
FROM event_outbox
GROUP BY status;


SELECT '========== 7. 재고 잔량 (메뉴별) ==========' AS '';

SELECT
    m.id AS menu_id,
    m.name AS menu_name,
    COALESCE(SUM(i.quantity), 0) AS remaining_stock,
    (SELECT COUNT(*) FROM orders o WHERE o.menu_id = m.id AND o.status = 'COMPLETED') AS sold_count
FROM menu m
LEFT JOIN inventory i ON i.menu_id = m.id AND i.status = 'AVAILABLE'
    AND i.expiration_date > CURDATE()
GROUP BY m.id, m.name;


SELECT '========== 8. 인기 메뉴 TOP 3 (7일) ==========' AS '';

SELECT
    m.id, m.name, COUNT(o.id) AS order_count
FROM orders o
JOIN menu m ON o.menu_id = m.id
WHERE o.status = 'COMPLETED'
  AND o.created_at >= NOW() - INTERVAL 7 DAY
GROUP BY m.id, m.name
ORDER BY order_count DESC, m.id ASC
LIMIT 3;


SELECT '========== 9. 분석 데이터 (시간대별) ==========' AS '';

SELECT order_hour, SUM(order_count) AS total_orders, SUM(total_revenue) AS total_revenue
FROM order_analytics
GROUP BY order_hour
ORDER BY order_hour;


SELECT '========== 10. 통계 요약 ==========' AS '';

SELECT
    (SELECT COUNT(*) FROM orders) AS total_orders,
    (SELECT COUNT(*) FROM point_history WHERE type = 'CHARGE') AS total_charges,
    (SELECT COUNT(*) FROM point_history WHERE type = 'USE') AS total_uses,
    (SELECT SUM(balance) FROM point) AS total_balance,
    (SELECT COUNT(*) FROM event_outbox) AS total_outbox,
    (SELECT COUNT(*) FROM event_outbox WHERE status = 'DEAD') AS dead_letters,
    (SELECT COALESCE(SUM(quantity), 0) FROM inventory WHERE status = 'AVAILABLE') AS total_remaining_stock;
