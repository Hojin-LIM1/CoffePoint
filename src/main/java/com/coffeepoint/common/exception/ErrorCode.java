package com.coffeepoint.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_001", "잘못된 요청입니다"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_002", "리소스를 찾을 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_003", "서버 내부 오류입니다"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_004", "현재 서비스를 이용할 수 없습니다"),

    // 포인트
    POINT_MINIMUM_AMOUNT(HttpStatus.BAD_REQUEST, "POINT_001", "충전 금액은 1,000원 이상이어야 합니다"),
    POINT_MAXIMUM_AMOUNT(HttpStatus.BAD_REQUEST, "POINT_002", "1회 최대 충전 금액을 초과하였습니다"),
    POINT_BALANCE_LIMIT(HttpStatus.BAD_REQUEST, "POINT_003", "포인트 보유 한도를 초과합니다"),
    POINT_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "POINT_004", "사용자를 찾을 수 없습니다"),
    POINT_CONFLICT(HttpStatus.CONFLICT, "POINT_005", "포인트 처리 중 충돌이 발생했습니다. 다시 시도해주세요"),

    // 주문
    ORDER_INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "ORDER_001", "잔액이 부족합니다"),
    ORDER_MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_002", "해당 메뉴를 찾을 수 없습니다"),
    ORDER_MENU_INACTIVE(HttpStatus.CONFLICT, "ORDER_003", "현재 주문할 수 없는 메뉴입니다"),
    ORDER_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_004", "사용자를 찾을 수 없습니다"),
    ORDER_CONFLICT(HttpStatus.CONFLICT, "ORDER_005", "결제 처리 중 충돌이 발생했습니다. 다시 시도해주세요"),

    // 메뉴
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU_001", "메뉴를 찾을 수 없습니다");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
