package com.example.backend.service;

import com.example.backend.common.exception.CustomException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.dto.ParticipationRequestDto;
import com.example.backend.dto.ParticipationResponse;
import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Member;
import com.example.backend.entity.Participation;
import com.example.backend.enums.ParticipationRole;
import com.example.backend.enums.ParticipationStatus;
import com.example.backend.repository.MeetingPostRepository;
import com.example.backend.repository.MemberRepository;
import com.example.backend.repository.ParticipationRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParticipationService {

    private final ParticipationRepository participationRepository;
    private final MeetingPostRepository meetingPostRepository; // 게시글 조회용
    private final MemberRepository memberRepository;
    private final NotificationService notificationService;

    @Transactional
    public Long applyForMeeting(ParticipationRequestDto requestDto, Long memberId) {
        // 1. 엔티티 존재 여부 확인
        MeetingPost meetingPost = meetingPostRepository.findById(requestDto.getMeetingPostId())
                .orElseThrow(() -> new CustomException(ErrorCode.MEETING_NOT_FOUND));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 2. 검증 로직
        validateApplication(meetingPost, memberId);

        // 3. 참여 정보 생성 및 저장
        Participation participation = Participation.createApplication(member, meetingPost, requestDto.getJoinReason());
        participationRepository.save(participation);

        // 3. 🔔 모임장(Host)에게 알림 생성
        notificationService.createNotification(
                meetingPost.getCreator(), // 알림 수신자: 모임 만든 사람
                "[" + meetingPost.getTitle() + "] 모임에 새로운 참여 신청이 도착했습니다! 📩",
                "/mypage?tab=hosted" // 모임장이 확인해야 할 페이지
        );


        return participation.getId();
    }

    private void validateApplication(MeetingPost meetingPost, Long memberId) {
        if (meetingPost.getCreator().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.FORBIDDEN_ACCESS); // 주최자 신청 불가
        }

        if (participationRepository.existsByMemberIdAndMeetingPostId(memberId, meetingPost.getId())) {
            throw new CustomException(ErrorCode.ALREADY_PARTICIPATED);
        }

        long acceptedCount = participationRepository.countByMeetingPostAndStatus(meetingPost, ParticipationStatus.ACCEPTED);
        if (acceptedCount >= meetingPost.getCapacity()) {
            throw new CustomException(ErrorCode.MEETING_FULL);
        }
    }

    /**
     * 3. 특정 모임의 신청자 명단 조회 (방장 권한 확인 필수)
     */
    @Transactional(readOnly = true)
    public List<ParticipationResponse> getParticipants(Long postId, Long hostId) {
        MeetingPost post = meetingPostRepository.findById(postId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEETING_NOT_FOUND));

        // 💡 방장 권한 체크
        if (!post.getCreator().getId().equals(hostId)) {
            throw new CustomException(ErrorCode.NOT_AUTHORIZED_PARTICIPATION);
        }

        return participationRepository.findAllByMeetingPostId(postId).stream()
                .map(ParticipationResponse::from)
                .collect(Collectors.toList());
    }



    /**
     * 4. 참여 신청 승인/거절 처리
     */
    @Transactional
    public Long updateParticipationStatus(Long participationId, String statusStr, Long hostId) {
        ParticipationStatus newStatus;
        try {
            newStatus = ParticipationStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 💡 잘못된 상태 값 (예: ACCEPTED인데 ACSEPTED로 보낸 경우 등)
            throw new CustomException(ErrorCode.INVALID_PARTICIPATION_STATUS);
        }

        // 1. 락 없이 meetingPostId만 조회 (어떤 MeetingPost를 잠글지 알아야 하므로)
        Long meetingPostId = participationRepository.findMeetingPostIdById(participationId)
                .orElseThrow(() -> new CustomException(ErrorCode.PARTICIPATION_NOT_FOUND));

        // 2. MeetingPost 먼저 잠금 (정원 race condition 방지, 데드락 회피를 위한 락 순서)
        MeetingPost post = meetingPostRepository.findByIdForUpdate(meetingPostId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEETING_NOT_FOUND));

        // 💡 권한 체크: Participation 락을 걸기 전에 fail fast
        if (!post.getCreator().getId().equals(hostId)) {
            throw new CustomException(ErrorCode.NOT_AUTHORIZED_PARTICIPATION);
        }

        // 3. Participation은 권한 확인 후 잠금 (동일 신청 중복 처리 방지)
        Participation participation = participationRepository.findByIdForUpdate(participationId)
                .orElseThrow(() -> new CustomException(ErrorCode.PARTICIPATION_NOT_FOUND));

        // 4. 멱등성 가드: 락 대기 중 다른 요청이 이미 처리했을 수 있음
        if (participation.getStatus() != ParticipationStatus.APPLIED
                && participation.getStatus() != ParticipationStatus.WAITING) {
            throw new CustomException(ErrorCode.ALREADY_PROCESSED_PARTICIPATION);
        }

        participation.updateStatus(newStatus);

        if (newStatus == ParticipationStatus.ACCEPTED) {
            post.addParticipant();
            // 알림 생성 호출
            notificationService.createNotification(
                    participation.getMember(), // 신청자
                    "[" + post.getTitle() + "] 모임 참여가 승인되었습니다! 🎉",
                    "/mypage?tab=applied"
            );
        } else if (newStatus == ParticipationStatus.REJECTED) {
            // 알림 생성 호출 (승인 알림과 동일한 패턴)
            notificationService.createNotification(
                    participation.getMember(), // 신청자
                    "[" + post.getTitle() + "] 모임 참여 신청이 거절되었습니다 😢",
                    "/mypage?tab=applied"
            );
        }

        return participation.getId();
    }
}
