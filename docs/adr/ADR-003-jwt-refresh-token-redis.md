# ADR-003: 인증 구조 — JWT + Refresh Token, Redis 저장

## Status
Accepted

## Context
사용자 인증을 어떻게 유지할지 결정이 필요했다. 후보는:
- **세션 기반 인증**: 서버가 세션 상태를 직접 관리, 확장 시 세션 클러스터링 필요
- **JWT만 사용 (Access Token만)**: 무상태(stateless)라 확장에 유리하지만, 토큰 탈취/로그아웃 시 즉시 무효화가 어려움
- **JWT + Refresh Token**: Access Token은 짧은 만료시간으로 발급하고, Refresh Token으로 재발급. Refresh Token을 서버 측에 저장해 무효화(로그아웃, 탈취 대응) 가능

## Decision
JWT(Access Token) + Refresh Token 구조를 채택하고, Refresh Token은 **Redis에 저장**한다.

- Access Token은 짧은 만료 시간으로 발급해 탈취 시 피해 범위를 제한한다.
- Refresh Token은 Redis에 저장하여 로그아웃 시 즉시 블랙리스트 처리할 수 있도록 한다. (JWT 자체는 무상태이므로 서버 저장소 없이는 강제 무효화가 불가능하다는 한계를 보완)
- 세션 기반 대비 서버가 세션 상태를 직접 들고 있지 않아, 이후 인스턴스 확장에도 유리하다.

## 실제 구현

**만료 시간 (`JwtTokenProvider.java`)**
- Access Token: `1000L * 60 * 60` = **1시간**
- Refresh Token: `1000L * 60 * 60 * 24 * 7` = **7일**
- 두 값 모두 코드에 하드코딩되어 있고(`private final long`), `application.yml` 등 외부 설정으로 분리되어 있지 않다.

**Redis 키 구조 (`RedisTokenRepository.java`)**
- Refresh Token: 키 `RT:{email}`, 값은 refresh token 문자열 그대로. TTL은 `refreshTokenExpirationTime`(7일)만큼 설정.
- 로그아웃 블랙리스트: 키 `BL:{accessToken 전체 문자열}`, 값은 `"logout"` 문자열. TTL은 해당 access token의 **남은 유효시간**(`JwtTokenProvider.getExpiration()`)만큼만 설정 — 즉 access token이 어차피 만료될 시점까지만 블랙리스트에 남아있고, 그 이후엔 Redis에서 자동 삭제된다.
- 키는 이메일/토큰 값 기준이며, memberId 기준이 아니다.

**토큰 재발급(reissue) 흐름 (`AuthController.reissue`, `AuthService.reissue`)**
1. Refresh Token은 응답 바디가 아니라 **httpOnly 쿠키**(`refreshToken`)로 클라이언트에 전달되고, `/api/auth/reissue`는 `@CookieValue`로 쿠키에서 읽는다.
2. `jwtTokenProvider.validateTokenOrThrow()`로 서명/만료 검증.
3. 토큰에서 이메일 추출 후 `tokenRepository.validateRefreshToken(email, refreshToken)`으로 Redis에 저장된 값과 일치하는지 확인. **불일치 시(탈취 의심) Redis에 저장된 토큰을 즉시 삭제하고 `EXPIRED_TOKEN` 예외를 던진다** — 토큰 재사용 탐지(rotation 시 탈취된 구 토큰 재사용 방어) 성격의 로직이다.
4. 새 Access/Refresh Token 세트를 생성하고, Redis의 `RT:{email}` 값을 새 Refresh Token으로 덮어쓴다(= Refresh Token Rotation).
5. 새 Refresh Token을 다시 httpOnly 쿠키로 세팅해 응답.

**로그아웃 흐름 (`AuthService.logout`)**
- Access Token(Authorization 헤더)에서 이메일과 남은 만료시간을 추출해, `RT:{email}` 삭제 + `BL:{accessToken}` 등록을 수행. Refresh Token 자체를 블랙리스트에 넣는 게 아니라 **삭제**하고, Access Token은 **블랙리스트**에 넣는 방식이다.

