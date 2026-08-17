# ADR-004: Docker Compose 기반 인프라 구성

## Status
Accepted

## Context
서비스를 AWS에서 개인 홈서버(GMKtec)로 마이그레이션하면서, 여러 컴포넌트(Spring 앱, MySQL, Redis, Nginx)를 어떻게 배포·관리할지 결정이 필요했다.
후보는:
- **개별 프로세스로 직접 설치·관리**: 서버에 MySQL, Redis 등을 직접 설치
- **Kubernetes**: 컨테이너 오케스트레이션, 확장성은 뛰어나지만 개인 서버 1대 규모에는 과도한 복잡도
- **Docker Compose**: 단일 서버에서 여러 컨테이너를 정의·관리하기 위한 가벼운 도구

## Decision
Docker Compose를 채택한다.

- 단일 홈서버 환경에서 Spring 앱, MySQL, Redis, Nginx를 컨테이너 단위로 격리한다.
- Kubernetes는 노드 1대 규모의 개인 프로젝트에는 오버엔지니어링이라고 판단했다.
- 각 컨테이너 간 통신은 Docker 내부 네트워크(`moim-network`, bridge)로 처리한다.

## 실제 구현

**파일 구성 — "docker-compose.yml 하나"가 아니라 3개 파일로 분리되어 있다**
- `docker-compose.local.yml`: 로컬 개발용. `db`(MySQL), `redis`만 정의되어 있고, `backend`/`nginx`는 없다 — **의도된 구성**. 로컬에서는 Spring 앱을 docker가 아니라 IntelliJ Run Configuration(`BackendApplication`)에서 직접 구동하며, EnvFile 플러그인(`net.ashald.envfile`)이 저장소 루트의 `.env` 파일을 읽어 환경변수를 주입한다(`.idea/workspace.xml`, `backend/.idea/workspace.xml`의 `RunManager` 설정에서 확인됨. `application.yml`에도 "로컬환경에서는 JWT_SECRET값은 .env 파일에서 읽는다"는 주석이 있음). `docker-compose.local.yml`의 `db`/`redis`도 동일한 `.env` 파일을 `env_file`로 참조하므로, 컨테이너로 뜨는 DB/Redis와 IDE로 구동되는 Spring 앱이 같은 값을 공유한다.
- `docker-compose.prod.yml`: 운영용. `nginx`, `backend`, `db`, `redis`, `certbot` 5개 서비스로 구성된 실제 운영 스택.
- `docker-compose.build.yml`: `backend`, `nginx` 이미지의 build context/Dockerfile 경로와 이미지 태그(`qcogns97/moim-backend:latest`, `qcogns97/moim-nginx:latest`)만 정의하는 빌드 전용 파일. 런타임 설정(포트, 볼륨, 환경변수)은 없다.

**운영 스택 (`docker-compose.prod.yml`) 서비스 구성**
- `nginx`: 이미지 `qcogns97/moim-nginx:latest`, 포트 80/443 게시, `certbot/conf`·`certbot/www`를 bind mount, `backend`에 `depends_on`.
- `backend`: 이미지 `qcogns97/moim-backend:latest`, `.env.prod`로 환경변수 주입, `expose: 8080`(외부 미개방, nginx를 통해서만 접근), `db`/`redis`에 `depends_on`. **볼륨이 전혀 마운트되어 있지 않다.**
- `db`: `mysql:8.0`, `db_data` 네임드 볼륨에 영속화, `.env.prod`로 계정 정보 주입, `utf8mb4` 문자셋 강제.
- `redis`: `redis:7-alpine`, `redis_data` 네임드 볼륨, `--requirepass ${REDIS_PASSWORD}`로 비밀번호 인증, 포트를 `${REDIS_PORT}:6379`로 **호스트에 직접 노출**.
- `certbot`: Let's Encrypt 인증서 자동 갱신 전용 컨테이너. `certbot renew`를 12시간 주기로 반복 실행하는 셸 루프.

**볼륨**
- 실제 bind mount는 `certbot/conf`, `certbot/www` (SSL 인증서 관련) **뿐이다.**
- `backend` 서비스에는 어떤 볼륨도 정의되어 있지 않다.
- 참고로 `nginx` 이미지 자체는 멀티스테이지 `Dockerfile_prod`에서 프론트엔드(React)를 빌드해 `/usr/share/nginx/html`에 `COPY`하는 방식이다. 이것도 bind mount가 아니라 **이미지 빌드 시점에 정적 파일을 이미지에 포함**시키는 방식이다.

