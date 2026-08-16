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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ParticipationServiceTest {
    @InjectMocks
    private ParticipationService participationService;

    @Mock private NotificationService notificationService;
    @Mock private ParticipationRepository participationRepository;
    @Mock private MeetingPostRepository meetingPostRepository;
    @Mock private MemberRepository memberRepository;

    private Member organizer;
    private Member applicant;
    private MeetingPost meetingPost;
    private ParticipationRequestDto requestDto;

    @BeforeEach
    void setUp() {
        organizer = Member.builder().id(999L).nickname("방장").build();
        applicant = Member.builder().id(1L).nickname("신청자").build();

        meetingPost = MeetingPost.builder()
                .title("백엔드 스터디")
                .capacity(5)
                .creator(organizer)
                .build();
        ReflectionTestUtils.setField(meetingPost, "id", 100L);

        requestDto = new ParticipationRequestDto(100L, "열심히 참여하겠습니다!");
    }

    @Nested
    @DisplayName("참여 신청 (applyForMeeting)")
    class ApplyForMeeting {

        @Test
        @DisplayName("성공: 신청 시 저장된 참여 ID를 반환한다")
        void applySuccess() {
            // given
            given(meetingPostRepository.findById(any())).willReturn(Optional.of(meetingPost));
            given(memberRepository.findById(any())).willReturn(Optional.of(applicant));
            given(participationRepository.existsByMemberIdAndMeetingPostId(any(), any())).willReturn(false);

            // save 시 DB가 채워주는 ID를 흉내내어 응답 매핑을 검증한다
            given(participationRepository.save(any(Participation.class))).willAnswer(invocation -> {
                Participation participation = invocation.getArgument(0);
                ReflectionTestUtils.setField(participation, "id", 500L);
                return participation;
            });

            // when
            Long participationId = participationService.applyForMeeting(requestDto, 1L);

            // then
            assertThat(participationId).isNotNull();
            assertThat(participationId).isEqualTo(500L);
            verify(participationRepository).save(any());
        }

        @Test
        @DisplayName("실패: 이미 신청한 모임에 재신청 시 ALREADY_PARTICIPATED 예외가 발생한다")
        void duplicateApplyFail() {
            // given
            given(meetingPostRepository.findById(any())).willReturn(Optional.of(meetingPost));
            given(memberRepository.findById(any())).willReturn(Optional.of(applicant));
            given(participationRepository.existsByMemberIdAndMeetingPostId(any(), any())).willReturn(true);

            // when & then
            assertThatThrownBy(() -> participationService.applyForMeeting(requestDto, 1L))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_PARTICIPATED);
        }
    }

    @Nested
    @DisplayName("신청자 명단 조회 (getParticipants)")
    class GetParticipants {

        @Test
        @DisplayName("성공: 방장이 조회하면 신청자 리스트를 반환한다")
        void getParticipants_success() {
            // given
            Long postId = 100L;
            Long hostId = 999L;

            Participation participation = Participation.builder()
                    .member(applicant)
                    .meetingPost(meetingPost)
                    .status(ParticipationStatus.APPLIED)
                    .role(ParticipationRole.PARTICIPANT)
                    .joinReason("함께 스터디하고 싶어요")
                    .build();
            ReflectionTestUtils.setField(participation, "id", 500L);

            given(meetingPostRepository.findById(postId)).willReturn(Optional.of(meetingPost));
            given(participationRepository.findAllByMeetingPostId(postId)).willReturn(List.of(participation));

            // when
            List<ParticipationResponse> result = participationService.getParticipants(postId, hostId);

            // then
            assertThat(result.size()).isEqualTo(1);
            assertThat(result.get(0).getNickname()).isEqualTo("신청자");
        }

        @Test
        @DisplayName("실패: 방장이 아닌 유저가 접근하면 NOT_AUTHORIZED_PARTICIPATION 예외가 발생한다")
        void getParticipants_fail_notHost() {
            // given
            given(meetingPostRepository.findById(100L)).willReturn(Optional.of(meetingPost));

            // when & then
            assertThatThrownBy(() -> participationService.getParticipants(100L, 1L))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_AUTHORIZED_PARTICIPATION);
        }
    }

    @Nested
    @DisplayName("상태 변경 (updateParticipationStatus)")
    class UpdateParticipationStatus {

        @Test
        @DisplayName("성공: 방장이 승인하면 상태가 ACCEPTED로 변경된다")
        void updateStatus_success() {
            // given
            Long partId = 500L;
            Long hostId = 999L;

            Participation participation = Participation.builder()
                    .meetingPost(meetingPost)
                    .status(ParticipationStatus.APPLIED)
                    .build();
            ReflectionTestUtils.setField(participation, "id", partId);

            given(participationRepository.findMeetingPostIdById(partId)).willReturn(Optional.of(meetingPost.getId()));
            given(meetingPostRepository.findByIdForUpdate(meetingPost.getId())).willReturn(Optional.of(meetingPost));
            given(participationRepository.findByIdForUpdate(partId)).willReturn(Optional.of(participation));

            // when
            Long resultId = participationService.updateParticipationStatus(partId, "ACCEPTED", hostId);

            // then
            assertThat(resultId).isEqualTo(partId);
            assertThat(participation.getStatus()).isEqualTo(ParticipationStatus.ACCEPTED);
        }
    }
}