**요청 시 인증 검증 (`JwtAuthenticationFilter.java`)**
- 매 요청마다 `redisTemplate.opsForValue().get("BL:" + token)`으로 블랙리스트 여부를 확인 — Access Token이 무상태(stateless)라고 설명되지만, 실제로는 **모든 요청에서 Redis 조회 1회가 발생**한다 (ADR 본문의 "매 요청마다 DB 조회가 필요 없다"는 DB 조회는 맞지만 Redis 조회는 매번 발생함).

**리프레시 토큰 쿠키의 `SameSite` 설정**
- **[2026-08-17 수정 완료]** `AuthController.login`/`reissue`에서 `ResponseCookie` 빌더 체이닝 중 `.sameSite("None")` 호출 직후 `.sameSite("Strict")`로 덮어써서, 실제로는 항상 `Strict`만 적용되고 `None` 설정 및 "크로스 도메인/사이트 간 쿠키 전송 허용" 주석은 죽은 코드였다. 프론트엔드가 nginx(운영)/vite(로컬) 프록시를 통해 항상 API와 동일 출처로 통신한다는 실제 배포 구조를 확인한 뒤(ADR-005 참고), 크로스 사이트 쿠키 전송이 애초에 불필요하다는 결론으로 죽은 `None` 설정과 주석을 제거하고 `Strict` 하나로 통일했다. 동작 변화는 없다(원래도 실질적으로 Strict로 동작 중이었음). (커밋 `1324e61`, 검증: `AuthControllerTest`에 로그인/재발급 응답의 `Set-Cookie`가 `SameSite=Strict`로만 설정되는지 확인하는 테스트 추가 — 재발급 API는 기존에 테스트가 없었음)

## Consequences
**장점**
- 로그아웃/토큰 탈취 시 Redis에서 즉시 무효화 가능 (순수 JWT 방식의 약점 보완)
- Refresh Token 검증 시 불일치를 탈취 신호로 보고 즉시 삭제하는 재사용 탐지 로직이 구현되어 있다.
- Access Token의 무상태성은 유지하여 매 요청마다 **DB** 조회가 필요 없다 (단, Redis 조회는 매 요청 발생 — 위 참고).

**단점 / 제약**
- Redis가 인증 흐름의 필수 의존성이 되어, Redis 장애 시 재발급(로그인 유지)뿐 아니라 **로그인 후 모든 API 요청의 인증(블랙리스트 조회)도 영향을 받는다** — Access Token 검증 자체가 Redis 가용성에 의존한다.
- Access Token 만료 1시간, Refresh Token 만료 7일이 코드에 하드코딩되어 있어, 값 변경 시 코드 수정과 재배포가 필요하다 (설정 파일로 분리되어 있지 않음).
- 재발급 정책 등 세부 값은 트래픽 특성에 따라 튜닝이 필요하다.

## 검증 필요 항목
- [x] 실제 Access/Refresh Token 만료 시간 값: Access 1시간, Refresh 7일 (코드 하드코딩)
- [x] 블랙리스트 저장 키 구조: `RT:{email}` (refresh token 값 저장), `BL:{accessToken}` (로그아웃 블랙리스트, 값 `"logout"`)
- [x] 토큰 재발급 API 흐름: 쿠키 기반 RT 전달 → Redis 일치 검증(불일치 시 삭제+예외) → 새 토큰 세트 발급 및 Redis 갱신(Rotation)
- [x] `sameSite` 이중 설정으로 실제로는 `Strict`가 적용되던 부분 — 방치된 죽은 코드로 확인, 동일 출처 배포 구조에 맞춰 `Strict`로 통일하여 수정 완료 (2026-08-17)
- [ ] **확인 필요 (사용자 답변 요청)**: Access 1시간 / Refresh 7일이라는 구체적인 값을 선택한 배경(트래픽/보안 요구사항 등)이 있는지, 아니면 임의로 정한 초기값인지?
- [ ] **확인 필요 (사용자 답변 요청)**: 만료 시간이 코드에 하드코딩된 것이 의도적 선택인지, 추후 설정 파일로 분리할 계획이 있는지? (이번 수정 범위 밖으로 남겨둠)
