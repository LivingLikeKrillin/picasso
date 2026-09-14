# Boston Dynamics Orbit — 벤더 API 사양 분석 및 측정 노트 (Vendor Measurement Note)

- **근거 등급**: `VENDOR_PRIMARY` (공식 1차 자료 기반)
- **측정 대상**: Orbit Web API 공식 게시본 (`openapi: "3.0.1"`, `info.version: "5.0.0"`, `servers: [{ url: "/api/v0" }]`) 및 `boston-dynamics/spot-sdk` 공식 기술 문서 (`docs/concepts/orbit/{about_orbit,orbit_api}.md`)
- **취득 일시**: 2026-09-08T18:02Z (`https://dev.bostondynamics.com/docs/orbit/docs`)
- **아티팩트 해시**: 원본 페이지 `sha256=7563e16e836f3c7829e5258f45ef0e0a2a484b8a93bce8f2bed0ed3a2e086e80`, 추출된 OpenAPI 객체 `sha256=78fd2f2d58bfb2a41e417466860963d32bf19a02f44fdb37306e4bcb056b2a8b` (128,868 B)

저장소 내 벤더 원문 바이너리 배제 원칙에 따라 심볼 명칭과 해시값만을 기록 관리합니다.

> **2026-09-10 매니페스트 확정**: 원본 데이터 재검증(페이지 해시 `7563e16e…`, 클라이언트 해시 `19c7f980…`)을 거쳐 **총 323개 공식 심볼**을 추출하고 `adapter-boston-dynamics-orbit/src/test/resources/vendor-manifest.txt`에 체크인했습니다. 추출 도구는 `tools/vendor-manifest/openapi_symbols.py`이며, 남쪽 포트 인용의 정합성은 `OrbitVendorSurfaceTest`를 통해 검증됩니다. 기계 판독용 데이터는 `profile/vendors/orbit.json`에 정의되어 있습니다.
>
> **공식 스펙 외 엔드포인트 실측 (9종)**: 공식 파이썬 클라이언트(`bosdyn-orbit`)가 호출하는 40개 경로 중 다음 9개 경로는 공식 게시 OpenAPI 스펙에 누락되어 있음이 확인되었습니다:
> - 즉시 파견: `calendar/mission/dispatch/{nickname}`
> - 기체 이송: `graph/send-robot`
> - 세션 제어: `robot-session/{nickname}/session`
> - 사이트 워크 관리: `site_walks/archive`, `site_walks/export_as_walk`, `site_walks/import_from_walk`
> - 시스템 및 백업: `settings/system-time`, `version`, `backup_tasks/{id}`
>
> **주요 데이터 스키마 발견 사항**:
> 1. `Robot` 스키마에 하드웨어 일련번호 필드가 부재합니다 (hostname, nickname, robotIndex, username만 제공). 기체 일련번호는 미션 실행 결과인 `Run.robotSerial`에만 포함되어 있어, 신규 발견 기체의 등록 시 당사 필수 원장 스키마(`robot.serial_number`)와 불일치가 발생합니다.
> 2. `Run.missionStatus`가 열거형(Enum)이 아닌 자유 문자열(String)로 정의되어 있습니다. Spot 직결 시 정밀한 `MissionStatus` 열거값을 수신하는 것과 대비되며, 관제 계층이 추가됨에 따라 상태 어휘의 해상도가 축소됨을 보여줍니다.

---

## 1. 조사 범위 및 유효성 한계 (Survey Scope)

본 분석은 공식 게시본인 5.0.0 버전을 기준으로 수행되었으며, 다음의 구조적 한계를 내포합니다.

### ① 릴리스 버전 지연
- 문서 포털 루트: Spot SDK **5.1.9** 기준
- SDK 릴리스 태그: v5.1.9 ← 5.1.4 ← 5.1.1 ← 5.1.0 ← 5.0.1.2 ← 5.0.1.1 ← 5.0.1 ← **5.0.0**
- Orbit Web API 레퍼런스: **5.0.0**

공식 API 문서는 동일 릴리스 라인 대비 7개 마이너 버전이 지연되어 있으며, 버전별 선택기 없이 단일 버전으로만 제공됩니다. 내부 스키마에서도 `Mission`은 `deprecated: true`로 마킹되었으나 `Schedule.task`는 여전히 `missionId`를 참조하는 등 정합성 지연이 관측됩니다.

