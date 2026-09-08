# Boston Dynamics Orbit — 측정 노트

- **근거 등급**: `VENDOR_PRIMARY`
- **측정 대상**: Orbit Web API 게시본 (`openapi: "3.0.1"`, `info.version: "5.0.0"`, `servers: [{ url: "/api/v0" }]`) + `boston-dynamics/spot-sdk` 의 산문 문서 `docs/concepts/orbit/{about_orbit,orbit_api}.md`
- **취득**: 2026-09-08T18:02Z, `https://dev.bostondynamics.com/docs/orbit/docs`
- **해시**: 페이지 `sha256=7563e16e836f3c7829e5258f45ef0e0a2a484b8a93bce8f2bed0ed3a2e086e80` · 추출한 스펙 객체 `sha256=78fd2f2d58bfb2a41e417466860963d32bf19a02f44fdb37306e4bcb056b2a8b` (128,868 B)

**원문은 이 저장소에 안 들인다.** `@VendorSurface` + `vendor-manifest.txt` 와 같은 규율이며, 여기 적는 것은 심볼 이름과 해시뿐이다.

스펙이 JSON 파일로 배포되지 않는다 — Swagger UI 페이지에 **JS 객체 리터럴로 인라인**돼 있어(키에 따옴표가 없어 `json.loads` 가 안 된다) 중괄호 짝을 맞춰 잘라냈다. 재현하려면 페이지에서 `openapi: "3.0.1"` 을 찾아 그것을 감싸는 `{` 부터 짝까지 자르면 된다.

---

## `survey_scope` — 먼저 읽을 것

**본 것은 "게시된 5.0.0 판"이고, 그 판이 불완전하다는 것까지 확인했다.** 아래 판정은 전부 그 범위 안에서만 유효하다.

### ① 최신이 아니다

| | |
|---|---|
| 문서 사이트 루트 | Spot **5.1.9** |
| `spot-sdk` 태그 | v5.1.9 ← 5.1.4 ← 5.1.1 ← 5.1.0 ← 5.0.1.2 ← 5.0.1.1 ← 5.0.1 ← **5.0.0** |
| Orbit API 레퍼런스 `info.version` | **5.0.0** |

같은 버전 라인에서 릴리스 7 개 뒤다. 버전별 URL(`/v5.1.9/docs/orbit/docs`)은 404 로, 선택기 없이 한 판만 게시된다.

스펙 내부에도 뒤처진 흔적이 있다 — `Mission` 이 `deprecated: true` 인데 `Schedule.task` 는 여전히 `missionId` 를 참조한다.

### ② 전부가 아니다 — 이건 증명된다

`about_orbit.md`(master, 5.1.9 라인)가 이렇게 쓴다:

> When a creation request is made to the **Work Order endpoint in the Orbit API**, Orbit will send an HTTP POST request to a configured external endpoint …

**게시된 5.0.0 스펙에 work order 문자열이 0 건이다.** 35 개 경로 어디에도 없다. 벤더가 스스로 문서화한 엔드포인트가 게시 스펙에 없다.

같은 이유로 **웹훅 이벤트 이름은 스펙이 아니라 산문 문서에만 있다** — `Webhook.events` 는 enum 없는 `type: "object"` 인데, `about_orbit.md` 는 `"ACTION_COMPLETED_WITH_ALERT"` 를 명시한다. 스펙만 보고 "이벤트 종류가 선언되지 않았다" 고 적으면 틀린다.

### ③ 구조적으로도 닫을 수 없다

API 는 **각 Orbit 인스턴스가 자기 `/api/v0` 에 서빙**한다. 배포된 인스턴스가 게시본보다 넓을 수 있고, 인스턴스에 붙기 전에는 못 본다.

> 근거 등급(*출처가 얼마나 1차인가*)과 범위(*얼마나 넓게 봤는가*)는 다른 축이다. 이 측정은 등급이 `VENDOR_PRIMARY` 이면서 범위가 좁다. **좁음을 적는 것이 이 절의 목적이다** — §15.65 의 *"근거 등급이 낮으면 NO 가 아니라 UNKNOWN"* 이 여기서는 *"범위가 좁으면 그 좁음을 적는다"* 로 나타난다.

---

## 표면 — 쓰기 가능한 것

경로 35 개 중 상태를 바꾸는 것:

| 경로 | 메서드 |
|---|---|
| `/calendar/schedule` | GET **POST** |
| `/calendar/schedule/{eventid}` | DELETE |
| `/calendar/disable-enable` | POST |
| `/site_walks` · `/site_elements` · `/site_docks` | POST (저작) |
| `/robots` · `/webhooks` · `/backup_tasks` | GET POST |
| `/anomalies` · `/anomalies/{anomalyId}` | PATCH |
| `/missions` | **GET 뿐** |

나머지 22 개는 읽기(`/runs`·`/run_events`·`/run_captures`·`/run_statistics`·facets 계열)다.

### 즉시 실행 경로가 없다

`run`·`execute`·`dispatch` 류 경로가 없고 `/missions` 에 POST 가 없다. **작업을 넣는 유일한 문이 캘린더 항목이다.**

