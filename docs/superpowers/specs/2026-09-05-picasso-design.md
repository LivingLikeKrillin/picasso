# picasso — 이기종 로봇 표준 I/F 계약과 프로파일 주도 에뮬레이터

설계 문서 · 2026-09-05

## 1. 목적과 범위

### 1.1 목적

이기종 모바일 로봇(휴머노이드·4족보행)을 공장 운영 시스템에 연계할 때 필요한 **표준 I/F 계약**을 정의하고, 그 계약을 **실물 없이 검증할 수 있는 상대**를 만든다. 검증 상대는 기종마다 구현하는 것이 아니라 **능력 선언(프로파일)을 읽어 런타임에 표면을 구성**한다.

한 문장으로: **이기종 대응은 코드가 아니라 프로파일 교체여야 한다.** 이 문서의 모든 결정은 이 주장을 데모가 아니라 CI 실패 조건으로 만들기 위한 것이다.

### 1.2 범위 (이번에 만드는 것)

근거 문서 `이기종-로봇-공장-연계-설계노트` §5 백로그 기준:

| 항목 | 내용 |
|---|---|
| A-1 | 스킬 모델의 proto 이식 |
| A-2 | 이벤트 계약 (상태/이벤트 분리, 스냅샷, 순서, 멱등, 결함 모델) |
| A-4 | 장기 실행 작업 모델 (goal/progress/result/cancel) |
| C-1 | 능력 프로파일 스키마 |
| C-2 | 프로파일 주도 에뮬레이터 (MiMic) |
| D-1 | 스키마 호환성 게이트 |
| D-2 | 결정 기록 (ADR) — 목록은 §12 |

여기에 설계 과정에서 추가된 둘:

| 항목 | 사유 |
|---|---|
| 능력 호환성 체계 | 제조사·버전에 따라 능력이 늘고 줄고 세분화된다. API·헤더·토픽·키 설계와 통신 시 검증 전략이 필요하다 |
| 레지스트리와 런타임 갱신 | 프로파일은 설계노트 §4.5 기준으로 데이터이며, 갱신을 위해 프로세스를 내리면 안 된다 |

### 1.3 비목표 (이번에 만들지 않는 것)

- **A-3 원자적 명령 전달의 대안 비교** — 파라미터와 실행을 단일 RPC로 묶어 경쟁 조건을 만들지 않는 쪽을 택한다. `ReadyConfigured` 중간 상태 방식과의 비교 검증은 하지 않는다.
- **A-5 이동·내비게이션 스킬 타입** — 스킬 타입 어휘를 늘리는 일이며 구조는 A-1이 이미 담는다.
- **A-6 위치 레지스트리** — 좌표를 계약에서 몰아내는 작업. 레지스트리 스키마가 이를 막지 않도록만 두고 구현하지 않는다.
- **B-1 무선 단절 대응의 집행** — 재기동 후 재개 지점 복원과 저장소 커밋. 다만 **멱등성 키는 계약에 지금 넣는다**(나중에 넣으면 계약 파괴이므로).
- **B-2 텔레메트리 경로 분리** — 따라서 프로파일은 텔레메트리 **항목**을 담지 않는다. 상태 발행 주기만 담는다(§7.2).
- **B-3 브리지 정책.**
- **C-3 적합성 역검증** — 실물이 도착해야 의미가 생긴다.
- **D-3 예외 구역 경계 집행** (사이트 분기 정적 검사).
- **미션 계층 전체** — 배차·라우팅·자원 중재·다중 로봇 경합.
- **물리 시뮬레이션** — 도달 가능성, 충돌, 파지 가능성.
- **운영 콘솔 전체** — 화면은 진단용 한 장만 만든다(§8.5).
- **정책 설정 저장소** — 임계·상한을 데이터로 두는 일은 소비자가 생긴 뒤에 만든다.

### 1.4 완료 기준

§11.2에 아홉 행으로 정리하며, 여섯은 설계노트 백로그의 완료 기준이고 셋은 §1.2에서 추가한 두 항목의 것이다.

## 2. 배경과 근거

### 2.1 근거 문서

`이기종-로봇-공장-연계-설계노트`(38쪽). 계층 구조(L3~L5), 여섯 무대, 변경 수용 전략, 구현 스택 선택, API 규격과 페이로드 카탈로그, §5 작업 백로그가 여기에 있다. 이 문서는 그중 최소 순환 고리의 구현 설계다.

### 2.2 벤치마킹 — 무엇을 어디서 가져왔는가

두 저장소를 직접 추적해 확인했다. 클론: `Labs/[oss]/opentcs`, `Labs/[oss]/VDA5050`(버전 3.0.0). 둘 다 MIT 라이선스다. **코드를 전사하지 않고 설계 기법만 가져오며, 출처는 ADR에 남긴다.** openTCS의 LLM 사용 금지 조항은 openTCS에 기여할 때의 규칙이므로 참조에는 적용되지 않는다.

| 가져온 것 | 출처 | 쓰이는 곳 |
|---|---|---|
| 실패 등급을 "남은 능력"으로 정의 | VDA5050 `state.schema` `errorLevel` | §4.5 |
| `RETRIABLE`을 상태로 | VDA5050 `actionStatus` | §4.4 |
| 결함 수명, `errorHint`, `errorReferences` | VDA5050 에러 카탈로그 | §4.5 |
| 연결 상태 4값 — 침묵의 세 원인을 구분 | VDA5050 `connection.schema` | §4.6 |
| 상태 발행 30초 상한 | VDA5050 §6.6 (`VDA5050_EN.md:1034`) | §7.2 |
| `(orderId, orderUpdateId)` 단조쌍 = 멱등성 키, 재수신 4케이스 | VDA5050 §6.1.4 | §4.4 |
| 능력 프로파일 구조 전반 | VDA5050 `factsheet.schema` | §7.2 |
| `pauseAllowed`/`cancelAllowed` 필수 선언 | 同 `mobileRobotActions` | §7.2 |
| `optionalParameters {parameter, support}` | 同 `protocolFeatures` | §5.3 |
| `protocolLimits` — 한계를 데이터로 | 同 | §7.2, §9.4 |
| 메이저 버전을 토픽 경로에 | VDA5050 §4 (`interfaceName/majorVersion/...`) | §5.5 |
| 거절에 이유를 붙이는 `ExplainedBoolean` | openTCS SPI 4곳 | §5.4, §8.5 |
| 취소는 즉시가 아니다 (`WITHDRAWN`) | openTCS `TransportOrder.State` | §4.4 |
| 에뮬레이터 제어 채널·단계 실행 | openTCS loopback | §9.5 |
| 계약 모듈 의존 0 | openTCS `opentcs-api-base` | §3.2 |
| 송신까지 검증하는 스키마 게이트 | openTCS | §6.2 |

**반례로만 쓰는 것 둘.**

- openTCS의 VDA5050 어댑터는 factsheet를 구독해 놓고 버린다(`v2_0/CommAdapterImpl.java:529`). 능력은 사람이 타이핑한 문자열에서 오고 미선언은 fail-open이다. 교훈은 **"선언 메커니즘이 있어도 소비하지 않으면 무의미하다"**이며, 이 문서는 §7.3(프로파일 → `Capability` 투영이 유일한 능력 출처)과 §10.2의 4번(투영이 proto에 맞는지 CI가 확인)으로 소비를 강제해 이를 피한다.
- openTCS의 스키마 검증 플래그는 이름이 `VALIDATE_INCOMING_MESSAGES`인데 끄면 송신 검증까지 함께 꺼진다 — §6.2에서 플래그를 분리하는 이유다.

**openTCS loopback은 프로파일이 아니다.** 노브가 여섯 개뿐이고(`loopback:initialPosition`, `operatingTime`, `loadOperation`, `unloadOperation`, `acceleration`, `deceleration`) 거동은 코드다. 기종 추가 = 코드 추가. 이 프로젝트의 C-1/C-2가 갖는 차별점의 근거다.