### ② 명세의 불완전성
공식 가이드 문서(`about_orbit.md`)에는 다음과 같이 기술되어 있습니다:
> *"When a creation request is made to the **Work Order endpoint in the Orbit API**, Orbit will send an HTTP POST request to a configured external endpoint …"*

그러나 실제 게시된 5.0.0 OpenAPI 스펙에는 'Work Order' 관련 문자열 및 엔드포인트가 전무(0건)합니다. 벤더가 공식 설명한 인터페이스가 게시 스펙에서 누락되어 있습니다.

### ③ 배포 인스턴스 종속성
Orbit API는 현장에 구축된 개별 서버 인스턴스의 `/api/v0`를 통해 제공되므로, 실제 배포된 인스턴스의 기능 표면이 문서 게시본보다 확장되어 있을 가능성이 존재합니다.

---

## 2. API 기능 표면 및 상태 변경 엔드포인트

전체 35개 엔드포인트 중 상태 변경(Write)이 가능한 인터페이스:

| REST 경로 | 지원 HTTP 메서드 | 기능 분류 |
|---|---|---|
| `/calendar/schedule` | GET, **POST** | 스케줄 등록 및 조회 |
| `/calendar/schedule/{eventid}` | DELETE | 스케줄 삭제 |
| `/calendar/disable-enable` | POST | 스케줄러 활성화/비활성화 |
| `/site_walks` · `/site_elements` · `/site_docks` | POST | 사이트 순찰 미션 및 도크 저작 |
| `/robots` · `/webhooks` · `/backup_tasks` | GET, POST | 기체 조회/등록, 웹훅 및 백업 관리 |
| `/anomalies` · `/anomalies/{anomalyId}` | PATCH | 이상 감지 결과 수정 |
| `/missions` | **GET 전용** | 미션 조회 (수정 불가, 폐기 예정) |

나머지 22개 엔드포인트는 통계 및 실행 이력 조회(`/runs`, `/run_events`, `/run_captures`, `/run_statistics`, facets 등) 전용입니다.

### 즉시 실행(Dispatch) 경로 분석

게시된 OpenAPI 스펙 상에는 즉시 실행 엔드포인트가 존재하지 않으며 스케줄 등록만이 유일한 작업 인입구로 보였습니다. 그러나 공식 파이썬 클라이언트(`bosdyn-orbit`) 분석 결과 스펙 외 엔드포인트가 확인되었습니다:

```
POST calendar/mission/dispatch/{nickname}?currentDriverId=...
```

- 본 엔드포인트는 `Schedule`과 유사한 페이로드를 수신하되 `schedule.timeMs`를 `1`로 설정하여 '즉시 실행'을 트리거합니다.
- `task.dispatchTarget`에 일회성 `walk` 데이터를 인라인으로 직접 주입할 수 있어, 자재 운반이나 순찰 액션을 동적으로 하달할 수 있습니다.
- 이는 **"SiteWalk는 Autowalk 데이터를 REST 프로토콜로 전송하는 래퍼"**임을 나타냅니다.

```
Schedule 데이터 모델:
  eventMetadata { name, modificationTimeMs, modificationUser }
  agent         { nickname }                      ← 대상 로봇 식별자
  task          { missionId, forceAcquireEstop }  ← 대상 미션 및 비상정지 권한 강제 획득
  schedule      { timeMs, repeatMs, blackouts[] } ← 실행 시각 및 반복 주기
```

스펙에 명시된 바와 같이 Orbit의 스케줄은 **"로봇이 자율 미션을 언제, 얼마나 자주 실행해야 하는가"**를 정의하는 시간 기반 오케스트레이션 모델입니다.

### 결과 어휘의 축소

```
RunEvent.error     { type: "integer" }  ← 에러 코드 (상세 열거형 스펙 부재)
RunEvent.eventType { enum: ["daq", "screenshot"] }
```

에러 코드가 단순 정수형으로 노출되며, 이벤트 유형은 데이터 취득(`daq`)과 스크린샷 2종으로 한정되어 매니퓰레이션 조작 실패에 대한 구체적 진단 어휘가 부재합니다.

---

## 3. 미션 모델의 전환: Mission → SiteWalk

공식 스펙 내 `deprecated: true`로 마킹된 항목 중 대다수가 `Mission` 모델에 집중되어 있습니다.
- `/missions` 계열 엔드포인트 → *"Use SiteWalk instead!"* 안내
- `/login` 엔드포인트 → *"Use `/api_token/authenticate` instead!"* 안내

