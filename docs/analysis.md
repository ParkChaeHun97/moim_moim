# moim_moim 리팩토링 분석

홈서버 재배포 이후 진행한 성능/안정성 개선 작업을 단계별로 기록한다.
각 항목은 **문제 정의 → 측정 → 조치 → 결과** 순으로 정리한다.

---

## 1. N+1 문제 개선 - 모임 목록 조회

### 문제

`MeetingService.getAllMeetings()`에서 카테고리 필터/전체 조회 시 Spring Data가 자동 생성한
`findByCategoryId`, `findAll` 메서드를 사용했고, 여기엔 fetch join이 걸려 있지 않았다.
이후 `MeetingListResponse.from()`에서 `post.getCategory()`, `post.getCreator()`에 접근하는
순간 지연 로딩이 각각 발동해, 게시글 N개당 최대 2N번의 추가 쿼리가 발생하는 구조였다.

```java
// Before
List<MeetingPost> posts = (categoryId != null)
        ? meetingPostRepository.findByCategoryId(categoryId, sort)  // fetch join 없음
        : meetingPostRepository.findAll(sort);                       // fetch join 없음
```

### 측정 방법

Hibernate `Statistics.getPrepareStatementCount()`로 실제 실행된 쿼리 개수를 측정했다.
- 테스트 데이터: 동일 카테고리에 속한 게시글 10개, 서로 다른 작성자 10명
- `@DataJpaTest` + H2로 실제 SQL을 로그로 확인하며 검증
- `em.flush() / em.clear()`로 1차 캐시를 비운 뒤 조회해 실제 쿼리 발생 여부를 확인

### Before: 12개 쿼리

```sql
-- 1. 목록 조회 (fetch join 없음)
select mp1_0.* from meeting_post mp1_0
left join category c1_0 on c1_0.id = mp1_0.category_id
where c1_0.id = ? order by mp1_0.created_at desc

-- 2. category 지연 로딩 (1회 - 모든 게시글이 같은 카테고리라 1차 캐시로 재사용됨)
select c1_0.id, c1_0.name from category c1_0 where c1_0.id = ?

-- 3. member(creator) 지연 로딩 (게시글마다 개별 쿼리, 10회 반복)
select m1_0.id, m1_0.nickname, ... from member m1_0 where m1_0.id = ?
-- ↑ 위와 동일한 쿼리가 작성자 수만큼(10회) 반복 실행됨
```

**목록 조회 1 + 카테고리 조회 1 + 작성자 조회 10 = 총 12개**

### 조치

`sortBy`(default / closing / popular / urgent) 값별로 fetch join이 걸린 리포지토리 메서드를
분리했다. 기존에 `urgent` 정렬에만 적용돼 있던 패턴(`categoryId is null or ...` 조건 + join fetch)을
나머지 정렬 옵션에도 동일하게 적용하는 방식이다.

```java
// After
@Query("select m from MeetingPost m join fetch m.category join fetch m.creator " +
        "where (:categoryId is null or m.category.id = :categoryId) " +
        "order by m.createdAt desc")
List<MeetingPost> findAllOrderByLatest(@Param("categoryId") Long categoryId);
// findAllOrderByClosing, findAllOrderByPopular도 동일한 패턴
```

```java
// MeetingService
List<MeetingPost> posts = switch (sortBy.toLowerCase()) {
    case "closing" -> meetingPostRepository.findAllOrderByClosing(categoryId);
    case "popular" -> meetingPostRepository.findAllOrderByPopular(categoryId);
    case "urgent" -> meetingPostRepository.findAllOrderByUrgent(categoryId);
    default -> meetingPostRepository.findAllOrderByLatest(categoryId);
};
```

### After: 1개 쿼리

```sql
select mp1_0.*, c1_0.*, c2_0.*
from meeting_post mp1_0
join category c1_0 on c1_0.id = mp1_0.category_id
join member c2_0 on c2_0.id = mp1_0.creator_id
where (? is null or c1_0.id = ?)
order by mp1_0.created_at desc
```