## 3. 아키텍처

### 3.1 모듈

```
picasso/
  contracts/   proto. 스킬·태스크·이벤트·결함                A-1 A-2 A-4
  profile/     능력 프로파일 JSON Schema + 기종 프로파일들    C-1
  registry/    개정판 저장·검증·활성화·감사·diff·진단 화면    8절
  mimic/       프로파일 주도 에뮬레이터 + 제어 채널           C-2
  client/      계약 소비자 — 완료 기준 증명용
  gate/        검증 라이브러리. CI와 registry가 함께 호출     D-1
  harness/     개정판 시험 하네스 — mimic과 client를 몰아 돌린다
  docs/adr/    결정 기록                                      D-2
```

진단 화면(§8.5)은 `registry/`에 속한다.

### 3.2 의존 규칙

**`contracts/`는 프로젝트 내 의존이 0이다.** 다른 모든 모듈이 `contracts/`를 소비하며, `contracts/`는 아무도 모른다. openTCS `opentcs-api-base`가 프로젝트 의존 0으로 배포되고 커널조차 소비자인 구조와 같다. 이 규칙은 §10.2의 5번 검사가 CI로 강제한다.

```
contracts  ← profile? 아니오. profile은 파일이며 gate가 둘을 잇는다
gate       → contracts, profile
registry   → gate, contracts
mimic      → contracts, (registry: 프로파일 읽기)
client     → contracts
harness    → mimic, client, registry
```

**`registry`는 `mimic`을 모른다.** §8.4 ②의 시험은 `harness`가 수행하고 결과를 `registry`에 API로 보고한다. 이렇게 두지 않으면 `registry ↔ mimic` 순환이 생긴다.

### 3.3 각 모듈의 책임

- **`contracts/`** — 런타임에 로봇이 말해야 하는 것의 구조. 스킬 상태머신, 태스크 생명주기, 이벤트, 결함 모델. 값이나 제약은 담지 않는다.
- **`profile/`** — 기종을 기술하는 선언 형식(JSON Schema)과 실제 기종 프로파일들. 값과 제약만 담는다.
- **`registry/`** — 프로파일 개정판의 저장·검증·활성화·이력, 기체와 개정판의 바인딩, 진단 화면. 런타임 갱신의 단일 통로.
- **`mimic/`** — 프로파일을 읽어 계약을 구현하는 서비스 가상화 계층. 어댑터가 아니라 "어댑터 + 로봇" 한 쌍을 대신한다. 상위는 엔드포인트만 바꿔 실물과 교체한다.
- **`client/`** — 계약을 두드려 완료 기준을 증명하는 얇은 소비자. **오케스트레이터가 아니다** — 배차·자원 중재는 미션 계층 몫이며 비목표다.
- **`gate/`** — 검증 규칙의 단일 구현. CI와 `registry` 등록 엔드포인트가 같은 코드를 호출한다.
- **`harness/`** — 후보 개정판으로 `mimic`을 띄우고 `client`로 계약 스위트를 돌린 뒤 결과를 `registry`에 보고한다. CI와 운영자 양쪽에서 같은 진입점으로 실행된다.

### 3.4 구현 스택

설계노트 §4.9를 따른다. 전부 **Kotlin**, 프레임워크는 Spring Boot이되 도메인 로직은 프레임워크를 모른다. MVC + 가상 스레드 + 도메인 층 코루틴이며 WebFlux는 쓰지 않는다.

**`mimic`도 Kotlin으로 둔다** — 설계노트가 어댑터에 Python을 배정했지만 `mimic`은 어댑터가 아니다. `mimic`이 하는 일이 스킬별 상태머신 인스턴스화와 실패 분류의 망라 처리이므로, sealed 클래스와 `when` 망라성이 컴파일 타임에 미처리 분기를 드러내는 이득이 여기에 가장 직접 적용된다.

저장소는 **PostgreSQL**(JSONB 사용).

### 3.5 전송 경계

두 전송을 쓰며 **역할이 겹치지 않는다.**

| 전송 | 담는 것 | 이유 |
|---|---|---|
| **gRPC** | 명령과 질의 — `Negotiate`, `StartTask`, `WatchTask`, `CancelTask`, `GetSnapshot`, `GetCapabilities` | 요청자가 하나이고 응답이 요청자에게만 간다. 데드라인·취소 전파가 필요하다 |
| **MQTT** | 발행 — 상태, 이벤트, 연결 상태 | 소비자가 여럿이고 팬아웃이 브로커의 몫이다 |

`WatchTask`의 `TaskUpdate` 스트림은 **요청자 전용**이다. 같은 사실 중 팬아웃 대상인 것(태스크 종착, 결함 발생·해소, 능력 변경)은 별도 이벤트로 MQTT에 발행된다. **중복 발행이 아니라 수신 대상이 다르다.**

토픽의 `{stream}` 값 집합은 셋이다 — `state`, `event`, `connection`.

**순서 보장.** MQTT에는 파티션 개념이 없다. 토픽이 기체 단위로 갈라져 있으므로 순서 보장의 단위는 **토픽**이며, 브로커가 재전송 등으로 순서를 흐트러뜨릴 수 있으므로 소비자는 헤더의 `sequence`로 재정렬하고 결손을 감지한다. 즉 **순서는 전송이 아니라 계약이 보장한다.**

## 4. 계약 (`contracts/`)

### 4.1 파일 구성

| 파일 | 담는 것 | 범위 항목 |
|---|---|---|
| `skill.proto` | 스킬 상태머신, 능력 선언, 핸드셰이크 | A-1 |
| `task.proto` | 장기 실행 태스크 — Start / Watch / Cancel | A-4 |
| `event.proto` | 상태와 이벤트, 스냅샷, 연결 상태, 공통 헤더 | A-2 |
| `fault.proto` | 결함 모델 | A-2 (A-4의 에러 모델 요구도 함께 만족) |

### 4.2 스킬 상태머신

OPC UA Skill 모델(fortiss / VDMA·OPC Foundation SOArc)의 구조를 언어 중립 계약으로 옮긴다. 상태는 넷이다.

| 상태 | 뜻 |
|---|---|
| `READY` | 실행 가능. 초기 상태이자 정상 완료 후 복귀 상태 |
| `RUNNING` | 실행 중 |
| `SUSPENDED` | 일시정지 |
| `HALTED` | 중단됨. `Reset` 없이는 다시 실행할 수 없다 |

전이 합법성:

| 전이 | 출발 | 도착 | 조건 |
|---|---|---|---|
| `Start` | `READY` | `RUNNING` | 파라미터를 함께 싣는다(§4.2 아래) |
| `Suspend` | `RUNNING` | `SUSPENDED` | 프로파일의 `pause_allowed=true`인 스킬만 |
| `Resume` | `SUSPENDED` | `RUNNING` | — |
| `Halt` | `RUNNING`, `SUSPENDED` | `HALTED` | 취소·결함의 결과 |
| `Reset` | `HALTED` | `READY` | — |
| `Complete` | `RUNNING` | `READY` | 정상 완료. 결과는 `TaskUpdate`로 전달 |

표에 없는 조합은 전부 불법이며 `INVALID_TRANSITION`으로 거절한다. 이 표가 §11.1의 "상태머신 망라성" 단위 시험의 대상이고, Kotlin sealed + `when` 망라성이 검사하는 대상이다.

**파라미터와 실행 요청을 단일 RPC로 묶는다.** 참조 모델이 스스로 인정한 경쟁 조건 — 파라미터 쓰기와 Start가 분리되어 그 사이에 다른 클라이언트가 덮어쓸 수 있는 문제 — 는 둘을 나누지 않으면 발생하지 않는다. 비용이 없으므로 처음부터 이렇게 둔다.

### 4.3 값 어휘

계약 전반에서 쓰는 닫힌 집합을 여기 모은다.

