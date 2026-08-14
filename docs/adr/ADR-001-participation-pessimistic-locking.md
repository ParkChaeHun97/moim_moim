# ADR-001: 참여 승인 처리에 비관적 락(Pessimistic Lock) 적용

- Status: Accepted
- Date: 2026-08-15

## Context

`ParticipationService.updateParticipationStatus()`에서 참여 신청을 `ACCEPTED`로 승인할 때
`MeetingPost.addParticipant()`(`MeetingPost.java:139-147`)가 정원을 검사하고 인원을 증가시킨다.

```java
public void addParticipant() {
    if (this.currentParticipants >= this.capacity) {
        throw new CustomException(ErrorCode.MEETING_FULL);
    }
    this.currentParticipants++;
}
```

이 메서드는 전형적인 **check-then-act** 패턴이다. 락 없이 `MeetingPost`를 조회하면, capacity=5에서
이미 4명이 ACCEPTED인 모임에 서로 다른 신청 2건을 방장이 거의 동시에 승인할 때 두 트랜잭션 모두
`currentParticipants=4`를 읽고 `4 >= 5` 검사를 통과한 뒤 각자 5로 증가시켜 저장한다. Hibernate의
dirty checking은 증분(`+1`) 연산이 아니라 계산된 절대값으로 UPDATE를 날리므로, 두 트랜잭션의
갱신이 서로를 덮어써 정원이 초과되거나 증가분 하나가 유실되는 lost update가 발생할 수 있다.

### 고려한 대안: 낙관적 락(`@Version`)

`MeetingPost`에 `@Version` 필드를 추가하고 `OptimisticLockException` 발생 시 재시도하는 방식도
검토했다. 낙관적 락은 컨텐션이 낮을 때 락 대기 비용이 없다는 장점이 있지만, 이 흐름에는 맞지
않는다고 판단했다:

- 참여 승인은 **방장 한 명이 순차적으로 여러 신청을 처리**하는 흐름이 대부분이지만, 인기 모임의
  마감 직전에는 여러 신청 건에 대한 승인 요청이 짧은 시간에 몰릴 수 있다. 이때 낙관적 락은
  실패한 트랜잭션마다 재시도 루프를 돌려야 하고, 재시도 중에도 계속 충돌하면 사용자에게 지연이
  누적된다.
- 승인/거절은 정원이라는 **공유 카운터를 직접 갱신**하는 쓰기 작업이라, 실패를 사용자에게
  돌려주고 재시도를 클라이언트나 서비스 계층에 맡기기보다는 애초에 충돌 자체를 직렬화해서
  막는 편이 응답 일관성 측면에서 낫다고 판단했다.
- 정원 초과 여부가 비즈니스적으로 중요한 불변식(초과 승인 시 신뢰 문제로 직결)이라, 재시도
  로직의 정확성에 기대기보다 DB 수준에서 확실히 직렬화하는 비관적 락을 우선 선택했다.

## Decision

`MeetingPostRepository`(`MeetingPostRepository.java:22-26`)와 `ParticipationRepository`
(`ParticipationRepository.java:35-39`)에 `PESSIMISTIC_WRITE` 락 조회 메서드를 추가하고,
`ParticipationService.updateParticipationStatus()`(`ParticipationService.java:98-144`)에서
다음 순서로 락을 건다.

1. `ParticipationRepository.findMeetingPostIdById()` — 락 없이 `meetingPostId`만 조회
2. `MeetingPostRepository.findByIdForUpdate()` — **`MeetingPost`를 먼저 잠금**
3. 방장 권한 체크 (fail fast — `Participation` 락을 걸기 전에 수행)
4. `ParticipationRepository.findByIdForUpdate()` — **`Participation`을 그 다음 잠금**
5. 멱등성 가드(상태가 여전히 `APPLIED`/`WAITING`인지 재확인) 후 상태 변경

