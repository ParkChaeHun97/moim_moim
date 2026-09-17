package com.example.backend.service;

import com.example.backend.common.exception.CustomException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.entity.Category;
import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Member;
import com.example.backend.entity.Participation;
import com.example.backend.enums.MemberStatus;
import com.example.backend.enums.ParticipationRole;
import com.example.backend.enums.ParticipationStatus;
import com.example.backend.enums.Role;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.MeetingPostRepository;
import com.example.backend.repository.MemberRepository;
import com.example.backend.repository.ParticipationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ParticipationService.updateParticipationStatus()의 정원 초과 동시성 방어(PESSIMISTIC_WRITE 락)를 검증한다.
 *
 * Mock이 아니라 실제 H2 DB에 커밋된 데이터를 대상으로, 서로 다른 스레드/트랜잭션에서 동시에
 * ACCEPTED 처리를 요청했을 때 락이 정상적으로 직렬화를 강제하는지 확인하는 목적이라
 * @DataJpaTest(테스트 메서드 자체가 트랜잭션으로 래핑되어 롤백됨) 대신 @SpringBootTest를 사용한다.
 * 테스트 메서드에는 @Transactional을 걸지 않는다 — 각 스레드가 서비스 메서드를 호출할 때마다
 * ParticipationService의 @Transactional이 별도 트랜잭션으로 동작해야 락 검증이 의미가 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ParticipationConcurrencyTest {

    @Autowired
    private ParticipationService participationService;
    @Autowired
    private MeetingPostRepository meetingPostRepository;
    @Autowired
    private ParticipationRepository participationRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    // 알림 발송은 이 테스트의 검증 대상이 아니고, 동시 요청 시 부수 효과(SSE 등)로 흔들리는 것을 막기 위해 목킹
    @MockitoBean
    private NotificationService notificationService;

    private Long meetingPostId;
    private Long hostId;
    private Long participationIdA;
    private Long participationIdB;

    private List<Long> acceptedParticipationIds = new ArrayList<>();
    private List<Long> acceptedMemberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Member host = memberRepository.save(Member.builder()
                .nickname("host")
                .email("concurrency-host@test.com")
                .password("encoded_password")
                .role(Role.ROLE_USER)
                .status(MemberStatus.ACTIVE)
                .age(30)
                .points(0)
                .level(1)
                .build());
        hostId = host.getId();

        Category category = categoryRepository.save(Category.builder().name("동시성테스트").build());

        MeetingPost post = meetingPostRepository.save(MeetingPost.builder()
                .title("동시성 테스트 모임")
                .description("정원 5명, 이미 4명 승인된 상태")
                .capacity(5)
                .startDate(LocalDateTime.now().plusDays(1))
                .creator(host)
                .category(category)
                .build());

        // 정원 5명 중 4명은 이미 ACCEPTED 상태로 채워둔다
        for (int i = 0; i < 4; i++) {
            Member accepted = memberRepository.save(Member.builder()
                    .nickname("accepted" + i)
                    .email("concurrency-accepted" + i + "@test.com")
                    .password("encoded_password")
                    .role(Role.ROLE_USER)
                    .status(MemberStatus.ACTIVE)
                    .age(25)
                    .points(0)
                    .level(1)
                    .build());

            Participation participation = participationRepository.save(Participation.builder()
                    .member(accepted)
                    .meetingPost(post)
                    .role(ParticipationRole.PARTICIPANT)
                    .status(ParticipationStatus.ACCEPTED)
                    .joinReason("먼저 승인된 참여자")
                    .build());

            acceptedParticipationIds.add(participation.getId());
            acceptedMemberIds.add(accepted.getId());


        }

        // addParticipant()가 검사하는 필드를 실제 ACCEPTED 인원 수(4)와 맞춰준다
        ReflectionTestUtils.setField(post, "currentParticipants", 4);
        meetingPostRepository.save(post);
        meetingPostId = post.getId();

        // 마지막 한 자리를 두고 동시에 ACCEPTED 처리될 신청자 2명 (APPLIED 상태)
        Member applicantA = memberRepository.save(Member.builder()
                .nickname("applicantA")
                .email("concurrency-applicantA@test.com")
                .password("encoded_password")
                .role(Role.ROLE_USER)
                .status(MemberStatus.ACTIVE)
                .age(26)
                .points(0)
                .level(1)
                .build());
        Member applicantB = memberRepository.save(Member.builder()
                .nickname("applicantB")
                .email("concurrency-applicantB@test.com")
                .password("encoded_password")
                .role(Role.ROLE_USER)
                .status(MemberStatus.ACTIVE)
                .age(27)
                .points(0)
                .level(1)
                .build());

        participationIdA = participationRepository.save(Participation.builder()
                .member(applicantA)
                .meetingPost(post)
                .role(ParticipationRole.PARTICIPANT)
                .status(ParticipationStatus.APPLIED)
                .joinReason("신청 A")
                .build()).getId();

        participationIdB = participationRepository.save(Participation.builder()
                .member(applicantB)
                .meetingPost(post)
                .role(ParticipationRole.PARTICIPANT)
                .status(ParticipationStatus.APPLIED)
                .joinReason("신청 B")
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        // FK 제약 순서상 자식(Participation)을 먼저 지운다
        participationRepository.deleteAll();
        meetingPostRepository.deleteAll();
        memberRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("정원이 1자리 남은 상태에서 서로 다른 신청 2건을 동시에 ACCEPTED 처리하면 1건만 성공하고 나머지는 MEETING_FULL로 실패한다")
    void concurrentAccept_onlyOneSucceeds() throws InterruptedException {
        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 대기 지점에 도달할 때까지
        CountDownLatch startLatch = new CountDownLatch(1);           // 동시 출발 신호
        CountDownLatch doneLatch = new CountDownLatch(threadCount);  // 모든 스레드 종료 대기

        List<Long> targetParticipationIds = List.of(participationIdA, participationIdB);
        List<Object> results = Collections.synchronizedList(new ArrayList<>());

        for (Long participationId : targetParticipationIds) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await(); // 여기서 대기하다가 동시에 출발
                    Long resultId = participationService.updateParticipationStatus(
                            participationId, "ACCEPTED", hostId);
                    results.add(resultId);
                } catch (Exception e) {
                    results.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();                       // 두 스레드 모두 준비 완료될 때까지 대기
        startLatch.countDown();                    // 동시 출발
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).as("스레드가 타임아웃 없이 종료되어야 한다 (락이 풀리지 않고 걸려있으면 실패)").isTrue();
        assertThat(results).hasSize(2);

        long successCount = results.stream()
                .filter(r -> r instanceof Long)
                .count();
        long meetingFullFailureCount = results.stream()
                .filter(r -> r instanceof CustomException)
                .map(r -> (CustomException) r)
                .filter(e -> e.getErrorCode() == ErrorCode.MEETING_FULL)
                .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(meetingFullFailureCount).isEqualTo(1);

        MeetingPost finalPost = meetingPostRepository.findById(meetingPostId).orElseThrow();
        assertThat(finalPost.getCurrentParticipants()).isEqualTo(5);

        long acceptedCount = participationRepository.findAllByMeetingPostId(meetingPostId).stream()
                .filter(p -> p.getStatus() == ParticipationStatus.ACCEPTED)
                .count();
        assertThat(acceptedCount).isEqualTo(5); // 기존 4명 + 새로 승인된 1명

        long stillAppliedCount = participationRepository.findAllByMeetingPostId(meetingPostId).stream()
                .filter(p -> p.getStatus() == ParticipationStatus.APPLIED)
                .count();
        assertThat(stillAppliedCount).isEqualTo(1); // 실패한 쪽은 상태 변경 없이 APPLIED로 남아야 한다

    }

    @Test
    @DisplayName("ACCEPTED 상태인 4명이 동시에 취소하면 정원이 정확히 0까지 감소한다")
    void concurrentCancel_decreasesCapacityCorrectly() throws InterruptedException {
        int threadCount = 4;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Object> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            Long participationId = acceptedParticipationIds.get(i);
            Long memberId = acceptedMemberIds.get(i);
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    participationService.cancelParticipation(participationId, memberId);
                    results.add("SUCCESS");
                } catch (Exception e) {
                    results.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).as("락이 풀리지 않고 걸려있으면 실패").isTrue();

        long successCount = results.stream().filter(r -> r.equals("SUCCESS")).count();
        assertThat(successCount).isEqualTo(4);

        MeetingPost finalPost = meetingPostRepository.findById(meetingPostId).orElseThrow();
        assertThat(finalPost.getCurrentParticipants()).isEqualTo(0); // 4 - 4 = 0, 마이너스면 버그

        long cancelledCount = participationRepository.findAllByMeetingPostId(meetingPostId).stream()
                .filter(p -> p.getStatus() == ParticipationStatus.CANCELLED)
                .count();
        assertThat(cancelledCount).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 참가 건에 취소 요청이 동시에 여러 번 들어와도 정원은 1번만 감소한다")
    void concurrentCancel_sameParticipation_onlyOneSucceeds() throws InterruptedException {
        Long targetParticipationId = acceptedParticipationIds.get(0);
        Long targetMemberId = acceptedMemberIds.get(0);

        int threadCount = 3;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Object> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    participationService.cancelParticipation(targetParticipationId, targetMemberId);
                    results.add("SUCCESS");
                } catch (Exception e) {
                    results.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).isTrue();

        long successCount = results.stream().filter(r -> r.equals("SUCCESS")).count();
        long alreadyProcessedCount = results.stream()
                .filter(r -> r instanceof CustomException)
                .map(r -> (CustomException) r)
                .filter(e -> e.getErrorCode() == ErrorCode.ALREADY_PROCESSED_PARTICIPATION)
                .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(alreadyProcessedCount).isEqualTo(2); // 나머지는 멱등성 가드에 걸려야 함

        MeetingPost finalPost = meetingPostRepository.findById(meetingPostId).orElseThrow();


        assertThat(finalPost.getCurrentParticipants()).isEqualTo(3); // 4 - 1 = 3, 여러 번 안 깎였는지
    }




}