| 이름 | 값 |
|---|---|
| `BlockingType` | `NONE`, `SOFT`, `SINGLE`, `HARD` |
| `SkillScope` | `INSTANT`(즉시 명령으로 호출 가능), `TASK_STEP`(태스크의 단계로만) |
| `ValueType` | `BOOL`, `INTEGER`, `NUMBER`, `STRING`, `ENUM` |
| `Reference` | `{key, value}`. `key` ∈ `task_id`, `skill_id`, `robot_id`, `parameter_key` |
| `Lifetime` | `UNTIL_CLEARED`, `UNTIL_NEW_TASK`, `UNTIL(timestamp)` |
| `RejectionCode` | `MAJOR_MISMATCH`, `SKILL_ABSENT`, `REQUIRED_OPTIONAL_MISSING`, `LIMIT_EXCEEDED`, `CANCEL_UNSUPPORTED`, `PAUSE_UNSUPPORTED`, `OUTDATED_REVISION`, `INVALID_TRANSITION` |

### 4.4 장기 실행 태스크

```
StartTask(TaskRequest)    -> TaskHandle         접수 응답. 종착이 아니다
WatchTask(TaskHandle)     -> stream TaskUpdate  진행률·부분결과·종착
CancelTask(CancelRequest) -> CancelAck          CANCELLING을 반환한다
```

태스크 상태:

| 상태 | 종착 | 뜻 |
|---|---|---|
| `ACCEPTED` | 아니오 | 접수됨, 아직 시작 전 |
| `RUNNING` | 아니오 | 실행 중 |
| `PAUSED` | 아니오 | 일시정지 |
| `CANCELLING` | 아니오 | 취소 요청을 받았고 로봇이 정리 중 |
| `RETRIABLE` | 아니오 | 실패했으나 같은 요청으로 재시도 가능 |
| `SUCCEEDED` | 예 | — |
| `FAILED` | 예 | 재시도해도 같은 결과 |
| `CANCELLED` | 예 | 취소로 종료 |

**취소는 즉시가 아니다.** `CancelTask`는 종착이 아니라 `CANCELLING`을 반환하고 종착(`CANCELLED` 또는 `SUCCEEDED`, 정리 중 완료된 경우)은 스트림으로 온다. openTCS `TransportOrder.State`의 `WITHDRAWN`("철회했으나 차량이 정리 중이라 아직 종착이 아님")과 같은 구조다.

`RETRIABLE`을 종착으로 두지 않는 것이 요점이다 — 실패했으나 재시도 가능한 상황은 에러 코드가 아니라 상태다(VDA5050 `actionStatus`).

**취소·일시정지 가능 여부는 계약이 아니라 프로파일이 답한다.** 계약에는 `CANCEL_UNSUPPORTED` / `PAUSE_UNSUPPORTED` 거절 사유만 둔다.

**멱등성 키는 `(task_id, revision)` 단조쌍이다.** VDA5050의 `(orderId, orderUpdateId)`를 그대로 가져온다.

| 수신한 revision | 처리 |
|---|---|
| 신규 `task_id` | 새 핸들 |
| 현재와 동일 | **같은 핸들을 그대로 반환**(멱등). 상태 메시지를 못 받아 재전송한 경우 |
| 현재보다 낮음 | `OUTDATED_REVISION` 거절 |
| 현재보다 높음 | 갱신으로 처리 |

`WatchTask`는 재접속 시 마지막으로 받은 `sequence`부터 이어받는다(§4.7). 데드라인은 gRPC 데드라인을 쓰고 취소 전파에 연결한다.

### 4.5 결함 모델

설계노트 A-4는 `google.rpc.Status` 위에 재시도 가능 / 이관 가능 / 사람 개입 3분류를 계약에 담자고 했다. **이 문서는 3분류를 계약에서 뺀다.**

3분류는 처방이고 처방은 판단이다. "이관 가능한가"를 어댑터가 답하려면 벤더가 우리 운영 정책을 알아야 한다. VDA5050은 대신 **남은 능력**을 묻고, 그래서 등급이 두 개의 불리언으로 결정되어 판단 여지가 없다.

```
Fault {
  string   error_type                 확장 가능 enum (문자열)
  bool     can_continue_current_task
  bool     can_accept_new_task
  repeated Reference references
  string   error_hint                 사람이 취할 조치
  Lifetime active_until
}
```

두 불리언은 어댑터가 기계적으로 채운다. 3분류는 미션 계층이 여기서 파생시킨다 — `can_continue=false, can_accept=true`면 이관 후보, 둘 다 false면 사람 개입. **분류가 사라지는 것이 아니라 계약 밖으로 나가고 대신 결정 가능해진다.**

`active_until`이 없으면 소비자가 "이 결함이 아직 유효한가"를 추측하게 되고, `error_hint`가 없으면 사람 개입 등급이 실무에서 쓸모가 없다. 둘 다 VDA5050 에러 카탈로그에서 가져왔다.

스킬 수준 결함과 로봇 수준 결함을 구분한다 — `references`가 `skill_id`를 담으면 그 스킬만의 문제이며, "이동은 되는데 조작만 안 되는" 상태가 이렇게 표현된다.

### 4.6 상태와 이벤트

상태는 현재값, 이벤트는 발생한 사실이며 **둘 다 발행한다.** 상태만 발행하면 소비자가 전이를 관찰하지 못하고, 이벤트만 있으면 신규 구독자가 현재 상태를 알 수 없다.

이벤트가 되는 전이는 넷이다 — 스킬 상태 전이, 태스크 상태 전이, 결함 발생·해소, 능력 변경. 그 밖의 값 변화는 상태로만 발행한다.

**연결 상태는 별도 스트림이고 네 값이다.**

| 값 | 뜻 |
|---|---|
| `ONLINE` | 연결 활성 |
| `OFFLINE` | 정상 종료 |
| `HIBERNATING` | **연결됐지만 의도적으로 상태를 발행하지 않음** |
| `CONNECTION_BROKEN` | 비정상 단절 |

retain으로 발행하며 `CONNECTION_BROKEN`은 브로커 Last Will로 설정한다. `HIBERNATING`이 있어야 "침묵하지만 정상"을 표현할 수 있다 — 침묵의 상한만 두면 절전 중인 로봇이 고장으로 오판된다.

### 4.7 세션·시퀀스·이벤트 보존

`sequence`는 **세션 단위**로 0부터 단조 증가한다. 로봇(또는 `mimic`)이 기동할 때마다 새 `session_id`(ULID)를 발급하고 헤더에 싣는다.

- 소비자는 `session_id`가 바뀌면 **이전 `sequence`를 이어서 해석하지 않고** `GetSnapshot`으로 다시 세운다. Sparkplug의 birth/rebirth와 같은 자리다.
- 발신자는 **세션 안에서 마지막 N개 이벤트를 재생 버퍼에 보관**한다. `WatchTask` 재접속과 `GetSnapshot` 직후의 이어받기가 이 버퍼에서 나온다. `N`은 프로파일이 선언한다(§7.2).
- 요청한 `sequence`가 버퍼를 벗어났으면 `SEQUENCE_EVICTED`로 답하고 소비자는 스냅샷부터 다시 세운다.
- **버퍼는 프로세스 메모리에 있으며 재기동하면 사라진다.** 지속 저장은 B-1의 몫이고 비목표다. 재기동이 곧 새 세션이므로 소비자 입장에서 모호함은 없다.

## 5. 능력 호환성

### 5.1 문제

능력은 기종의 고정 속성이 아니라 **(제조사 × 모델 × 버전)의 함수**이며 시간에 따라 변한다. 늘어나거나, 줄어들거나, **같은 이름의 능력이 더 섬세해진다.**

