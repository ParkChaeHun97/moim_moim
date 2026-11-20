package com.example.backend.dto;

import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Member;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class MeetingListResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("목록 응답 변환 시 직렬화 결과에 작성자 이메일 값이 포함되지 않는다")
    void from_doesNotExposeCreatorEmail() throws Exception {
        Member creator = mock(Member.class);
        given(creator.getEmail()).willReturn("creator@test.com");
        given(creator.getNickname()).willReturn("러너");
        MeetingPost post = mock(MeetingPost.class);
        given(post.getCreator()).willReturn(creator);

        String json = objectMapper.writeValueAsString(MeetingListResponse.from(post));

        assertThat(json).doesNotContain("creator@test.com");
        assertThat(json).contains("러너");
    }

    @Test
    @DisplayName("작성자가 null이어도 변환 중 예외가 발생하지 않고 탈퇴한 사용자로 표시한다")
    void from_whenCreatorNull_doesNotThrow() {
        MeetingPost post = mock(MeetingPost.class);
        given(post.getCreator()).willReturn(null);

        MeetingListResponse response = MeetingListResponse.from(post);

        assertThat(response.getCreatorNickname()).isEqualTo("탈퇴한 사용자");
    }
}