신규 아키텍처 계층은 **SiteWalk(미션) → SiteElement(액션 단위) → RunEvent(실행 결과)** 구조로 재편되었습니다:
- `SiteWalk`: 자율 로봇 운영을 정의하는 일련의 태스크 시퀀스. `SiteElements`를 순차 실행하며 도킹 스테이션(`SiteDocks`) 선택은 런타임에 로봇이 자율 결정합니다.
- `SiteElement`: 특정 웨이포인트 위치와 결속된 수행 액션 정의.
- `targetFailureBehavior` 및 `actionFailureBehavior`를 통해 미션 저작 시점에 실패 대응 정책을 정적으로 선언합니다.

---

## 4. 작업 지시(Work Order) 모델의 방향성

공식 가이드 문서(`about_orbit.md`)에 기술된 Work Order 연동 방식:
> *"When alerts occur in Orbit, work orders can be created manually from within the Orbit UI or automatically at the time of the alert. … Orbit will send an HTTP POST request to a configured external endpoint … The external system is then responsible for creating the work order in its own system."*

이는 **Orbit 내부에서 발생한 이상 감지 이벤트를 외부 유지보수 시스템(SAP, EAM 등)으로 통보하는 아웃바운드 티켓팅 구조**입니다. 상류 시스템이 로봇에게 세부 작업을 지시하는 인바운드 명령 채널이 아닙니다.

따라서 Orbit의 공개 REST 표면만으로는 상류 비즈니스 시스템이 동적 파라미터를 실어 로봇에게 실시간 일감을 직접 하달할 수 없으며, 중간 연동 계층(Middleman)의 구축이 필수적입니다.

---

## 5. Atlas 및 Stretch 기종의 Orbit 수용성 분석 (`INFERRED`)

- **분석 등급**: `INFERRED` (공식 공개 자료로부터의 논리적 추론)
1. **공개 SDK의 기종 한계**: 현재 공식 개발자 포털(`dev.bostondynamics.com`)에서 공개 제공하는 SDK는 Spot 1종에 한정됩니다.
2. **마케팅 및 제품 발표**: 공식 블로그는 Orbit을 통해 Atlas를 WMS/MES에 통합하는 워크플로 연동을 발표한 바 있습니다.
3. **Orbit 신원 스키마의 기종 중립성**: Orbit OpenAPI 스펙 전체에서 `Atlas`, `Stretch`, `quadruped`, `model` 명칭은 전무(0건)하며, `Robot` 스키마는 기종 구분 없이 `{ robotIndex, hostname, nickname, username }`만으로 정의되어 있습니다.

**결론**: Atlas 및 Stretch 기종은 별도의 공개 저수준 SDK 없이 Orbit 플릿 플랫폼을 통해 상위 시스템에 연동되는 구조로 설계되어 있을 가능성이 높습니다.

### 아키텍처적 시사점
- **직결 제어의 이점 부재**: Spot과 같이 gRPC로 직결하여 정밀한 상태 피드백을 수신하는 구조가 불가능하며, Orbit의 축소된 결과 어휘(정수형 에러)와 스케줄 파견 방식을 공유하게 됩니다.
- **플릿 어댑터 모델**: 단일 어댑터가 N대의 로봇을 관리하는 1:N 플릿 어댑터 구조가 요구됩니다.
- **용량 한계**: 단일 Orbit 인스턴스당 관제 가능한 기체 수는 통상 32대 수준(`robotIndex`)으로 제한됩니다.

---

## 6. 미결 과제 (Known Open Issues)

1. **거리 재측정**: `vendor_layer`를 `MissionService`에서 `SiteWalk` 기준으로 재평가
2. **결함 결과 어휘 정준화**: `Fault.error_type`의 정수 코드 표준화
3. **Work Order 실제 사양 파악**: 배포 인스턴스 기반 실제 페이로드 스펙 검증
4. **인스턴스 API 전수 검증**: 실제 구동 중인 Orbit 인스턴스와의 라이브 연동 확인
5. **Atlas/Stretch 연동 채널 확인**: 비공개 전용 SDK 존재 여부 검증 (확인 전까지 가설 유지)

> 마지막 대조: 2026-09-15 · sha256:79e5b7bd9b80 · 열림: C-3