세 번째가 어렵다. 능력의 **존재**를 묻는 것은 쉽지만 세분화는 **동일성**을 묻게 만든다. `Pick(object_id)`를 하던 로봇이 다음 버전에서 `Pick(object_id, grip_force, approach_vector)`가 되면 같은 `Pick`인가. 클라이언트가 아는 `Pick`과 로봇이 말하는 `Pick`이 같다는 보장이 어디에서 오는가.

### 5.2 동일성 규칙

식별자는 `(skill_type, major.minor)`이며 **동일성은 major가 결정한다.**

- **minor 증가** = 선택 파라미터 추가만. 클라이언트가 몰라도 동작해야 한다. 세분화의 정상 경로다.
- **major 증가** = 셋 중 하나. 필수 파라미터 추가 / 기존 파라미터의 의미·단위 변경 / **성공 판정 기준 변경**. 마지막이 자주 누락된다 — `Pick`이 "잡았다"에서 "잡고 들어올렸다"로 바뀌면 구조가 같아도 다른 능력이다.
- **파라미터 키는 불변이다.** `grip_force`가 한 번 뉴턴이면 영원히 뉴턴이다. 의미나 단위가 바뀌면 새 키를 만들고 옛 키를 폐기한다. proto 필드 번호와 같은 규율이다.

계약(`contracts/`)은 스킬 타입마다 **`major`별로 하나의 정의**를 갖고, 그 정의가 아는 파라미터 집합이 곧 그 major의 최신 minor다. 프로파일은 자신이 구현하는 `major.minor`를 선언하며, 선언한 minor는 계약이 아는 최신 minor 이하여야 한다(초과하면 계약에 없는 파라미터를 쓰는 것이므로 §10.2의 4번이 막는다).

클라이언트는 요구를 `pick_place@^1.2` 형태로 표현한다. 로봇이 `1.5`를 선언하면 통과, `2.0`이면 거절하고 이유를 반환한다.

집행은 §10.2의 6번 검사가 한다. 집행의 한계는 §13의 1번이다.

### 5.3 키 설계

세 종류가 있고 미지 키 처리가 다르다.

| 종류 | 예 | 모르는 것을 받으면 |
|---|---|---|
| 코어 | `grip_force` | **실패**(fail-closed). 계약에 없는 코어 키는 오염이다 |
| 벤더 확장 | `x-<vendor>.<key>` | **무시**(fail-open). 코어는 `x-`를 절대 읽지 않는다 |
| 필수 선택 필드 | `{parameter, support: REQUIRED}` | 핸드셰이크에서 실패 |

세 번째는 VDA5050 `optionalParameters`에서 가져온 장치다. 로봇이 "이 선택 필드를 나는 요구한다"고 선언할 수 있어서, 그것을 보내지 않는 클라이언트가 런타임이 아니라 핸드셰이크에서 걸린다. 참조는 점표기 경로로 한다(예: `task.parameters.approach_vector`).

`x-` 규칙은 openTCS의 반례에서 나왔다. 확장 공간이 없으면 벤더가 코어 키를 오염시키므로, 공간을 주되 코어가 읽지 못하게 막는다.

### 5.4 핸드셰이크

세션 시작 시 한 번 수행한다.

```
Negotiate(CapabilityRequirement) -> NegotiationResult
```

클라이언트가 요구 능력 집합(스킬 타입과 버전 범위, 사용할 선택 필드)을 보내고, 로봇이 가능 여부와 **거절 이유**를 반환한다. openTCS `ExplainedBoolean(value, reason)`의 일반화이며, 그 저장소에서 `VehicleCommAdapter`·`VehicleController`·`PeripheralCommAdapter`·`PeripheralController` 네 곳이 같은 관용구를 쓴다.

거절 사유는 §4.3의 `RejectionCode` 중 넷을 쓴다 — `MAJOR_MISMATCH`, `SKILL_ABSENT`, `REQUIRED_OPTIONAL_MISSING`, `LIMIT_EXCEEDED`. 태스크를 보내보고 실패하는 것이 아니라 사전 협상이다.

**클라이언트의 요구 집합은 코드가 아니라 설정이다.** `client`는 요구 집합을 파일에서 읽으며, 기종을 식별해 분기하지 않는다. 이것이 §11.2 A-1의 "분기 없음"이 성립하는 방식이다 — 기종마다 다른 요구를 보내되 그 차이가 데이터에 있다.

**거절은 `registry`에 보고된다.** `mimic`(그리고 장차 실물 어댑터)은 거절 시 요구 집합과 사유를 `registry`의 수집 엔드포인트로 전송하고, `registry`는 `handshake_rejection`에 적재한다(§8.3). §8.5의 진단 4번이 이 테이블 위에 선다.

### 5.5 경로와 헤더

**경로는 라우팅과 굵은 호환성, 헤더는 정합과 추적.**

```
topic: picasso/{major}/{site}/{robot_id}/{stream}
       {stream} ∈ state | event | connection
```

메이저 버전을 경로에 두는 것은 VDA5050의 `interfaceName/majorVersion/manufacturer/serialNumber/topic`(예: `vda5050/v3/KIT/0001/order`)에서 가져왔다. 구독자가 자신이 이해하지 못하는 메이저의 페이로드를 애초에 받지 않는다.

```
header:
  schema_id          예: "picasso.v1.TaskUpdate"
  contract_revision  buf 모듈 다이제스트
  capability_epoch   uint64
  session_id         ULID. 발신자 기동마다 새로 발급
  profile_ref        {id, revision}          로봇 발신 메시지에만
  robot_id / sequence / event_id / occurred_at / state_as_of
```

gRPC 응답과 스트림에도 같은 헤더를 싣는다. 판정 규칙이 전송마다 갈리지 않게 하기 위해서다.

두 필드가 값을 만든다.

- **`contract_revision`** — 같은 major 안에서도 송신자가 다른 개정판으로 빌드했음을 드러낸다. 불일치는 **차단이 아니라 경보**이며, 수신 측이 결함 이벤트(`error_type = CONTRACT_REVISION_MISMATCH`, `can_continue=true`, `can_accept=true`)로 발행해 §8.5의 진단에 남긴다. 차단할 수 없는 이유와 그 대가는 §13의 4번이다.
- **`capability_epoch`** — 로봇의 유효 능력 집합이 바뀔 때마다 증가한다. 능력을 캐시한 소비자가 매 메시지에서 O(1)로 캐시 유효성을 판정하고, 틀어진 때만 다시 조회한다. 능력의 ETag이며 런타임 능력 축소가 여기서 소비 가능한 신호가 된다.

**능력 차이는 경로에 넣지 않는다.** 넣으면 능력이 바뀔 때 토픽이 바뀌고 구독자가 조용히 끊긴다. 원칙 한 줄 — **경로는 프로토콜 호환성, 헤더는 세대, 페이로드는 능력.**

## 6. 검증 전략

### 6.1 보장의 출처는 셋이고 비용이 다르다

| 시점 | 보장 | 비용 | 한계 |
|---|---|---|---|
| 빌드 | 구조 — 필드·타입·번호 | CI 1회 | 재생성한 쪽만. 의미는 못 본다 |
| 핸드셰이크 | 의미 — 능력 집합·버전·필수 선택필드 | 세션당 1회 | 선언이 정직할 때만 |
| 메시지마다 | 정합 — 계약 개정판·순서·능력 세대 | O(1) | 페이로드 의미는 못 본다 |

**핵심은 비용을 뒤로 미루지 않는 것이다.** 메시지마다 무거운 검증을 돌리면 프로덕션에서 반드시 꺼지고, 꺼지는 순간 아무 보장도 남지 않는다.

### 6.2 무엇을 어디서 얼마나