```
Schedule
  eventMetadata { name, modificationTimeMs, modificationUser }
  agent         { nickname }                      ← 로봇 닉네임
  task          { missionId, forceAcquireEstop }  ← 파라미터 자리 없음
  schedule      { timeMs, repeatMs, blackouts[] }
```

스펙의 설명문이 그것을 그대로 말한다 — *"A schedule describes **when and how often** a robot should execute an autonomous mission."* **무엇을·어떻게가 아니라 언제·얼마나 자주다.**

`task.forceAcquireEstop` 는 *"Determines whether Orbit should forcibly acquire Estop authority"* 다. 캘린더 항목 하나에 E-stop 권한 강제 취득 불리언이 달려 있다 — 정지 종류를 가르는 논의(§2 의 안전 경계)에 직접 걸린다.

### 결과 어휘가 좁다

```
RunEvent.error     { type: "integer" }  "The error code for an error which occured during this event."
RunEvent.eventType { enum: ["daq", "screenshot"] }
```

에러 코드 표는 스펙에 없다. `eventType` 은 취득(daq)과 스크린샷 둘뿐 — 검사 도메인 전용이라 **매니퓰레이션 결과 어휘가 없다.**

> **이것이 우리 어댑터가 gRPC 직결인 이유를 실증한다.** Spot 의 `ManipulationFeedbackState` 는 파지 실패와 raycast 실패를 열거값으로 가른다(`GRASP_PLANNING_NO_SOLUTION`·`GRASP_FAILED_TO_RAYCAST_INTO_MAP`·`PLACE_SUCCEEDED/FAILED` 등). 그 구분이 정수 하나를 통과할 수 없다. 로봇은 원인을 아는데 이 경로로는 못 올린다.
>
> 다만 우리 `Fault.error_type` 이 열린 문자열이라 **받을 자리는 있고 어휘가 없다.** 열림.

---

## Mission → SiteWalk

스펙 전체의 `deprecated: true` 5 건 중 4 건이 Missions 다.

| 대상 | 안내문 |
|---|---|
| `/missions` GET · `/missions/{id}` GET·DELETE · `Mission` 스키마 | *"Use SiteWalk instead!"* |
| `/login` POST | *"Use `/api_token/authenticate` instead!"* |

새 계층은 **SiteWalk(미션) → SiteElement(액션) → RunEvent(결과)** 이고 옆에 SiteDock(충전소)이 있다. `orbit_api.md` 의 정의:

- `SiteWalk` — *"a series of tasks that define autonomous robot operation"*, `SiteElements` 를 **순서대로 시도**하고 `SiteDocks` 중 무엇을 쓸지는 **로봇이 런타임에 고른다**
- `SiteElement` — *"describes what a robot should do and where to do it"*, 보통 웨이포인트에 결속

`SiteWalk` 가 무엇을 나르는지가 중요하다:

```
siteElementIds[]        방문 순서
globalParameters        미션 전역 파라미터
targetFailureBehavior   이동 실패 시 기본 거동
actionFailureBehavior   액션 실패 시 기본 거동
batteryMonitor          도크 이탈·복귀 기준
travelParams · entityParams
preferRecordedRoutes · skipDockingAfterCompletion
```

> **실패 정책을 저작 시점에 기본값으로 선언한다.** 우리 계약은 그것을 태스크마다 런타임에 처리한다. ADR 36 의 층 ② 후보로 볼 만하다 — 벤더가 이미 가진 것이므로 발명이 아니다.

### 우리 거리 측정에 미치는 영향 — 열림

`profile/distance/spot-arm.json` 의 `vendor_layer` 는 **로봇 쪽 `MissionService`(행동트리)** 를 가리킨다. Orbit 의 SiteWalk 는 **플릿 쪽 저작 모델**이다. **같은 단어의 다른 층이므로 그 측정을 SiteWalk 에 대고 다시 봐야 한다.**

---

## Work Order — 방향이 반대다

`about_orbit.md`:

> **When alerts occur in Orbit**, work orders can be created manually from within the Orbit UI or automatically at the time of the alert. … Orbit will send an HTTP POST request to a configured external endpoint with information about the work order to be created. **The external system is then responsible for creating the work order in its own system.**

**알림 → 외부 시스템에 티켓 생성**이다. Orbit 이 나가는 방향이고, 상류가 들어오는 방향이 아니다. 점검 도메인 그대로이며 **작업 지시가 아니다.**

⇒ **Work Order 가 있어도 "상류가 파라미터를 실어 로봇에게 일을 시킨다" 는 경로는 이 표면에 없다.**

그리고 벤더 자신이 중간 계층을 권고한다:

> If you need more granular control of how the HTTP calls to the external system are made, **an intermediate layer can be used to act as a "middleman" between Orbit and the external system.**

work order 템플릿이 **`bosdyn.api.DictParamSpec` 모양**이라고 명시된 것도 기록해 둔다 — `service_customization` 의 그 패턴이 상류 연동에도 재사용된다. **벤더가 이미 가진 런타임 선언 파라미터 메커니즘**이라는 뜻이고, ADR 36 의 등재 기준(발명 금지)에서 근거가 된다.