**총 1개**

### 결과

| 항목 | Before | After |
|---|---|---|
| 쿼리 개수 (게시글 10건, 작성자 10명 기준) | 12 | 1 |

게시글 수(N)와 작성자 다양성에 비례해 쿼리가 늘어나는 구조였기 때문에, 실제 운영 데이터
규모(수백 건)에서는 격차가 훨씬 커질 수 있는 문제였다. sortBy별로 쿼리를 나눈 덕분에
정렬 옵션이 늘어나도 각 조회 경로가 독립적으로 fetch join을 갖게 되어 회귀 위험도 줄었다.

---

## 2. Phase 2: 참여 승인 정원 초과 - 비관적 락(Pessimistic Lock)

### 문제

`ParticipationService.updateParticipationStatus()`에서 참여 신청을 `ACCEPTED`로 승인할 때
`MeetingPost.addParticipant()`(`MeetingPost.java:139-147`)를 호출해 정원을 검사하고 인원을
증가시킨다.

```java
// MeetingPost.addParticipant() - Before
public void addParticipant() {
    if (this.currentParticipants >= this.capacity) {
        throw new IllegalStateException(...);
    }
    this.currentParticipants++;
}
```

이 메서드는 전형적인 **check-then-act** 패턴이다. 락 없이 `MeetingPost`를 조회했기 때문에,
capacity=5에서 이미 4명이 ACCEPTED인 모임에 서로 다른 신청 2건을 방장이 거의 동시에 승인하면
두 트랜잭션 모두 `currentParticipants=4`를 읽고 `4 >= 5` 검사를 통과한 뒤 각자 5로 증가시켜
저장한다. Hibernate의 dirty checking은 증분(`+1`) 연산이 아니라 계산된 절대값으로 UPDATE를
날리므로, 두 트랜잭션의 갱신이 서로를 덮어써 정원이 6명으로 초과되거나 증가분 하나가
유실되는 lost update가 발생할 수 있는 구조였다.

### 측정/재현

Mock이 아니라 실제 DB 트랜잭션을 검증해야 하는 문제라, `ParticipationConcurrencyTest`
(`src/test/java/com/example/backend/service/ParticipationConcurrencyTest.java`)를
`@SpringBootTest`로 작성해 재현했다. `@DataJpaTest`는 테스트 메서드 자체가 트랜잭션으로
래핑되어 롤백되므로 이 목적에는 맞지 않아 배제했다.

- capacity=5, 이미 ACCEPTED 4명이 채워진 `MeetingPost`와 `APPLIED` 상태 신청 2건(A, B)을
  실제로 커밋
- `CountDownLatch` 3개(`readyLatch` → `startLatch` → `doneLatch`)로 두 스레드를 동시 출발시켜,
  각 스레드가 별도 트랜잭션에서 `participationService.updateParticipationStatus()`를 호출
- 결과(성공한 ID / 발생한 예외)를 모아 성공 1건, `MEETING_FULL` 실패 1건인지 검증

재현 검증을 위해 `MeetingPostRepository.findByIdForUpdate()` 호출을 일부러 락 없는
`findById()`로 임시 교체하고 3회 연속 실행한 결과 **3번 모두 실패**(성공이 2건으로 집계됨)
했고, 원복 후에는 다시 통과하는 것을 확인해 이 테스트가 실제로 race condition을 잡아낸다는
것을 검증했다.

### 조치

`MeetingPostRepository`(`MeetingPostRepository.java:22-26`)와 `ParticipationRepository`
(`ParticipationRepository.java:35-39`)에 `PESSIMISTIC_WRITE` 락 조회 메서드를 추가하고,
`ParticipationService.updateParticipationStatus()`(`ParticipationService.java:98-144`)를
다음 순서로 재구성했다.