| 시점 | 검사 대상 | 실패 시 |
|---|---|---|
| CI | §10.2의 아홉 가지 | PR 차단 |
| 등록 | **프로파일 문서**를 JSON Schema와 proto 교차검증에 건다 | `VALIDATED` 진입 거부 |
| 핸드셰이크 | **요구 집합**을 선언 능력과 대조 | 연결 거부 + 사유 |
| 메시지마다 | 토픽의 `major` 일치, `schema_id` 기지 여부, `sequence` 단조·세션 일치, `capability_epoch` 캐시 일치 | 드롭 + 결함 이벤트 |
| 송신 직전 | **프로파일 파생 제약** — 파라미터 값 범위, 문자열·배열 최대 길이, 발행 간격. 비프로덕션 100%, 프로덕션 샘플링 | 발행 차단 |

**메시지마다의 판정 넷 중 `major`는 헤더가 아니라 토픽에서 읽는다.** gRPC 경로에서는 채널 수립 시 한 번 확인한다.

**송신 직전 검사가 구조 검증이 아니라는 점이 중요하다.** 구조는 proto 생성 코드가 빌드 시점에 이미 보장한다. 여기서 보는 것은 프로파일이 선언한 값 제약이며, 그래서 검사기는 `mimic`과 실물 어댑터 양쪽에서 같은 프로파일을 읽어 돈다.

openTCS는 발행 직전 송신 검증도 하지만 플래그가 `VALIDATE_INCOMING_MESSAGES` 하나여서 끄면 송신 검증까지 꺼진다. **수신·송신 플래그를 독립으로 두고 이름을 각각 정확히 붙인다.**

**`mimic`은 양방향 100% 검증을 항상 켠다.** 시험 도구이므로 비용이 문제가 아니고, 프로덕션 샘플링이 놓칠 계약 이탈을 잡는 것이 존재 이유다.

## 7. 능력 프로파일 (`profile/`)

### 7.1 왜 proto가 아니라 JSON Schema인가

계약은 proto, 프로파일은 JSON Schema로 나눈다. 프로파일에 담기는 것은 구조가 아니라 **제약**이기 때문이다 — 값 범위, 최대 길이, 최소 발행 간격, 조건부 필수. proto는 구조를 정의하는 언어라 이것들이 주석이나 옵션으로 밀려나고 검증이 런타임 코드로 샌다.

대가는 게이트가 하나 늘고 두 세계를 잇는 지점이 어긋날 수 있다는 것이며, §10.2의 4번 교차검증으로 갚는다.

### 7.2 프로파일이 담는 것

VDA5050 `factsheet.schema`의 구조를 따른다.

| 항목 | 내용 |
|---|---|
| 기종 좌표 | `vendor`, `model`, `revision` |
| `schema_version` | 이 문서가 따르는 프로파일 JSON Schema의 버전 |
| 지원 스킬 | 스킬 타입과 `major.minor`, 필수/선택 |
| 스킬별 플래그 | `pause_allowed`, `cancel_allowed` (**둘 다 필수 필드**), `blocking_types`, `scopes` |
| 파라미터 선언 | 키, `ValueType`, 선택 여부, **값 범위와 단위**(가반하중 kg, 도달 범위 m 등) |
| 필수 선택 필드 | `{parameter: 점표기 경로, support: SUPPORTED\|REQUIRED}` |
| 소요시간 | 스킬별 상수(초). 선택적으로 ± 지터 비율 |
| 실패 모드 | `{error_type, rate, can_continue_current_task, can_accept_new_task, error_hint, active_until}` |
| 상태 발행 간격 | 최소·최대. 최대의 기본값은 30초(VDA5050 §6.6의 상한) |
| 재생 버퍼 크기 | §4.7의 `N` |
| 프로토콜 한계 | 문자열·배열 최대 길이 |

`pause_allowed`/`cancel_allowed`를 모든 스킬의 필수 필드로 두는 것이 중요하다 — "이 스킬이 취소 가능한가"가 코드의 암묵 지식이 아니라 데이터가 된다.

프로토콜 한계와 값 범위를 선언으로 두면 `mimic`이 그것을 강제하는 것만으로 실패 주입이 따로 만들 필요 없이 생긴다(§9.4).

**텔레메트리 항목은 담지 않는다.** B-2가 비목표라 생산자도 소비자도 없다.

### 7.3 계약과의 관계 — 투영

프로파일은 저작 형식이고, 런타임 `Capability` 메시지는 그 프로파일의 **투영**이다. `mimic`이 하는 일이 이 투영이며, 실물 어댑터도 같은 투영을 수행해야 한다. §10.2의 4번 교차검증은 "투영이 proto 타입에 맞는가"를 확인한다.

**프로파일은 계약을 참조만 할 수 있고 확장할 수 없다.** proto에 없는 스킬 타입이나 파라미터 키를 선언하는 프로파일은 등록 단계에서 거부된다. 이것이 없으면 런타임 갱신이 게이트를 우회하는 뒷문이 된다.

### 7.4 이번에 만드는 프로파일

`humanoid-a`(휴머노이드형)와 `quadruped-b`(4족보행형) 둘. 능력 집합이 겹치되 다르게 만들며, 차이의 종류를 의도적으로 갖춘다.

| 차이의 종류 | 최소 하나씩 |
|---|---|
| 공통 스킬 | 두 기종 모두 같은 `major.minor` |
| 한쪽에만 있는 스킬 | `SKILL_ABSENT` 거절을 시험하기 위해 |
| 같은 스킬의 minor 차이 | 선택 파라미터 하나가 한쪽에만 |
| `cancel_allowed` 차이 | `CANCEL_UNSUPPORTED` 거절을 시험하기 위해 |
| 프로토콜 한계 차이 | 문자열 최대 길이가 다르게 |
| `REQUIRED` 선택 필드 | 한쪽만 요구하게 |

C-1의 완료 기준이 "둘의 차이가 전부 데이터로만 표현됨"이므로 이 여섯이 코드 변경 없이 표현되어야 한다.

세 번째 프로파일 `quadruped-c`는 §11.2의 C-2 시험에서만 쓴다.

## 8. 레지스트리와 저장 (`registry/`)

### 8.1 데이터와 코드의 경계

설계노트 §4.5의 기준을 그대로 쓴다 — **값이 잘못됐을 때 다른 값을 참조하지 않고 판정할 수 있으면 데이터, 조합·순서·실행 결과를 봐야 알면 코드.**

| 데이터 (DB · 런타임 갱신) | 코드 (배포) |
|---|---|
| 능력 프로파일 | 계약 proto |
| 기체 등록과 바인딩 | 스킬 상태머신 해석기 |
| 런타임 능력 오버라이드 | 조건 분기·계산·변환 파이프라인 |

**비대칭: 계약은 배포, 프로파일은 런타임.** §7.3의 참조 전용 규칙이 이 비대칭을 지킨다.

### 8.2 권한 소재 — 능력이 줄어드는 두 가지 사건

능력 축소에는 원인이 다른 두 사건이 있고 **경로도 다르다.**

| 사건 | 누가 | 경로 | `cause` |
|---|---|---|---|
| 로봇이 능력을 잃음 (팔 고장) | 로봇 | 로봇이 `CapabilityChanged` 이벤트 발행 → `registry`가 구독해 적재 | `RUNTIME_DEGRADED` |
| 운영자가 능력을 막음 (정책) | 사람 | `registry` API | `OPERATOR_BLOCKED` |

**두 경우 모두 `runtime_capability_override`에 기록되고 `capability_epoch`가 오른다.** `registry`가 이 테이블의 유일한 기록자다.

`mimic` 제어 채널의 `RemoveCapability`(§9.5)는 **첫 번째 사건을 흉내내는 것**이다 — 호출하면 `mimic`이 실제 로봇처럼 `CapabilityChanged`를 발행하고, 나머지 경로는 동일하다. 제어 채널이 `registry`를 직접 쓰지 않는다.

### 8.3 관계형 스키마 (PostgreSQL)

