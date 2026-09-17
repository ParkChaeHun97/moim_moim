package com.example.backend.dto;

import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Participation;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MeetingSummaryResponse {
    private Long id;
    private Long participationId; // 추가 — applied 조회 시에만 값이 채워짐
    private String title;
    private String categoryName;
    private LocalDateTime startDate;
    private int capacity;
    private int currentParticipants;

    @JsonProperty("isHost")
    private boolean isHost;

    private String status;

    public static MeetingSummaryResponse from(MeetingPost post, boolean isHost) {
        return MeetingSummaryResponse.builder()
                .id(post.getId())
                .participationId(null) // 방장 조회 시엔 참여 정보 없음
                .title(post.getTitle())
                .categoryName(post.getCategory().getName())
                .startDate(post.getStartDate())
                .capacity(post.getCapacity())
                .currentParticipants(post.getCurrentParticipants())
                .isHost(isHost)
                .status(null)
                .build();
    }

    public static MeetingSummaryResponse from(Participation participation) {
        MeetingPost post = participation.getMeetingPost();
        return MeetingSummaryResponse.builder()
                .id(post.getId())
                .participationId(participation.getId()) // 취소 API 호출용
                .title(post.getTitle())
                .categoryName(post.getCategory().getName())
                .startDate(post.getStartDate())
                .capacity(post.getCapacity())
                .currentParticipants(post.getCurrentParticipants())
                .isHost(false)
                .status(participation.getStatus().name())
                .build();
    }
}