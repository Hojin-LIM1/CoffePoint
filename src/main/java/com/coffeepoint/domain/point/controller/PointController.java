package com.coffeepoint.domain.point.controller;

import com.coffeepoint.domain.point.dto.PointChargeRequest;
import com.coffeepoint.domain.point.dto.PointResponse;
import com.coffeepoint.domain.point.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Point", description = "포인트 API")
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @Operation(summary = "포인트 충전", description = "사용자의 포인트를 충전합니다")
    @PatchMapping("/{userId}/charge")
    public ResponseEntity<PointResponse> charge(
            @PathVariable Long userId,
            @RequestBody @Valid PointChargeRequest request) {
        return ResponseEntity.ok(pointService.charge(userId, request.getAmount()));
    }

    @Operation(summary = "포인트 잔액 조회", description = "사용자의 포인트 잔액을 조회합니다")
    @GetMapping("/{userId}")
    public ResponseEntity<PointResponse> getBalance(@PathVariable Long userId) {
        return ResponseEntity.ok(pointService.getBalance(userId));
    }
}
