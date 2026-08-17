# ADR-005: Nginx 리버스 프록시 및 정적 파일 서빙 분리

## Status
Accepted

## Context
프론트엔드(React SPA)와 백엔드(Spring API)를 같은 도메인(`moimmoim.co.kr`)에서 서빙해야 했고, 다음을 결정해야 했다:
- SPA 빌드 산출물(정적 파일)과 `/api` 요청을 같은 도메인에서 어떻게 서빙·라우팅할지
- HTTPS 종료(SSL termination)를 어디서 처리할지

Spring(Tomcat)이 정적 파일까지 직접 서빙하면:
- 정적 파일 요청도 WAS 스레드/리소스를 점유해 API 처리 성능에 영향을 줄 수 있다.
- HTTPS 종료, 요청 로깅, 리버스 프록시 등 인프라 레벨 기능을 Spring 코드에서 직접 처리해야 한다.

## Decision
Nginx를 프론트엔드 앞단에 두고, 역할을 분리한다.

- **Nginx**: SPA 빌드 산출물(정적 파일) 서빙, HTTPS 종료, `/api` 요청을 Spring 앱으로 리버스 프록시
- **Spring**: API 요청만 처리
- 정적 파일 요청이 WAS까지 도달하지 않도록 하여, Spring 인스턴스는 API 처리에만 리소스를 집중한다.

## 실제 구현

**nginx.conf (`nginx/conf.d/default.prod.conf`, `default.local.conf`)**
- 운영(`default.prod.conf`): 80번 포트는 `/.well-known/acme-challenge/`(Certbot 도메인 검증용)만 처리하고 나머지는 모두 443으로 301 리다이렉트. 443은 Let's Encrypt 인증서(`/etc/letsencrypt/live/moimmoim.co.kr/`)로 SSL 종료.
  - `location /`: `root /usr/share/nginx/html`, `try_files $uri $uri/ /index.html` — **프론트엔드 SPA(React) 빌드 결과물**을 서빙.
  - `location /api`: `proxy_pass http://moim-backend:8080`으로 백엔드 프록시. `proxy_buffering off`, `proxy_read_timeout`/`proxy_send_timeout 3600s`, `Connection: keep-alive` 등 **SSE(ADR-002) 연결을 끊기지 않게 하기 위한 설정**이 명시적으로 되어 있다(주석에도 "SSE 지원" 명시).
- 로컬(`default.local.conf`): 구조는 유사하나 SSL 없이 80 포트만 사용, `backend:8080`으로 프록시(서비스명이 prod와 다름), `proxy_cache off` 등 SSE용 설정 추가.
- **정적 파일(프론트엔드 빌드 산출물)은 bind mount가 아니라 Nginx 이미지 빌드 시점에 포함된다.** `nginx/Dockerfile_prod`가 멀티스테이지로 `frontend`를 `npm run build`한 뒤 `COPY --from=build /app/dist /usr/share/nginx/html`로 이미지에 굽는다. 즉 "정적 파일 서빙 분리"는 되어 있지만, 그 정적 파일은 이미지 자체에 포함되어 배포되며 런타임에 Spring과 공유되는 디렉토리가 아니다.

**HTTPS 인증서 관리 — Let's Encrypt + Certbot 컨테이너**
- `docker-compose.prod.yml`의 `certbot` 서비스가 `certbot/certbot` 이미지로 `certbot renew --webroot -w /var/www/certbot`를 12시간 주기 셸 루프(`while :; do ...; sleep 12h; done`)로 반복 실행해 갱신한다.
- `nginx`와 `certbot` 컨테이너가 `./certbot/conf`(인증서), `./certbot/www`(webroot 검증용)를 동일 경로로 bind mount하여 공유한다 — **ADR-004/005에서 실제로 확인되는 유일한 애플리케이션 레벨 bind mount는 이 인증서 관련 볼륨이다.**
- 최초 인증서 발급(`certbot certonly` 등) 명령이 compose 파일이나 스크립트에 보이지 않는다 — 최초 발급은 수동으로 수행했을 것으로 추정된다 (확인 필요).

## Consequences
**장점**
- 정적 파일(프론트엔드 빌드 산출물) 서빙이 Nginx로 분리되어 WAS(Tomcat) 스레드가 API 처리에 집중된다.
- SSL, 리버스 프록시 등 인프라 관심사가 애플리케이션 코드에서 분리된다.
- SSE 연결 유지를 위한 프록시 설정(버퍼링/캐시 비활성화, 긴 타임아웃)이 명시적으로 되어 있어 ADR-002의 SSE 결정과 실제로 정합성이 있다.

**단점 / 제약**
- Nginx 설정이 배포 구성의 일부가 되어, 인프라 변경 시 Nginx 설정도 함께 관리해야 한다.
- 최초 SSL 인증서 발급 절차가 코드/설정에 기록되어 있지 않아, 재현 가능성(reproducibility) 관점에서 문서화가 필요하다.

## 검증 필요 항목
- [x] 실제 nginx.conf 설정: `location /`(SPA 정적 파일), `location /api`(백엔드 프록시, SSE용 설정 포함)
- [x] HTTPS 인증서 관리 방식: Let's Encrypt, `certbot` 컨테이너가 12시간 주기로 자동 갱신, nginx와 `certbot/conf`·`certbot/www` bind mount 공유
- [ ] 확인 필요 : 최초 Let's Encrypt 인증서 발급은 어떤 절차로 진행했는지(수동 `certbot certonly` 등)? 재현 가능하도록 문서화가 필요한지?