```sql
-- proto에서 배포 시 동기화되는 읽기 전용 투영. 프로파일의 참조 무결성 대상.
-- major당 한 행이며 max_minor는 계약이 아는 최신 minor다.
skill_type(skill_type_id PK, name, major, max_minor,
           contract_revision, synced_at,
           UNIQUE(name, major))

skill_type_param(skill_type_id FK, key, value_type, optional, since_minor,
                 PK(skill_type_id, key))

capability_profile(profile_id PK, vendor, model,
                   UNIQUE(vendor, model))

profile_revision(profile_revision_id PK,
                 profile_id FK, revision INT,
                 document JSONB, document_hash, schema_version,
                 status,   -- DRAFT|VALIDATED|TESTED|ACTIVE|SUPERSEDED|REVOKED
                 created_by, created_at, activated_at,
                 UNIQUE(profile_id, revision))

-- document의 평탄화. 질의용 파생물.
profile_skill(profile_revision_id FK, skill_type_id FK,
              minor, pause_allowed, cancel_allowed, blocking_types, scopes,
              PK(profile_revision_id, skill_type_id))

profile_skill_param(profile_revision_id, skill_type_id, key,
                    value_type, optional, min_value, max_value, unit,
                    PK(profile_revision_id, skill_type_id, key))

-- 개정판 시험 결과. harness가 기록하고 ACTIVATE가 참조한다.
revision_test_run(run_id PK, profile_revision_id FK,
                  suite, result,          -- PASS | FAIL
                  ran_by, ran_at, detail JSONB)

robot(robot_id PK, site_id, serial_number, display_name,
      UNIQUE(site_id, serial_number))

robot_binding(robot_id FK, profile_revision_id FK,
              bound_at, unbound_at NULL, bound_by, reason)
  CREATE UNIQUE INDEX ON robot_binding(robot_id) WHERE unbound_at IS NULL;

capability_epoch_log(robot_id, epoch, cause, detail JSONB, occurred_at)
  -- cause: BINDING_CHANGED | RUNTIME_DEGRADED | OPERATOR_BLOCKED | RESTORED

runtime_capability_override(robot_id, skill_type_id, state, cause, reason, occurred_at)
  -- state: REMOVED | RESTORED

-- 핸드셰이크 거절 수집(§5.4). 8.5의 진단 4번이 이 위에 선다.
handshake_rejection(rejection_id PK, robot_id, client_id,
                    requirement JSONB, reason_code, detail JSONB, at)

task(task_id PK, robot_id FK, profile_revision_id FK,
     revision INT, state, started_at, ended_at)
  -- profile_revision_id가 8.4의 pinning을 담당한다

audit_log(actor, action, target_type, target_id, before JSONB, after JSONB, at)
```

설계 결정 다섯.

1. **개정판은 UPDATE하지 않는다.** 고치면 새 `revision`을 만들고 활성화한다. 진행 중이던 태스크가 어느 선언으로 시작됐는지 재구성할 수 있어야 하고, 롤백이 "이전 개정판 재활성화" 한 동작이 된다. 활성화 시 같은 `profile_id`의 이전 `ACTIVE`는 `SUPERSEDED`로 전이하며, 사람이 명시적으로 폐기한 개정판만 `REVOKED`가 된다.
2. **원본(`document`)과 평탄화 테이블을 둘 다 둔다.** 원본이 진실이고 평탄화는 질의용이다. **평탄화는 트리거가 아니라 등록 시점에 애플리케이션이 계산한다** — 트리거로 두면 DB 안에 로직이 숨는다.
3. **런타임 축소는 프로파일을 건드리지 않는다.** `runtime_capability_override`에 얹는다. 팔 고장은 기종의 성질이 아니라 이 기체의 지금 상태이므로 층이 다르다. **유효 능력 = 프로파일 − 오버라이드**이며, 이 계산 결과가 `Capability` 투영이 된다.
4. **`skill_type`과 `skill_type_param`은 배포 시 동기화되는 읽기 전용이다.** 사람이 여기 INSERT하면 §8.1의 비대칭이 깨진다. `since_minor`가 있어 프로파일이 선언한 minor로 어떤 파라미터가 유효한지 판정할 수 있다.
5. **바인딩 이력을 보존한다.** 어느 기체가 언제 어느 개정판으로 돌았는지가 사고 조사의 출발점이다.

위치 레지스트리는 비목표이나, `robot`이 나중에 참조할 수 있도록 스키마를 막지 않는다.

### 8.4 런타임 갱신

```
① 등록    새 개정판 제출 → JSON Schema 검증 + proto 교차검증 → VALIDATED
          실패하면 시험·활성화 경로가 열리지 않는다
② 시험    harness가 후보 개정판으로 mimic을 띄우고 client로 계약 스위트 실행
          결과를 revision_test_run에 PASS/FAIL로 기록. PASS면 TESTED
③ 활성화  바인딩 전환. capability_epoch 증가. TESTED가 아니면 거부한다
④ 전파    다음 메시지 헤더로 소비자가 O(1) 감지 → 캐시 재조회
⑤ 롤백    이전 개정판 재활성화. 같은 경로, 같은 검증
```

**②는 `harness`가 수행한다.** `registry`는 결과를 받아 적재할 뿐 `mimic`을 알지 못한다(§3.2).

**③의 승인 조건은 둘이다** — 해당 개정판의 최신 `revision_test_run.result = PASS`, 그리고 승인자 기록. 둘 중 하나라도 없으면 활성화가 거부된다.

**진행 중인 태스크는 시작 시점 개정판으로 끝까지 간다(pinning).** `task` 레코드가 `profile_revision_id`를 들고 있으므로 갱신이 진행 중인 작업의 발밑을 바꾸지 않는다. 새 태스크부터 새 개정판을 쓴다. 설계노트 §4.3이 위치 레지스트리에서 제기한 "매핑이 바뀌는 순간 진행 중인 태스크는 어느 좌표를 쓰는가"와 같은 문제이며 답도 같다.

②가 있으므로 "일단 올려보고 깨지면 롤백"이 되지 않는다. `mimic`이 시험 도구에서 운영 장치가 되는 지점이다.

**계약(proto) 변경은 이 경로로 올 수 없다.** 배포다.

### 8.5 운영 표면

**조작 단위가 테이블 행이 아니라 의도여야 한다.** 사람이 여러 테이블을 순서대로 UPDATE하는 상황을 만들지 않는다. API 한 번 = 트랜잭션 한 번 = 감사 로그 한 줄.

조작: 기종 등록 / 개정판 올리기 / 시험 실행 요청 / 기체를 개정판으로 바인딩 / 롤백 / 능력 차단·해제(`OPERATOR_BLOCKED`).

승인 경계는 하나다 — **DRAFT 편집은 자유, ACTIVATE는 §8.4 ③의 두 조건.**

운영에서 값이 큰 쪽은 조작이 아니라 **진단**이며, 화면 한 장이 담는 것은 넷이다.

| # | 화면 | 출처 테이블 |
|---|---|---|
| 1 | 이 기체는 지금 어느 개정판인가 / 이 개정판을 쓰는 기체는 몇 대인가 | `robot_binding` |
| 2 | **두 개정판의 능력 diff** — 무엇이 늘고 줄고 세분화됐는가 | `profile_skill`, `profile_skill_param` |
| 3 | `capability_epoch` 이력과 사유 | `capability_epoch_log` |
| 4 | **어떤 클라이언트가 어떤 요구 집합으로 거절당했는가** (집계) | `handshake_rejection` |

4번이 로그에 흩어져 있으면 이기종 운영이 사실상 불가능하다. §5.4의 거절이 이유를 담은 구조체로 나오고 `registry`로 수집되므로 집계해서 세울 수 있다.

## 9. `mimic` 내부 구조

### 9.1 원칙

**거동은 프로파일에서 오고 코드는 해석기다.** 기종별 클래스가 없다. 세 번째 기종 추가는 프로파일 한 장이며 소스 변경이 0이다.

