package com.coffeepoint.domain.point.controller;

import com.coffeepoint.domain.point.entity.Point;
import com.coffeepoint.domain.point.repository.PointHistoryRepository;
import com.coffeepoint.domain.point.repository.PointRepository;
import com.coffeepoint.domain.user.entity.User;
import com.coffeepoint.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PointControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        pointHistoryRepository.deleteAll();
        pointRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder().name("테스트유저").build());
        userId = user.getId();
        pointRepository.save(Point.builder().userId(userId).build());
    }

    @Test
    @DisplayName("PATCH /api/points/{userId}/charge - 포인트를 충전한다")
    void charge() throws Exception {
        mockMvc.perform(patch("/api/points/{userId}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 10000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.balance").value(10000));

        // 이력 확인
        assertThat(pointHistoryRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).hasSize(1);
    }

    @Test
    @DisplayName("PATCH /api/points/{userId}/charge - 최소 금액 미만이면 400")
    void chargeMinimumFail() throws Exception {
        mockMvc.perform(patch("/api/points/{userId}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 500))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POINT_001"));
    }

    @Test
    @DisplayName("PATCH /api/points/{userId}/charge - 1회 최대 초과시 400")
    void chargeMaximumFail() throws Exception {
        mockMvc.perform(patch("/api/points/{userId}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 2000000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POINT_002"));
    }

    @Test
    @DisplayName("PATCH /api/points/{userId}/charge - 존재하지 않는 사용자면 404")
    void chargeUserNotFound() throws Exception {
        mockMvc.perform(patch("/api/points/{userId}/charge", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 10000))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POINT_004"));
    }

    @Test
    @DisplayName("GET /api/points/{userId} - 포인트 잔액을 조회한다")
    void getBalance() throws Exception {
        mockMvc.perform(get("/api/points/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    @DisplayName("충전 후 잔액 조회 시 잔액이 일치한다")
    void chargeAndGetBalance() throws Exception {
        // 충전
        mockMvc.perform(patch("/api/points/{userId}/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 50000))))
                .andExpect(status().isOk());

        // 조회
        mockMvc.perform(get("/api/points/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50000));

        // 정합성: balance == SUM(history)
        Point point = pointRepository.findByUserId(userId).orElseThrow();
        long historySum = pointHistoryRepository.calculateBalanceByUserId(userId);
        assertThat(point.getBalance()).isEqualTo(historySum);
    }
}
