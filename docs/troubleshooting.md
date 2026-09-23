### SSE 알림 전송 실패가 참여 승인 트랜잭션을 롤백시키던 문제

**문제**
`SseService.send()`가 연결이 끊긴 상태(`IOException`)에서 `CustomException(SSE_SEND_ERROR)`을 던지고 있었다. 이 예외는 `NotificationService.createNotification()`을 거쳐 `ParticipationService`의 `@Transactional` 메서드(`applyForMeeting`, `updateParticipationStatus`)까지 그대로 전파되어, 알림 전송 실패만으로 정상적인 참가 신청/승인 처리 자체가 롤백되는 구조였다. SSE는 있으면 좋은 부가 기능일 뿐인데, 그게 핵심 비즈니스 로직의 성패를 좌우하고 있었던 것. 이미 배포되어 운영 중이던 코드였고, ADR 문서가 실제 코드와 일치하는지 검증하는 작업 중에 발견했다.

**조치**
알림을 호출하는 쪽(`ParticipationService`)에서 예외를 방어적으로 try-catch하는 방법도 검토했으나, 호출부가 늘어날 때마다 매번 방어 코드를 잊지 않고 넣어야 하는 임시방편이라 기각했다. 대신 알림을 보내는 쪽(`SseService`) 자체를 애초에 실패해도 괜찮은 구조(best-effort)로 바꾸는 방법을 택해, `IOException` 발생 시 예외를 던지지 않고 로그만 남기도록 변경했다. 더 이상 쓰이지 않는 `ErrorCode.SSE_SEND_ERROR`도 함께 제거했다. (커밋 [`4837933`](https://github.com/ParkChaeHun97/moim_moim/commit/4837933))

**결과**
끊긴 SSE 연결이 있어도 참여 승인 트랜잭션이 정상 커밋되고 알림도 함께 저장됨을 증명하는 통합 테스트(`ParticipationSseFailureTest`)를 추가해 검증했다. `SseServiceTest`에도 IOException이 호출자에게 전파되지 않고 emitter만 정리되는지 확인하는 테스트를 추가했다.

---

### 리프레시 토큰 쿠키 SameSite 이중 설정 (죽은 코드)

**문제**
`AuthController`의 `login()`, `reissue()`에서 `ResponseCookie` 빌더 체이닝 중 `.sameSite("None")`을 호출한 뒤 바로 다음 줄에서 `.sameSite("Strict")`로 덮어쓰고 있었다. 빌더 패턴이라 마지막 호출만 적용되므로 실제로는 항상 `Strict`만 걸렸고, `None` 설정과 그 옆 주석은 코드만 존재하고 동작하지 않는 죽은 코드였다. 실제 사용자가 겪는 문제는 아니었고, ADR 문서가 실제 코드와 일치하는지 대조 검증하던 중에 발견했다.

**조치**
프론트엔드는 운영에서는 Nginx, 로컬에서는 Vite 프록시를 통해 항상 API와 동일 출처로 통신하는 구조라 크로스 사이트 쿠키 전송(`SameSite=None`)이 애초에 불필요했다. 죽은 `None` 설정과 관련 주석을 제거하고, `Strict` 하나로 코드와 실제 동작·의도를 일치시켰다. 동작 자체는 원래도 Strict였으므로 기능 변화는 없다. (커밋 [`1324e61`](https://github.com/ParkChaeHun97/moim_moim/commit/1324e61))

**결과**
로그인/재발급 응답의 `Set-Cookie`가 `SameSite=Strict`로만 설정되고 `None`은 포함되지 않는지 검증하는 테스트를 추가했다. 재발급(`reissue`) API는 이전까지 테스트 자체가 없었어서, 이번에 처음 테스트를 붙였다.

---

### SSE 연결이 끊긴 뒤 재연결을 시도하지 않던 문제

**문제**
클라이언트(`Header.jsx`)의 `EventSource.onerror`가 `close()`만 호출하고 재연결을 전혀 시도하지 않았다. 서버 쪽 SSE 타임아웃(1시간)이나 네트워크 순단으로 연결이 한 번 끊기면, 사용자가 새로고침하기 전까지 실시간 알림을 전혀 못 받는 상태였다. 브라우저의 EventSource 네이티브 재연결에 맡기는 방법도 있었지만, 그건 끊긴 시점의(만료됐을 수 있는) 토큰을 그대로 재사용해서 재연결 자체가 실패하는 구조였다.

**조치**
클라이언트에서 `onerror` 시 명시적으로 `close()`한 뒤, `localStorage`의 최신 access token으로 지수 백오프(1s→2s→4s→...최대 30s)를 두고 재연결하도록 수정했다. 연결 성공 시 백오프 카운터를 초기화하고, 로그아웃/언마운트 시 예약된 재연결 타이머도 함께 정리해 메모리 누수를 방지했다. 서버 쪽에는 클라이언트 JS 재연결 로직이 어떤 이유로든 동작하지 않는 극단적 상황에 대한 방어선으로, `SseService`에서 SSE `retry` 필드(`reconnectTime`)를 3초로 명시했다. (커밋 [`5b756ab`](https://github.com/ParkChaeHun97/moim_moim/commit/5b756ab))

**결과**
프론트엔드는 별도 테스트 프레임워크가 없어 lint(eslint)와 프로덕션 빌드(vite build)로 검증했고, 백엔드는 기존 SSE 테스트 스위트가 회귀 없이 통과하는지 확인했다.

---

### 참여 신청 거절 시 알림이 발송되지 않던 누락

**문제**
`ParticipationService.updateParticipationStatus()`가 `ACCEPTED`로 상태가 바뀔 때만 신청자에게 알림을 보내고 있었다. `REJECTED`로 바뀔 때는 알림 생성 로직 자체가 없어서, 신청자는 거절당한 사실을 실시간 알림으로도 목록 조회로도 확인할 방법이 없었다. 승인 케이스만 구현하고 거절 케이스를 빠뜨린, 분기 하나가 통째로 없던 경우.

**조치**
승인 알림과 동일한 패턴(`notificationService.createNotification()` 재사용)으로 `REJECTED` 분기를 추가했다. 정원 처리(`addParticipant`)는 승인 시에만 필요한 로직이라 그대로 두고, 알림 발송만 추가했다. (커밋 [`8ab73e2`](https://github.com/ParkChaeHun97/moim_moim/commit/8ab73e2))

**결과**
`updateParticipationStatus`에 대한 테스트를 보강했다: 승인 시 "승인" 문구가 포함된 알림이 발송되는지 검증을 추가하고, `APPLIED→REJECTED`, `WAITING→REJECTED` 두 케이스 모두 거절 알림이 발송되면서 참여자 수(정원 카운트)는 변하지 않는지 확인하는 테스트를 추가했다.

---

### 엉뚱한 파라미터-값 불일치로 조회하던 실수

**문제**
`findByIdForUpdate(meetingPostId)`처럼 파라미터 이름과 실제 넘긴 값의 의미가 다른 코드를 작성했다. Long 타입끼리라 컴파일러가 잡아내지 못하는 실수였다.

**조치**
참가 취소 기능의 동시성 테스트(`ParticipationConcurrencyTest`)를 짜는 과정에서 발견해 바로잡았다. (커밋 [`1d95770`](https://github.com/ParkChaeHun97/moim_moim/commit/1d95770))

**결과**
타입 시스템이 못 잡는 실수는 결국 테스트로 검증해야 한다는 걸 다시 확인했다.

---

### JPQL에서 연관 엔티티와 ID를 직접 비교

**문제**
`WHERE p.member = :memberId`처럼 엔티티 필드와 Long 값을 비교하는 JPQL을 작성해 `InvalidDataAccessApiUsageException`이 발생했다.

**조치**
`p.member.id = :memberId`로 명시적으로 id 필드까지 접근하도록 수정했다. (커밋 [`1d95770`](https://github.com/ParkChaeHun97/moim_moim/commit/1d95770))

**결과**
JPQL에서 연관 엔티티는 필드 자체가 아니라 식별자까지 명시해서 비교해야 한다는 걸 확인했다.

---

### 테스트 setUp()의 중복 저장으로 데이터 정합성 붕괴

**문제**
동일 엔티티를 반복문 안에서 두 번 저장하는 실수로 테스트 데이터 개수가 의도한 것의 2배가 되었고, 이로 인해 무관해 보이는 다른 테스트까지 함께 실패했다.

**조치**
`setUp()`의 저장 로직을 점검해 중복 저장 지점을 제거했다. (커밋 [`1d95770`](https://github.com/ParkChaeHun97/moim_moim/commit/1d95770))

**결과**
동시성 테스트처럼 정확한 데이터 상태를 전제로 하는 테스트는, 증상이 엉뚱한 곳에서 나타나더라도 `setUp()` 자체의 정합성을 먼저 의심해야 한다는 교훈을 얻었다.

---

### 참가 취소 후 프론트 화면이 갱신되지 않던 문제

**문제**
취소 API는 성공하는데 마이페이지 카드가 새로고침 전까지 사라지지 않았다.

**조치**
"안 사라진다"는 현상만 보고 프론트 코드부터 의심했지만, 실제 원인은 백엔드 조회 쿼리(`findAllAppliedByMemberId`)가 `Participation` 상태와 무관하게 전체 이력을 반환하고 있었던 것이었다. 다만 이건 버그가 아니라, 참가 취소를 삭제 대신 상태 전이(`CANCELLED`)로 처리하기로 한 [ADR-006](./docs/adr) 결정의 취지(이력 보존)와 맞는 동작이라 판단해 백엔드 쿼리는 그대로 두기로 했다. 대신 프론트에서 즉시 제거(filter) 대신 상태만 갱신(map)하는 방식으로 로컬 state를 바꾸고, `CANCELLED` 상태에 대한 뱃지("🚫 취소함")를 추가하고 취소 버튼은 `APPLIED`/`ACCEPTED` 상태일 때만 노출되도록 조건부 처리했다. (커밋 [`ad6d447`](https://github.com/ParkChaeHun97/moim_moim/commit/ad6d447))

**결과**
증상의 위치와 원인의 위치가 다를 수 있다는 걸 다시 확인했고, 원인이 백엔드에 있다고 해서 항상 백엔드를 고쳐야 하는 건 아니라는 것도 함께 판단했다.