**환경변수/시크릿 관리**
- `db`, `backend`, `redis` 모두 `.env.prod` 파일을 `env_file`로 로드한다. `.env.prod`, `.env.local`, `.env`는 모두 `.gitignore`에 등록되어 저장소에 포함되지 않는다. 다만 실제로 쓰이는 파일명은 `.env.local`이 아니라 **`.env`**다 (로컬 IDE Run Configuration과 `docker-compose.local.yml`이 공통으로 참조).
- **로컬**: IntelliJ Run Configuration의 EnvFile 플러그인과 `docker-compose.local.yml`의 `env_file`이 같은 `.env` 파일을 읽는다.
- **운영**: `.env.prod` 파일을 미니PC 홈서버에 사전에 배치해두고, `docker-compose.prod.yml`이 `env_file`로 이를 읽어 `backend`/`db`/`redis` 컨테이너에 주입한다. `.github/workflows/deploy.yml`의 배포 스크립트는 `git pull` 후 `docker compose --env-file .env.prod -f docker-compose.prod.yml up -d`만 실행하며, `.env.prod` 파일 자체를 생성하거나 GitHub Secrets에서 끌어와 쓰지 않는다 — 즉 이 파일은 CI/CD 파이프라인이 아니라 서버에 수동으로 미리 배치되어 있다가 재사용되는 방식이다(코드로 확인됨).
- Redis는 `${REDIS_PASSWORD}`, `${REDIS_PORT}`를 compose 파일에서 변수 치환 형태로 참조한다.

## Consequences
**장점**
- 서버 1대에서 핵심 인프라(nginx/backend/db/redis/certbot)를 재현 가능한 상태로 관리할 수 있다.
- 로컬과 운영은 완전히 동일한 구성은 아니다 — 이는 미완성이 아니라 **의도된 차이**다: 로컬은 DB/Redis만 컨테이너로 띄우고 Spring 앱은 IntelliJ에서 직접 구동하는 반면, 운영은 nginx/backend/db/redis 전체를 컨테이너화한다 (위 참고). DB/Redis 이미지 버전과 환경변수 로딩 방식(`env_file`)은 로컬·운영에서 동일한 패턴을 공유하므로, 데이터 계층의 동일성은 유지된다.

**단점 / 제약**
- 단일 서버이므로 컨테이너 단위 장애 격리는 되지만, 서버 자체(하드웨어) 장애에는 취약하다.
- 다중 서버로 확장 시 Docker Compose만으로는 한계가 있어, 그 시점엔 Kubernetes 등으로 전환이 필요하다.
- `redis` 포트가 호스트에 직접 노출(`${REDIS_PORT}:6379`)되어 있어, 비밀번호 인증에만 의존하는 구조다.

## 검증 필요 항목
- [x] 실제 docker-compose 파일 구성: `local`/`prod`/`build` 3개로 분리, 서비스 목록·볼륨·네트워크는 위 "실제 구현" 참고
- [x] bind mount 정확한 경로: 실제 bind mount는 certbot 인증서 경로(`certbot/conf`, `certbot/www`)뿐
- [x] 환경변수/시크릿 관리 방식: `.env.prod` / `.env` 파일을 `env_file`로 로드, `.gitignore`에 등록되어 저장소에는 포함되지 않음
- [x] `docker-compose.local.yml`에 `backend`/`nginx`가 없는 이유: 로컬에서는 Spring 앱을 docker가 아니라 IntelliJ Run Configuration에서 직접 구동하고, DB/Redis만 컨테이너로 띄우는 **의도된 구성**. IDE의 EnvFile 플러그인이 `.env`를 읽어 환경변수를 주입한다 (`.idea/workspace.xml`, `backend/.idea/workspace.xml`, `application.yml` 주석으로 코드상 확인됨).
- [x] `.env.prod` 파일 서버 배포 방식: 미니PC 홈서버에 `.env.prod` 파일을 사전에 두고, `docker-compose.prod.yml`이 `env_file`로 읽어 `backend`/`db`/`redis` 컨테이너에 주입한다. `.github/workflows/deploy.yml`의 CI/CD는 `git pull` + `docker compose --env-file .env.prod up -d`만 수행할 뿐 이 파일을 생성/전송하지 않으므로, 서버에 수동으로 미리 배치되어 있다가 재사용되는 구조로 코드상 확인됨(CI/CD 시크릿을 통한 자동 생성이 아님).