---

## Atlas·Stretch 는 Orbit 을 통하는가 — `INFERRED`

**등급을 먼저 적는다. 이 절의 결론은 `INFERRED` 이고 위의 판정들과 등급이 다르다.**

확인된 사실은 셋이다.

1. `[VENDOR_PRIMARY]` **공개 SDK 는 Spot 뿐이다.** `dev.bostondynamics.com` 이 다루는 제품이 Spot 하나이고, Atlas·Stretch 문서는 없다.
2. `[VENDOR, 마케팅]` BD 블로그가 *"Orbit enables powerful workflow integrations, connecting **Atlas** to your MES, WMS, or other systems of record"* 라 쓰고, 기존 Spot·Stretch 고객이 *"turnkey integration of **Atlas** into their existing Orbit instance"* 를 쓰게 된다고 한다. 날짜 표기가 없는 블로그다.
3. `[VENDOR_PRIMARY]` **Orbit 의 신원 계층은 기종 중립이다.** 스펙 전체에서 `Atlas`·`Stretch`·`species`·`model`·`quadruped` 가 **0 건**, `Spot` 이 1 건이다. `Robot` 스키마에 기종 필드 자체가 없다:

   ```
   Robot { robotIndex, hostname, nickname, username }
   ```

   기종을 안 적으므로 Atlas 를 넣는 데 스키마 변경이 필요 없다.

⇒ **"Atlas·Stretch 는 공개 SDK 없이 Orbit 을 통해 붙는다" 는 읽기가 현재 자료와 정합적이다.** 다만 다음 둘 때문에 단정하지 않는다.

- **"공개 SDK 가 없다" 와 "SDK 가 없다" 는 다르다.** 비공개·NDA 배포나 고객·파트너 채널이 있을 수 있고, 이 저장소의 자료로는 구별할 수 없다.
- **Orbit 의 일감 계층은 기종 중립이 아니다.** `SiteElement` 가 웨이포인트에 묶여 있다:

  ```
  SiteElement { waypointId, waypointMaxDistance[m], waypointMaxYaw[rad],
                action, actionWrapper, actionDuration, relocalize,
                targetFailureBehavior, actionFailureBehavior }
  ```

  `relocalize` 와 `waypointId` 는 GraphNav 개념이고, `eventType` 이 `daq`·`screenshot` 인 것과 합치면 **게시된 Orbit 은 점검·순찰 모양**이다. 매니퓰레이션 일감이 이 모양에 그대로 들어가지 않는다.

  단 `action` 과 `actionWrapper` 가 **둘 다 `type: "object"` 로 열려 있다.** 실제 어휘가 게시본에 없다는 뜻이며, 위 `survey_scope` ② 와 같은 종류의 공백이다.

### 사실이라면 우리 구조가 바뀐다 — 미리 적어 둔다

- **직결의 이점이 Atlas 로 전이되지 않는다.** 우리가 Spot 에서 `ManipulationFeedbackState` 를 안 잃는 것은 어댑터가 gRPC 로 직접 붙기 때문이다. Atlas 가 Orbit 뒤에 있으면 그 경로가 없고, 정수 에러 코드와 캘린더 투입을 그대로 물려받는다.
- **어댑터의 단위가 달라진다.** ADR 31·33 은 *어댑터 하나 = 기종 하나* 를 전제한다. Orbit 어댑터는 **플릿 하나에 하나**이고 뒤에 로봇 N 대가 `nickname` 으로 구분돼 붙는다. 기종 어댑터가 아니라 **플릿 어댑터**다.
- **배타 제어 모델이 안 맞는다.** Spot 은 `Lease` 로 소유권을 준다. Orbit 표면에는 리스가 없고 `task.forceAcquireEstop` 불리언과 `robotIndex` 만 있다.
- **규모 상한이 선언돼 있다.** `robotIndex` 가 *"a number between 0 and the max for your Orbit server (typically 32)"* — **인스턴스당 대략 32 대**다.
- **ADR 35 가 플릿 계층에서 또 확인된다.** `SiteElement.waypointId` 는 사이트가 붙인 이름이 아니라 **로봇이 생성한 웨이포인트 id** 다. 우리 Spot 어댑터가 `location` 을 그대로 `destination_waypoint_id` 로 넘겨 틀렸던 것과 같은 자리이며, 벤더의 플릿 계층도 같은 결속을 쓴다.

## 열린 것

1. **거리 재측정** — `vendor_layer` 를 SiteWalk 에 대고 다시 잰다(위).
2. **결과 어휘** — `Fault.error_type` 이 열린 문자열인 채로 남아 있다. 정준화 대상.
3. **Work Order 엔드포인트의 실제 모양** — 게시 스펙에 없다. 인스턴스에 붙기 전에는 `UNKNOWN`.
4. **인스턴스 표면** — 게시본보다 넓은지 여부. 붙어 보기 전에는 못 닫는다.
5. **Atlas·Stretch 의 접속 경로** — 위 절의 `INFERRED` 를 닫으려면 비공개 SDK 유무를 확인해야 한다. 확인 전까지 어느 쪽으로도 설계를 굳히지 않는다.
