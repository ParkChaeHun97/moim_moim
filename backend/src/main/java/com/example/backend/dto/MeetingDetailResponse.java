package com.example.backend.dto;

import com.example.backend.entity.MeetingPost;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor // Builder와 함께 사용하기 위한 생성자
@NoArgsConstructor
public class MeetingDetailResponse {
    private Long id;
    private String title;
    private String description;
    private int capacity;
    private int currentParticipants;
    private int viewCount;
    private String categoryName;
    private Long categoryId; // 수정시 프론트 Select 박스 초기값용
    private String creatorNickname;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private LocalDateTime createdAt;
    @JsonProperty("isHost") // JSON 키값을 무조건 "isHost"로 고정
    private boolean isHost;

    public static MeetingDetailResponse from(MeetingPost post, boolean isHost) {
        return MeetingDetailResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .description(post.getDescription())
                .capacity(post.getCapacity())
                .currentParticipants(post.getCurrentParticipants())
                .viewCount(post.getViewCount())
                .categoryName(post.getCategory().getName())
                .categoryId(post.getCategory().getId())
                .creatorNickname(post.getCreator().getNickname())
                .startDate(post.getStartDate())
                .endDate(post.getEndDate())
                .createdAt(post.getCreatedAt())
                .isHost(isHost)
                .build();
    }
}
