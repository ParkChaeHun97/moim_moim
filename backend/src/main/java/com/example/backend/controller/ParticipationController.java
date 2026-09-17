package com.example.backend.controller;

import com.example.backend.dto.ParticipationRequestDto;
import com.example.backend.service.ParticipationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/participation")
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;

    @PostMapping("/apply")
    public ResponseEntity<Long> apply(@Valid @RequestBody ParticipationRequestDto requestDto,
                                      Authentication authentication) {
        // JWT 필터에서 저장한 principal 정보 추출
        Long memberId = Long.valueOf(authentication.getName());
        // 식별자(Email 등)를 서비스로 넘깁니다.
        Long participationId = participationService.applyForMeeting(requestDto, memberId);

        return ResponseEntity.status(HttpStatus.CREATED).body(participationId);
    }

    /**
     * 2. 참여 신청 승인/거절 처리 (추가)
     * @param participationId 변경할 신청 고유 ID
     * @param status "ACCEPTED" 또는 "REJECTED"
     */
    @PatchMapping("/{participationId}/status")
    public ResponseEntity<Long> updateStatus(
            @PathVariable("participationId") Long participationId,
            @RequestParam("status") String status,
            Authentication authentication
    ) {
        // 현재 로그인한 사용자가 방장인지 서비스에서 검증해야 함
        Long hostId = Long.valueOf(authentication.getName());
        Long updatedId = participationService.updateParticipationStatus(participationId, status, hostId);
        return ResponseEntity.status(HttpStatus.OK).body(updatedId);
    }

    @DeleteMapping("/{participationId}")
    public ResponseEntity<Void> cancelParticipation(
            @PathVariable Long participationId,
            @AuthenticationPrincipal Long memberId) {
        participationService.cancelParticipation(participationId, memberId);
        return ResponseEntity.noContent().build();
    }
}
