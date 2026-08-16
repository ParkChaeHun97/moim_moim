package com.example.backend.service;


import com.example.backend.dto.*;
import com.example.backend.entity.*;
import com.example.backend.enums.ParticipationRole;
import com.example.backend.enums.ParticipationStatus;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.MeetingPostRepository;
import com.example.backend.repository.MemberRepository;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {
    @InjectMocks private MeetingService meetingService;
    @Mock private MeetingPostRepository meetingPostRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private CategoryRepository categoryRepository;

    private Member testMember;
    private Category studyCategory;

    @BeforeEach
    void setUp() {
        testMember = Member.builder().id(1L).email("test@test.com").nickname("테스터").build();

        studyCategory = Category.builder().name("스터디").build();
        ReflectionTestUtils.setField(studyCategory, "id", 1L);
    }

    private MeetingPost createPost(Long id, String title, int viewCount) {
        MeetingPost post = MeetingPost.builder()
                .title(title)
                .description("테스트 설명")
                .capacity(5)
                .creator(testMember)
                .category(studyCategory)
                .build();
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "viewCount", viewCount);
        return post;
    }

    private MeetingPost createPost(String title) {
        return createPost(1L, title, 0);
    }

    @Nested
    @DisplayName("모임 생성 (createMeeting)")
    class CreateMeeting {

        @Test
        @DisplayName("성공: 생성자가 자동으로 참여자로 등록된다")
        void createMeeting_success() {
            // given
            Long memberId = 1L;
            Long categoryId = 1L;
            Long expectedPostId = 100L;

            MeetingPostCreateRequest request = new MeetingPostCreateRequest(
                    "자바 백엔드 스터디", "설명", 5,
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(30),
                    categoryId
            );

            given(memberRepository.findById(memberId)).willReturn(Optional.of(testMember));
            given(categoryRepository.findById(categoryId)).willReturn(Optional.of(studyCategory));

            // save 시 DB가 채워주는 ID를 흉내내어 반환값 매핑을 검증한다
            given(meetingPostRepository.save(any(MeetingPost.class))).willAnswer(invocation -> {
                MeetingPost savedPost = invocation.getArgument(0);
                ReflectionTestUtils.setField(savedPost, "id", expectedPostId);
                return savedPost;
            });

            // when
            Long resultId = meetingService.createMeeting(request, memberId);

            // then
            assertThat(resultId).isEqualTo(expectedPostId);
            verify(meetingPostRepository, times(1)).save(any(MeetingPost.class));
        }
    }

    @Nested
    @DisplayName("내가 만든 모임 조회 (getMyCreatedMeetings)")
    class GetMyCreatedMeetings {

        @Test
        @DisplayName("성공: 내가 만든 모임 목록을 반환한다")
        void getMyCreatedMeetings_success() {
            // given
            Long memberId = 1L;
            given(meetingPostRepository.findByCreatorIdOrderByCreatedAtDesc(memberId))
                    .willReturn(List.of(createPost("내가 만든 모임")));

            // when
            List<MeetingSummaryResponse> result = meetingService.getMyCreatedMeetings(memberId);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("내가 만든 모임");
        }
    }

    @Nested
    @DisplayName("내가 신청한 모임 조회 (getMyAppliedMeetings)")
    class GetMyAppliedMeetings {

        @Test
        @DisplayName("성공: 내가 신청한 모임 목록을 반환한다")
        void getMyAppliedMeetings_success() {
            // given
            Long memberId = 1L;
            Participation participation = Participation.builder()
                    .meetingPost(createPost("신청한 모임"))
                    .member(testMember)
                    .role(ParticipationRole.PARTICIPANT)
                    .status(ParticipationStatus.ACCEPTED)
                    .build();

            given(meetingPostRepository.findAllAppliedByMemberId(memberId)).willReturn(List.of(participation));

            // when
            List<MeetingSummaryResponse> result = meetingService.getMyAppliedMeetings(memberId);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ACCEPTED");
        }
    }

    @Nested
    @DisplayName("상세 조회 및 조회수 (getMeetingDetail)")
    class GetMeetingDetail {
        private final Long postId = 100L;
        private MockHttpServletRequest request;
        private MockHttpServletResponse response;

        @BeforeEach
        void init() {
            request = new MockHttpServletRequest();
            response = new MockHttpServletResponse();
        }

        @Test
        @DisplayName("성공: 최초 조회 시 조회수가 증가하고 쿠키가 생성된다")
        void increases_count_on_first_view() {
            // given
            MeetingPost post = createPost(postId, "최초 조회", 0);
            given(meetingPostRepository.findByIdWithDetails(postId)).willReturn(Optional.of(post));

            // when
            MeetingDetailResponse result = meetingService.getMeetingDetail(postId, 1L, request, response);

            // then
            assertThat(result.getViewCount()).isEqualTo(1);
            assertThat(response.getCookie("postView").getValue()).contains("[" + postId + "]");
        }

        @Test
        @DisplayName("성공: 이미 조회한 이력이 있으면 조회수가 유지된다")
        void no_increase_on_duplicate_view() {
            // given
            MeetingPost post = createPost(postId, "중복 조회", 10);
            given(meetingPostRepository.findByIdWithDetails(postId)).willReturn(Optional.of(post));
            request.setCookies(new Cookie("postView", "[" + postId + "]"));

            // when
            MeetingDetailResponse result = meetingService.getMeetingDetail(postId, 1L, request, response);

            // then
            assertThat(result.getViewCount()).isEqualTo(10);
        }
    }
}