```java
Long meetingPostId = participationRepository.findMeetingPostIdById(participationId)
        .orElseThrow(() -> new CustomException(ErrorCode.PARTICIPATION_NOT_FOUND));

MeetingPost post = meetingPostRepository.findByIdForUpdate(meetingPostId)
        .orElseThrow(() -> new CustomException(ErrorCode.MEETING_NOT_FOUND));

if (!post.getCreator().getId().equals(hostId)) {
    throw new CustomException(ErrorCode.NOT_AUTHORIZED_PARTICIPATION);
}

Participation participation = participationRepository.findByIdForUpdate(participationId)
        .orElseThrow(() -> new CustomException(ErrorCode.PARTICIPATION_NOT_FOUND));
```

### 왜 MeetingPost를 먼저, Participation을 나중에 잠그는가

- **정합성 보호 대상이 `MeetingPost`이기 때문**: `currentParticipants`/`capacity` 불변식은
  `MeetingPost`에 있다. check(정원 초과 여부) → act(증가) 구간 전체를 원자적으로 만들려면,
  검사가 시작되기 전부터 `MeetingPost` 행이 잠겨 있어야 한다.
- **일관된 락 순서로 AB-BA 데드락을 방지하기 위해서**: `MeetingPost`는 여러 `Participation`이
  공유하는 부모 자원이라 컨텐션이 몰리는 지점이다. 만약 다른 코드 경로(예: 향후 취소 처리)가
  반대 순서로 락을 건다면 — Tx1(승인)이 `MeetingPost`를 잠그고 `Participation`을 기다리는 동안,
  Tx2(취소)가 `Participation`을 잠그고 `MeetingPost`를 기다리는 — 전형적인 교착 상태가 발생한다.
  "부모(`MeetingPost`) 먼저, 자식(`Participation`) 나중"이라는 순서를 모든 트랜잭션에서 일관되게
  지키면 이 문제를 원천 차단할 수 있다.

락 대기가 무한정 걸리지 않도록 `@QueryHints`로 `jakarta.persistence.lock.timeout`을 3000ms로
설정했다.

## Consequences

### 트레이드오프: 락 홀드 시간 동안의 컨텐션

같은 `MeetingPost`에 대한 승인 요청은 이제 완전히 직렬화된다. 방장이 짧은 시간에 여러 건을
연달아 승인하면, 뒤에 오는 요청은 앞 트랜잭션이 커밋(또는 lock timeout)될 때까지 대기한다.
지금 규모(방장 1인이 순차적으로 처리하는 흐름)에서는 문제가 되지 않지만, 승인 트랜잭션 안에서
느린 작업(외부 API 호출, 무거운 로직 등)을 추가하면 락 홀드 시간이 늘어나 대기가 길어질 수
있으므로, 트랜잭션 범위를 계속 좁게 유지해야 한다.

### 향후 취소/거절 로직도 동일한 락 순서 규칙을 따라야 함

`MeetingPost` → `Participation` 순서는 이 트랜잭션 하나만의 규칙이 아니라 **두 엔티티를 함께
잠그는 모든 코드 경로가 지켜야 하는 전역 규칙**이다. 신청 취소, 거절 처리 등 향후 추가되는
기능이 이 순서를 어기면 데드락 위험이 다시 생긴다. 새 기능을 추가할 때 이 ADR을 참조해 락
순서를 검토해야 한다.

### 트래픽 증가 시 낙관적 락 + 재시도로 전환 고려 가능

지금은 방장 1인이 순차적으로 승인 처리하는 흐름이라 비관적 락의 대기 비용이 작지만, 향후
동시 승인 트래픽이 커지거나(예: 여러 관리자가 동시에 승인 처리하는 기능이 추가되는 경우)
락 대기가 병목이 된다면, `MeetingPost`에 `@Version`을 추가하고 `OptimisticLockException` 시
제한된 횟수만 재시도하는 낙관적 락 방식으로 전환하는 것을 고려할 수 있다. 그 경우 재시도
로직과 최대 재시도 횟수 초과 시의 사용자 응답(예: "잠시 후 다시 시도해주세요")을 별도로
설계해야 한다.