```java
// ParticipationService.updateParticipationStatus() - After
// 1. 락 없이 meetingPostId만 조회 (어떤 MeetingPost를 잠글지 알아야 하므로)
Long meetingPostId = participationRepository.findMeetingPostIdById(participationId)
        .orElseThrow(() -> new CustomException(ErrorCode.PARTICIPATION_NOT_FOUND));

// 2. MeetingPost 먼저 잠금 (정원 race condition 방지, 데드락 회피를 위한 락 순서)
MeetingPost post = meetingPostRepository.findByIdForUpdate(meetingPostId)
        .orElseThrow(() -> new CustomException(ErrorCode.MEETING_NOT_FOUND));

// 💡 권한 체크: Participation 락을 걸기 전에 fail fast
if (!post.getCreator().getId().equals(hostId)) {
    throw new CustomException(ErrorCode.NOT_AUTHORIZED_PARTICIPATION);
}

// 3. Participation은 권한 확인 후 잠금 (동일 신청 중복 처리 방지)
Participation participation = participationRepository.findByIdForUpdate(participationId)
        .orElseThrow(() -> new CustomException(ErrorCode.PARTICIPATION_NOT_FOUND));

// 4. 멱등성 가드: 락 대기 중 다른 요청이 이미 처리했을 수 있음
if (participation.getStatus() != ParticipationStatus.APPLIED
        && participation.getStatus() != ParticipationStatus.WAITING) {
    throw new CustomException(ErrorCode.ALREADY_PROCESSED_PARTICIPATION);
}
```

- **락 순서 규칙 (`MeetingPost` → `Participation`)**: `MeetingPost`는 `currentParticipants`/
  `capacity` 불변식을 가진 자원이라 check-then-act 구간 전체를 잠가야 하고, 동시에 여러
  `Participation`이 공유하는 컨텐션 지점이기도 하다. 이후 추가될 취소/거절 등 다른 흐름도
  같은 순서(부모 먼저, 자식 나중)를 지키도록 규칙화해, 반대 순서로 락을 거는 코드 경로가
  생겨 AB-BA 데드락이 나는 상황을 원천 차단한다.
- **멱등성 가드**: `MeetingPost` 락 대기 중 다른 요청이 같은 `Participation`을 먼저 처리했을
  가능성을 차단하기 위해, 락 획득 후 상태가 여전히 `APPLIED`/`WAITING`인지 재확인한다
  (`ErrorCode.ALREADY_PROCESSED_PARTICIPATION`, `PART_006`, `ErrorCode.java:34`).
- `MeetingPost.addParticipant()`(`MeetingPost.java:139-147`)가 던지던 `IllegalStateException`을
  `CustomException(ErrorCode.MEETING_FULL)`로 통일해, 락 덕분에 실제로 발동하게 된 정원 초과
  케이스도 일관된 에러 응답 형식을 따르게 했다.
- 락 대기가 무한정 걸리지 않도록 `@QueryHints`로 `jakarta.persistence.lock.timeout`을
  3000ms로 설정했다.

### 결과

| 항목 | Before | After |
|---|---|---|
| 동시 승인 2건 중 성공 건수 | 최대 2건(초과 승인 가능) | 정확히 1건 |
| `currentParticipants` 정합성 | 락 없는 경쟁 상태에서 어긋날 수 있음 | 항상 실제 ACCEPTED 수와 일치 |
| 정원 초과 시 응답 | `IllegalStateException`(비일관) | `CustomException(MEETING_FULL)` |

`ParticipationConcurrencyTest`가 CI에서 반복 실행되며 동시 승인 시나리오를 검증한다.
정원이 1자리 남은 상태에서 서로 다른 신청 2건을 동시에 승인 요청하면 1건만 `ACCEPTED`로
성공하고 나머지 1건은 `MEETING_FULL` 예외로 정상 거부되며, 최종 `currentParticipants`와
ACCEPTED 참여자 수가 항상 5명으로 일치함을 확인했다.
