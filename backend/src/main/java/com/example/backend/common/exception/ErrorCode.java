package com.example.backend.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Member
    MEMBER_NOT_FOUND(404, "MEMBER_001", "회원을 찾을 수 없습니다."),
    REGION_NOT_FOUND(404, "MEMBER_002", "존재하지 않는 지역입니다."),
    DUPLICATE_EMAIL(409, "MEMBER_003", "이미 사용 중인 이메일입니다."),

    // Auth
    INVALID_PASSWORD(401, "AUTH_001", "비밀번호가 일치하지 않습니다."),
    INVALID_TOKEN(401, "AUTH_002", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(401, "AUTH_003", "만료된 토큰입니다."),
    FORBIDDEN_ACCESS(403, "AUTH_004", "해당 리소스에 대한 접근 권한이 없습니다."),

    // Meeting
    MEETING_NOT_FOUND(404, "MEETING_001", "해당 모임을 찾을 수 없습니다."),
    CATEGORY_NOT_FOUND(404, "MEETING_002", "카테고리를 찾을 수 없습니다."),
    NOT_MEETING_CREATOR(403, "MEETING_003", "모임 수정/삭제 권한이 없습니다."),
    MINIMUM_CAPACITY_REQUIRED(400,"MEETING_004","모임 인원은 최소 2명 이상이어야 합니다."),
    INVALID_CAPACITY(400, "MEETING_005", "현재 참여 인원보다 적은 정원으로 수정할 수 없습니다."),

    // Participation
    PARTICIPATION_NOT_FOUND(404, "PART_001", "참여 신청 내역을 찾을 수 없습니다."),
    NOT_AUTHORIZED_PARTICIPATION(403, "PART_002", "참여 신청을 관리할 권한이 없습니다."),
    INVALID_PARTICIPATION_STATUS(400, "PART_003", "잘못된 참여 상태 변경 요청입니다."),
    ALREADY_PARTICIPATED(400, "PART_004", "이미 신청했거나 참여 중인 모임입니다."),
    MEETING_FULL(400, "PART_005", "모임 정원이 가득 찼습니다."),
    ALREADY_PROCESSED_PARTICIPATION(409, "PART_006", "이미 처리된 참여 신청입니다."),

    // SSE (Server-Sent Events)
    SSE_CONNECTION_ERROR(500, "SSE_001", "실시간 연결 중 오류가 발생했습니다."),
    SSE_SEND_ERROR(500, "SSE_002", "알림 전송에 실패했습니다."),
    SSE_REGISTRY_NOT_FOUND(404, "SSE_003", "등록된 SSE 연결을 찾을 수 없습니다."),

    // Notification
    NOTIFICATION_NOT_FOUND(404, "NOTI_001", "해당 알림을 찾을 수 없습니다."),
    NOT_AUTHORIZED_NOTIFICATION(403, "NOTI_002", "해당 알림에 대한 접근 권한이 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}