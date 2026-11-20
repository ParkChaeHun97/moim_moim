package com.example.backend.dto;

import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Member;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class MeetingListResponse {
    private Long id;
    private String title;
    private String categoryName;
    private String creatorNickname;
    private int capacity;
    private int currentParticipants;
    private int viewCount;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime startDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime endDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime createdAt;
    private boolean isClosed; // 모집 종료 여부

    // Entity -> DTO 변환 메서드
    public static MeetingListResponse from(MeetingPost post) {
        Member creator = post.getCreator();
        String creatorNickname = (creator != null && creator.getNickname() != null)
                ? creator.getNickname() : "탈퇴한 사용자";

        return MeetingListResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                // Null 발생 가능성이 있는 연관관계는 안전하게 처리
                .categoryName(post.getCategory() != null ? post.getCategory().getName() : "미지정")
                .creatorNickname(creatorNickname)
                .capacity(post.getCapacity())
                .currentParticipants(post.getCurrentParticipants())
                .viewCount(post.getViewCount())
                .startDate(post.getStartDate())
                .endDate(post.getEndDate())
                .createdAt(post.getCreatedAt())
                .isClosed(post.getCurrentParticipants() >= post.getCapacity())
                .build();
    }
}
