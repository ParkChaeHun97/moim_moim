package com.example.backend.service;

import com.example.backend.entity.Category;
import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Member;
import com.example.backend.entity.Notification;
import com.example.backend.entity.Participation;
import com.example.backend.enums.MemberStatus;
import com.example.backend.enums.ParticipationRole;
import com.example.backend.enums.ParticipationStatus;
import com.example.backend.enums.Role;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.MeetingPostRepository;
import com.example.backend.repository.MemberRepository;
import com.example.backend.repository.NotificationRepository;
import com.example.backend.repository.ParticipationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * SSE 전송 실패(끊긴 연결로 인한 IOException)가 참여 승인 트랜잭션에 영향을 주지 않는지 검증한다.
 * Mock이 아니라 실제 ParticipationService -> NotificationService -> SseService 체인과 H2 DB 커밋을 대상으로 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ParticipationSseFailureTest {

    @Autowired
    private ParticipationService participationService;
    @Autowired
    private SseService sseService;
    @Autowired
    private MeetingPostRepository meetingPostRepository;
    @Autowired
    private ParticipationRepository participationRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    private Long hostId;
    private Long applicantId;
    private Long participationId;
    private Long meetingPostId;

    @BeforeEach
    void setUp() {
        Member host = memberRepository.save(Member.builder()
                .nickname("host")
                .email("sse-failure-host@test.com")
                .password("encoded_password")
                .role(Role.ROLE_USER)
                .status(MemberStatus.ACTIVE)
                .age(30)
                .points(0)
                .level(1)
                .build());
        hostId = host.getId();

        Member applicant = memberRepository.save(Member.builder()
                .nickname("applicant")
                .email("sse-failure-applicant@test.com")
                .password("encoded_password")
                .role(Role.ROLE_USER)
                .status(MemberStatus.ACTIVE)
                .age(25)
                .points(0)
                .level(1)
                .build());
        applicantId = applicant.getId();

        Category category = categoryRepository.save(Category.builder().name("SSE실패테스트").build());

        MeetingPost post = meetingPostRepository.save(MeetingPost.builder()
                .title("SSE 장애 테스트 모임")
                .description("SSE 전송 실패가 승인 트랜잭션에 영향을 주지 않아야 한다")
                .capacity(5)
                .startDate(LocalDateTime.now().plusDays(1))
                .creator(host)
                .category(category)
                .build());
        meetingPostId = post.getId();

        participationId = participationRepository.save(Participation.builder()
                .member(applicant)
                .meetingPost(post)
                .role(ParticipationRole.PARTICIPANT)
                .status(ParticipationStatus.APPLIED)
                .joinReason("SSE 실패 테스트")
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        notificationRepository.deleteAll();
        participationRepository.deleteAll();
        meetingPostRepository.deleteAll();
        memberRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("신청자의 SSE 연결이 끊겨 전송이 실패해도 참여 승인 트랜잭션은 정상 커밋된다")
    void sseSendFailure_doesNotRollbackParticipationApproval() throws IOException {
        // given: 신청자에게 연결되어 있던 SSE emitter가 끊겨서 전송 시 IOException을 던지는 상황을 재현
        @SuppressWarnings("unchecked")
        Map<Long, SseEmitter> emitters = (Map<Long, SseEmitter>) ReflectionTestUtils.getField(sseService, "emitters");
        SseEmitter brokenEmitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(brokenEmitter).send(any(SseEmitter.SseEventBuilder.class));
        emitters.put(applicantId, brokenEmitter);

        // when: 방장이 승인 처리 (내부적으로 알림 생성 -> SSE 전송 시도 -> IOException 발생)
        assertThatCode(() ->
                participationService.updateParticipationStatus(participationId, "ACCEPTED", hostId)
        ).doesNotThrowAnyException();

        // then 1: SSE 전송 실패와 무관하게 참여 상태 변경 트랜잭션은 커밋되어야 한다
        Participation persisted = participationRepository.findById(participationId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ParticipationStatus.ACCEPTED);

        // MeetingPost.currentParticipants는 생성 시 개설자(1명)를 포함해 1로 시작한다 (MeetingPost.java:38)
        MeetingPost persistedPost = meetingPostRepository.findById(meetingPostId).orElseThrow();
        assertThat(persistedPost.getCurrentParticipants()).isEqualTo(2);

        // then 2: 알림 저장(DB)도 SSE 전송 실패와 무관하게 함께 커밋되어야 한다
        List<Notification> notifications = notificationRepository.findByReceiverIdOrderByCreatedAtDesc(applicantId);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getContent()).contains("승인");

        // then 3: 끊긴 emitter는 SseService가 실제로 예외를 잡고 정리했어야 한다
        assertThat(emitters).doesNotContainKey(applicantId);
    }
}
