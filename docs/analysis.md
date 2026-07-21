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
