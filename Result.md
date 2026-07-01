# 작업 결과 요약

마인크래프트 로비 서버 + **DataGSM OAuth 인증** 시스템을 Velocity 프록시 네트워크 기반으로 구현했습니다.
멀티모듈 Gradle (Kotlin DSL) 프로젝트이며 **Java 25** 툴체인으로 전체 빌드(`./gradlew build`)가 성공했고,
인증 서버 REST API는 실제 구동하여 동작을 확인했습니다.

---

## 1. 모듈 구성

| 모듈 | 기술 | 산출물 | 상태 |
|------|------|--------|------|
| `common` | Java | 공용 DTO·통신 포맷 (`StudentData`, `AuthMessage`, REST DTO, 채널 상수) | ✅ 컴파일 완료 |
| `auth-server` | Kotlin · Ktor · SQLite · **DataGSM 공식 SDK** | `auth-server-all.jar` (독립 웹 서버) | ✅ **구동·테스트 완료** |
| `velocity-plugin` | Java · Velocity API | `velocity-plugin.jar` (프록시 플러그인) | ✅ shaded jar + 디스크립터 자동 생성 |
| `lobby-server` | Java · Minestom | `lobby-server-all.jar` (독립 서버) | ✅ shaded jar |
| `content-lib` | Java · Paper API | `content-lib.jar` → **SmpAuth** Paper 플러그인 | ✅ shaded jar |
| `sample-content-plugin` | Java · Paper API | 컨텐츠 서버 예제 플러그인 | ✅ 컴파일 완료 |

---

## 2. 인증 흐름

1. 플레이어가 로비에서 `/login` 입력 → 인증 서버 URL 표시 (클릭 가능)
2. 브라우저에서 DataGSM OAuth 로그인 → 인증 서버가 학생 정보 조회
3. 인증 서버가 **8자리 키** 발급 (5분 유효, 1회용, 혼동되는 문자 제외)
4. 플레이어가 로비에서 `/verify <키>` 입력 → 인증 서버가 `uuid → 학생정보` 영구 저장 후 반환
5. 로비가 Velocity에 플러그인 메시징으로 통보 → Velocity가 전역 인증 상태 보관
6. 컨텐츠 서버 접속 시 `SmpAuth.get(player)` 로 인증 정보 조회

**게이팅:** 로비는 항상 접속 가능하지만, 인증을 완료하기 전까지 Velocity가 **다른 모든 컨텐츠 서버로의 이동을 차단**합니다.

---

## 3. 핵심 설계 결정

- **신원 모델:** 온라인 모드 (Mojang UUID가 기준), OAuth는 학생 정보로 **보강(enrich)**
- **영속성:** `uuid → 학생정보`를 SQLite에 영구 저장, **최초 인증 시 스냅샷 1회 저장 후 갱신 안 함**
- **런타임 권한:** **Velocity 중심** — Velocity가 접속 시 UUID로 인증 서버 조회 후 상태 보관, 컨텐츠 서버는 HTTP를 직접 다루지 않음
- **전달 방식:** **접속 시 Pull** — 컨텐츠 서버가 접속 시 Velocity에 요청, Velocity가 응답 (경합 없음)
- **서버 간 보안:** 정적 공유 시크릿(Bearer 헤더)
- **통신 포맷:** JSON (Gson)

---

## 4. 실제 동작 검증 (인증 서버)

| 테스트 | 결과 |
|--------|------|
| 인증 헤더 없이 호출 | `401` |
| 공유 시크릿으로 호출 (미연동) | `200 {"linked":false}` |
| 잘못된 키로 bind | `410 invalid_or_expired_key` |
| `/login` 접속 | `302` → DataGSM authorize 엔드포인트 (PKCE S256, scope `self:read`) |

→ DataGSM 공식 SDK가 PKCE 인증 URL을 정상적으로 생성함을 확인했습니다.
(실제 OAuth 완주는 발급받은 클라이언트 자격증명 필요)

---

## 5. 구현 중 주요 결정/변경 사항

- **Kotlin 2.3.21** 사용 — JVM 타깃 25를 지원하기 위함 (2.2.x는 24까지만 지원)
- **content-lib을 독립 Paper 플러그인(`SmpAuth`)으로 구현** — 각 컨텐츠 플러그인에 shade하는 대신
  `depend: [SmpAuth]`로 의존. `StudentData`/`AuthMessage`의 단일 런타임 소유자가 되어 클래스로더 충돌 방지
- **인증 서버는 Exposed 대신 순수 JDBC** 사용 — Exposed 1.x API 변동 회피. `links` 테이블에
  전체 `StudentData` JSON 스냅샷 + 조회용 컬럼 일부 저장
- **DataGSM SDK 실제 API 확인** — 패키지 `team.themoment.datagsm.sdk.oauth`,
  `DataGsmOAuthClient.builder(id, secret).build()`, `createAuthorizationUrl(redirectUri).enablePkce()` +
  `getCodeVerifier()` → `exchangeCodeForToken(code, redirectUri, verifier)`
- **Minestom Velocity 포워딩** — `MinecraftServer.init(new Auth.Velocity(secret))` 방식
  (구버전 `extras.velocity.VelocityProxy`는 `2026.06.20-26.1.2`에서 제거됨)

---

## 6. 산출 문서

- `README.md` — 빌드/실행 가이드 (4개 런타임)
- `SPEC.md` — 전체 설계 명세 + 구현 노트(§11a)
- `docs/CONTENT-SERVER-GUIDE.md` — **컨텐츠 서버 개발자용 사용 가이드** (요구 산출물)

---

## 7. 실제 배포 전 준비 사항

- DataGSM 클라이언트 등록 (https://www.datagsm.kr/clients) → `clientId`/`clientSecret`,
  redirect URI = `<publicBaseUrl>/callback`
- 인증 서버·로비·Velocity가 공유하는 **공유 시크릿** 1개
- Velocity **모던 포워딩 시크릿** (로비와 공유)
- 설정 템플릿 제공됨: `config.properties.example`, 최초 실행 시 설정 파일 자동 생성
