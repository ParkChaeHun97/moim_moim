# ADR-002: 실시간 알림에 SSE 채택 (WebSocket 대신)

## Status
Accepted

## Context
모임 참가 신청, 승인/거절 등 상태 변화를 사용자에게 실시간으로 알려야 했다.
후보는 두 가지였다:
- **WebSocket**: 양방향 통신, 별도 프로토콜(ws://), 연결 관리·핸드셰이크 로직 필요
- **SSE(Server-Sent Events)**: 서버→클라이언트 단방향, 일반 HTTP 기반, 브라우저 EventSource API로 클라이언트 구현 간단

moim_moim의 알림 요구사항은 서버 → 클라이언트 단방향 푸시만 필요했고, 클라이언트가 서버로 실시간 스트림을 보낼 필요는 없었다.

## Decision
SSE를 채택한다.

- 알림은 서버에서 클라이언트로만 흐르면 되므로 WebSocket의 양방향 채널은 불필요한 복잡도였다.
- HTTP 기반이라 기존 Spring MVC/Nginx 인프라를 그대로 활용할 수 있고, 별도의 WebSocket 핸들러·프로토콜 업그레이드 로직이 필요 없다.
- 클라이언트 구현이 EventSource 표준 API 하나로 끝나 단순하다.

## 실제 구현

**서버 (`SseService`, `SseController`)**
- Spring MVC의 `org.springframework.web.servlet.mvc.method.annotation.SseEmitter`를 사용한다.
- 연결은 `Map<Long, SseEmitter>` (`ConcurrentHashMap`)로 애플리케이션 메모리에 저장한다. memberId 1개당 연결 1개만 유지하며, 같은 사용자가 재구독하면 기존 emitter를 `complete()` 후 교체한다.
- 타임아웃은 `60L * 1000 * 60`(1시간)으로 고정되어 있다. `onTimeout`/`onCompletion`/`onError` 리스너에서 맵에서 제거하는 정리(clean-up)를 수행한다.
- 최초 구독 시 503 에러 방지를 위해 `"connect"` 이벤트로 더미 데이터를 즉시 전송한다.
- 이벤트 전송 시 SSE 표준 `retry` 필드(`reconnectTime`)를 3초로 명시해, 클라이언트 JS 재연결 로직이 어떤 이유로 동작하지 않는 극단적 상황에서도 브라우저 기본 재시도 지연이 합리적인 값을 갖도록 방어선을 둔다.
- **[2026-08-17 수정 완료]** 알림 전송(`send`) 중 `IOException` 발생 시 예외를 던지지 않고 WARN 로그만 남기도록 변경했다(best-effort). 이전에는 `CustomException(SSE_SEND_ERROR)`를 던져 `NotificationService.createNotification()` → `ParticipationService`의 `@Transactional` 메서드(`applyForMeeting`, `updateParticipationStatus`) 호출 스택을 통해 예외가 그대로 전파되어, **SSE 전송 실패가 참여 신청/승인 처리 트랜잭션 자체를 롤백시키는 버그**가 있었다. 이제 SSE 전송 실패는 알림 저장이나 참여 처리 트랜잭션에 어떤 영향도 주지 않는다. (커밋 `4837933`, 검증: `ParticipationSseFailureTest`)

**클라이언트 (`frontend/src/components/common/Header.jsx`)**
- `EventSource`로 `/api/subscribe?token=...`를 구독한다.
- **[2026-08-17 수정 완료]** 이전에는 `onerror`에서 `eventSource.close()`만 호출하고 끝나, 브라우저 기본 자동 재연결까지 차단한 채 재구독 시도가 전혀 없었다. 지금은 `onerror`에서 `close()` 후 `localStorage`의 **최신** access token으로 지수 백오프(1s→2s→4s→...→최대 30s, 연결 성공 시 카운터 리셋)를 두고 직접 재연결을 예약한다. 브라우저 네이티브 재연결에만 맡기지 않는 이유: 네이티브 재연결은 최초 연결 시점의(만료됐을 수 있는) 토큰을 그대로 재사용해 결국 401로 영구 실패하기 때문. 로그아웃/언마운트 시에는 예약된 재연결 타이머를 정리해 좀비 재시도를 방지한다. (커밋 `5b756ab`)
- 프론트엔드에는 테스트 프레임워크가 없어 이 부분은 자동화 테스트 대신 `eslint` + `vite build` 통과로만 검증했다.

**알림 종류 (실제 구현된 것만)**
- `ParticipationService.applyForMeeting()`: 참여 신청 도착 시 → 모임장에게 알림 (`"[제목] 모임에 새로운 참여 신청이 도착했습니다! 📩"`)
- `ParticipationService.updateParticipationStatus()`: 상태가 `ACCEPTED`로 바뀔 때 → 신청자에게 승인 알림 (`"[제목] 모임 참여가 승인되었습니다! 🎉"`)
- **[2026-08-17 수정 완료]** 상태가 `REJECTED`로 바뀔 때 → 신청자에게 거절 알림 (`"[제목] 모임 참여 신청이 거절되었습니다 😢"`). 이전에는 `ParticipationStatus.REJECTED` enum 값은 존재했지만 알림 생성 분기가 없어 신청자가 거절 사실을 알 방법이 없었다. 승인 알림과 동일한 패턴을 재사용해 구현. (커밋 `8ab73e2`, 검증: `ParticipationServiceTest`)

## Consequences
**장점**
- 구현 복잡도가 낮아 빠르게 적용할 수 있었다.
- 기존 HTTP 인프라(Nginx 리버스 프록시 등)와 호환된다.

**단점 / 제약**
- SSE 연결은 애플리케이션 서버 인스턴스의 메모리(`ConcurrentHashMap`)에 저장된다. 이 저장소가 프로세스 로컬이므로 **인스턴스가 2개 이상이면 A 인스턴스에 연결된 사용자는 B 인스턴스에서 발생한 알림을 받을 수 없다.** 별도의 메시지 브로커(Redis Pub/Sub, Kafka 등)로 emitter 상태를 공유하는 기능은 코드에 없으며(grep 결과 Redis pub/sub 관련 코드 전무), 현재도 **미구현** 상태다 (이번 수정 범위 밖 — 수평 확장 시 별도 작업 필요).
- 클라이언트 → 서버 실시간 통신이 필요한 기능이 추후 추가되면 WebSocket으로 전환하거나 병행해야 한다.

## 검증 필요 항목
- [x] 실제 사용 중인 SSE 구현 방식: Spring `SseEmitter`, `ConcurrentHashMap<Long, SseEmitter>` 기반 인메모리 저장 (`SseService.java`)
- [x] 타임아웃/재연결 처리 로직 존재 여부: 서버 타임아웃 1시간 고정 + `reconnectTime` 힌트, 클라이언트는 지수 백오프로 최신 토큰을 사용해 재연결 (2026-08-17 수정 완료)
- [x] 알림 종류: 참여 신청 도착(모임장 대상), 참여 승인(신청자 대상), 참여 거절(신청자 대상) — 3종 구현 (거절 알림 2026-08-17 추가)
- [x] SSE 전송 실패 시 참여 신청/승인 트랜잭션이 함께 실패하던 문제 — 방치된 버그로 확인, best-effort 전송으로 수정 완료 (2026-08-17)
- [x] 클라이언트가 `onerror`에서 재연결을 시도하지 않던 이유 — 미완성으로 확인(재연결을 의도적으로 막을 이유가 없었음), 지수 백오프 재연결 구현으로 해결
- [ ] **확인 필요 (사용자 답변 요청)**: SSE의 단일 인스턴스 제약(수평 확장 미지원)을 언제, 어떤 트래픽 규모에서 해소할 계획인지 — 이번 수정 범위 밖으로 남겨둠
