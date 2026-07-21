package com.example.backend.service;

import com.example.backend.entity.Category;
import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Member;
import com.example.backend.enums.MemberStatus;
import com.example.backend.enums.Role;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.MeetingPostRepository;
import com.example.backend.repository.MemberRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MeetingService.getAllMeetings() 호출 시 실제로 나가는 SQL 쿼리 개수를 측정한다.
 * N+1 리팩토링 전/후 수치를 비교하기 위한 테스트.
 *
 * @SpringBootTest 대신 @DataJpaTest를 쓴 이유:
 *  - 전체 앱 컨텍스트(시큐리티, JWT 설정, 외부 설정값 등)를 안 띄우고
 *    JPA 관련 빈 + 테스트 DB(H2)만 가볍게 띄워서 "Dialect 못 잡는" 류의
 *    설정 문제를 원천적으로 피함
 *  - MeetingService는 @DataJpaTest 기본 스캔 대상이 아니라서 @Import로 직접 등록
 *  - MeetingService가 의존하는 NotificationService는 이 테스트와 무관하므로 @MockBean으로 대체
 *
 * 사용법:
 *  1. 리팩토링 전 코드 상태에서 이 테스트를 돌려서 BEFORE 쿼리 수를 기록
 *  2. 리팩토링 후 코드로 교체하고 동일하게 돌려서 AFTER 쿼리 수를 기록
 *  3. 콘솔에 출력되는 "쿼리 실행 횟수"를 analysis.md에 그대로 옮겨 적으면 됨
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(MeetingService.class)
class MeetingServiceQueryCountTest {

    @Autowired
    private MeetingService meetingService;
    @Autowired
    private MeetingPostRepository meetingPostRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private EntityManager em;

    @MockitoBean
    private NotificationService notificationService; // getAllMeetings와 무관, 컨텍스트 로딩용 목킹

    private Statistics statistics;
    private Category studyCategory;

    @BeforeEach
    void setUp() {
        SessionFactory sessionFactory = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);

        studyCategory = categoryRepository.save(Category.builder().name("스터디").build());

        // 게시글 10개 생성 (실무 시나리오처럼 N건 데이터를 넣어 N+1이 눈에 띄게)
        for (int i = 0; i < 10; i++) {
            Member creator = memberRepository.save(Member.builder()
                    .email("user" + i + "@test.com")
                    .password("pw")
                    .nickname("user" + i)
                    .role(Role.ROLE_USER)
                    .status(MemberStatus.ACTIVE)
                    .build());

            meetingPostRepository.save(MeetingPost.builder()
                    .title("모임 " + i)
                    .description("설명 " + i)
                    .category(studyCategory)
                    .creator(creator)
                    .capacity(10)
                    .startDate(LocalDateTime.now().plusDays(i))
                    .endDate(LocalDateTime.now().plusDays(i + 1))
                    .build());
        }

        em.flush();
        em.clear(); // 영속성 컨텍스트(1차 캐시) 비우기 - 진짜 쿼리 나가는지 확인하려면 필수

        statistics.clear(); // 데이터 세팅 과정에서 나간 쿼리는 카운트에서 제외
    }

    @Test
    @DisplayName("카테고리 필터 + 기본 정렬 조회 시 쿼리 개수 확인")
    void countQueries_withCategoryFilter() {
        // when
        meetingService.getAllMeetings("default", studyCategory.getId());

        // then
        long queryCount = statistics.getPrepareStatementCount();
        System.out.println("========================================");
        System.out.println("카테고리 필터 조회 - 쿼리 실행 횟수: " + queryCount);
        System.out.println("========================================");

        // N+1이 있으면 대략 1(목록) + 10(category) + 10(creator) = 21개 근처가 나옴
        // fetch join으로 고쳤다면 1~2개 근처로 떨어져야 함
        assertThat(queryCount).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("카테고리 필터 없이 전체 조회 시 쿼리 개수 확인")
    void countQueries_withoutCategoryFilter() {
        // when
        meetingService.getAllMeetings("default", null);

        // then
        long queryCount = statistics.getPrepareStatementCount();
        System.out.println("========================================");
        System.out.println("전체 조회 - 쿼리 실행 횟수: " + queryCount);
        System.out.println("========================================");

        assertThat(queryCount).isLessThanOrEqualTo(3);
    }
}