```
mimic/
  profile/     레지스트리에서 로드 · 검증 · 투영(Profile → Capability)
  engine/      스킬 상태머신 인스턴스화 · 태스크 실행 · 시계 · 재생 버퍼
  fault/       선언 실패 · 전송 장애 · 한계 집행
  control/     제어 채널 (별도 포트)
  transport/   gRPC 서버 · MQTT 발행
```

### 9.2 기동과 주소 지정

레지스트리에서 활성 개정판 로드(또는 `harness`가 지정한 후보 개정판) → JSON Schema 검증(실패 시 기동 거부) → 오버라이드 적용해 유효 능력 계산 → `Capability`로 투영 → 스킬마다 상태머신 인스턴스화 → `session_id` 발급 → 포트 개방 → `ONLINE` 발행.

**한 프로세스가 여러 가상 로봇을 호스팅하며, 기체는 요청 헤더의 `robot_id`로 지정한다.** 포트는 하나다. 따라서 "엔드포인트만 바꿔 실물과 교체"는 호스트·포트만 바뀌고 `robot_id`는 그대로라는 뜻이다.

실물 어댑터에서는 연결 소유권이 기체 단위이므로 이렇게 두지 않겠지만, `mimic`은 시험 대상이 아니라 시험 도구이므로 단순한 쪽이 낫다. **PoC의 편의이지 아키텍처 주장이 아니며 ADR에 그렇게 적는다.**

### 9.3 시계와 시드

둘 다 **제어 채널로 설정하고 조작한다**(§9.5). 이것이 없으면 §11.1의 결정성 규율이 성립하지 않는다.

- **시계 모드** — `REAL`(데모) 또는 `VIRTUAL`(시험). `VIRTUAL`에서는 시간이 저절로 흐르지 않고 `AdvanceClock(duration)`으로만 전진한다. 30초짜리 태스크가 테스트에서 즉시 통과하는 방식이다.
- **시드** — `SetSeed(seed)`. 선언된 실패 모드의 확률 추첨과 소요시간 지터가 이 시드에서 나온다.

### 9.4 실패 주입 세 갈래

1. **선언된 실패 모드** — 프로파일의 실패 모드와 발생률. 시드 기반이라 재현된다.
2. **전송 계층 장애** — 단절, 지연, **이벤트 유실(시퀀스 결손)**, 중복 명령, 이벤트 순서 역전. 프로파일이 아니라 제어 채널의 실행 옵션이다(기종 속성이 아니라 환경 속성이므로).
3. **한계 집행** — 프로파일이 선언한 파라미터 값 범위, 문자열·배열 최대 길이, 발행 간격을 그대로 강제한다. 따로 만들 것이 없으며, 클라이언트가 기종 A에서 통과하고 기종 B에서 거절당하는 상황이 여기서 나온다.

### 9.5 제어 채널

확률적 실패만으로는 "이 실패가 났을 때 클라이언트가 어떻게 행동하는가"를 시험할 수 없다. **별도 포트에 제어 서비스를 둔다.**

| RPC | 쓰임 |
|---|---|
| `SetSeed(seed)` | 결정성 |
| `SetClockMode(REAL\|VIRTUAL)` / `AdvanceClock(duration)` | 가상 시계 전진 |
| `ForceFault(task_id, error_type)` | 특정 결함을 다음 전이에서 발생 |
| `SetSingleStep(bool)` / `Step()` | 상태 전이를 한 칸씩 |
| `InjectTransportFault(kind)` | `kind` ∈ `DISCONNECT`, `DELAY`, `EVENT_LOSS`, `DUPLICATE`, `REORDER` |
| `SetConnection(ONLINE\|OFFLINE\|HIBERNATING\|BROKEN)` | 연결 상태 강제 |
| `RemoveCapability(robot_id, skill)` / `RestoreCapability(...)` | 로봇 유래 능력 축소를 흉내낸다(§8.2) |
| `DumpInternalState(robot_id)` | **시험 오라클.** 엔진 내부 상태를 그대로 반환한다 |

`DumpInternalState`가 §11.2 A-2의 오라클이다. 이것이 없으면 "재구성한 상태가 내부 상태와 일치"를 확인할 방법이 `GetSnapshot`뿐인데, 그것은 계약 표면의 투영이라 투영을 투영과 비교하는 순환이 된다.

openTCS loopback의 `tcs:virtualVehicle:currentMovementCommandFailed` 같은 명명된 제어 메시지와 단계 실행·일시정지에서 이 구조를 가져왔다.

**별도 포트인 것이 설계의 일부다.** 표준 계약과 같은 표면에 두면 프로덕션 소비자가 이 능력에 손댈 수 있고, 그러면 목이 계약을 오염시킨다. 이 서비스는 `contracts/`에 들어가지 않으며 별도 proto로 `mimic/` 안에 둔다.

### 9.6 `mimic`이 하지 않는 것

물리적 도달 가능성과 충돌은 흉내내지 않는다. 가반하중·도달 범위는 선언으로 담기지만 "이 그리퍼로 이 형상을 잡을 수 있는가"는 담기지 않는다. 3D 시뮬레이터가 아니며, 필요해지면 물리 시뮬레이터를 백엔드로 붙이는 확장이지 `mimic`이 떠안을 몫이 아니다. **표현력의 한계를 인정하는 것 자체가 이 트랙의 설계 결과물이다.**

## 10. 게이트 (`gate/`)

### 10.1 라이브러리이지 CI 스크립트가 아니다

§8.4 ①의 등록 검증과 CI 검증은 같아야 한다. 따로 구현하면 벌어지고, 벌어지면 "CI는 통과했는데 운영에서 거부"가 나온다. **구현은 하나이고 호출 지점이 둘이다** — CI와 `registry`.

호출 지점이 다르므로 **기준선(baseline)은 인자로 받는다.**

| 호출 지점 | 기준선 |
|---|---|
| CI | 기본 브랜치의 같은 프로파일 파일 (`git show origin/main:<path>`) |
| `registry` 등록 | 같은 `profile_id`의 직전 `ACTIVE` 개정판 |

기준선이 없으면(신규 프로파일) 파괴 검사는 통과로 처리한다.

### 10.2 검사 아홉

| # | 검사 | 막는 것 | 판정 방식 |
|---|---|---|---|
| 1 | `buf lint` | 명명 규칙 이탈 | buf |
| 2 | `buf breaking` | 필드 번호 재사용, 타입 변경 | buf, 기준선 대비 |
| 3 | 프로파일 JSON Schema | 잘못된 프로파일 | 스키마 검증 |
| 4 | 프로파일 ↔ proto 교차검증 | proto에 없는 스킬·파라미터 키 참조, 계약이 아는 최신 minor 초과 | 프로파일의 스킬·키를 proto 기술자와 대조 |
| 5 | `contracts/` 의존 0 | 계약 모듈이 무언가를 알게 되는 것 | 빌드 그래프에서 `contracts`의 프로젝트 의존 수 == 0 |
| 6 | 능력 어휘 파괴 검사 | §5.2 버전 규칙 위반 | 기준선 대비 diff를 분류하고 선언된 버전 증가와 대조 |
| 7 | 클라이언트 기종 분기 금지 | A-1의 "같은 클라이언트 코드" | `client/` 소스에 프로파일의 `vendor`·`model` 값 문자열이 등장하면 실패 |
| 8 | 프로파일 전용 변경 확인 | C-2의 "프로파일 한 장" | 변경 파일이 `profile/profiles/**`뿐인 PR에서 다른 모듈 소스 변경 0을 확인하고 전체 스위트 실행 |
| 9 | 음성 테스트 | 위 여덟이 실제로는 안 막고 있는 상태 | 아래 |

**6번**은 기준선 개정판과 새 개정판을 diff해 변화를 분류하고, **선언된 버전 증가가 분류와 맞는지** 본다. 선택 파라미터 추가면 minor로 충분하고, 파라미터 키 삭제·타입 변경·단위 변경·필수 파라미터 추가면 major를 요구한다. §5.2의 규칙을 사람의 규율이 아니라 기계가 집행하는 지점이다.

