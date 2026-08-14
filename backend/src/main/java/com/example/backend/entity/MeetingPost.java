package com.example.backend.entity;

import com.example.backend.common.exception.CustomException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.dto.MeetingPostCreateRequest;
import com.example.backend.dto.MeetingPostUpdateRequest;
import com.example.backend.enums.ParticipationRole;
import com.example.backend.enums.ParticipationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class MeetingPost extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private int capacity; // maxParticipants 대신 capacity로 통일

    @Builder.Default
    @Column(nullable = false)
    private int currentParticipants = 1;

    @Column(nullable = false)
    private int viewCount = 0;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    private Member creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Builder.Default
    @OneToMany(mappedBy = "meetingPost", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<Participation> participations = new ArrayList<>();

    /**
     * 조회시 뷰 카운팅
     * */
    public void incrementViewCount() {
        this.viewCount++;
    }

    /**
     * 모임 생성
     * */
    public static MeetingPost createMeeting(MeetingPostCreateRequest request, Member creator, Category category) {

        // 1. 생성 전 파라미터 검증 (static 메서드 호출)
        validateMinimumCapacity(request.getCapacity());
        validateDateOrder(request.getStartDate(), request.getEndDate());

        MeetingPost post = MeetingPost.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .capacity(request.getCapacity())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .creator(creator)
                .category(category)
                .build();


        // 방장을 참여자로 자동 등록하는 규칙을 엔티티 내부에서 수행
        post.addParticipation(creator, ParticipationRole.ORGANIZER, ParticipationStatus.ACCEPTED, "모임 개설자 자동 등록");

        return post;
    }


    // 참여자 추가를 위한 편의 메서드
    public void addParticipation(Member member, ParticipationRole role, ParticipationStatus status, String reason) {
        Participation participation = Participation.builder()
                .member(member)
                .meetingPost(this)
                .role(role)
                .status(status)
                .joinReason(reason)
                .build();
        this.participations.add(participation);
    }

    @Builder // 생성자의 파라미터명이 빌더 메서드명이 됩니다.
    public MeetingPost(String title, String description, int capacity,
                       LocalDateTime startDate, LocalDateTime endDate,
                       Member creator, Category category) {
        this.title = title;
        this.description = description;
        this.capacity = capacity;
        this.startDate = startDate;
        this.endDate = endDate;
        this.creator = creator;
        this.category = category;
    }


    /**
     * 모임 정보 수정 (비즈니스 로직)
     * Dirty Checking에 의해 트랜잭션 종료 시 자동으로 DB에 반영됩니다.
     */
    public void update(MeetingPostUpdateRequest request, Category category) {

        // 1. 최소 인원(2명) 검증
        validateMinimumCapacity(request.getCapacity());
        // 2. 현재 참여자 수와 비교 검증
        validateNewCapacityWithCurrent(request.getCapacity());

        this.title = request.getTitle();
        this.description = request.getDescription();
        this.capacity = request.getCapacity();
        this.category = category;
        this.startDate = request.getStartDate();
        this.endDate = request.getEndDate();
    }


    // 참여 인원 증가 (신규 신청 시 사용)
    public void addParticipant() {
        // 1. 엣지 케이스 검증: 현재 인원이 정원(capacity)과 같거나 큰지 확인
        if (this.currentParticipants >= this.capacity) {
            throw new CustomException(ErrorCode.MEETING_FULL);
        }

        // 2. 상태 변경: 필드명 일관성 유지 (currentCount -> currentParticipants)
        this.currentParticipants++;
    }

    private static void validateDateOrder(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 빨라야 합니다.");
        }
    }

    // 참여 인원 검증 1 (생성시 참여인원이 2명보다 많은가)
    private static void validateMinimumCapacity(int capacity) {
        if (capacity < 2) {
            throw new CustomException(ErrorCode.MINIMUM_CAPACITY_REQUIRED);
        }
    }

    // 참여 인원 검증 2 (수정시 현재 인원 승인된 인원보다 적은가)
    private void validateNewCapacityWithCurrent(int newCapacity) {
        if (newCapacity < this.currentParticipants) {
            throw new CustomException(ErrorCode.INVALID_CAPACITY);
        }
    }

    // 수정, 삭제등 작성자 권한 검증
    public void validateCreator(MeetingPost post, Long memberId) {
        if (!post.getCreator().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.NOT_MEETING_CREATOR);
        }
    }
    // 호스트 여부 확인
    public boolean isHost(Long memberId) {
        if (memberId == null || this.creator == null) return false;
        return this.creator.getId().equals(memberId);
    }

    // 신청 버튼
    public boolean isFull() {
        return this.currentParticipants >= this.capacity;
    }



}
