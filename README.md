# 🚀 MoimMoim (모임모임)
> **"누구나 쉽게 만들고 참여하는 커뮤니티 모임 플랫폼"**

모임 만드는 것도, 참여 신청 결과를 기다리는 것도 답답하지 않게. MoimMoim은 참여 프로세스를 단순화하고, SSE 실시간 알림으로 승인/거절 결과를 기다림 없이 바로 받아볼 수 있게 만든 커뮤니티 플랫폼입니다.


---

## 🛠️ Tech Stack
### **Backend**
* **Framework**: Java 17, Spring Boot 3.4.x
* **Database**: MySQL 8.0, Flyway (스키마 버전 관리, V1~V6)
* **Cache/Store**: Redis (JWT Refresh Token 저장 및 블랙리스트 관리)
* **ORM**: Spring Data JPA
* **Security**: Spring Security, **JWT** (Stateless 인증)
* **Communication**: **SSE (Server-Sent Events)**

### **Frontend**
* **Library**: React, Vite
* **Communication**: Axios, EventSource

### **Infra**
* Docker Compose (local / prod 분리), Nginx 리버스 프록시
* 홈서버(GMKtec mini PC, Ubuntu) 자체 배포, GitHub Actions CI/CD

---

## 📂 Project Structure
역할에 따른 계층 분리를 통해 유지보수성과 확장성을 고려하여 설계되었습니다.

```text
com.example.backend/
├── common/             # 글로벌 공통 모듈
│   ├── config/         # App, Swagger, Redis 등 각종 설정
│   ├── exception/      # 전역 예외 처리 (GlobalExceptionHandler)
│   └── security/       # JWT Provider 및 시큐리티 필터
├── controller/         # API 엔드포인트 레이어
├── dto/                # 요청/응답 데이터 전송 객체
├── entity/             # JPA 엔티티 도메인 모델
│   ├── Member, MeetingPost, Participation
│   └── Notification, Category, Region, BaseTimeEntity
├── enums/              # 상태 및 타입 관리를 위한 Enum 모음
├── repository/         # DB 접근을 위한 Spring Data JPA 인터페이스
└── service/            # 핵심 비즈니스 로직 및 외부 연동 (SSE 등)
```
---

## 📌 핵심 MVP 기능 (Current Status)

### 1. 실시간 알림 시스템 (SSE)
* 사용자의 참여 신청 및 방장의 승인/거절 상태를 **SSE(Server-Sent Events)**를 통해 실시간으로 전달합니다.
* 커스텀 이벤트(`newNotification`)를 정의하여 데이터 전송의 명확성을 확보했습니다.

### 2. 참여 신청 및 승인 프로세스
* 방장(작성자)은 모임 생성 시 자동으로 참여자로 등록되며, 일반 유저의 신청에 대한 승인/거절 권한을 가집니다.
* **Query Optimization**: 참여 내역 조회 시 방장 본인을 제외하는 필터링을 통해 UI상의 중복 데이터를 제거했습니다.

### 3. JWT 기반 인증 시스템
* `JwtAuthenticationFilter`를 통해 무상태(Stateless) 기반의 보안을 구축했습니다.
* 회원가입, 로그인, 로그아웃 전반의 인증 프로세스를 처리합니다.

---

## 🔬 리팩토링 기록

측정 없이 "개선했다"고 말하지 않기 위해, 각 리팩토링을 **문제 정의 → 측정 → 조치 → 결과** 순으로 기록합니다.
👉 [`docs/analysis.md`](./docs/analysis.md)

주요 기술적 의사결정은 ADR(Architecture Decision Record)로 별도 기록합니다.
👉 [`docs/adr/`](./docs/adr/)

---

## 💡 Trouble Shooting
* **SSE 이벤트 리스너 미작동**: 기본 `message` 이벤트가 아닌 커스텀 네이밍을 사용할 때, 송수신 측의 이벤트 이름 불일치 문제를 해결하여 실시간 통신을 성공시켰습니다.
* **정적 리소스 예외**: 로그아웃 엔드포인트 부재로 인한 `NoResourceFoundException`을 확인하고, API 매핑 및 시큐리티 설정을 통해 정상화했습니다.
* **참여 목록 데이터 중복**: JPQL에 작성자 제외 조건을 추가하여 비즈니스 로직과 UI 출력 간의 괴리를 해결했습니다.
* **CI/CD SSH 타임아웃**: 홈서버가 SSH를 22번이 아닌 별도 포트로 노출하고 있었던 게 원인. GitHub Actions 배포 스텝에 포트 시크릿을 명시해 해결했습니다.

---

## 📅 Roadmap — 측정 기반 리팩토링 5단계

`docs/analysis.md`에 각 단계별 문제→측정→조치→결과를 기록하며 진행합니다.

- [x] **Phase 1. N+1 문제 해결** — fetch join 분리 적용, 쿼리 12→1개 (완료)
- [x] **Phase 2. 비관적 락 (Pessimistic Locking)** — 모임 참여 동시성 제어, `PESSIMISTIC_WRITE` + "글 락 → 참여 처리" 순서로 데드락 방지, `ExecutorService`+`CountDownLatch` 기반 동시성 테스트 (완료)
- [ ] **Phase 3. Redis 캐싱** — `getAllMeetings()`에 `@Cacheable` 적용 (TTL 30s~1min), 변경 시 `@CacheEvict`
- [ ] **Phase 4. 조회수 Redis INCR** — viewCount 원자적 증가 처리 (선택)
- [ ] **Phase 5. k6 부하 테스트** — 로컬 PC에서 운영 서버(moimmoim.co.kr) 대상 3개 시나리오로 TPS/p95/에러율/DB 정합성 측정