**7번**의 판정이 문자열 검사인 것은 의도적이다. AST 분석은 우회 방법이 많고 유지 비용이 크다. 기종 식별자가 `client/` 소스에 나타나지 못하게 막는 것만으로 "요구 집합은 설정에서 온다"(§5.4)는 규율이 강제된다.

**9번**은 의도적으로 깨는 케이스를 스위트로 유지한다 — 필드 번호 재사용, 파라미터 키 삭제 후 minor만 증가, `contracts/`에 의존 추가, proto에 없는 스킬 선언, `client/`에 기종 문자열 삽입. 각각이 CI를 실패시키지 못하면 그 자체가 실패다. 게이트를 시험하지 않으면 게이트가 조용히 죽어도 모른다.

## 11. 시험 전략

### 11.1 계층

단위(상태머신 망라성) → 계약 테스트(`mimic` ↔ `client`, 스키마 단일 출처) → 결정성 → 음성.

**결정성이 최우선 규율이다.** 확률적 실패 모드를 프로파일에 선언하는 순간 시드 없이는 테스트가 흔들리고, 흔들리는 테스트는 무시된다. **시드 + 가상 시계(§9.3) 고정 = 동일 이벤트 시퀀스**를 불변식으로 걸고, 확률이 필요한 시험은 시드를 여럿 돌려 하드 불변식으로 검사한다.

계약 테스트 스위트는 `harness/`가 소유하며, CI와 §8.4 ②가 같은 스위트를 실행한다.

### 11.2 완료 기준 · 증명 · 관측 지점

| 완료 기준 | 증명하는 시험 | 그것을 가능하게 하는 메커니즘 |
|---|---|---|
| **A-1** 능력 집합이 다른 두 로봇을 같은 클라이언트 코드로 제어 | `client`가 두 프로파일의 `mimic`에 동일 코드 경로로 태스크 완주 | 요구 집합이 설정 파일(§5.4) + 게이트 7번 |
| **A-2** 중간 구독자가 현재 상태와 이후 전이를 재구성 | 태스크 진행 중 신규 구독 → 스냅샷 + 이벤트로 재구성한 상태가 내부 상태와 일치 | `DumpInternalState`(§9.5)가 오라클, 재생 버퍼(§4.7) |
| **A-2** 결손 감지 | `sequence` 결손을 주입하면 소비자가 감지 | `InjectTransportFault(EVENT_LOSS)`(§9.5) |
| **A-4** 30초+ 태스크의 진행률·중도취소·부분결과 | 가상 시계로 압축. `CANCELLING` → 종착 순서 확인 | `SetClockMode(VIRTUAL)` + `AdvanceClock`(§9.3) |
| **A-4** 취소 불가 스킬 | `cancel_allowed=false` 스킬은 `CANCEL_UNSUPPORTED` | 프로파일 §7.4의 차이 |
| **C-1** 두 기종이 같은 스키마로, 차이가 전부 데이터 | 두 프로파일이 동일 JSON Schema 통과, §7.4의 여섯 차이가 코드 변경 없이 표현 | 게이트 3번·7번 |
| **C-2** 세 번째 기종을 프로파일 한 장으로 | `quadruped-c` 파일만 추가한 PR에서 전체 스위트 통과 | **게이트 8번이 소스 변경 0을 CI로 강제** |
| **D-1** 깨는 PR이 사람 없이 차단 | 게이트 9번의 음성 스위트 | — |
| 능력 호환성 | 핸드셰이크가 major 불일치와 `REQUIRED` 미충족을 사유와 함께 거절 | `Negotiate`(§5.4) + `handshake_rejection` 적재 |
| 런타임 축소 | `RemoveCapability` → `capability_epoch` 증가 → 캐시 무효화 → **해당 스킬만 거절되고 이동은 계속됨** | 제어 채널 → `CapabilityChanged` → `registry`(§8.2) |
| 런타임 갱신 | 무중단 활성화 — 진행 중 태스크는 옛 개정판으로 완주, 새 태스크는 새 개정판. 롤백도 같은 경로 | `task.profile_revision_id` pinning(§8.4) |

C-2의 "소스 변경 0" 강제가 가장 값이 크다. 이 프로젝트의 주장이 문장이 아니라 **CI 실패 조건**이 된다.

### 11.3 시험하지 않는 것

물리·충돌·파지 가능성. 실물과의 적합성 역검증. 다중 로봇 자원 경합과 배차. 성능·부하. **`mimic`이 통과시킨 계약이 실물에서도 통과한다는 보장은 이 범위에서 나오지 않는다.**

## 12. 남길 결정 기록 (ADR · D-2)

1. 계약은 proto, 프로파일은 JSON Schema — 이유와 대가
2. 실패 3분류를 계약에서 빼고 두 불리언으로 대체
3. 능력 동일성을 major로 결정하는 규칙과 그 집행 한계
4. 경로·헤더·페이로드의 역할 분리
5. 검증을 세 시점으로 나눈 이유와 프로덕션 샘플링
6. gRPC와 MQTT의 역할 분리, 순서를 계약이 보장하는 이유
7. 개정판 불변 + 바인딩 이력 보존
8. 진행 중 태스크의 개정판 pinning
9. 능력 축소의 두 경로와 `registry`가 유일한 기록자인 이유
10. `harness`를 분리해 `registry ↔ mimic` 순환을 피한 것
11. `mimic` 한 프로세스 다중 로봇 — PoC 편의이지 아키텍처 주장이 아님
12. `gate`를 라이브러리로 두고 CI·registry가 기준선만 달리해 공유
13. openTCS·VDA5050에서 가져온 것과 반례로 쓴 것의 출처

## 13. 알려진 한계

1. **버전 규칙은 벤더가 지킬 때만 작동한다.** 선언을 그대로 두고 거동만 바꾸면 §10.2의 6번 diff에 아무것도 나오지 않는다. `Pick`이 "잡았다"에서 "잡고 들어올렸다"로 바뀌는 경우가 이에 해당한다. 적합성 역검증에서만 드러나며, 그때까지의 방어는 "프로파일을 벤더 문서에서 파생시키고 괴리가 발견되면 프로파일을 고친다" 하나다.
2. **프로파일의 표현력에 한계가 있다.** 가반하중과 도달 범위는 담기지만 형상별 파지 가능성은 담기지 않는다. 어디까지를 선언으로 두고 어디부터를 시도해봐야 아는 것으로 남길지가 이 트랙의 진짜 설계 문제이며, 이 문서는 물리적 도달 가능성과 충돌을 선언 밖에 둔다.
3. **`mimic`이 계약을 정의해버릴 위험이 있다.** 실물이 없는 동안 계약이 에뮬레이터에 맞춰 굳을 수 있다. 적합성 역검증이 이를 막는 장치이며 그전까지는 프로파일을 벤더 문서에서 파생시키는 규율에 의존한다.
4. **`contract_revision` 불일치를 경보로만 다룬다.** minor 호환이면 정상 동작해야 하므로 차단할 수 없고, 따라서 실제로 호환되지 않는 minor 변경이 있었다면 이 신호는 사후 조사에만 쓰인다.
5. **재생 버퍼가 프로세스 메모리에 있다.** 재기동하면 세션이 바뀌고 소비자는 스냅샷부터 다시 세운다. 지속 저장은 B-1의 몫이며 비목표다.
6. **게이트 7번이 문자열 검사다.** 기종 식별자를 상수로 두지 않고 계산해 만들면 우회된다. 우회를 막는 것이 아니라 실수를 막는 장치다.
7. **다중 사이트는 스키마와 토픽 경로에만 반영돼 있다.** `site_id`가 있고 경로에 자리가 있으나, 사이트별 배포 격리와 브리지 정책은 비목표다.
