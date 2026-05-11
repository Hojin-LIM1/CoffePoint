package com.coffeepoint.domain.menu.service;

import com.coffeepoint.domain.menu.dto.MenuResponse;
import com.coffeepoint.domain.menu.dto.PopularMenuResponse;
import com.coffeepoint.domain.menu.entity.Menu;
import com.coffeepoint.domain.menu.entity.MenuStatus;
import com.coffeepoint.domain.menu.repository.MenuRepository;
import com.coffeepoint.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private static final String POPULAR_MENU_KEY = "popular:menus";
    private static final String POPULAR_MENU_TEMP_KEY = "popular:menus:temp";
    private static final String POPULAR_MENU_LOCK_KEY = "popular:menus:lock";
    private static final Duration POPULAR_MENU_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final int POPULAR_MENU_LIMIT = 3;

    private final MenuRepository menuRepository;
    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;  // Fix #6: StringRedisTemplate

    // ========================
    // 메뉴 목록 조회
    // ========================

    /**
     * 메뉴 목록 조회 — Caffeine 로컬 캐시 (TTL 10분)
     * 메뉴 변경 빈도가 낮으므로 로컬 캐시로 Redis 네트워크 비용 절감
     */
    @Cacheable(value = "menuList")
    public List<MenuResponse> getMenus() {
        return menuRepository.findAllByStatus(MenuStatus.ACTIVE)
                .stream()
                .map(MenuResponse::from)
                .toList();
    }

    /**
     * Fix #5: 메뉴 변경 시 Caffeine 캐시 무효화
     * 메뉴 생성/수정/삭제 시 호출해야 한다
     */
    @CacheEvict(value = "menuList", allEntries = true)
    public void evictMenuCache() {
        log.info("메뉴 목록 캐시 무효화");
    }

    // ========================
    // 인기 메뉴 조회
    // ========================

    /**
     * 인기 메뉴 조회 — Redis 공유 캐시 (TTL 5분, Cache-Aside)
     *
     * 설계 포인트:
     * - ZSET에 menuId만 저장 (Fix #1: 문자열 split 제거)
     * - RENAME으로 원자적 교체 (Fix #2: Race Condition 방어)
     * - SETNX 락으로 Cache Stampede 방어 (Fix #3)
     * - Redis 장애 시 DB fallback
     */
    public List<PopularMenuResponse> getPopularMenus() {
        // 1. Redis 캐시 확인
        List<PopularMenuResponse> cached = getPopularMenusFromCache();
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 2. Cache Miss → Cache Stampede 방어: 락 획득 시도
        //    락을 못 잡으면 바로 DB 조회 (다른 스레드가 갱신 중)
        boolean lockAcquired = tryAcquireLock();
        try {
            if (lockAcquired) {
                // 3. Double-check: 락 잡는 사이 다른 스레드가 이미 갱신했을 수 있음
                cached = getPopularMenusFromCache();
                if (cached != null && !cached.isEmpty()) {
                    return cached;
                }
            }

            // 4. DB 집계
            List<PopularMenuResponse> result = getPopularMenusFromDb();

            // 5. Redis에 원자적 저장 (락 획득한 스레드만)
            if (lockAcquired) {
                cachePopularMenus(result);
            }

            return result;
        } finally {
            if (lockAcquired) {
                releaseLock();
            }
        }
    }

    /**
     * Fix #1: ZSET에 menuId만 저장하고, 메뉴 정보는 DB에서 조회
     * - 메뉴명에 특수문자가 있어도 안전
     * - 타입 안정성 확보
     */
    private List<PopularMenuResponse> getPopularMenusFromCache() {
        try {
            ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    zSetOps.reverseRangeWithScores(POPULAR_MENU_KEY, 0, POPULAR_MENU_LIMIT - 1);

            if (tuples == null || tuples.isEmpty()) {
                return null;
            }

            // menuId 목록 추출
            List<Long> menuIds = new ArrayList<>();
            Map<Long, Long> orderCountMap = new LinkedHashMap<>();

            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Long menuId = Long.valueOf(tuple.getValue());
                long count = tuple.getScore().longValue();
                menuIds.add(menuId);
                orderCountMap.put(menuId, count);
            }

            // 메뉴 정보는 DB에서 일괄 조회 (N+1 방지)
            List<Menu> menus = menuRepository.findAllById(menuIds);
            Map<Long, Menu> menuMap = new HashMap<>();
            for (Menu menu : menus) {
                menuMap.put(menu.getId(), menu);
            }

            // 순위별 응답 조립 (ZSET 순서 유지)
            List<PopularMenuResponse> result = new ArrayList<>();
            int rank = 1;
            for (Long menuId : menuIds) {
                Menu menu = menuMap.get(menuId);
                if (menu == null) continue;

                result.add(PopularMenuResponse.builder()
                        .rank(rank++)
                        .id(menu.getId())
                        .name(menu.getName())
                        .price(menu.getPrice())
                        .orderCount(orderCountMap.get(menuId))
                        .build());
            }
            return result;

        } catch (Exception e) {
            log.warn("Redis 캐시 조회 실패, DB fallback", e);  // Fix #7: stack trace
            return null;
        }
    }

    private List<PopularMenuResponse> getPopularMenusFromDb() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        List<Object[]> results = orderRepository.findPopularMenus(
                sevenDaysAgo, PageRequest.of(0, POPULAR_MENU_LIMIT));

        List<PopularMenuResponse> popularMenus = new ArrayList<>();
        int rank = 1;
        for (Object[] row : results) {
            // DTO Projection: [menuId, menuName, menuPrice, orderCount]
            Long menuId = (Long) row[0];
            String menuName = (String) row[1];
            long menuPrice = (long) row[2];
            long orderCount = (long) row[3];

            popularMenus.add(PopularMenuResponse.builder()
                    .rank(rank++)
                    .id(menuId)
                    .name(menuName)
                    .price(menuPrice)
                    .orderCount(orderCount)
                    .build());
        }
        return popularMenus;
    }

    /**
     * Fix #1: ZSET에 menuId만 저장 (score = 주문 수)
     * Fix #2: 임시 key에 쓴 후 RENAME으로 원자적 교체
     *
     * 기존 문제: delete → add 사이에 다른 요청이 오면 캐시가 빈 상태
     * 개선: temp key에 완성 후 RENAME → 조회 중단 없음
     */
    private void cachePopularMenus(List<PopularMenuResponse> menus) {
        try {
            // 1. 임시 key에 데이터 적재
            redisTemplate.delete(POPULAR_MENU_TEMP_KEY);
            ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();

            for (PopularMenuResponse menu : menus) {
                zSetOps.add(POPULAR_MENU_TEMP_KEY, String.valueOf(menu.getId()), menu.getOrderCount());
            }
            redisTemplate.expire(POPULAR_MENU_TEMP_KEY, POPULAR_MENU_TTL);

            // 2. RENAME으로 원자적 교체 (기존 key를 즉시 대체)
            redisTemplate.rename(POPULAR_MENU_TEMP_KEY, POPULAR_MENU_KEY);

        } catch (Exception e) {
            // 원자적 교체 실패 시 임시 key 정리
            try {
                redisTemplate.delete(POPULAR_MENU_TEMP_KEY);
            } catch (Exception ignored) {}
            log.warn("Redis 캐시 저장 실패", e);  // Fix #7: stack trace
        }
    }

    // ========================
    // Cache Stampede 방어
    // ========================

    /**
     * Fix #3: SETNX 기반 간단 분산 락
     * 캐시 만료 시 동시에 여러 인스턴스가 DB를 조회하는 것(stampede)을 방지
     * 락을 획득한 하나의 스레드만 DB 조회 + 캐시 갱신을 수행
     */
    private boolean tryAcquireLock() {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(
                            POPULAR_MENU_LOCK_KEY, "1", LOCK_TTL.getSeconds(), TimeUnit.SECONDS));
        } catch (Exception e) {
            log.warn("Redis 락 획득 실패", e);
            return false;
        }
    }

    private void releaseLock() {
        try {
            redisTemplate.delete(POPULAR_MENU_LOCK_KEY);
        } catch (Exception e) {
            log.warn("Redis 락 해제 실패", e);
        }
    }
}
