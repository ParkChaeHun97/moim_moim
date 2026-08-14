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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
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
        // 1. 주최자(Host) 생성
        organizer = Member.builder().id(999L).nickname("방장").build();

        // 2. 신청자(Member) 생성
        applicant = Member.builder().id(1L).nickname("신청자").build();

        // 3. 모임 게시글 생성
        meetingPost = MeetingPost.builder()
                .title("백엔드 스터디")
                .capacity(5)
                .creator(organizer)
                .build();
        ReflectionTestUtils.setField(meetingPost, "id", 100L);

        requestDto = new ParticipationRequestDto(100L, "열심히 참여하겠습니다!");
    }

    // --- 참여 신청 테스트 ---
    @Test
    @DisplayName("참여 신청 성공")
    void applySuccess() {
        // 1. Given: 다른 모킹들
        given(meetingPostRepository.findById(any())).willReturn(Optional.of(meetingPost));
        given(memberRepository.findById(any())).willReturn(Optional.of(applicant));
        given(participationRepository.existsByMemberIdAndMeetingPostId(any(), any())).willReturn(false);

        // 2. 핵심 수정 부분: save 메서드가 호출될 때, ID가 세팅된 객체를 반환하도록 함
        given(participationRepository.save(any(Participation.class))).willAnswer(invocation -> {
            Participation participation = invocation.getArgument(0);
            // Reflection을 사용해 ID 강제 주입 (DB가 생성해주는 ID를 흉내냄)
            ReflectionTestUtils.setField(participation, "id", 500L);
            return participation;
        });

        // 3. When
        Long participationId = participationService.applyForMeeting(requestDto, 1L);

        // 4. Then
        assertThat(participationId).isNotNull(); // 여기서 에러가 났던 것
        assertThat(participationId).isEqualTo(500L);
        verify(participationRepository).save(any());
    }

    @Test
    @DisplayName("중복 신청 시 ALREADY_PARTICIPATED 예외 발생")
    void duplicateApplyFail() {
        given(meetingPostRepository.findById(any())).willReturn(Optional.of(meetingPost));
        given(memberRepository.findById(any())).willReturn(Optional.of(applicant));
        given(participationRepository.existsByMemberIdAndMeetingPostId(any(), any())).willReturn(true);

        assertThatThrownBy(() -> participationService.applyForMeeting(requestDto, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_PARTICIPATED);
    }

    // --- 신청자 명단 조회 테스트 ---
    @Test
    @DisplayName("신청자 명단 조회 성공: 방장이 조회하면 리스트를 반환한다")
    void getParticipants_success() {
        Long postId = 100L;
        Long hostId = 999L; // 방장 ID

        Participation participation = Participation.builder()
                .member(applicant)
                .meetingPost(meetingPost)
                .status(ParticipationStatus.APPLIED)
                .role(ParticipationRole.PARTICIPANT) // 👈 이 부분이 누락되면 NPE 발생!
                .joinReason("함께 스터디하고 싶어요")
                .build();
        ReflectionTestUtils.setField(participation, "id", 500L);

        given(meetingPostRepository.findById(postId)).willReturn(Optional.of(meetingPost));
        given(participationRepository.findAllByMeetingPostId(postId)).willReturn(List.of(participation));

        List<ParticipationResponse> result = participationService.getParticipants(postId, hostId);

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).getNickname()).isEqualTo("신청자");
    }

    @Test
    @DisplayName("신청자 명단 조회 실패: 방장이 아닌 유저가 접근하면 NOT_AUTHORIZED_PARTICIPATION 발생")
    void getParticipants_fail_notHost() {
        given(meetingPostRepository.findById(100L)).willReturn(Optional.of(meetingPost));

        assertThatThrownBy(() -> participationService.getParticipants(100L, 1L)) // 신청자 ID로 조회 시도
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_AUTHORIZED_PARTICIPATION);
    }

    // --- 상태 변경 테스트 ---
    @Test
    @DisplayName("참여 승인 성공: 상태가 ACCEPTED로 변경된다")
    void updateStatus_success() {
        // given
        Long partId = 500L;
        Long hostId = 999L;

        Participation participation = Participation.builder()
                .meetingPost(meetingPost)
                .status(ParticipationStatus.APPLIED)
                .build();

        //이 부분이 핵심입니다 엔티티에 ID를 직접 입력
        // 서비스 로직에서 return participation.getId() 할 때 500L이 나옵니다.
        ReflectionTestUtils.setField(participation, "id", partId);

        given(participationRepository.findMeetingPostIdById(partId)).willReturn(Optional.of(meetingPost.getId()));
        given(meetingPostRepository.findByIdForUpdate(meetingPost.getId())).willReturn(Optional.of(meetingPost));
        given(participationRepository.findByIdForUpdate(partId)).willReturn(Optional.of(participation));

        // when
        Long resultId = participationService.updateParticipationStatus(partId, "ACCEPTED", hostId);

        // then
        assertThat(resultId).isEqualTo(partId); // 이제 null이 아니라 500L이 기대됩니다.
        assertThat(participation.getStatus()).isEqualTo(ParticipationStatus.ACCEPTED);
    }
}