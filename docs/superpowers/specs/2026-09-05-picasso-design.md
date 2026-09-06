# picasso — 이기종 로봇 표준 I/F 계약과 운영 변경 체계

설계 문서 · 2026-09-05

## 1. 목적과 범위

### 1.1 목적

이기종 모바일 로봇(휴머노이드·4족보행)을 공장 운영 시스템에 연계할 때 필요한 **표준 I/F 계약**을 정의하고, 그 계약을 **실물 없이 검증할 수 있는 상대**를 만들고, **운영 중 변경을 계산 가능하게** 만든다.

주장 둘이다.

1. **이기종 대응은 코드가 아니라 프로파일 교체여야 한다.**
2. **운영 변경은 파급을 미리 계산할 수 있어야 한다.** 계산할 수 없으면 모든 변경이 도박이고, 도박이면 아무도 변경하지 않게 되어 시스템이 굳는다.

두 주장 모두 데모가 아니라 CI 실패 조건 또는 조작 거부 조건으로 만든다.

**조직 원리 하나 — 추가는 안전하고 삭제는 위험하다.** §9의 모든 규칙이 이 비대칭에서 나온다.

### 1.2 범위

근거 문서 `이기종-로봇-공장-연계-설계노트` §5 백로그 기준:

| 항목 | 내용 |
|---|---|
| A-1 | 스킬 모델의 proto 이식 |
| A-2 | 이벤트 계약 (상태/이벤트 분리, 스냅샷, 순서, 멱등, 결함 모델) |
| A-4 | 장기 실행 작업 모델 (goal/progress/result/cancel/retry) |
| C-1 | 능력 프로파일 스키마 |
| C-2 | 프로파일 주도 에뮬레이터 (MiMic) |
| D-1 | 스키마 호환성 게이트 |
| D-2 | 결정 기록 (ADR) — 목록은 §14 |

설계 과정에서 추가된 셋:

| 항목 | 사유 |
|---|---|
| 능력 호환성 체계 | 제조사·버전에 따라 능력이 늘고 줄고 세분화된다. API·헤더·토픽·키 설계와 통신 시 검증 전략이 필요하다 |
| 레지스트리와 런타임 갱신 | 프로파일은 설계노트 §4.5 기준으로 데이터이며, 갱신을 위해 프로세스를 내리면 안 된다 |
| **운영 변경 체계** (§9) | 어댑터 추가·변경, 기능 추가·변경·삭제가 운영에서 실제로 벌어진다. 파급 범위와 반영 순서가 정립돼 있어야 하며, 상위 시스템까지의 순서가 특히 그렇다 |

구현은 §13의 네 단계로 나눈다.

### 1.3 비목표

- **A-3 원자적 명령 전달의 대안 비교** — 파라미터와 실행을 단일 RPC로 묶어 경쟁 조건을 만들지 않는 쪽을 택한다.
- **A-5 이동·내비게이션 스킬 타입** — 스킬 타입 어휘를 늘리는 일이며 구조는 A-1이 이미 담는다.
- **A-6 위치 레지스트리** — 스키마가 이를 막지 않도록만 두고 구현하지 않는다.
- **B-1 무선 단절 대응의 집행** — 재기동 후 재개 지점 복원과 저장소 커밋. 다만 **멱등성 키는 계약에 지금 넣는다.**
- **B-2 텔레메트리 경로 분리** — 따라서 프로파일은 텔레메트리 **항목**을 담지 않는다.
- **B-3 브리지 정책.**
- **C-3 적합성 역검증의 실행** — 실물이 도착해야 의미가 생긴다. 다만 **워크플로우상의 자리와 상태는 만든다**(§9.7 ④).
- **D-3 예외 구역 경계 집행** (사이트 분기 정적 검사).
- **미션 계층** — 배차·라우팅·자원 중재·다중 로봇 경합. 따라서 **동시성 정책 어휘(blocking type)도 담지 않는다.**
- **즉시 명령(instant action)** — 태스크 한 단계로 표현할 수 있으므로 별도 표면을 두지 않는다.
- **상위 시스템 어댑터(ACL) 구현** — MES·WMS·WCS별 변환기는 만들지 않는다. **다만 상위가 붙을 표면과 순서 규약은 만든다**(§9.6).
- **물리 시뮬레이션** — 도달 가능성, 충돌, 파지 가능성.
- **인증·인가 체계** — §6.3에 전제를 적는다.
- **정책 설정 저장소** — 임계·상한을 데이터로 두는 일은 소비자가 생긴 뒤에 만든다.

### 1.4 완료 기준

§12.2에 **24행**으로 정리한다. 백로그 15행(A-1 1, A-2 4, A-4 7, C-1 2, C-2 1), D-1 1행, 능력 호환성 1행, 레지스트리·런타임 갱신 2행, 운영 변경 5행이다.

A-4가 일곱으로 늘어난 것은 실물 조사(§2.3)의 결과다 — 취소가 복구를 동반하고, 실패가 사람 개입 대기일 수 있고, 종착이 래치되지 않는 로봇이 있고, 제어권을 빼앗길 수 있다는 사실이 전부 태스크 생명주기에 걸린다.

## 2. 배경과 근거

### 2.1 근거 문서

`이기종-로봇-공장-연계-설계노트`(38쪽). 계층 구조(L3~L5), 여섯 무대, 변경 수용 전략, 구현 스택 선택, API 규격과 페이로드 카탈로그, §5 작업 백로그가 여기에 있다.

### 2.2 벤치마킹

두 저장소를 직접 추적해 확인했다. 클론: `Labs/[oss]/opentcs`, `Labs/[oss]/VDA5050`(버전 3.0.0). 둘 다 MIT 라이선스다. **코드를 전사하지 않고 설계 기법만 가져오며 출처는 ADR에 남긴다.** openTCS의 LLM 사용 금지 조항은 openTCS에 기여할 때의 규칙이므로 참조에는 적용되지 않는다.

| 가져온 것 | 출처 | 쓰이는 곳 |
|---|---|---|
| 실패 등급을 "남은 능력"으로 정의 | VDA5050 `state.schema` `errorLevel` | §4.6 |
| `RETRIABLE`을 상태로 | VDA5050 `actionStatus` | §4.4 |
| 결함 수명, `errorHint`, `errorReferences` | VDA5050 에러 카탈로그 | §4.6 |
| 연결 상태 4값 — 침묵의 세 원인을 구분 | VDA5050 `connection.schema` | §4.7 |
| 상태 발행 30초 상한 | VDA5050 §6.6 (`VDA5050_EN.md:1034`) | §7.2 |
| `(orderId, orderUpdateId)` 단조쌍 = 멱등성 키, 재수신 4케이스 | VDA5050 §6.1.4 | §4.4 |
| 능력 프로파일 구조 전반 | VDA5050 `factsheet.schema` | §7.2 |
| `pauseAllowed`/`cancelAllowed` 필수 선언 | 同 `mobileRobotActions` | §7.2 |
| `optionalParameters {parameter, support}` | 同 `protocolFeatures` | §5.3 |
| `protocolLimits` — 한계를 데이터로 | 同 | §7.2, §10.4 |
| 메이저 버전을 토픽 경로에 | VDA5050 §4 | §5.5 |
| 거절에 이유를 붙이는 `ExplainedBoolean` | openTCS SPI 4곳 | §5.4 |
| 취소는 즉시가 아니다 (`WITHDRAWN`) | openTCS `TransportOrder.State` | §4.4 |
| 에뮬레이터 제어 채널·단계 실행 | openTCS loopback | §10.5 |
| 계약 모듈 의존 0 | openTCS `opentcs-api-base` | §3.2 |
| 송신까지 검증하는 스키마 게이트 | openTCS | §6.2 |

**반례로만 쓰는 것 둘.**

- openTCS의 VDA5050 어댑터는 factsheet를 구독해 놓고 버린다(`v2_0/CommAdapterImpl.java:529`). 능력은 사람이 타이핑한 문자열에서 오고 미선언은 fail-open이다. 교훈은 **"선언 메커니즘이 있어도 소비하지 않으면 무의미하다"**이며, 이 문서는 두 장치로 피한다 — 정적으로는 §11.2의 4번, **런타임으로는 §12.2의 투영 일치 시험**(`GetCapabilities` 응답이 프로파일에서 파생한 기대 능력과 정확히 일치). 정적 검사만으로는 구현이 능력을 하드코딩해도 걸리지 않는다.
- openTCS의 스키마 검증 플래그는 이름이 `VALIDATE_INCOMING_MESSAGES`인데 끄면 송신 검증까지 함께 꺼진다 — §6.2에서 플래그를 분리하는 이유다.

**openTCS loopback은 프로파일이 아니다.** 노브가 여섯 개뿐이고 거동은 코드다. 기종 추가 = 코드 추가. C-1/C-2 차별점의 근거다.

### 2.3 실물 로봇 조사 (2026-09-05)

계약이 실물을 담을 수 있는지 확인하려고 SDK가 공개된 것과 문서가 회수 가능한 것 셋을 직접 조사했다. **이 절이 §4의 여러 결정의 근거이며, 다시 조사하지 말고 여기서 인용한다.**

**Boston Dynamics Spot** — 4족보행. gRPC + protobuf. **우리 설계의 정본 사례다.** `DirectoryService.ListServiceEntries`로 런타임 서비스 발견, `RobotIdService.GetRobotId`로 신원(`serial_number`·`species`·`software_release`), `GetRobotHardwareConfiguration`이 `can_power_command_request_*`·`has_audio_visual_system` 같은 **능력 불리언**을 반환한다. 결함은 `SystemFault{severity, dtc, attributes}` + `BehaviorFault{Cause ∈ FALL|HARDWARE|LEASE_TIMEOUT, Status ∈ CLEARABLE|UNCLEARABLE}`로 **복구 가능성을 로봇이 스스로 판정**한다. 소유권은 `Lease{resource, epoch, sequence[]}` 벡터 클럭.
⚠️ **라이선스 `20191101-BDSDK-SL` §2(c)가 "BD 하드웨어 전용"이고 §2(b)가 재라이선스를 금한다. Spot proto·생성 스텁을 벤더 중립 공통 계층에 넣으면 위반이다** — 개념만 차용하고 코드는 Spot 어댑터 안에 격리한다.

**Unitree G1** — 휴머노이드. **양산 휴머노이드 중 SDK가 공개된 사실상 유일한 것**이며 BSD-3-Clause라 제약이 없다. CycloneDDS 기반이고 `unitree_ros2`는 브리지가 아니라 같은 DDS wire에 rmw로 직접 참여한다. **그러나 능력 표현이 빈약하다** — 고수준 API가 전부 `int fsm_id` 매직 넘버 위의 얇은 래퍼이고(`Damp()=SetFsmId(1)`, `Start()=SetFsmId(500)`), **로봇 신원 질의가 아예 없으며**, 결함 목록을 보고하지 않아 클라이언트가 `terminations.hpp`로 과열·자세이상을 **스스로 판정**한다. 조인트 한계는 SDK가 아니라 URDF `<limit lower upper effort velocity>`에만 있다. 인증이 없어 네트워크 도달이 곧 전권이다.

**Agility Digit / Arc** — 휴머노이드. 로봇측은 WebSocket JSON API(`ws://<ip>:8080`, 서브프로토콜 `json-v1-agility`), 봉투는 `["type", {...}, refnum]`. **우리 계약과 충돌하는 사실 넷:**
- `change-action-command` 권한을 **전 시스템에서 한 클라이언트만** 보유하고, 빼앗기면 로봇이 즉시 `action-idle`로 리셋된다 → §4.9
- **취소·일시정지 프리미티브가 없다.** 중단은 다른 액션으로 덮어쓰기이고 `remove-action`은 컨테이너에 성공한 것처럼 보인다 → §7.2의 `Support` 3값
- 매뉴얼이 명시한다 — *"This status does not latch once reached"*. `success`에서 `running`으로 되돌아갈 수 있다 → §4.4의 래치 불변식과 `TERMINAL_STATE_VIOLATED`
- 실패 사유가 사람이 읽는 자유 문자열 `info`뿐이다 → `error_type` 어휘를 어댑터가 합성해야 한다

**Arc(클라우드)는 우리와 독립적으로 같은 결론에 도달해 있었다.** 도메인이 `organization → facility → workcell → device` + `workflow`/`skill`/`intervention`이고, **능력 선언이 `(deviceModelIds, oasVersion) → 블록 집합` 조회이며 워크플로가 그 버전에 핀 고정된다** — §8.4의 개정판 pinning과 같은 구조다. UI가 "No model guarantee"라고 경고하는 것은 §15.1의 한계를 그들도 안고 있다는 뜻이다. 그리고 워크플로 상태에 `CANCELED_WITH_RECOVERY` / `CANCELED_RUNNING_RECOVERY` / `CANCELED_FAILED_RECOVERY` 세 변종과 `INTERVENTION` 1급 개념이 있다 → §4.4의 `NEEDS_INTERVENTION`·`CANCELLED_RECOVERY_FAILED`의 근거.

**프로파일 필드가 실물에서 채워지는가 — 대조 결과.**

| §7.2 항목 | Spot | Unitree G1 | Digit |
|---|---|---|---|
| 기종 좌표 | ✅ `RobotId` | ❌ 신원 질의 없음 | △ `robot-info` 4필드 |
| 지원 스킬·버전 | ✅ `ListServiceEntries` | △ 팔만 `GetActionList` | ✅ 액션 어휘 고정 |
| 취소·일시정지 가능 여부 | △ 선언 없음 | ❌ | ❌ 개념 자체가 없음 |
| 파라미터 값 범위·단위 | △ `Skeleton` | ✅ URDF `<limit>` | △ 문서상 5 kg |
| 프로토콜 한계 | ❌ | ❌ | ❌ |
| 실패 모드 | ✅✅ | ❌ 클라이언트가 판정 | ❌ 자유 문자열 |
| 상태 발행 간격 | △ `liveness_timeout_secs` | ❌ | ✅ `query-group{period}` |

**어느 실물도 프로파일을 전부 채우지 못하며 그것이 정상이다.** 프로파일은 로봇이 선언하는 것이 아니라 **우리가 벤더 문서에서 파생시키는 것**이기 때문이다(§15.1). 다만 이 대조가 스키마 결함 하나를 드러냈다 — **"지원하지 않음"과 "근거가 없어 모름"을 구분하지 못하면 Digit 프로파일 작성자가 거짓말을 하게 된다.** `Support` 3값이 여기서 나왔다.

**표준 지형 — 2026년에 두 가지가 바뀌었다.**
- **ISO 21423**(산업용 모바일 로봇 통신·상호운용, MQTT+JSON, MassRobotics와 VDA5050을 통합)이 **stage 60.00, 2026-07-21**로 발행 임박이다. `IMR Identity and Capability Report`에 `capabilities` 객체가 있다. **유료라 본문 미확인** — 우리 발행 경로가 이것과 정렬될지 그 위에 앉을지는 열린 결정이다.
- **IDTA 02020 Capability Description**(AAS 능력 서브모델)이 **2026-04-15 발행**됐고, 그 §1.8.4가 *"A generic skill Submodel has yet to be developed"*라고 적고 있다. **능력은 표준화됐고 스킬은 비어 있다.**

그리고 가장 말해주는 증거 — **NVIDIA `isaac_mission_control`이 VDA5050의 AGV class 열거를 규격 밖으로 포크해 `MANIPULATOR`와 `HUMANOID`를 추가했다.** VDA5050 3.0의 `mobileRobotKinematics`는 여전히 전부 바퀴이고 `mobileRobotClass`는 전부 운반이며, 저장소 이슈·PR 전수 검색에서 `humanoid`는 0건이다. **표준이 형태를 표현하지 못한다는 것을 업계가 코드로 인정한 것이다.**

## 3. 아키텍처

### 3.1 모듈

```
picasso/
  contracts/          proto. 스킬·태스크·이벤트·결함           A-1 A-2 A-4
  profile/
    schema/           능력 프로파일 JSON Schema                C-1
    profiles/         기종 프로파일 문서들 (*.json)
    fixtures/         게이트·시험 전용 픽스처 (프로파일·요구 집합)
  profile-model/      프로파일 문서의 읽기 전용 모델 — gate·mimic 공유 (ADR 29)
  registry/           개정판·어댑터·원장·변경 계획·카탈로그    8·9절
  mimic/              프로파일 주도 에뮬레이터 + 제어 채널      C-2
  client/             계약 소비자 — 완료 기준 증명용
  gate/
    src/              검증 라이브러리. CI와 registry가 호출     D-1
    negative/         음성 케이스를 **데이터로** 보관 (깨진 proto 조각, 합성 diff)
  harness/            계약 스위트 실행기                        12절
  docs/adr/           결정 기록                                 D-2
```

`profile/profiles/**`와 `profile/fixtures/**`를 나누는 것은 규약이다 — §11.2의 8번 검사가 앞의 경로로만 "프로파일만 바뀐 PR"을 판정하므로, 픽스처가 섞이면 판정이 흐려진다.

### 3.2 의존 규칙

**`contracts/`는 프로젝트 내 의존이 0이다.** openTCS `opentcs-api-base`와 같은 구조다. §11.2의 5번 검사가 CI로 강제한다.

**빌드 의존:**

```
profile-model → (없음)
gate          → profile-model   ※ 아래 단서
registry      → gate, contracts
mimic         → profile-model, contracts
client        → contracts, profile-model   ※ 아래 단서
harness       → mimic, client, contracts
```

**`profile-model`은 2단계에서 신설했다(ADR 29).** 프로파일 문서를 읽는 코드가 `gate`와 `mimic` 양쪽에 필요한데, `mimic`이 `gate`에 의존하면 buf 실행기와 검사 아홉을 끌고 오고, 두 벌로 쓰면 이 프로젝트가 막으려는 바로 그 드리프트를 우리가 낸다. `ProfileDocument.NON_PROJECTION`이 §7.2의 비투영 집합이므로 게이트 6번과 `mimic`의 투영이 **같은 상수를 쓰는 것이 옳다**는 부수 이득도 있다.

**`client → profile-model`은 2단계 Chunk 3a에서 더했다.** §5.4가 요구 집합을 "코드가 아니라 설정"이라 못박았고 그 파일을 읽는 코드가 `client`(만드는 쪽)와 `mimic`(판정하는 쪽) 양쪽에 필요한데, `client → mimic`은 이 표가 금지한다. ADR 29가 `profile-model`을 만든 것과 같은 이유다. **대가는 `ProfileDocument`(기종 저작 형식)가 `client`의 클래스패스에 들어온다는 것이고**, 얇은 소비자가 그것을 읽기 시작하면 §11.2의 7번이 막으려는 바로 그것이 된다 — `ClientBoundaryTest`가 그 선을 지킨다.

**`gate`는 `contracts`에 빌드 의존을 걸지 않는다(1단계 실측 반영).** `buf.gen.yaml`이 Java를 생성하지 않으므로 의존해 봐야 클래스패스에 얹힐 것이 없고, `gate`가 필요한 것은 생성 코드가 아니라 `buf build`가 만든 `FileDescriptorSet` **바이트**다. 그것은 런타임 입력(`Resource.CONTRACT_DESCRIPTOR`)으로 받는다. 이 선택의 대가는 **`buf build`가 `./gradlew build`보다 먼저 돌아야 한다**는 것이고, 새 클론에서 그 순서를 어기면 `ContractIndexTest`가 만드는 법을 찍고 실패한다. Gradle 태스크 의존이 아니라 순서에 기대는 것이므로 CI의 스텝 순서가 그 계약이다.

여기서 검사 5번이 이 불일치를 잡지 못한다는 점을 적어 둔다 — 5번은 `contracts`의 의존만 센다.

**런타임 접근 (HTTP·MQTT. 빌드 의존이 아니며 상대가 없어도 모듈이 빌드·동작한다):**

| 방향 | 무엇 | 없을 때 |
|---|---|---|
| `mimic` ⇢ `registry` | 프로파일 로드·폴링(§10.2·§10.3) | 파일 모드로 동작 |
| `mimic` ⇢ `registry` | 핸드셰이크 결과 보고(§5.4) | 로컬 파일에 기록 |
| `mimic` ⇢ 브로커 | 상태·이벤트·연결 발행 | — |
| `registry` ⇠ 브로커 | 이벤트 **구독** — 능력 변경, **태스크 전이**(§8.3의 `task` 적재), 결함 | 해당 테이블이 비고 §9.3의 드레인 판정이 불가 |
| `registry` ⇢ 브로커 | **사이트 카탈로그 스트림 발행**(§9.6) | 상위가 폴링으로 대체 |
| `harness` ⇢ `registry` | 시험 요청 폴링·결과 보고(§8.4 ②) | 직접 실행 모드 |
| `client` ⇢ `mimic` | gRPC·MQTT | — |
| `client` ⇢ `registry` | 요구 등록(§9.2), 소비자 측 결함 보고(§6.2) | 원장 없이 동작(파급 계산 불가) |

**순환을 피하는 두 규칙.**

1. **`registry`는 `mimic`도 `harness`도 모른다.** 시험은 `harness`가 수행하고, `registry`는 시험 요청을 `revision_test_request` 행으로 **적재만** 한다. `harness`가 폴링해 집어간 뒤 결과를 API로 보고한다.
2. **`registry`는 어느 모듈에도 직접 밀지 않는다.** 갱신 반영은 `mimic`이 당기고(§10.3), 관측은 브로커 구독으로 받는다. **브로커에 발행하는 것은 이 규칙의 예외가 아니다** — 발행은 특정 모듈을 지목하지 않으므로 간선이 생기지 않는다. 카탈로그 스트림이 그 경우다.

### 3.3 각 모듈의 책임

- **`contracts/`** — 런타임에 로봇이 말해야 하는 것의 구조, **그리고 그 구조를 채우는 규칙** — §5.5의 헤더 표와 계약 신원(`contract_digest`·`contract_semver`). 헤더 표를 발신자 쪽에만 두면 `client`가 요청 열을 두 번째로 옮겨 적게 되므로 계약이 갖는다. 값이나 제약은 담지 않는다.
- **`profile/`** — 기종을 기술하는 선언 형식과 실제 프로파일들. 값과 제약만 담는다.
- **`registry/`** — 개정판·어댑터의 수명주기, 바인딩, **의존 원장, 변경 계획, 능력 카탈로그**, 진단 표면.
- **`mimic/`** — 프로파일을 읽어 계약을 구현하는 서비스 가상화 계층. "어댑터 + 로봇" 한 쌍을 대신한다.
- **`client/`** — 계약을 두드려 완료 기준을 증명하는 얇은 소비자. **오케스트레이터가 아니다.**
- **`gate/`** — 검증 규칙의 단일 구현. CI와 `registry`가 기준선만 달리해 같은 코드를 호출한다.
- **`harness/`** — 지정한 프로파일로 `mimic`을 띄우고 `client`로 계약 스위트를 돌린 뒤 결과를 보고한다.

### 3.4 구현 스택

설계노트 §4.9를 따른다. 전부 **Kotlin**, Spring Boot이되 도메인 로직은 프레임워크를 모른다. MVC + 가상 스레드 + 도메인 층 코루틴이며 WebFlux는 쓰지 않는다.

**`mimic`도 Kotlin으로 둔다** — 하는 일이 스킬별 상태머신 인스턴스화와 실패 분류의 망라 처리이므로 sealed + `when` 망라성의 이득이 가장 직접 적용된다.

저장소는 **PostgreSQL**(JSONB 사용).

### 3.5 전송 경계

| 전송 | 담는 것 |
|---|---|
| **gRPC** | 명령과 질의 — §4.4의 RPC 전부, `Negotiate`, `GetSnapshot`, `GetCapabilities` |
| **MQTT** | 발행 — 상태, 이벤트, 연결 상태 |

**§4.7이 정의하는 이벤트는 전부 MQTT `event` 스트림에 나간다.** 태스크 상태 전이도 예외가 아니다. `WatchTask` 스트림은 그 위에 **진행률과 부분결과를 더해** 요청자에게만 보낸다. 따라서 **전이의 관찰은 `event` 스트림만으로 충분하고**, 중간에 구독을 시작한 소비자는 스냅샷 하나를 더해 현재를 세운다.

**두 스트림은 서로 다른 카운터를 쓴다.**

| 카운터 | 범위 | 쓰임 |
|---|---|---|
| `sequence` | **기체 단위**, 세션 안에서 단조 증가 | MQTT `state`·`event`. 결손 감지와 재정렬 |
| `update_index` | **태스크 단위**, 0부터 단조 증가 | gRPC `WatchTask` 스트림. 재접속 시 재개 지점 |

나누지 않으면 gRPC에만 나가는 진행률 메시지가 소비한 번호를 MQTT 소비자가 결손으로 오탐한다.

토픽의 `{stream}` 값 집합은 셋이다 — `state`, `event`, `connection`.

**순서 보장.** MQTT에는 파티션 개념이 없다. 토픽이 기체 단위로 갈라져 있으므로 순서 보장의 단위는 **토픽**이며, 소비자는 `sequence`로 재정렬하고 결손을 감지한다. **순서는 전송이 아니라 계약이 보장한다.**

**재정렬 창.** 소비자는 `sequence` 불연속을 만나면 **재정렬 창** 동안 판정을 보류한다 — 기본값은 *뒤따르는 이벤트 8개 또는 2초 중 먼저 오는 쪽*이며 소비자 설정이다. 창이 지나도 오지 않으면 결손으로 확정한다. 이 임계가 없으면 `REORDER`와 `EVENT_LOSS`를 구분하는 시험(§12.2의 3번)이 결정적이지 않다.

## 4. 계약 (`contracts/`)

### 4.1 파일 구성

| 파일 | 담는 것 | 범위 항목 |
|---|---|---|
| `common.proto` | **공통 헤더**, `Reference`, `Lifetime`, `ProfileRef`, `Support`, `Resolution`, `RejectionCode`, `Rejection`. **의존이 없다** | — |
| `fault.proto` | 결함 모델 | A-2 |
| `skill.proto` | 스킬 상태머신, `Capability`, `GetCapabilities`, `Negotiate` | A-1 |
| `skill_catalog.proto` | **계약이 소유하는 스킬 타입 어휘.** `since_minor`·`is_optional`·`skill_type_max_minor`를 proto 커스텀 옵션으로 싣는다 | A-1 |
| `task.proto` | 장기 실행 태스크 RPC와 상태 | A-4 |
| `event.proto` | 이벤트, `StateMessage`, `GetSnapshot`, `ReplayEvents`, `CapabilityChanged`, 연결 상태. 위 넷을 참조하므로 마지막 | A-2 |

**공통 헤더가 `common.proto`에 있는 이유**는 import 순환이다. 헤더를 `event.proto`에 두면 `skill.proto`가 헤더를 쓰려고 `event.proto`를 import하고, `event.proto`는 이벤트 본문에 `SkillState`·`TaskState`를 담으려고 그 둘을 import하게 되어 protoc이 거부하는 순환이 생긴다.

**`skill_catalog.proto`가 따로 있는 이유**는 §5.2가 "계약은 스킬 타입마다 major별 정의를 갖고 파라미터마다 몇 번째 minor부터 존재하는가를 기록한다"고 요구하고 §8.3의 결정 4가 `skill_type`·`skill_type_param`을 "proto에서 동기화되는 읽기 전용 투영"으로 규정하기 때문이다. 어휘를 proto에 두면 **`buf breaking`이 파라미터 삭제·타입 변경을 공짜로 막아준다** — §11.2의 6번은 그 위에서 "선언된 버전 증가가 변화 분류와 맞는가"만 보면 된다. `skill.proto`와 나누는 것은 변경 이유가 다르기 때문이다(기계는 안정적이고 어휘는 자란다).

**`max_minor`와 `optional`은 유도하지 않고 선언한다.** `max(since_minor)`로 최신 minor를 유도하면 파라미터를 더하지 않는 minor 증가를 표현하지 못하고, `since_minor > 0`으로 선택 여부를 유도하면 처음부터 선택인 파라미터를 잡지 못한다. 둘 다 전용 옵션을 둔다.

### 4.2 스킬 상태머신

OPC UA Skill 모델(fortiss / VDMA·OPC Foundation SOArc)의 구조를 언어 중립 계약으로 옮긴다.

| 상태 | 뜻 |
|---|---|
| `READY` | 실행 가능. 초기 상태이자 복귀 상태 |
| `RUNNING` | 실행 중 |
| `SUSPENDED` | 일시정지 |
| `HALTED` | 중단됨. `Reset` 없이는 다시 실행할 수 없다 |

| 전이 | 출발 | 도착 | 유발 |
|---|---|---|---|
| `Start` | `READY` | `RUNNING` | `StartTask` / `RetryTask` (파라미터를 함께 싣는다) |
| `Suspend` | `RUNNING` | `SUSPENDED` | `PauseTask`. `pause_support ≠ NO`인 스킬만 |
| `Resume` | `SUSPENDED` | `RUNNING` | `ResumeTask` |
| `Halt` | `RUNNING`, `SUSPENDED` | `HALTED` | 취소 확정, 또는 `can_continue_current_task=false`인 결함 |
| `Complete` | `RUNNING` | `READY` | 정상 완료 |
| `Reset` | `HALTED` | `READY` | **엔진 내부 전이.** 셋 중 하나 직후 자동 수행 — 태스크가 종착에 듦, `RETRIABLE`이 됨, **갱신으로 스킬을 다시 시작함**(§4.4) |

표에 없는 조합은 전부 불법이며 `INVALID_TRANSITION`으로 거절한다. 이 표가 §12.1의 "상태머신 망라성" 단위 시험의 대상이며, Kotlin sealed + `when` 망라성이 검사하는 대상이다.

`Reset`을 RPC로 두지 않는 것이 결정이다. 외부에 노출하면 "누가 언제 리셋하는가"라는 정책이 계약에 들어오는데, 그 정책의 주인은 미션 계층이고 비목표다.

**파라미터와 실행 요청을 단일 RPC로 묶는다.** 참조 모델이 인정한 경쟁 조건은 둘을 나누지 않으면 발생하지 않는다.

### 4.3 값 어휘

| 이름 | 값 |
|---|---|
| `SkillState` | `READY`, `RUNNING`, `SUSPENDED`, `HALTED` |
| `TaskState` | §4.4의 표 |
| `ConnectionState` | `ONLINE`, `OFFLINE`, `HIBERNATING`, `CONNECTION_BROKEN` |
| `ValueType` | `BOOL`, `INTEGER`, `NUMBER`, `STRING`, `ENUM` |
| `Reference` | `{key, value}`. `key` ∈ `task_id`, `skill_id`, `robot_id`, `parameter_key` |
| `Lifetime` | `UNTIL_CLEARED`, `UNTIL_NEW_TASK`, `UNTIL(timestamp)` |
| `Support` | `YES`, `NO`, `UNKNOWN` — 3값인 이유는 §7.2 |
| — | **아래 표의 값 이름은 개념 이름이다.** proto에서는 `ENUM_VALUE_PREFIX` 규칙 때문에 enum 이름 접두사가 붙고 0값 `_UNSPECIFIED`가 앞에 온다 — 예: `CONNECTION_BROKEN` → `CONNECTION_STATE_CONNECTION_BROKEN`, `YES` → `SUPPORT_YES`. **wire 상의 이름은 접두사가 붙은 쪽이다.** 값 이름이 바뀌는 것은 §11.2의 6번이 다루는 변화이므로 이 대응을 여기 못 박는다 |
| `Resolution` | `SELF_RETRIABLE`, `NEEDS_INTERVENTION`, `TERMINAL` — 실패 모드가 어떻게 풀리는가 |
| `RejectionCode` | 아래 |

`RejectionCode`는 쓰이는 표면에 따라 나뉜다.

| 표면 | 코드 |
|---|---|
| `Negotiate` (다섯) | `MAJOR_MISMATCH`, `SKILL_ABSENT`, `REQUIRED_OPTIONAL_MISSING`, `LIMIT_EXCEEDED`, `IDENTITY_MISMATCH` |
| 태스크 RPC (여덟) | `CAPABILITY_WITHDRAWN`, `CANCEL_UNSUPPORTED`, `PAUSE_UNSUPPORTED`, `OUTDATED_REVISION`, `INVALID_TRANSITION`, `SKILL_ABSENT`, `IDENTITY_MISMATCH`, `PARAMETER_INVALID` |
| `ReplayEvents` (하나) | `SEQUENCE_EVICTED` |

**`CAPABILITY_WITHDRAWN`은 핸드셰이크 이후 능력이 사라진 스킬로 `StartTask`가 왔을 때 반환**하며, 응답에 현재 `capability_epoch`를 실어 클라이언트가 능력을 다시 가져오게 한다. **애초에 선언한 적 없는 스킬은 `SKILL_ABSENT`다** — 전자는 소비자가 캐시를 다시 세우면 되고 후자는 요구 집합이 틀린 것이라 대응이 다르다.

**`PARAMETER_INVALID`는 파라미터가 프로파일의 선언을 어겼을 때다** — 모르는 코어 키(§5.3의 fail-closed), 필수 누락, 값 범위·허용 값·최대 길이 위반(§10.4 ③). 이 코드가 없으면 §10.4 ③이 말하는 "클라이언트가 기종 A에서 통과하고 기종 B에서 거절당하는 상황"이 소비자에게 **다른 종류의 사건**으로 보인다 — 억지로 기존 코드에 접거나 전송 계층 오류로 내보내야 하기 때문이다.

**판정의 자리를 가르는 규칙은 하나다** — **요청을 해석하지 못했으면 gRPC 상태, 해석했는데 거절하면 응답 `oneof`의 `Rejection`.** 모르는 `robot_id`·`task_id`는 `NOT_FOUND`, 빈 `robot_id`와 해석 불가능한 요구 문자열은 `INVALID_ARGUMENT`, 로그 범위를 넘는 `from_update_index`는 `OUT_OF_RANGE`, 요청 헤더의 계약 major 불일치는 `FAILED_PRECONDITION`이다. 표면마다 자리를 달리 정하면 소비자가 RPC마다 다른 분기를 쓰게 된다. **예외는 `GetCapabilities` 하나다** — `GetCapabilitiesResponse`에 `Rejection` 자리가 없어 신원 불일치가 `INVALID_ARGUMENT`로 나간다(§15).

**`error_type` 명명 규칙.** `SCREAMING_SNAKE_CASE`이고 코어 값은 계약이 소유하며 벤더 값은 `X_<VENDOR>_` 접두사를 쓴다. 코어 최소 집합 **여덟**:

| `error_type` | 뜻 |
|---|---|
| `LOCALIZATION_LOST` | 자기 위치를 잃음 |
| `PAYLOAD_LOST` | 들고 있던 것을 놓침 |
| `SKILL_EXECUTION_FAILED` | 스킬이 실패 |
| `PARAMETER_OUT_OF_RANGE` | 프로파일이 선언한 제약 위반 |
| `CONTRACT_REVISION_MISMATCH` | §5.5의 경보 |
| `INTERNAL_ERROR` | 그 밖 |
| **`TERMINAL_STATE_VIOLATED`** | **종착 뒤에 로봇이 다시 움직였다** — §4.4의 래치 불변식이 깨진 것 |
| **`CONTROL_AUTHORITY_LOST`** | **제어 권한을 다른 클라이언트에게 빼앗겼다** — §4.9 |

프로파일은 이 중 하나이거나 `X_` 접두사 값만 실패 모드에 쓸 수 있고 §11.2의 3번이 검사한다. **뒤의 둘은 프로파일이 선언하는 실패 모드가 아니라 어댑터가 런타임에 발행하는 것**이므로 프로파일에 쓰면 안 된다 — 이것도 3번이 막는다.

### 4.4 장기 실행 태스크

```
StartTask(TaskRequest)                 -> TaskHandle    접수 응답. 종착이 아니다
WatchTask(TaskHandle, from_update_index) -> stream TaskUpdate
RetryTask(TaskHandle)                  -> Ack           RETRIABLE에서만 합법
PauseTask(TaskHandle)                  -> Ack           pause_support=NO면 PAUSE_UNSUPPORTED
ResumeTask(TaskHandle)                 -> Ack
CancelTask(CancelRequest)              -> CancelAck     cancel_support=NO면 CANCEL_UNSUPPORTED
GetSnapshot(robot_id)                  -> Snapshot      현재 상태 + 그 시점의 sequence
ReplayEvents(robot_id, from_sequence)  -> stream Event  재생 버퍼에서 이어받기
GetCapabilities(robot_id)              -> Capability    유효 능력의 투영 (§7.3)
```

파일 귀속은 §4.1이 정본이다 — 이 블록은 호출자 관점의 목록이며 `GetSnapshot`·`ReplayEvents`는 `event.proto`, `GetCapabilities`는 `skill.proto`에 있다.

`ReplayEvents`가 §4.8의 재생 버퍼를 요청하는 유일한 표면이다. 요청한 `from_sequence`가 버퍼를 벗어났으면 `SEQUENCE_EVICTED`로 답하며, 소비자는 `GetSnapshot`부터 다시 세운다.

| 상태 | 종착 | 뜻 |
|---|---|---|
| `ACCEPTED` | 아니오 | 접수됨, 아직 시작 전 |
| `RUNNING` | 아니오 | 실행 중 |
| `PAUSED` | 아니오 | `PauseTask`로 일시정지 |
| `CANCELLING` | 아니오 | 취소 요청을 받았고 로봇이 정리 중 |
| `RETRIABLE` | 아니오 | 실패했으나 **로봇 혼자** 재시도하면 될 수 있음 |
| `NEEDS_INTERVENTION` | 아니오 | 실패했고 **사람이 무언가 해야** 재시도가 의미 있음 |
| `SUCCEEDED` | 예 | — |
| `FAILED` | 예 | 재시도해도 같은 결과 |
| `CANCELLED` | 예 | 취소로 종료하고 **복구까지 마쳤다** |
| `CANCELLED_RECOVERY_FAILED` | 예 | 취소로 종료했으나 **복구에 실패했다** |

**취소는 즉시가 아니고, 복구를 동반한다.** `CancelTask`는 종착이 아니라 `CANCELLING`을 반환한다. 그 구간에 로봇은 **하던 일을 안전하게 되돌린다** — 휴머노이드는 들고 있던 것을 내려놓아야 하므로 즉시 중단이 물리적으로 불가능하다. 복구까지 마치면 `CANCELLED`, 복구가 실패하면 `CANCELLED_RECOVERY_FAILED`다. **둘을 나누는 이유는 후자가 "로봇이 물건을 든 채 멈춰 있다"는 전혀 다른 운영 상황이기 때문이다.** 후자는 거의 언제나 로봇 수준 결함을 동반하며, 그 결함이 `can_accept_new_task=false`를 든다.

Agility Arc가 같은 결론에 도달해 있다 — 워크플로 상태에 `CANCELED_WITH_RECOVERY` / `CANCELED_RUNNING_RECOVERY` / `CANCELED_FAILED_RECOVERY` 세 변종이 있다. 우리는 진행 중(`CANCELLING`)과 결과(둘)로 갈라 같은 것을 두 축으로 표현한다.

**`CancelTask`는 비종착 여섯 전부에서 합법이다.** `RETRIABLE`·`NEEDS_INTERVENTION`에서도 받아야 하는데, 안 그러면 **재시도를 포기한 태스크가 영원히 비종착으로 남아** §9.3의 드레인 판정이 영영 0이 되지 않는다 — 축소가 영구히 막힌다. 그 두 상태에서는 스킬이 이미 `READY`라 되돌릴 것이 없으므로 복구가 즉시 끝나지만, **관측되는 순서는 그래도 `CANCELLING` → 종착이다.** 한 경로로 통일하는 편이 소비자에게 거짓말하지 않는다. `CANCELLING`에서 다시 받으면 멱등이다(응답을 못 받아 재전송한 경우).

**`RETRIABLE`과 `NEEDS_INTERVENTION`의 탈출구는 둘 다 `RetryTask`다.** 같은 `(task_id, revision)`으로 스킬을 다시 `Start`하며 `attempt`가 오른다. 그 밖의 상태에서 부르면 `INVALID_TRANSITION`이다. 재시도 횟수 상한은 계약이 정하지 않는다 — 그건 정책이고 미션 계층 몫이다.

**둘을 나누는 이유**는 운영자에게 답해야 할 질문이 다르기 때문이다. `RETRIABLE`은 미션 계층이 자동으로 다시 걸어도 되지만, `NEEDS_INTERVENTION`은 자동 재시도가 **같은 실패를 반복하며 자원만 태운다.** 어느 쪽인지는 프로파일의 실패 모드가 선언한 `Resolution`이 정한다(§4.5).

**종착은 래치된다 — 이것이 계약의 불변식이다.** 종착에 든 태스크는 어떤 이유로도 비종착으로 돌아가지 않는다. 실물 중에는 이를 지키지 않는 것이 있다(Digit의 `action-status`는 매뉴얼이 *"does not latch once reached"*라고 명시하며 `success`에서 `running`으로 되돌아갈 수 있다). **그런 로봇에서는 어댑터가 래치 책임을 진다** — 처음 종착에 도달한 순간을 확정하고, 이후 로봇이 무엇을 보고하든 계약상으로는 끝난 것이다.

그리고 **흡수했으면 흡수가 실패했다는 사실을 숨기지 않는다.** 어댑터가 종착 확정 후에도 로봇이 그 태스크를 계속 수행 중임을 관측하면 `TERMINAL_STATE_VIOLATED` 결함을 발행한다(두 불리언 모두 `false`). 계약의 단순함은 지키되, **계약이 실물과 어긋나 있다는 사실은 관측 가능하게** 만든다. 비래치를 계약에 올리는 대안은 종착 개념 위에 선 것들(pinning·갱신 규칙·`RETRIABLE` 구분)을 전부 무너뜨리고, 래치하는 로봇에 비용을 전가한다.

**진행률은 `0.0..1.0`의 실수**이며 `mimic`에서는 프로파일이 선언한 소요시간 대비 경과 비율로 파생한다. **단조 비감소는 `(task_id, revision, attempt)` 세 값이 같은 구간 안에서만 성립하는 불변식**이다. `revision`이 오르거나(갱신) `attempt`가 오르면(재시도) 진행률은 0에서 다시 세며, `TaskUpdate`가 셋을 모두 싣고 있으므로 소비자는 재시작을 위반과 구분한다.

**멱등성 키는 `(task_id, revision)` 단조쌍이다.** VDA5050의 `(orderId, orderUpdateId)`를 그대로 가져온다.

| 수신한 revision | 처리 |
|---|---|
| 신규 `task_id` | 새 핸들 |
| 현재와 동일 | **같은 핸들을 그대로 반환**(멱등). 상태 메시지를 못 받아 재전송한 경우 |
| 현재보다 낮음 | `OUTDATED_REVISION` 거절 |
| 현재보다 높음 | 갱신 — 아래 |

**갱신 규칙.** 갱신은 파라미터를 교체하되 태스크를 재시작하지 않는다. 태스크 상태별로 이렇게 처리한다.

| 갱신을 받은 상태 | 처리 |
|---|---|
| `ACCEPTED` | 파라미터만 교체. 스킬 전이 없음 |
| `RUNNING` | 스킬을 `Halt` → `Reset` → 새 파라미터로 `Start`. 태스크는 `RUNNING` 유지 |
| `PAUSED` | 파라미터만 교체하고 `PAUSED` 유지. `ResumeTask` 때 새 파라미터로 `Start` |
| `RETRIABLE` | 파라미터만 교체하고 `RETRIABLE` 유지. `RetryTask` 때 새 파라미터로 `Start` |
| `NEEDS_INTERVENTION` | 같다. **개입한 사람이 파라미터를 고쳐 넣는 경로가 이것이다** — 막으면 이 상태의 존재 이유와 어긋난다 |
| `CANCELLING` | `INVALID_TRANSITION`. 정리 중에 파라미터를 바꾸는 것은 의미가 없다 |
| 종착 셋 | `INVALID_TRANSITION` |

진행률은 새 `revision`에서 0부터 다시 세고 `attempt`는 0으로 되돌아간다. **갱신은 §8.4의 개정판 pinning을 바꾸지 않는다.**

### 4.5 두 상태머신의 관계

**태스크는 스킬 호출의 나열이고, 스킬 FSM은 그중 지금 실행 중인 하나의 상태다.**

| 태스크 상태 | 현재 스킬 상태 |
|---|---|
| `ACCEPTED` | 없음 |
| `RUNNING` | `RUNNING` |
| `PAUSED` | `SUSPENDED` |
| `CANCELLING` | `RUNNING` 또는 `SUSPENDED` (복구 수행 중) |
| `RETRIABLE`, `NEEDS_INTERVENTION` | `HALTED` → 즉시 `Reset` → `READY` (`RetryTask`를 기다린다) |
| `FAILED`, `CANCELLED`, `CANCELLED_RECOVERY_FAILED` | `HALTED` → 즉시 `Reset` → `READY` |
| `SUCCEEDED` | `READY` (`Complete` 후) |

**전파 규칙 셋.**

1. **스킬 `Halt` → 태스크 판정은 결함의 `Resolution`이 정한다**(프로파일 선언, §7.2).

   | `Resolution` | 태스크 상태 |
   |---|---|
   | `SELF_RETRIABLE` | `RETRIABLE` |
   | `NEEDS_INTERVENTION` | `NEEDS_INTERVENTION` |
   | `TERMINAL` | `FAILED` |

2. **여러 단계로 이루어진 태스크에서 한 단계의 `Halt`는 태스크 전체를 종착시킨다.** 보상 동작이나 부분 재개는 미션 계층 몫이며 비목표다.
3. **`CANCELLING` 중의 `Halt`는 복구 실패다** → `CANCELLED_RECOVERY_FAILED`. `Resolution`을 보지 않는다 — 취소는 이미 결정된 것이고 남은 질문은 "되돌리는 데 성공했는가"뿐이다.

### 4.6 결함 모델

설계노트 A-4는 3분류(재시도 가능 / 이관 가능 / 사람 개입)를 계약에 담자고 했다. **이 문서는 3분류를 계약에서 뺀다.** 3분류는 처방이고 처방은 판단이다. VDA5050은 대신 **남은 능력**을 묻고, 그래서 등급이 두 불리언으로 결정되어 판단 여지가 없다.

```
Fault {
  string   error_type                 §4.3의 명명 규칙
  bool     can_continue_current_task
  bool     can_accept_new_task
  repeated Reference references
  string   error_hint                 사람이 취할 조치
  Lifetime active_until
}
```

3분류는 미션 계층이 여기서 파생시킨다. **분류가 사라지는 것이 아니라 계약 밖으로 나가고 대신 결정 가능해진다.**

스킬 수준과 로봇 수준을 구분한다 — `references`가 `skill_id`를 담으면 그 스킬만의 문제이며, "이동은 되는데 조작만 안 되는" 상태가 이렇게 표현된다.

### 4.7 상태와 이벤트

상태는 현재값, 이벤트는 발생한 사실이며 **둘 다 발행한다.**

이벤트가 되는 전이는 넷 — **스킬 상태 전이, 태스크 상태 전이, 결함 발생·해소, 능력 변경**(`CapabilityChanged`).

모든 이벤트는 공통 헤더(§5.5) + `kind` + 종류별 본문을 갖는다.

| `kind` | 본문 |
|---|---|
| `SKILL_TRANSITION` | `{skill_type, from, to, task_id?}` |
| `TASK_TRANSITION` | `{task_id, skill_type, from, to, revision, attempt}` |
| `FAULT_RAISED` / `FAULT_CLEARED` | `Fault`(§4.6) |
| `CAPABILITY_CHANGED` | 아래 |

`TASK_TRANSITION`이 `skill_type`을 싣는 것이 중요하다 — **`registry`가 이 이벤트를 구독해 `task` 테이블을 적재하며**(§3.2·§8.3), §9.3의 드레인 판정이 스킬 단위로 서려면 이 필드가 있어야 한다. 계약이 싣는 것은 **스킬 타입 이름**이고 `skill_type_id`는 §8.3 레지스트리의 대리키다 — 이름이 겹치지 않게 계약 쪽은 `skill_type`으로 통일한다.

proto에서는 `kind` 필드 대신 `oneof body`의 판별자가 그 역할을 하고, `FAULT_RAISED`/`FAULT_CLEARED` 두 값은 `FaultEvent.cleared` 불리언으로 합쳐진다.

`CapabilityChanged`는 `{robot_id, capability_epoch, added[], removed[], cause}`를 담는다. `cause`는 §8.3 `capability_epoch_log.cause`와 같은 값 집합이다. **전체 능력을 싣지 않는 것이 의도**다 — 소비자는 delta로 캐시를 갱신하거나 `GetCapabilities`로 전량을 다시 가져온다.

**연결 상태는 별도 스트림이고 네 값이다.**

| 값 | 뜻 |
|---|---|
| `ONLINE` | 연결 활성 |
| `OFFLINE` | 정상 종료 |
| `HIBERNATING` | **연결됐지만 의도적으로 상태를 발행하지 않음** |
| `CONNECTION_BROKEN` | 비정상 단절 |

retain으로 발행하며 `CONNECTION_BROKEN`은 브로커 Last Will이다. `HIBERNATING`이 있어야 "침묵하지만 정상"을 표현할 수 있다.

### 4.8 세션·시퀀스·재생 버퍼

**단위는 전부 기체(`robot_id`)다.** 한 프로세스가 여러 기체를 호스팅해도 세션과 시퀀스는 기체마다 독립이다.

- `session_id`(ULID)는 **기체가 온라인이 될 때마다** 발급하고, `sequence`는 세션 안에서 0부터 단조 증가한다.
- 소비자는 `session_id`가 바뀌면 이전 `sequence`를 이어 해석하지 않고 `GetSnapshot`으로 다시 세운다(Sparkplug birth/rebirth와 같은 자리).
- 발신자는 세션 안에서 **기체마다 마지막 N개 이벤트를 재생 버퍼에 보관**한다. `N`은 프로파일이 선언한다.
- 요청한 `sequence`가 버퍼를 벗어났으면 `SEQUENCE_EVICTED`로 답하고 소비자는 스냅샷부터 다시 세운다.
- **버퍼는 프로세스 메모리에 있으며 재기동하면 사라진다.** 지속 저장은 B-1의 몫이다.

### 4.9 제어 권한 상실

**실물 로봇은 예외 없이 배타적 제어 소유권 모델을 갖는다.** Digit은 `change-action-command` 권한을 전 시스템에서 한 클라이언트만 보유하며 **빼앗기면 로봇이 즉시 `action-idle`로 리셋**된다. Spot은 `Lease{resource, epoch, sequence[]}`로 벡터 클럭까지 형식화했다. Unitree는 lease id는 있으나 인증이 없어 네트워크 도달이 곧 전권이다.

**권한의 획득과 협상은 계약 밖이다.** 우선순위를 누가 갖는지는 판단이고 그 주인은 미션 계층이며 비목표다. 게다가 세 로봇의 모델이 전부 달라(Digit=정수 우선순위, Spot=벡터 클럭, Unitree=무인증) 공통분모를 잡으면 아무것도 못 하고 최대공약수를 잡으면 한 벤더 전용 계약이 된다.

**그러나 권한의 상실은 사실이고 태스크 결과를 좌우하므로 계약 안이다.** 어댑터가 제어 권한을 잃으면 `CONTROL_AUTHORITY_LOST` 결함을 발행한다(두 불리언 모두 `false`). 진행 중이던 태스크는 이 결함을 유발자로 하여 §4.5 전파 규칙 1을 탄다.

이것이 없으면 제어권을 빼앗겨 죽은 태스크가 `FAILED`로 떨어져 **로봇 고장과 구분되지 않는다.** 운영자가 "왜 실패했나"에 답할 수 없게 된다. §4.6에서 실패 3분류를 빼고 두 불리언만 남긴 것과 같은 결정이다 — **판단은 밖으로, 사실은 안으로.**

`Capability`는 `exclusive_control_required`를 싣는다. 참이면 소비자는 **자신이 유일한 명령자임을 전제해야 하고**, 다른 클라이언트가 붙을 수 있는 환경에서는 이 결함을 정상 경로로 다뤄야 한다.

## 5. 능력 호환성

### 5.1 문제

능력은 기종의 고정 속성이 아니라 **(제조사 × 모델 × 버전)의 함수**이며 시간에 따라 변한다. 늘어나거나, 줄어들거나, **같은 이름의 능력이 더 섬세해진다.** 세 번째가 어렵다 — 세분화는 **동일성**을 묻게 만든다.

### 5.2 동일성 규칙

식별자는 `(skill_type, major.minor)`이며 **동일성은 major가 결정한다.**

- **minor 증가** = 선택 파라미터 추가만. 클라이언트가 몰라도 동작해야 한다.
- **major 증가** = 셋 중 하나. 필수 파라미터 추가 / 기존 파라미터의 의미·단위 변경 / **성공 판정 기준 변경**.
- **파라미터 키는 불변이다.** 의미나 단위가 바뀌면 새 키를 만들고 옛 키를 폐기한다. proto 필드 번호와 같은 규율이다.

계약은 스킬 타입마다 `major`별로 하나의 정의를 갖고 파라미터마다 "몇 번째 minor부터 존재하는가"를 기록한다. 프로파일은 자신이 구현하는 `major.minor`를 선언하며 계약이 아는 최신 minor를 초과할 수 없다.

클라이언트는 요구를 `pick_place@^1.2` 형태로 표현한다. 집행은 §11.2의 6번, 한계는 §15의 1번이다.

### 5.3 키 설계

| 종류 | 예 | 모르는 것을 받으면 |
|---|---|---|
| 코어 | `grip_force` | **실패**(fail-closed) |
| 벤더 확장 | `x-<vendor>.<key>` | **무시**(fail-open). 코어는 `x-`를 읽지 않는다 |
| 필수 선택 필드 | `{parameter, support: REQUIRED}` | 핸드셰이크에서 실패 |

세 번째는 VDA5050 `optionalParameters`에서 가져왔다. 로봇이 "이 선택 필드를 나는 요구한다"고 선언할 수 있어서 보내지 않는 클라이언트가 런타임이 아니라 핸드셰이크에서 걸린다. 참조는 점표기 경로다(예: `task.parameters.approach_vector`).

### 5.4 핸드셰이크

```
Negotiate(CapabilityRequirement) -> NegotiationResult
  CapabilityRequirement {
    client_id, robot_id,
    requirements[],           예: "pick_place@^1.2"
    optional_fields_used[],   점표기 경로
    limits_needed { max_string_length, max_array_length }
  }
```

`limits_needed`가 `LIMIT_EXCEEDED`의 판정 입력이다 — 클라이언트가 "나는 이만큼의 문자열·배열을 보낸다"를 미리 말하고, 로봇이 선언한 프로토콜 한계보다 크면 **핸드셰이크에서** 걸린다. 이것이 없으면 한계 초과가 런타임 발행 시점(§10.4 ③)에야 드러난다.

**`client_id`와 `robot_id`의 권위는 헤더(§5.5)에 있으며** 페이로드의 같은 필드는 페이로드만 보고도 해석되도록 복사한 것이다. 어긋나면 `IDENTITY_MISMATCH`로 거절한다.

로봇은 가능 여부와 **거절 이유**를 반환한다. openTCS `ExplainedBoolean(value, reason)`의 일반화다.

**클라이언트의 요구 집합은 코드가 아니라 설정이다.** `client`는 요구 집합을 파일에서 읽으며 기종을 식별해 분기하지 않는다.

**결과는 성공·실패 모두 `registry`에 보고된다.** 실패는 `handshake_rejection`에, **성공은 `consumer_requirement`(의존 원장, §9.2)에** 적재된다. 보고 실패는 핸드셰이크 결과에 영향을 주지 않는다.

### 5.5 경로와 헤더

토픽은 두 형태다.

```
기체 스트림  picasso/{major}/{site}/robot/{robot_id}/{stream}
             {stream} ∈ state | event | connection
사이트 스트림 picasso/{major}/{site}/site/catalog        (§9.6)
```

사이트 스트림은 `registry`가 발행하며 기체가 없다. 헤더에서 `robot_id`·`profile_ref`·`capability_epoch`가 빠지고, `session_id`·`sequence`는 **`registry` 자신의 세션**으로 발급한다(재기동하면 새 세션이 되고 상위는 `GET /catalog`로 다시 세운다). retain으로 발행해 신규 구독자가 즉시 현재 카탈로그를 받는다.

메이저 버전을 경로에 두는 것은 VDA5050에서 가져왔다. 구독자가 이해하지 못하는 메이저의 페이로드를 애초에 받지 않는다.

**헤더는 세 방향 모두에 실린다.**

| 필드 | 발행 | gRPC 요청 | gRPC 응답 |
|---|---|---|---|
| `schema_id` | ● | ● | ● |
| `contract_digest` | ● | ● | ● |
| `contract_semver` | ● | ● | ● |
| `robot_id` | ● | ● (기체 지정) | ● |
| `capability_epoch` | ● | — | ● |
| `sequence` | ● | — | — |
| `session_id` | ● | — | ● |
| `update_index` | — | — | ● (`WatchTask` 스트림) |
| `profile_ref {id, revision}` | ● | — | ● |
| `event_id` / `occurred_at` / `state_as_of` | ● | — | ● |
| `client_id` | — | ● | — |

**`contract_revision`은 구현에서 두 필드로 나뉜다** — 다이제스트만으로는 차단 판정을 못 하므로 `contract_digest`와 `contract_semver`를 함께 싣는다.

**`session_id`가 gRPC 응답에도 실리는 이유**는 §4.8의 세션 판정 때문이다. `SEQUENCE_EVICTED` 후 `GetSnapshot`으로 다시 세운 소비자가 그 `sequence`가 어느 세션의 것인지 알아야 하는데, 응답에 세션이 없으면 판정이 불가능해진다.

소비처: `event_id`는 소비자 측 멱등 처리, `state_as_of`는 신선도 판정(§7.2가 선언한 최대 발행 간격과 대조), `schema_id`는 §6.2의 메시지마다 판정, **`profile_ref`는 `registry`가 이벤트를 적재할 때 개정판 귀속에 쓴다** — 카나리 중 두 개정판이 동시에 도는 것을 관측하는 유일한 수단이며 §12.2의 카나리 시험이 이 필드로 판정한다.

두 필드는 따로 설명이 필요하다.

- **`contract_revision`** — `buf` 모듈 다이제스트와 **`contracts/`의 semver 태그를 함께** 싣는다. semver가 있으면 major 불일치는 차단, 그 외 불일치는 경보로 갈린다. **경보를 남기는 방식은 발신자와 수신자가 다르다** — 로봇 측(`mimic`·어댑터)은 자기 `event` 스트림에 결함 이벤트(`CONTRACT_REVISION_MISMATCH`, 두 불리언 모두 `true`)로 발행하고, **소비자 측은 발행할 스트림이 없으므로 `registry`의 수집 엔드포인트로 보고한다**(§6.2). 남는 한계는 §15의 4번이다.
- **`capability_epoch`** — 유효 능력 집합이 바뀔 때마다 증가한다. 캐시한 소비자가 매 메시지에서 O(1)로 유효성을 판정한다. 능력의 ETag다.

**능력 차이는 경로에 넣지 않는다.** 넣으면 능력이 바뀔 때 토픽이 바뀌고 구독자가 조용히 끊긴다. **경로는 프로토콜 호환성, 헤더는 세대, 페이로드는 능력.**

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
| CI | §11.2의 아홉 가지 | PR 차단 |
| 등록 | **프로파일 문서** — JSON Schema, proto 교차검증, **그리고 능력 어휘 파괴 검사(§11.2의 6번)** | `VALIDATED` 진입 거부 |
| 핸드셰이크 | **요구 집합**을 선언 능력과 대조 | 연결 거부 + 사유 |
| 메시지마다 | 토픽(또는 채널 수립 시)의 `major` 일치, `schema_id` 기지 여부, `sequence` 단조·세션 일치, `capability_epoch` 캐시 일치 | 드롭 + 결함 기록(아래) |
| 송신 직전 | **프로파일 파생 제약** — 파라미터 값 범위·허용 값·최대 길이, 발행 간격 | 발행 차단 |

**송신 직전 검사가 구조 검증이 아니라는 점이 중요하다.** 구조는 proto 생성 코드가 빌드 시점에 보장한다.

**결함 기록의 경로가 발신자와 소비자에서 다르다.** 로봇 측은 자기 `event` 스트림에 결함 이벤트를 발행하면 되지만, 소비자(`client`·상위 시스템)에게는 기체도 세션도 없어 발행할 스트림이 없다. 소비자는 `POST /diag/consumer-faults { consumer_id, robot_id, error_type, detail }`로 `registry`에 보고하고, `registry`가 없으면 로컬 로그에 남긴다. **보고 실패가 소비자의 동작을 막지 않는다.**

**수신·송신 플래그는 독립이며 이름도 각각 붙인다** — `validate.inbound`, `validate.outbound`. 기본값은 비프로덕션 `1.0`, 프로덕션 `0.01`. openTCS는 플래그가 하나여서 끄면 송신 검증까지 꺼진다.

**`mimic`은 둘 다 `1.0`으로 고정한다.**

### 6.3 신원과 접근 — 이번 범위의 전제

- `registry` API는 **신뢰 네트워크 안**에 있다고 가정하고 행위자 신원은 요청 헤더의 `actor`를 그대로 기록한다. **위조 방지는 범위 밖이며 감사 로그는 부인방지 근거가 아니라 조사 단서다**(§15의 3번).
- `mimic`의 제어 채널(§10.5)은 **인증이 없고 루프백에만 바인딩한다.** 루프백이 아닌 주소로 바인딩하려 하면 기동을 거부한다.
- 표준 계약 표면과 MQTT 브로커의 인증은 배포 환경의 몫이다.

## 7. 능력 프로파일 (`profile/`)

### 7.1 왜 proto가 아니라 JSON Schema인가

프로파일에 담기는 것은 구조가 아니라 **제약**이다 — 값 범위, 허용 값, 최대 길이, 발행 간격. proto는 구조를 정의하는 언어라 이것들이 주석이나 옵션으로 밀려나고 검증이 런타임 코드로 샌다. 대가는 게이트가 하나 늘고 두 세계가 어긋날 수 있다는 것이며 §11.2의 4번으로 갚는다.

### 7.2 프로파일이 담는 것

VDA5050 `factsheet.schema`의 구조를 따른다. **투영에 들어가는 것과 발신자 내부 설정을 나눈다** — 기준은 하나다: **소비자가 행동을 결정하는 데 필요한가.**

**투영에 들어간다 (`Capability`로 나간다):**

| 항목 | 내용 |
|---|---|
| 기종 좌표 | `vendor`, `model`, `revision` |
| 지원 스킬 | 스킬 타입과 `major.minor` |
| 스킬별 플래그 | `pause_support`, `cancel_support` — **`Support` 3값**(`YES`/`NO`/`UNKNOWN`), 둘 다 필수 필드 |
| 제어 소유권 | `exclusive_control_required` — 이 로봇이 배타 제어 모델인가(§4.9) |
| 파라미터 선언 | 키, `ValueType`, 선택 여부, 제약 — 수치형은 값 범위와 단위, `ENUM`형은 **허용 값 목록**, 문자열은 최대 길이 |
| 필수 선택 필드 | `{parameter: 점표기 경로, support: SUPPORTED\|REQUIRED}` |
| 상태 발행 간격 | 최소·최대. 최대의 기본값은 30초(VDA5050 §6.6). 소비자의 신선도 판정 입력 |
| 프로토콜 한계 | 문자열·배열 최대 길이 |
| 폐기 예고 | 스킬별 `deprecated_after`(선택). §9.3의 예고 단계가 여기에 쓴다 |

**투영에 들어가지 않는다 (발신자 내부 설정):**

| 항목 | 이유 |
|---|---|
| `schema_version` | 문서의 메타이지 능력이 아니다 |
| 소요시간 상수·지터 | 진행률 파생에만 쓴다. 소비자는 진행률을 받지 소요시간을 받지 않는다 |
| 실패 모드와 `rate`, `resolution` | 발생률은 시뮬레이션 값이다. 실제 결함은 `Fault`로 나가고, `resolution`은 §4.5 전파 규칙 1의 입력이라 어댑터 내부에서만 쓰인다 |
| 재생 버퍼 크기 `N` | 발신자 내부 자원 |

**`Capability` 메시지는 위 표의 투영 항목을 그대로 담는다.** §12.2의 투영 일치 시험은 "프로파일 문서에서 이 표대로 파생한 값 == `GetCapabilities` 응답"을 정확 비교한다. 이 표가 파생 함수의 명세다.

### 7.3 계약과의 관계

프로파일은 저작 형식이고 `Capability`는 그 **투영**이다. 정확성은 두 곳에서 지켜진다 — 정적으로는 §11.2의 4번(참조 무결성), **런타임으로는 §12.2의 투영 일치 시험**. 정적 검사만으로는 구현이 프로파일을 무시하고 능력을 하드코딩해도 걸리지 않는다.

**프로파일은 계약을 참조만 할 수 있고 확장할 수 없다.** proto에 없는 스킬 타입이나 키를 선언하면 등록 단계에서 거부된다. 이것이 없으면 런타임 갱신이 게이트를 우회하는 뒷문이 된다.

### 7.4 이번에 만드는 프로파일

`profile/profiles/`에 `humanoid-a`와 `quadruped-b` 둘. 차이의 종류를 의도적으로 갖춘다.

| 차이의 종류 | 시험하는 것 |
|---|---|
| 공통 스킬 (같은 `major.minor`) | 같은 코드로 양쪽 제어 |
| 한쪽에만 있는 스킬 | `SKILL_ABSENT` |
| 같은 스킬의 minor 차이 | 선택 파라미터를 몰라도 동작 |
| `cancel_support` 차이 (`YES` vs `NO`) | `CANCEL_UNSUPPORTED` |
| `pause_support` 차이 (`YES` vs `UNKNOWN`) | `UNKNOWN`이면 시도가 허용되고 로봇이 거절할 수 있다 |
| 프로토콜 한계 차이 | `LIMIT_EXCEEDED` |
| `REQUIRED` 선택 필드 (한쪽만) | `REQUIRED_OPTIONAL_MISSING` |

이 일곱이 코드 변경 없이 표현되어야 한다.

`profile/fixtures/`에는 게이트·시험 전용 **프로파일**을 둔다 — 1단계용 픽스처 프로파일 한 장, 게이트 음성 케이스용 변형들. **요구 집합은 `profile/requirements/`에 둔다** — 정상 한 장과 **틀린 것 한 장**(공통 스킬을 `@^2.0`으로 요구해 `MAJOR_MISMATCH`를 유발). 요구 집합은 기종 선언이 아니라 소비자 선언이고, 무엇보다 게이트의 `--profiles`가 `profile/fixtures/`의 모든 `*.json`을 프로파일로 읽으므로 거기 두면 검사 3번이 스키마 위반으로 터진다(실측). 세 번째 기종 `quadruped-c`는 §12.2의 C-2 시험에서 `profile/profiles/`에 **전용 커밋 하나로** 추가한다.

## 8. 레지스트리와 저장 (`registry/`)

### 8.1 데이터와 코드의 경계

설계노트 §4.5의 기준을 그대로 쓴다 — **값이 잘못됐을 때 다른 값을 참조하지 않고 판정할 수 있으면 데이터, 조합·순서·실행 결과를 봐야 알면 코드.**

| 데이터 (DB · 런타임) | 코드 (배포) |
|---|---|
| 능력 프로파일 | 계약 proto |
| 어댑터 등록과 바인딩 | 스킬·태스크 상태머신 해석기 |
| 의존 원장, 변경 계획 | 조건 분기·계산·변환 파이프라인 |

**비대칭: 계약은 배포, 나머지는 런타임.**

### 8.2 권한 소재

**능력이 줄어드는 두 사건.**

| 사건 | 누가 | 경로 | `cause` |
|---|---|---|---|
| 로봇이 능력을 잃음 (팔 고장) | 로봇 | `CapabilityChanged` 발행 → `registry`가 구독해 적재 | `RUNTIME_DEGRADED` |
| 운영자가 능력을 막음 | 사람 | `registry` API → `mimic`이 폴링으로 반영 → `CapabilityChanged` 발행 | `OPERATOR_BLOCKED` |

**`registry`가 `runtime_capability_override`의 유일한 기록자다.**

**다만 `capability_epoch`의 채번자는 발신자다.** 헤더에 실리는 값이므로 기체마다 단조 증가시키는 주체는 `mimic`(장차 실물 어댑터)이고, `registry`는 `CapabilityChanged`를 구독해 그 값을 `capability_epoch_log`에 **관측 기록으로** 적재한다. 그래서 `registry`가 없는 구성(§13의 2단계)에서도 epoch가 정상 동작한다.

### 8.3 관계형 스키마 (PostgreSQL)

```sql
-- 계약 축 — proto에서 배포 시 동기화되는 읽기 전용 투영.
skill_type(skill_type_id PK, name, major, max_minor,
           introduced_in_semver,      -- 이 스킬을 담은 최초 계약 semver. 바인딩 검사 입력(§9.1)
           contract_revision, synced_at,
           removed_from_contract BOOL DEFAULT false,
           UNIQUE(name, major))

-- 계약 축의 폐기 예고. skill_type이 읽기 전용이므로 별도 테이블에 둔다.
skill_type_deprecation(skill_type_id PK FK, deprecated_after,
                       announced_by, announced_at, note)

skill_type_param(skill_type_id FK, key, value_type, optional, since_minor,
                 PK(skill_type_id, key))

-- 프로파일 축
capability_profile(profile_id PK, vendor, model, UNIQUE(vendor, model))

profile_revision(profile_revision_id PK, profile_id FK, revision INT,
                 document JSONB, document_hash, schema_version,
                 status,   -- DRAFT|VALIDATED|TESTED|ACTIVE|SUPERSEDED|REVOKED
                 created_by, created_at,
                 activated_by NULL, activated_at NULL,
                 UNIQUE(profile_id, revision))
  -- revision은 프로파일 문서가 스스로 선언한 값을 그대로 쓴다(§7.2의 기종 좌표).
  -- 레지스트리는 채번하지 않고 profile_id 안에서 단조 증가만 강제한다.

profile_skill(profile_revision_id FK, skill_type_id FK,
              minor, pause_support, cancel_support, deprecated_after NULL,
              PK(profile_revision_id, skill_type_id))

profile_skill_param(profile_revision_id, skill_type_id, key,
                    value_type, optional,
                    min_value, max_value, unit,     -- 수치형
                    allowed_values JSONB,           -- ENUM형
                    max_length,                     -- 문자열형
                    PK(profile_revision_id, skill_type_id, key))

profile_optional_field(profile_revision_id, parameter_path,
                       support,                     -- SUPPORTED|REQUIRED
                       PK(profile_revision_id, parameter_path))

-- 어댑터 축 (§9.1)
adapter(adapter_id PK, vendor, name, UNIQUE(vendor, name))

adapter_version(adapter_version_id PK, adapter_id FK, version,
                contract_semver,        -- 이 빌드가 따르는 계약 semver
                conformance_status,     -- UNTESTED|PASSED|FAILED  (C-3의 자리)
                registered_by, registered_at,
                UNIQUE(adapter_id, version))

-- 바인딩 축
robot(robot_id PK, site_id, serial_number, display_name,
      UNIQUE(site_id, serial_number))

robot_binding(robot_id FK, adapter_version_id FK, profile_revision_id FK,
              bound_at, unbound_at NULL, bound_by, reason)
  CREATE UNIQUE INDEX ON robot_binding(robot_id) WHERE unbound_at IS NULL;

-- 시험
revision_test_request(request_id PK, profile_revision_id FK,
                      requested_by, requested_at,
                      claimed_by NULL, claimed_at NULL, claim_expires_at NULL)
  -- 클레임은 기본 15분 뒤 만료된다. 만료된 요청은 다른 harness가 다시 집어간다.
  -- 그러지 않으면 harness가 죽었을 때 요청이 영구히 잡힌다.

revision_test_run(run_id PK, profile_revision_id FK, request_id FK NULL,
                  suite,     -- CONTRACT|NEGATIVE|DETERMINISM
                  result,    -- PASS|FAIL
                  ran_by, ran_at, detail JSONB)

-- 관측
capability_epoch_log(robot_id, epoch, cause, profile_ref JSONB,
                     detail JSONB, occurred_at)
  -- cause: BINDING_CHANGED | RUNTIME_DEGRADED | OPERATOR_BLOCKED | RESTORED

runtime_capability_override(robot_id, skill_type_id,
                            state,   -- REMOVED | RESTORED
                            cause,   -- RUNTIME_DEGRADED | OPERATOR_BLOCKED
                            reason, occurred_at)
  -- 두 cause 모두 여기에 쌓인다. 유효 능력 계산(결정 3)은 기체·스킬별 최신 행만 본다.

handshake_rejection(rejection_id PK, robot_id, client_id,
                    requirement JSONB, reason_code, detail JSONB, at)

-- 의존 원장 (§9.2)
consumer(consumer_id PK, kind, site, display_name, registered BOOL, first_seen)
  -- kind: CLIENT | UPSTREAM_SYSTEM
  -- consumer_id는 헤더의 client_id와 같은 값이다. 별도 매핑을 두지 않는다.
  -- 미등록 소비자는 첫 OBSERVED 관측 때 자동 생성한다:
  --   kind=CLIENT, site=토픽의 site, display_name=client_id, registered=false.
  -- POST /requirements가 오면 registered=true가 되고 kind·display_name이 갱신된다.

consumer_requirement(consumer_id FK, skill_type_name, source, version_range,
                     first_seen, last_seen, active,
                     PK(consumer_id, skill_type_name, source))
  -- source: DECLARED(등록) | OBSERVED(협상 성공)
  -- PK에 source가 들어가야 같은 소비자·같은 스킬에 두 행이 공존한다(§8.3 결정 6).
  -- §9.3의 조회 1은 source를 구분하지 않고 active인 행이 하나라도 있으면 "사용 중"으로 본다.

-- 변경 계획 (§9.5)
change_plan(plan_id PK, intent, target JSONB, target_key, site,
            status,     -- DRAFT|ANNOUNCED|MIGRATING|DRAINING|APPLIED|ABANDONED
            created_by, created_at, applied_at NULL)
  -- target_key = intent와 target을 정규화한 문자열. 경합 방지용:
  CREATE UNIQUE INDEX ON change_plan(target_key, site)
    WHERE status NOT IN ('APPLIED','ABANDONED');
  -- 같은 대상을 겨냥한 비종착 계획은 하나뿐이다. 둘째 생성은 거부되고 기존 계획을 가리킨다.

change_plan_step(plan_id FK, seq, kind, precondition JSONB,
                 satisfied BOOL, satisfied_at NULL,
                 PK(plan_id, seq))
  -- satisfied는 화면용 캐시다. 실행 시점에는 언제나 precondition을 다시 평가한다(§9.5).

task(task_id PK, robot_id FK, profile_revision_id FK,
     skill_type_id FK, revision INT, attempt INT,
     state, started_at, ended_at)
  -- skill_type_id가 있어야 드레인(§9.3)을 스킬 단위로 판정할 수 있다

audit_log(actor, action, target_type, target_id, plan_id NULL,
          before JSONB, after JSONB, at)
```

설계 결정 여섯.

1. **개정판은 `VALIDATED`에 든 뒤로 불변이다.** `DRAFT` 동안에는 편집·재제출이 자유롭고, `VALIDATED` 이후에는 고치는 대신 새 `revision`을 만든다. `status` 전이는 다음뿐이다.

   ```
   DRAFT ──검증 통과──> VALIDATED ──시험 PASS──> TESTED ──활성화──> ACTIVE
                                                                      │
                                    SUPERSEDED <──새 개정판 활성화────┘
   DRAFT | VALIDATED | TESTED | SUPERSEDED ──폐기──> REVOKED
   ```

   **활성화가 받는 상태는 `TESTED`와 `SUPERSEDED` 둘이다.** `SUPERSEDED`는 이미 시험을 통과하고 운영된 개정판이므로 재활성화(롤백)에 다시 시험을 요구하지 않는다. **`ACTIVE`는 직접 폐기할 수 없다** — 먼저 다른 개정판을 활성화해 `SUPERSEDED`로 만든 뒤 폐기한다. `REVOKED`는 종착이다.

2. **원본(`document`)과 평탄화 테이블을 둘 다 둔다.** 원본이 진실이고 평탄화는 질의용이다. **평탄화는 트리거가 아니라 등록 시점에 애플리케이션이 계산한다.**
3. **런타임 축소는 프로파일을 건드리지 않는다.** `runtime_capability_override`에 얹는다. **유효 능력 = 프로파일 − 오버라이드**이며 이 결과가 `Capability` 투영이 된다.
4. **`skill_type`·`skill_type_param`은 배포 시 동기화되는 읽기 전용이다.** **동기화 원본은 `contracts/proto/picasso/v1/skill_catalog.proto`이고**, `registry` 기동 시 그 디스크립터를 읽어 upsert하는 잡이 수행한다. `max_minor`와 `optional`은 유도가 아니라 **선언된 커스텀 옵션**(`skill_type_max_minor`, `is_optional`)에서 온다(§4.1). 계약에서 사라진 스킬은 삭제하지 않고 `removed_from_contract=true`로 표시한다(참조 무결성 보존). **폐기 예고는 사람이 정하는 값이므로 이 테이블이 아니라 `skill_type_deprecation`에 쓴다** — 그래야 읽기 전용 원칙이 유지된다.

   ⚠️ **구현 주의**: `.binpb`를 `FileDescriptorSet.parseFrom()`으로 그냥 파싱하면 커스텀 옵션이 unknown field로 떨어져 값이 보이지 않는다. 디스크립터 집합에서 확장 정의를 뽑아 동적 `ExtensionRegistry`를 만들고 `options` 바이트를 다시 파싱해야 한다.
5. **바인딩이 어댑터와 프로파일 둘 다를 참조한다.** "어댑터만 바뀜"과 "기종이 바뀜"이 이 컬럼 분리로 구분된다.
6. **원장의 `source`가 둘이다.** `DECLARED`는 소비자가 등록한 것, `OBSERVED`는 협상 성공에서 관측한 것. 등록하지 않은 소비자도 관측으로 잡히므로 원장이 비어 있을 수 없다.

### 8.4 프로파일 개정판의 런타임 갱신

```
① 등록    DRAFT 적재 → JSON Schema 검증 + proto 교차검증 + 능력 어휘 파괴 검사 → VALIDATED
          실패 시 DRAFT에 머무르며 사유가 붙는다 (편집 후 재제출 가능)
② 시험    registry가 revision_test_request 적재 → harness가 폴링해 집어감
          harness가 후보 개정판으로 mimic을 띄우고 계약 스위트 실행
          결과를 revision_test_run에 보고. PASS면 TESTED
③ 활성화  바인딩 전환 (카나리 가능 — 기체 일부만)
④ 반영    mimic이 registry를 폴링해 바인딩·오버라이드 변화를 집어 든다(§10.3)
          변화가 있으면 capability_epoch를 올리고 CapabilityChanged 발행
⑤ 전파    소비자가 헤더의 capability_epoch 불일치를 O(1)로 감지 → 캐시 재조회
⑥ 롤백    이전 개정판 재활성화. 같은 경로, 같은 검증
```

**③은 바인딩만 전환한다.** `capability_epoch` 증가는 ④에서 발신자가 수행한다(§8.2). `registry`에 epoch 증가 로직을 넣으면 이중 채번이 된다.

**③의 승인 조건은 셋** — `status`가 `TESTED` 또는 `SUPERSEDED`, **세 스위트(`CONTRACT`·`NEGATIVE`·`DETERMINISM`) 각각의 최신 `revision_test_run.result`가 모두 `PASS`**(`SUPERSEDED` 재활성화는 과거 기록으로 충족), `activated_by` 기록.

**진행 중인 태스크는 시작 시점 개정판으로 끝까지 간다(pinning).** `task.profile_revision_id`가 최초 접수 시점 값을 유지한다. **새 태스크는 ④에서 집어 든 새 개정판으로 접수된다.**

**계약(proto) 변경은 이 경로로 올 수 없다.** 배포이며 §9.4가 다룬다.

### 8.5 운영 표면

**조작 단위가 테이블 행이 아니라 의도여야 한다.** API 한 번 = 트랜잭션 한 번 = 감사 로그 한 줄.

조작 열넷: 기종 등록 / 기체 등록 / **어댑터 등록** / **어댑터 버전 등록** / 개정판 올리기(DRAFT 생성·편집·재제출) / 시험 요청 적재 / 바인딩(카나리 포함) / 롤백 / 개정판 폐기 / 능력 차단·해제 / **계약 축 폐기 예고 설정**(`skill_type_deprecation`) / **소비자 요구 등록**(`POST /requirements`) / **변경 계획 생성** / **변경 계획 단계 실행**.

전부 감사 로그 대상이다. 요구 등록은 소비자가 자기 것을 쓰는 조작이라 승인 경계 밖이지만 기록은 남긴다.

승인 경계 — **DRAFT 편집은 자유, ACTIVATE는 §8.4 ③의 세 조건, 변경 계획 단계는 §9.5의 전제 조건.**

진단은 **read-only JSON 엔드포인트 여섯**으로 낸다. 화면은 이를 그대로 표로 그리는 한 장이며 폴링(기본 10초)한다.

| # | 엔드포인트 | 답하는 것 | 출처 |
|---|---|---|---|
| 1 | `GET /diag/bindings` | 이 기체는 어느 어댑터·개정판인가 / 이 개정판을 쓰는 기체는 몇 대인가 | `robot_binding` |
| 2 | `GET /diag/diff?from=&to=` | 두 개정판의 능력 diff | `profile_skill*`, `profile_optional_field` |
| 3 | `GET /diag/epochs?robot_id=` | `capability_epoch` 이력과 사유, **관측된 개정판** | `capability_epoch_log` |
| 4 | `GET /diag/rejections` | 어떤 클라이언트가 어떤 요구로 거절당했는가 | `handshake_rejection` |
| 5 | `GET /diag/dependents?skill=` | **이 능력을 지금 누가 쓰는가** | `consumer_requirement` |
| 6 | `GET /diag/plans` | 진행 중인 변경 계획과 각 단계의 충족 여부 | `change_plan*` |

## 9. 운영 변경

**계산할 수 없는 파급은 도박이다.** 이 절의 모든 장치는 파급을 조회로 바꾸기 위한 것이다.

### 9.1 독립적으로 변하는 네 축

| 축 | 무엇 | 바뀌는 방식 | 되돌리는 법 |
|---|---|---|---|
| **계약** | `contracts/` proto | 배포. semver | **되돌릴 수 없다** (소비자가 이미 생성 코드를 갖고 있다) |
| **프로파일** | 기종 선언 | 개정판. 런타임 | `SUPERSEDED` 재활성화 |
| **어댑터** | 그 기종을 계약에 붙이는 구현체 | 배포. 벤더 버전 | 이전 버전 재배포 |
| **바인딩** | 이 기체 = 이 어댑터 + 이 프로파일 | 런타임 | 이전 바인딩으로 재전환 (이력 보존) |

"어댑터만 올렸다", "프로파일만 바꿨다", "계약이 올라 전부 다시 빌드했다"가 전부 다른 사건이고 파급도 다르다. 조합 폭발은 **바인딩 시점에 한 번 합법성을 검사**하는 것으로 막는다.

```
required = max( skill_type.introduced_in_semver
                for 프로파일 개정판이 선언한 모든 스킬 타입 )
만족    = adapter_version.contract_semver.major == required.major
       && adapter_version.contract_semver >= required
```

즉 **어댑터가 그 프로파일이 쓰는 스킬을 전부 아는 계약으로 빌드됐는가**를 본다. major가 다르면 호환이 아니고, 같은 major 안에서 어댑터가 더 옛 계약으로 빌드됐다면 새 스킬을 모른다. 불만족이면 바인딩이 거부된다.

`introduced_in_semver`가 스킬 타입마다 다르기 때문에 이 검사가 의미를 갖는다 — 계약이 올라도 옛 스킬만 쓰는 프로파일은 옛 어댑터로 계속 돈다.

**되돌릴 수 없는 축이 하나뿐이도록 설계를 몰아둔 것**이 이 구조의 요점이며, 그래서 계약을 보수적으로 다루고 게이트 2번이 존재한다.

### 9.2 의존 원장

**소비자가 자신의 요구를 등록한다.**

```
POST /requirements  { consumer_id, kind, site, requires: ["pick_place@^1.2", ...] }
```

여기에 **협상 성공에서 관측한 것**이 더해진다(§5.4). `source`가 `DECLARED`인 행과 `OBSERVED`인 행이 같은 테이블에 쌓이므로, **등록하지 않은 소비자도 잡힌다.**

이 하나가 모든 것을 바꾼다. "지금 `pick_place@1`을 쓰는 소비자는 누구인가"가 **추측에서 조회로** 바뀐다. 원장 없이 능력을 지우는 것은 "아무도 안 쓰겠지"이고, 원장이 있으면 "쓰는 사람 0명임을 관측했다"이다. 전자는 사고가 나고 후자는 안 난다.

`OBSERVED` 행은 `last_seen`이 갱신되며, 일정 기간(기본 30일) 갱신되지 않으면 `active=false`가 된다. **비활성화는 자동이지만 삭제는 하지 않는다** — 계절성 소비자를 지워버리면 원장이 거짓말을 한다.

### 9.3 방향 규칙 — 확장·이행·축소

**추가는 아래에서 위로, 삭제는 위에서 아래로.**

| 단계 | 하는 일 | 방향 |
|---|---|---|
| **확장** | 새 능력을 넣되 옛 능력을 유지한다 | 어댑터 → 미들웨어 → 카탈로그 → 상위 |
| **이행** | 상위가 옮겨간다. 원장이 옛 능력 사용자 0이 되는 것을 **관측**한다 | 상위가 주도 |
| **축소** | 옛 능력을 뺀다 | 상위 → 카탈로그 → 미들웨어 → 어댑터 |

**축소 단계의 진입 조건은 시간이 아니라 관측이다.** "예고 30일이 지났다"는 거짓말을 한다 — 아무도 안 옮겼을 수 있다. 조건은 둘이며 **둘 다 조회로 판정된다.**

1. `consumer_requirement`에 그 능력을 요구하는 `active` 소비자가 **0**
2. `task`에 그 `skill_type_id`를 쓰는 비종착 태스크가 **0** (드레인)

하나라도 아니면 축소 조작이 **거부**된다. 운영자가 판단하지 않는다.

**폐기 예고**는 축소의 선행 단계이며 축마다 기입 경로가 다르다.

| 축 | 어디에 쓰는가 | 누가 |
|---|---|---|
| 계약 | `skill_type_deprecation` (§8.3) | 운영자 조작(§8.5) |
| 프로파일 | 새 개정판의 `profile_skill.deprecated_after` | 개정판 제출 |

두 값 중 이른 쪽이 `Capability` 투영과 카탈로그에 실려 상위에 보인다. 예고는 **정보이지 게이트가 아니다** — 게이트는 위의 두 조회다.

### 9.4 변경 시나리오 여섯

| 시나리오 | 계약 | 프로파일 | 어댑터 | 파급 | 절차 |
|---|---|---|---|---|---|
| **어댑터 최초 추가** (기존 스킬만) | 무변경 | 새 프로파일 | 새 등록 | 없음 | §9.7 |
| **어댑터 버전 업** (거동만) | 무변경 | 무변경 | 재배포 + 새 `adapter_version` | 없음 | 바인딩 전환 |
| **선택 파라미터 추가** | minor↑ | 새 개정판 | 재빌드 | 없음(하위호환) | §8.4 |
| **새 스킬 타입** | 새 정의 | 새 개정판 | 재빌드 | 없음 | §8.4 + 카탈로그 |
| **스킬 의미 변경** | **major↑** | 새 개정판 | 재빌드 | **원장 조회** | **확장·이행·축소** |
| **기능 삭제** | deprecate | 개정판에서 제거 | 무변경 가능 | **원장 조회 + 드레인** | **축소** |

**앞의 넷은 파급이 없어 그냥 진행한다. 뒤의 둘만 절차가 필요하다.** 앞뒤를 시스템이 자동으로 가른다 — §11.2의 6번이 이미 변경을 분류하고 있으므로 그 분류에 **확장/축소 판정**을 붙인다. 축소로 분류되면 §9.3의 두 조회를 강제한다.

계약 축의 변경(뒤 넷)은 배포이므로 `registry`가 막을 수 없다. 대신 **CI가 막는다** — 게이트 6번이 축소를 감지하면 PR에 원장 조회 결과를 요구한다. `registry`가 없는 환경에서는 이 검사를 건너뛰되 그 사실을 CI 출력에 남긴다.

**다만 6번의 입력은 언제나 프로파일 문서이므로(§11.1) proto만 바뀐 PR은 6번에 잡히지 않는다.** 그 경우는 게이트 2번(`buf breaking`)이 구조 파괴를 막고, 실제 능력 축소는 그 계약을 쓰는 프로파일 개정판이 올라올 때 6번에 잡힌다. **두 검사의 역할 분담이 이것이다** — 2번은 계약 축의 구조, 6번은 프로파일 축의 어휘와 파급.

### 9.5 변경 계획을 1급 객체로

운영자가 의도를 선언하면 시스템이 절차를 만든다. **이것이 "SQL이 아니라 운영에 적합한 체계"의 실체다.**

```
POST /change-plans { intent: REMOVE_CAPABILITY, target: {skill: "pick_place", major: 1}, site: "A" }

→ 시스템이 산출:
   영향받는 소비자   MES-A(@^1.0, DECLARED), WCS-B(@^1.2, OBSERVED)   ← consumer_requirement
   진행 중 태스크    3건                                              ← task
   단계             ① 예고 → ② 이행 관측 → ③ 드레인 → ④ 제거
   현재 차단 사유    소비자 2, 진행 중 3
```

**단계의 종류(`kind`)는 넷이다.**

| `kind` | 하는 일 | 가역성 |
|---|---|---|
| `ANNOUNCE` | 폐기 예고를 기입한다(§9.3의 표) | 가역 — 예고를 지우면 된다 |
| `OBSERVE_MIGRATION` | 아무것도 하지 않고 조건 충족만 기다린다 | 무해 |
| `DRAIN` | 아무것도 하지 않고 진행 중 태스크가 빠지기를 기다린다 | 무해 |
| `APPLY` | 실제 제거를 수행한다 | §9.1의 축별 되돌리는 법 |

**`precondition`은 검사 목록이다.** 구조는 `{ checks: [ {type, params} ] }`이고 `type`은 다섯이다.

| `type` | 참이 되는 조건 |
|---|---|
| `NO_ACTIVE_CONSUMERS` | `consumer_requirement`에 대상 스킬을 요구하는 `active` 행이 0 |
| `NO_INFLIGHT_TASKS` | `task`에 대상 `skill_type_id`의 비종착 행이 0 |
| `DEPRECATION_PUBLISHED` | 대상의 `deprecated_after`가 채워져 있고 카탈로그에 반영됨 |
| `NO_ACTIVE_BINDINGS` | 대상 어댑터 버전·개정판을 쓰는 `robot_binding`이 0 |
| `SUCCESSOR_ACTIVE` | 대체할 개정판·버전이 이미 `ACTIVE`이고 바인딩되어 있음 |

**`intent`별 단계와 `APPLY`가 조작하는 축.**

| `intent` | 단계 | `APPLY`가 하는 일 | 축 |
|---|---|---|---|
| `REMOVE_CAPABILITY` | `ANNOUNCE` → `OBSERVE_MIGRATION`(`NO_ACTIVE_CONSUMERS`) → `DRAIN`(`NO_INFLIGHT_TASKS`) → `APPLY` | 그 스킬을 뺀 **새 프로파일 개정판을 활성화**한다. 계약 축은 건드리지 않는다(계약은 배포이며 §9.4가 다룬다) | 프로파일 |
| `MIGRATE_MAJOR` | `ANNOUNCE` → `OBSERVE_MIGRATION`(`SUCCESSOR_ACTIVE` + `NO_ACTIVE_CONSUMERS` on 옛 major) → `DRAIN` → `APPLY` | 옛 major를 뺀 개정판을 활성화 | 프로파일 |
| `RETIRE_ADAPTER_VERSION` | `OBSERVE_MIGRATION`(`SUCCESSOR_ACTIVE`) → `DRAIN` → `APPLY` | 그 `adapter_version`을 쓰는 바인딩이 없음을 확인하고 폐기 표시 | 바인딩 |
| `RETIRE_PROFILE_REVISION` | `OBSERVE_MIGRATION`(`NO_ACTIVE_BINDINGS`) → `APPLY` | 개정판을 `REVOKED`로 전이 | 프로파일 |

**전제 조건은 실행 시점에 다시 평가한다.** `change_plan_step.satisfied`는 화면을 위한 캐시일 뿐이며 권위가 아니다. `satisfied=true`가 된 뒤 새 소비자가 협상에 성공하거나 새 태스크가 시작되면, 실행을 눌렀을 때 재평가에서 걸려 **거부된다.** 이것이 없으면 "충족을 확인한 순간"과 "실행한 순간" 사이의 창이 사고가 된다.

**같은 대상을 겨냥한 비종착 계획은 하나뿐이다.** `change_plan(target_key, site)`의 부분 유일 인덱스(§8.3)가 강제하며, 둘째 생성 시도는 거부하고 기존 계획을 가리킨다.

계획은 `ABANDONED`로 버릴 수 있다. 버려도 이미 실행된 단계는 되돌아가지 않으므로 **각 단계는 그 자체로 가역이거나 무해해야 한다** — 위 표의 가역성 열이 그것을 보장한다. 감사 로그는 `plan_id`로 묶인다.

### 9.6 업스트림 표면

상위 시스템 어댑터(ACL) 구현은 비목표다. **하지만 붙을 자리는 만든다.**

| 표면 | 내용 |
|---|---|
| `GET /catalog?site=` | **"지금 이 사이트가 할 수 있는 일".** 능력 단위 — 스킬 타입, 사용 가능한 최소·최대 버전, **가용 기체 수**, `deprecated_after`, 필수 선택 필드 |
| `POST /requirements` | 소비자가 자기 의존을 등록. 원장의 입력(§9.2) |
| `picasso/{major}/{site}/site/catalog` | 사이트 단위 능력 변경 스트림(§5.5의 사이트 스트림). retain. 폐기 예고도 여기로 |

**카탈로그가 기체가 아니라 능력 단위인 것이 중요하다.** 상위는 "3번 로봇"이 아니라 "이 공장에서 `pick_place`가 되는가"를 알아야 하고, 기체 한 대가 빠졌을 때 카탈로그가 흔들리면 안 된다. 가용 기체 수는 0이 될 때만 능력이 카탈로그에서 사라진다.

사이트 단위 스트림이 있어야 상위가 기체마다 구독하지 않는다. 이 스트림은 `registry`가 기체 단위 `CapabilityChanged`를 구독해 집계한 결과다.

### 9.7 어댑터 생명주기

```
① 어댑터 등록      vendor, name, version, 빌드된 계약 semver
② 프로파일 제출    벤더 문서에서 파생 → 검증 (§8.4 ①)
③ 계약 시험        mimic이 프로파일로 계약 스위트 통과 — 실물 없이 (§8.4 ②)
④ 적합성 시험      실물 어댑터가 프로파일대로 행동하는가
                   → adapter_version.conformance_status. 실행은 C-3이며 비목표
⑤ 바인딩           기체 등록 + 카나리(일부 기체만) → 전체
⑥ 카탈로그 반영    능력이 카탈로그에 올라가고 사이트 스트림으로 통지
```

**④는 이번에 구현하지 않지만 상태는 만든다.** 만들지 않으면 나중에 워크플로우를 다시 짜야 하고, 무엇보다 **"우리는 실물 검증을 아직 안 했다"가 화면에 보여야 정직하다.** `UNTESTED` 상태로 바인딩하는 것은 허용하되 진단 1번에 표시한다.

**⑤의 카나리는 공짜다** — `robot_binding`이 기체 단위이므로 일부만 새 조합으로 바인딩하면 된다. 카나리 중에는 두 개정판이 동시에 돌며, 헤더의 `profile_ref`(§5.5)로 관측된다.

## 10. `mimic` 내부 구조

### 10.1 원칙

**거동은 프로파일에서 오고 코드는 해석기다.** 기종별 클래스가 없다. 세 번째 기종 추가는 프로파일 한 장이며 소스 변경이 0이다.

```
mimic/
  profile/     로드(파일 또는 registry) · 검증 · 투영 · 폴링
  engine/      스킬·태스크 상태머신 · 시계 · 재생 버퍼
  fault/       선언 실패 · 전송 장애 · 한계 집행
  control/     제어 채널 (별도 포트, 루프백 전용)
  transport/   gRPC 서버 · MQTT 발행
```

### 10.2 프로파일 출처와 기동

| 모드 | 인자 | 쓰임 |
|---|---|---|
| 파일 | `--profile <path>` (기체마다) | §13의 2단계, 단위·계약 시험 |
| 레지스트리 | `--registry <url> --robot <id>...` | 3단계 이후, 운영 |
| 후보 개정판 | `--registry <url> --profile-revision <id>` | `harness`가 §8.4 ②를 수행할 때 |

기동: 프로파일 로드 → JSON Schema 검증(실패 시 기동 거부) → 오버라이드 적용해 유효 능력 계산 → `Capability` 투영 → 상태머신 인스턴스화 → 기체마다 `session_id` 발급 → 포트 개방 → `ONLINE` 발행.

**한 프로세스가 여러 가상 로봇을 호스팅하며 기체는 요청 헤더의 `robot_id`로 지정한다.** 포트는 하나다. "엔드포인트만 바꿔 실물과 교체"는 호스트·포트만 바뀌고 `robot_id`는 그대로라는 뜻이다. **PoC의 편의이지 아키텍처 주장이 아니며 ADR에 그렇게 적는다.**

### 10.3 폴링·시계·시드

**폴링.** 레지스트리 모드에서 바인딩과 오버라이드를 주기적으로 조회한다(기본 5초). 변화가 있으면 유효 능력을 다시 계산하고 `capability_epoch`를 올린 뒤 `CapabilityChanged`를 발행한다. 이것이 §8.4 ④이며 `registry → mimic` 간선 없이 런타임 갱신을 성립시키는 방식이다. **진행 중인 태스크는 바꾸지 않는다.** 남는 지연은 §15의 5번이다.

**시계와 시드**는 제어 채널로 설정한다. 이것이 없으면 §12.1의 결정성 규율이 성립하지 않는다.

- **시계 모드** — `REAL`(데모) 또는 `VIRTUAL`(시험). `VIRTUAL`에서는 `AdvanceClock(duration)`으로만 전진한다.
- **시드** — 선언된 실패 모드의 확률 추첨과 소요시간 지터가 여기서 나온다.

### 10.4 실패 주입 세 갈래

1. **선언된 실패 모드** — 프로파일의 실패 모드와 발생률. 시드 기반이라 재현된다.
2. **전송 계층 장애** — 단절, 지연, **이벤트 유실**, 중복 명령, 순서 역전. 제어 채널의 실행 옵션이다(기종 속성이 아니라 환경 속성이므로).
3. **한계 집행** — 프로파일이 선언한 값 범위·허용 값·최대 길이·발행 간격을 그대로 강제한다. 따로 만들 것이 없으며, 클라이언트가 기종 A에서 통과하고 기종 B에서 거절당하는 상황이 여기서 나온다.

### 10.5 제어 채널

**별도 포트에 두며 루프백에만 바인딩한다**(§6.3).

| RPC | 쓰임 |
|---|---|
| `SetSeed(seed)` | 결정성 |
| `SetClockMode(REAL\|VIRTUAL)` / `AdvanceClock(duration)` | 가상 시계 전진 |
| `ForceFault(robot_id, error_type, task_id?)` | `task_id`를 비우면 **로봇 수준 결함** |
| `SetSingleStep(bool)` / `Step()` | 상태 전이를 한 칸씩 |
| `InjectTransportFault(kind)` | `DISCONNECT`, `DELAY`, `EVENT_LOSS`, `DUPLICATE`, `REORDER` |
| `SetConnection(robot_id, ConnectionState)` | §4.3의 값을 그대로 쓴다 |
| `RemoveCapability(robot_id, skill)` / `RestoreCapability(...)` | 로봇 유래 능력 축소를 흉내낸다(§8.2) |
| `ForceTerminalViolation(task_id)` | 종착 확정 후 로봇이 그 태스크를 계속 수행 중인 상황을 만든다(§4.4의 래치 위반) |
| `ForceControlAuthorityLoss(robot_id)` | 제어 권한을 빼앗긴 상황을 만든다(§4.9) |
| `DumpInternalState(robot_id)` | **시험 오라클.** 엔진 내부 상태를 그대로 반환한다 |

`DumpInternalState`가 §12.2의 A-2 오라클이다. 없으면 "재구성한 상태가 내부 상태와 일치"를 확인할 방법이 `GetSnapshot`뿐인데 그것은 계약 표면의 투영이라 투영을 투영과 비교하는 순환이 된다.

**프로파일에 선언되지 않은 `error_type`을 `ForceFault`에 주면 거절한다** — §4.5 전파 규칙 1의 입력인 `resolution`이 프로파일에서만 오므로, 허용하면 태스크 종착 판정이 미정의가 된다. 단 `TERMINAL_STATE_VIOLATED`와 `CONTROL_AUTHORITY_LOST`는 프로파일이 선언하지 않는 어댑터 발행 결함이므로 전용 RPC로 주입한다(아래).

**별도 포트인 것이 설계의 일부다.** 표준 계약과 같은 표면에 두면 프로덕션 소비자가 손댈 수 있고 목이 계약을 오염시킨다. `contracts/`에 들어가지 않으며 별도 proto로 `mimic/` 안에 둔다.

### 10.6 의존 불통 시 거동

| 상황 | 거동 |
|---|---|
| `registry` 불통 (기동 시) | 기동 실패. 능력을 모르는 채 표면을 열지 않는다 |
| `registry` 불통 (폴링 중) | 마지막으로 성공한 유효 능력을 유지하고 경고를 남긴다. 복구되면 다음 폴링에서 반영 |
| MQTT 브로커 단절 | **세션을 유지한 채** 기체당 최대 `N`개(§4.8의 재생 버퍼와 **같은 버퍼·같은 `N`**)까지 쌓고 재연결 시 순서대로 재생한다. 넘치면 그때 새 `session_id`를 발급해 소비자가 스냅샷부터 다시 세우게 한다 |
| `WatchTask` 소비자가 느림 | gRPC 흐름 제어에 맡기되 발신 대기가 임계를 넘으면 그 스트림만 끊는다. 태스크 실행은 영향받지 않는다 |

세 번째가 중요하다 — 단절만으로 세션을 바꾸면 버퍼링이 무의미해지고, 넘칠 때만 바꾸면 버퍼가 실제로 값을 한다.

### 10.7 `mimic`이 하지 않는 것

물리적 도달 가능성과 충돌은 흉내내지 않는다. 가반하중·도달 범위는 선언으로 담기지만 "이 그리퍼로 이 형상을 잡을 수 있는가"는 담기지 않는다. 3D 시뮬레이터가 아니며, 필요해지면 물리 시뮬레이터를 백엔드로 붙이는 확장이다. **표현력의 한계를 인정하는 것 자체가 이 트랙의 설계 결과물이다.**

## 11. 게이트 (`gate/`)

### 11.1 라이브러리이지 CI 스크립트가 아니다

등록 검증과 CI 검증은 같아야 한다. **구현은 하나이고 호출 지점이 둘이다.** 기준선은 인자로 받는다.

| 호출 지점 | 기준선 |
|---|---|
| CI | 기본 브랜치의 같은 프로파일 파일 (`git show origin/main:<path>`) |
| `registry` 등록 | 같은 `profile_id`의 직전 `ACTIVE` 개정판의 `document` |

기준선이 없으면(신규) 파괴 검사는 통과로 처리한다. **6번은 언제나 프로파일 `document`(JSON)를 diff한다** — 평탄화 테이블이 아니다. CI에는 DB가 없으므로 입력을 문서로 통일해야 두 호출이 같은 답을 낸다.

`gate`는 `client/`·`mimic/`·`harness/` 경로를 설정 상수로 안다(7번). 빌드 의존이 아니다.

### 11.2 검사 아홉

| # | 검사 | 막는 것 | 판정 방식 |
|---|---|---|---|
| 1 | `buf lint` | 명명 규칙 이탈 | buf |
| 2 | `buf breaking` | 필드 번호 재사용, 타입 변경 | buf, 기준선 대비 |
| 3 | 프로파일 JSON Schema + **스키마로 표현 불가능한 구조 규칙** | 잘못된 프로파일, 미등록 `error_type`, 뒤집힌 범위, 중복 선언 | 아래 |
| 4 | 프로파일 ↔ proto 교차검증 | proto에 없는 스킬·키, 최신 minor 초과, **선언한 minor의 필수 파라미터 누락** | **양방향** 대조 — 아래 |
| 5 | `contracts/` 의존 0 | 계약 모듈이 무언가를 알게 되는 것 | 빌드 그래프에서 프로젝트 의존 수 == 0 |
| 6 | **능력 어휘 파괴 검사 + 확장/축소 분류** | §5.2 버전 규칙 위반, 파급 미확인 축소 | 아래 |
| 7 | 기종 분기 금지 | A-1의 "같은 클라이언트 코드", §10.1의 "기종별 클래스 없음" | `client/`·`mimic/`·`harness/` 소스에 프로파일의 `vendor`·`model` 값 문자열이 등장하면 실패 |
| 8 | 프로파일 전용 변경 확인 | C-2의 "프로파일 한 장" | 변경 파일이 `profile/profiles/**`뿐인지 `git diff --name-only`로 판정 |
| 9 | 음성 테스트 | 위 여덟이 실제로는 안 막고 있는 상태 | 아래 |

**3번은 JSON Schema 검증만이 아니다.** 2020-12에는 **필드 간 수치 비교가 없고**, `uniqueItems`는 항목 전체를 비교하므로 부분 키 중복을 잡지 못한다. 아래 넷은 스키마 검증 뒤에 프로그램으로 확인한다.

| 규칙 | 놓치면 |
|---|---|
| `min_value ≤ max_value` | 뒤집힌 범위가 런타임에야 터진다 |
| `publish_interval.min_seconds ≤ max_seconds` | 같은 이유 |
| `(skill_type, major)` 중복 금지 | §5.2가 이 쌍을 동일성으로 규정하는데 둘이 공존하게 된다 |
| 한 스킬 안 파라미터 `key` 중복 금지 | 어느 선언이 유효한지 미정의가 된다 |

`error_type` 어휘 검사는 스키마의 enum과 `X_` 패턴이 담당하며, **어댑터 전용 둘(`TERMINAL_STATE_VIOLATED`·`CONTROL_AUTHORITY_LOST`)은 그 enum에서 제외**되어 프로파일이 선언할 수 없다.

**4번은 양방향이다.** 프로파일→proto만 보면 **프로파일이 필수 파라미터를 빠뜨려도 통과한다** — 없는 것을 선언하지 않았을 뿐이므로 참조 무결성은 깨지지 않기 때문이다. 그래서 반대 방향도 본다: 프로파일이 `skill_type@major.minor`를 선언했으면, 계약 카탈로그에서 그 major의 **`since_minor ≤ 선언한 minor`이고 `is_optional = false`인 파라미터가 전부 프로파일에 있는지** 확인한다. `since_minor`와 `is_optional`을 옵션으로 선언해 둔 것이 여기서 값을 한다.

**존재성 판정에 주의한다.** 옵션 확장은 proto2 존재성을 가지므로 `skill_type_max_minor = 0`처럼 **명시적으로 0을 준 것과 아예 선언하지 않은 것이 구분된다**(실측 확인). "최신 minor 초과" 판정은 이 구분에 의존하므로 값만 읽지 말고 존재 여부를 함께 본다.

**6번**은 기준선 문서와 새 문서를 diff해 변화를 분류하고 두 가지를 본다.

1. **선언된 버전 증가가 분류와 맞는가** — 선택 파라미터 추가면 minor로 충분하고, 키 삭제·타입 변경·단위 변경·필수 파라미터 추가면 major를 요구한다.
2. **변경이 축소인가** — 스킬 제거, major 증가, 필수 파라미터 추가는 축소다. 축소면 §9.3의 두 조회 결과를 요구한다. `registry`에 접근할 수 없는 환경에서는 검사를 건너뛰되 **그 사실을 출력에 남긴다**(조용히 통과시키지 않는다).

**7번의 판정이 문자열 검사인 것은 의도적이다.** AST 분석은 우회 방법이 많고 유지 비용이 크다. `harness`가 프로파일을 지칭해야 하는 문제는 **기동 인자(`--profile <path>`)로 CI가 주입**해 푼다. 남는 한계는 §15의 6번이다.

**8번은 diff 판정까지만 하고 스위트를 실행하지 않는다.** 계약 스위트는 `harness`가 소유하므로(§12.1) `gate`가 부르면 §3.2의 그래프가 깨진다. **CI가 `gate`와 `harness`를 순서대로 부른다.** `quadruped-c`는 전용 커밋 하나로 추가하며 그 커밋의 변경 파일은 프로파일 한 장뿐이어야 한다.

**9번은 케이스를 데이터로 보관하고 격리 실행한다.** 깨진 proto 조각이나 "소스 변경이 섞인 커밋" 같은 것을 저장소 본체에 두면 진짜 CI가 깨진다. 그래서 `gate/negative/`에 **케이스마다 디렉터리 하나**를 두고 그 안에 변형된 파일과 합성 diff(`base.patch`, `head.patch`)를 담는다. 실행할 때 임시 작업 디렉터리에 정상 트리를 복사하고 케이스를 덮어쓴 뒤 1~8번을 돌려 **실패를 기대**한다. 통과하면 그 케이스가 실패다. 본체는 오염되지 않는다.

케이스는 1~8번 각각에 최소 하나씩 대응한다.

| 겨냥 | 음성 케이스 |
|---|---|
| 1 | 명명 규칙을 어긴 메시지·필드 이름 |
| 2 | 필드 번호 재사용 |
| 3 | 미등록 `error_type` 사용 |
| 4 | proto에 없는 스킬 선언 / 최신 minor 초과 선언 |
| 5 | `contracts/`에 프로젝트 의존 추가 |
| 6 | 파라미터 키 삭제 후 minor만 증가 / 소비자가 남은 능력 제거 |
| 7 | `client/`·`mimic/`·`harness/`에 기종 문자열 삽입 |
| 8 | 프로파일 커밋에 소스 변경 섞기 |

각각이 CI를 실패시키지 못하면 그 자체가 실패다.

## 12. 시험 전략

### 12.1 계층

단위(상태머신 망라성) → 계약 테스트(`mimic` ↔ `client`) → 결정성 → 음성 → **운영 시나리오**.

**결정성이 최우선 규율이다.** 확률적 실패 모드를 선언하는 순간 시드 없이는 테스트가 흔들리고, 흔들리는 테스트는 무시된다. **시드 + 가상 시계 고정 = 동일 이벤트 시퀀스**를 불변식으로 걸고, 확률이 필요한 시험은 시드를 여럿 돌려 하드 불변식으로 검사한다.

계약 스위트는 `harness/`가 소유하며 CI와 §8.4 ②가 같은 스위트를 실행한다. `revision_test_run.suite` 값은 `CONTRACT`, `NEGATIVE`, `DETERMINISM` 셋이다.

### 12.2 완료 기준 · 증명 · 관측 지점

| # | 완료 기준 | 증명하는 시험 | 그것을 가능하게 하는 메커니즘 |
|---|---|---|---|
| 1 | **A-1** 능력 집합이 다른 두 로봇을 같은 클라이언트 코드로 | 두 프로파일의 `mimic`에 동일 코드 경로로 태스크 완주 | 요구 집합이 설정 파일(§5.4) + 게이트 7번 |
| 2 | **A-2** 중간 구독자의 상태 재구성 | 진행 중 신규 구독 → 스냅샷 + `event`로 재구성한 상태가 내부 상태와 일치 | `GetSnapshot`(§4.4), `DumpInternalState`(§10.5), 재생 버퍼(§4.8) |
| 3 | **A-2** 결손·중복·순서 역전에서 복원 | 셋을 각각 주입해 결손은 감지, 중복은 무시, 역전은 재정렬해 같은 최종 상태 | `InjectTransportFault`(§10.5), `sequence`·`event_id`, **재정렬 창**(§3.5) |
| 4 | **A-2** 멱등 재수신과 revision 규칙 | 같은 revision 재전송은 같은 핸들, 낮으면 `OUTDATED_REVISION`, 높으면 갱신. 버퍼 밖 `ReplayEvents`는 `SEQUENCE_EVICTED` | §4.4의 4케이스 표와 갱신 상태별 표, `ReplayEvents`(§4.4), §4.8의 버퍼, `SetSingleStep`/`Step`으로 재전송 시점 특정 |
| 5 | **A-2** 침묵의 세 원인을 구분 | `OFFLINE`·`HIBERNATING`·`CONNECTION_BROKEN`을 강제하면 소비자가 셋을 다르게 판정 | `SetConnection`(§10.5), 연결 스트림(§4.7), 최대 발행 간격(§7.2) |
| 6 | **A-4** 30초+ 태스크의 진행률·중도취소·부분결과 | 가상 시계로 압축. **같은 `(revision, attempt)` 구간 안에서** 진행률 단조 비감소, `CANCELLING` → 종착 순서 | `SetClockMode(VIRTUAL)` + `AdvanceClock`(§10.3), 진행률 정의(§4.4) |
| 7 | **A-4** 취소·일시정지 불가 스킬 | `CANCEL_UNSUPPORTED` / `PAUSE_UNSUPPORTED` 반환 | 프로파일 §7.4의 차이 + `PauseTask`·`CancelTask` |
| 8 | **A-4** 재시도와 개입 | 프로파일의 `resolution` 셋이 각각 `RETRIABLE` / `NEEDS_INTERVENTION` / `FAILED`를 만들고, 앞의 둘만 `RetryTask`를 받아 `attempt`가 오른다. 나머지 상태에서는 `INVALID_TRANSITION` | `RetryTask`(§4.4), 프로파일의 `resolution`(§7.2), §4.5 전파 규칙 1 |
| 8b | **A-4** 취소와 복구 | 복구 성공은 `CANCELLED`, 실패는 `CANCELLED_RECOVERY_FAILED`. 후자는 로봇 수준 결함을 동반한다 | `CancelTask`(§4.4), §4.5 전파 규칙 3, `ForceFault`(§10.5) |
| 8c | **A-4** 래치 위반 관측 | 종착 확정 후 로봇이 계속 수행 중이면 `TERMINAL_STATE_VIOLATED` 결함이 발행되고, **태스크 상태는 종착에 머문다** | `ForceTerminalViolation`(§10.5), §4.4의 래치 불변식 |
| 8d | **A-4** 제어권 상실 구분 | 제어권을 잃으면 `CONTROL_AUTHORITY_LOST`로 종착하며, 로봇 고장에 의한 `FAILED`와 결함의 `error_type`으로 구분된다 | `ForceControlAuthorityLoss`(§10.5), §4.9 |
| 9 | **C-1** 두 기종이 같은 스키마로, 차이가 전부 데이터 | 두 프로파일이 동일 JSON Schema 통과, §7.4의 일곱 차이가 코드 변경 없이 표현 | 게이트 3번·7번 |
| 10 | **C-1** 투영 일치 | `GetCapabilities` 응답 == §7.2의 투영 표대로 프로파일에서 파생한 값 | §7.2의 투영 표가 파생 함수의 명세. **능력을 하드코딩하면 여기서 걸린다** |
| 11 | **C-2** 세 번째 기종을 프로파일 한 장으로 | `quadruped-c` 전용 커밋에서 전체 스위트 통과 | **게이트 8번이 소스 변경 0을 CI로 강제** |
| 12 | **D-1** 깨는 PR이 사람 없이 차단 | 게이트 9번의 음성 스위트 | 9번의 케이스가 1~8번에 하나씩 대응(§11.2) |
| 13 | 능력 호환성 | 핸드셰이크가 §4.3의 `Negotiate` 거절 다섯을 각각 사유와 함께 반환 | `Negotiate`(§5.4). 픽스처는 셋 — 프로파일 차이(`SKILL_ABSENT`, `LIMIT_EXCEEDED`+`limits_needed`, `REQUIRED_OPTIONAL_MISSING`), `profile/requirements/`의 틀린 요구 집합(`MAJOR_MISMATCH`), `client --identity-override`로 헤더와 페이로드를 어긋나게 함(`IDENTITY_MISMATCH`) |
| 14 | 런타임 축소 | `RemoveCapability` → epoch 증가 → 캐시 무효화 → **해당 스킬만 `CAPABILITY_WITHDRAWN`, 이동은 계속됨** | 제어 채널 → `CapabilityChanged`(§8.2·§4.7), 로봇 수준 결함 |
| 15 | 런타임 갱신 | 개정판 활성화 시 진행 중 태스크는 완주, 새 태스크는 새 개정판. 롤백도 같은 경로 | `mimic` 폴링(§10.3), pinning(§8.4), `SUPERSEDED` 재활성화(§8.3 결정 1) |
| 16 | **운영** 어댑터 최초 추가 | §9.7의 여섯 단계를 통과해 새 기종이 카탈로그에 오른다. `conformance_status=UNTESTED`가 진단 1번에 표시된다 | `adapter`·`adapter_version`(§8.3), 카탈로그(§9.6) |
| 17 | **운영** 축 분리 | 어댑터 버전만 올린 바인딩 전환에서 프로파일 개정판이 그대로임이 관측된다. 계약 semver 불만족 조합은 바인딩이 거부된다 | `robot_binding`의 두 참조(§8.3 결정 5), 합법성 검사(§9.1) |
| 18 | **운영** 의존 원장 | 등록하지 않은 소비자도 협상 성공에서 `OBSERVED`로 잡힌다. 30일 미갱신 시 `active=false`가 되되 삭제되지 않는다 | `consumer_requirement`(§9.2), §5.4의 성공 보고 |
| 19 | **운영** 축소 거부 | 소비자가 남아 있거나 진행 중 태스크가 있으면 **`REMOVE_CAPABILITY` 계획의 제거 단계가 거부된다.** 둘 다 0이 된 뒤에야 열린다 | `change_plan_step.precondition`(§9.5), §9.3의 두 조회, `task.skill_type_id` |
| 20 | **운영** 카나리 | 일부 기체만 새 개정판으로 바인딩했을 때 두 개정판이 동시에 돌고, 헤더 `profile_ref`로 어느 기체가 어느 개정판인지 관측된다 | `robot_binding` 기체 단위(§9.7 ⑤), `profile_ref` 헤더(§5.5) |

11번의 "소스 변경 0" 강제와 19번의 "축소 거부"가 이 프로젝트의 두 주장을 각각 **CI 실패 조건**과 **조작 거부 조건**으로 바꾼다.

### 12.3 시험하지 않는 것

물리·충돌·파지 가능성. 실물과의 적합성 역검증. 다중 로봇 자원 경합과 배차. 성능·부하. 상위 시스템 ACL의 실제 연동. **`mimic`이 통과시킨 계약이 실물에서도 통과한다는 보장은 이 범위에서 나오지 않는다.**

## 13. 구현 단계

| 단계 | 만드는 것 | 끝나면 증명되는 것 |
|---|---|---|
| **1** | `contracts/`, `profile/schema/`, `profile/fixtures/`의 픽스처 한 장, `gate/`(검사 1~6과 9의 해당 케이스), CI 배선 | D-1의 대부분. 계약과 프로파일이 어긋나면 PR이 막힌다 |
| **2** | `mimic`(파일 모드), `client`, `harness`(직접 실행 모드), 기종 프로파일 셋, 게이트 7~8과 9의 나머지 | **완료 기준 1~12.** `registry` 없이 핵심 주장이 CI 실패 조건이 된다. D-1 완결 |
| **3a** | `registry` 코어 — DB, 개정판·어댑터 수명주기, 바인딩과 합법성 검사, 시험 요청·보고, `skill_type` 동기화 잡, MQTT 구독기(`task` 적재 포함), 핸드셰이크·소비자 결함 수집, **`GET /catalog` 읽기 표면**, 진단 1~4. `mimic` 레지스트리 모드와 폴링 | 완료 기준 13~17, 20 |
| **3b** | 의존 원장, 변경 계획, **사이트 카탈로그 스트림**, 진단 5~6, 게이트 6번의 축소 판정 | 완료 기준 18~19 |

2단계가 `registry` 없이 성립하는 것이 이 분할의 핵심이다(§3.2의 규칙, §10.2의 파일 모드). **가장 값이 큰 주장이 가장 적은 인프라로 증명된다.**

3b가 마지막인 것도 의도다 — 원장은 소비자가 있어야 의미가 있고, 소비자는 3a까지 서야 붙는다.

## 14. 남길 결정 기록 (ADR · D-2)

| # | 결정 | 한계 |
|---|---|---|
| 1 | 계약은 proto, 프로파일은 JSON Schema — 대가는 게이트 하나가 늘고 두 세계가 어긋날 수 있다는 것(§7.3·§11.2의 4번으로 갚는다) | — |
| 2 | 실패 3분류를 계약에서 빼고 두 불리언으로 대체 | — |
| 3 | 능력 동일성을 major로 결정하는 규칙과 그 집행 한계 | → §15.1 |
| 4 | 경로·헤더·페이로드의 역할 분리와 `contract_revision`의 semver 병기 | → §15.4 |
| 5 | 검증을 세 시점으로 나눈 이유와 프로덕션 샘플링 | — |
| 6 | gRPC와 MQTT의 역할 분리, 두 카운터를 나눈 이유, 순서를 계약이 보장하는 것 | — |
| 7 | 세션 단위 시퀀스와 메모리 재생 버퍼 — 지속 저장을 B-1로 미룬 것 | → §15.8 |
| 8 | `Reset`을 RPC로 노출하지 않고 엔진 내부 전이로 둔 것 | — |
| [9](../../adr/0009-no-declaration-without-consumer.md) | 즉시 명령과 동시성 어휘를 계약에서 뺀 것 — 소비 표면이 없는 선언은 두지 않는다 | — |
| [10](../../adr/0010-projection-boundary.md) | 투영에 들어가는 것과 발신자 내부 설정을 나눈 기준 | — |
| 11 | 개정판 불변, `SUPERSEDED` 재활성화로 롤백, `ACTIVE` 직접 폐기 금지 | — |
| 12 | 진행 중 태스크의 개정판 pinning과 갱신이 그것을 바꾸지 않는 것 | — |
| 13 | 능력 축소의 두 경로, `registry`가 오버라이드의 유일한 기록자이되 epoch의 채번자는 발신자인 것 | — |
| 14 | **네 축을 분리하고 되돌릴 수 없는 축을 하나로 몰아둔 것** | — |
| 15 | **의존 원장 — `DECLARED`와 `OBSERVED`를 함께 쌓는 이유, 비활성화는 하되 삭제하지 않는 이유** | → §15.10 |
| 16 | **축소의 게이트를 시간이 아니라 관측으로 둔 것** | — |
| 17 | **변경 계획을 1급 객체로 두고 단계마다 전제 조건을 건 것** | — |
| 18 | **카탈로그를 기체가 아니라 능력 단위로 둔 것** | — |
| 19 | `mimic`이 `registry`를 폴링하는 방향 선택 — push를 쓰지 않아 순환을 피한다 | → §15.5 |
| 20 | `harness`를 분리하고 시험 요청을 행으로 적재해 순환을 피한 것 | — |
| 21 | `mimic` 한 프로세스 다중 로봇 — PoC 편의이지 아키텍처 주장이 아님 | — |
| [22](../../adr/0022-gate-as-library.md) | `gate`를 라이브러리로 두고 CI·registry가 기준선만 달리해 공유, 6번은 언제나 문서를 diff | — |
| [23](../../adr/0023-string-checks-over-ast.md) | 게이트의 구조 검사(5·7번)를 문자열 검사로 둔 것 | → §15.6·§15.11 |
| 24 | 인증·인가를 범위 밖으로 두고 신뢰 네트워크를 전제한 것 | → §15.3 |
| 25 | 실물 없이 계약을 먼저 굳히는 순서를 택한 것 — C-3를 뒤로 미룬 대가 | → §15.7 |
| 26 | 사이트 축을 스키마·경로·카탈로그에만 남기고 격리·브리지를 비목표로 둔 것 | → §15.9 |
| 27 | **물리적 도달 가능성·충돌을 프로파일 선언 밖에 두고 `mimic`이 흉내내지 않기로 한 것** — 표현력의 경계를 어디에 그었는가 | → §15.2 |
| 28 | openTCS·VDA5050에서 가져온 것과 반례로 쓴 것의 출처 | — |
| [29](../../adr/0029-shared-profile-model.md) | **`profile-model`을 뽑아 `gate`와 `mimic`이 공유** — 2단계에서 추가 | — |
| [30](../../adr/0030-codegen-outside-buf.md) | **proto 코드 생성을 `buf` 밖으로** — 2단계에서 추가 | → §15.20 |

기록은 `docs/adr/`에 있고 번호가 이 표의 행 번호다. **이미 내려서 코드에 박힌 것만 쓴다** — 3a·3b가 만들 것(11~21)은 그때 쓴다. 결정하지 않은 것을 미리 적어 두면 그것이 결정처럼 보인다.

## 15. 알려진 한계

1. **버전 규칙은 벤더가 지킬 때만 작동한다.** 선언을 그대로 두고 거동만 바꾸면 게이트 6번 diff에 아무것도 나오지 않는다. `Pick`이 "잡았다"에서 "잡고 들어올렸다"로 바뀌는 경우다. 적합성 역검증에서만 드러나며, 그때까지의 방어는 "프로파일을 벤더 문서에서 파생시키고 괴리가 발견되면 고친다" 하나다.
2. **프로파일의 표현력에 한계가 있다.** 가반하중과 도달 범위는 담기지만 형상별 파지 가능성은 담기지 않는다. 어디까지를 선언으로 두고 어디부터를 시도해봐야 아는 것으로 남길지가 이 트랙의 진짜 설계 문제다.
3. **감사 로그가 부인방지 근거가 아니다.** 행위자 신원을 요청 헤더에서 그대로 받으므로 위조 가능하다. 조사 단서로만 쓴다.
4. **`contract_revision`의 다이제스트 부분은 경보 전용이다.** semver로 major 불일치는 차단하지만, 같은 semver 안에서 실제로 호환되지 않는 변경이 있었다면 사후 조사에만 쓰인다.
5. **런타임 갱신에 폴링 지연이 있다.** 기본 5초만큼 반영이 늦는다. push로 줄일 수 있으나 `registry → mimic` 간선이 생겨 §3.2가 깨지므로 지연을 받아들였다.
6. **게이트 7번이 문자열 검사다.** 기종 식별자를 계산해 만들면 우회된다. 우회를 막는 것이 아니라 실수를 막는 장치다.
7. **`mimic`이 계약을 정의해버릴 위험이 있다.** 실물이 없는 동안 계약이 에뮬레이터에 맞춰 굳을 수 있다. 적합성 역검증이 이를 막는 장치이며 그전까지는 프로파일을 벤더 문서에서 파생시키는 규율에 의존한다.
8. **재생 버퍼가 프로세스 메모리에 있다.** 재기동하면 세션이 바뀌고 소비자는 스냅샷부터 다시 세운다.
9. **다중 사이트는 스키마·토픽 경로·카탈로그에만 반영돼 있다.** 사이트별 배포 격리와 브리지 정책은 비목표다.
10. **원장은 소비자가 우리를 거쳐 갈 때만 정확하다.** `OBSERVED`는 핸드셰이크를 하는 소비자만 잡는다. 우리를 우회해 로봇에 직접 붙는 경로가 생기면 원장이 조용히 거짓말을 하고, 그 위에 선 축소 판정도 함께 틀린다. **원장의 정확성은 "모든 접근이 이 계약을 지난다"는 조직적 합의에 의존하며 기술로 강제되지 않는다.**

11. **게이트 5번도 문자열 검사다.** 7번과 같은 이유이며(Gradle을 부르면 `gate → Gradle → gate`가 된다) 대가가 셋이다. 관례 플러그인이나 별도 `.gradle.kts`가 주입하는 프로젝트 의존은 **못 잡고**, 블록 주석(`/* … */`) 안의 `project(` 와 루트가 **자기 자신을 위해** 갖는 의존은 **거짓 실패**다. 잡는 형태는 `project(":x")`·`project(path = ":x")`·`projects.x` 셋이며, 넷째 형태가 생기면 이 검사를 고쳐야 한다.
12. **`gate`는 `contracts`에 빌드 의존을 걸지 않는다**(§3.2의 단서). 그래서 `buf build`가 `./gradlew build`보다 먼저 돌아야 하고, 그 순서를 강제하는 것은 Gradle이 아니라 CI의 스텝 순서다. 검사 5번은 이 불일치를 잡지 못한다 — `contracts`의 의존만 세기 때문이다.
13. **proto 파일 하나가 나쁘면 `ContractIndex`가 통째로 실패한다.** 게이트는 빨간불이 되므로 안전한 방향이지만, 나머지 스킬들의 대조 결과를 함께 잃는다.
14. **게이트 6번이 분류하지 않는 파괴가 있다.** 허용값 축소·문자열 길이 축소·수치 범위 축소는 §5.2가 열거하지 않아 **경고로만** 낸다. 실제로 클라이언트를 깰 수 있으므로 사양이 이를 분류하면 그때 오류로 올린다. 반대로 §5.2의 "성공 판정 기준 변경"은 프로파일에 담기지 않아 6번이 볼 수 없다 — 그것은 위 1번과 같은 성격이다.
15. **음성 하네스의 케이스 1·2는 Docker가 있어야 돈다.** 없으면 건너뛰며, `picasso.negative.strict=true`(CI)일 때만 실패한다. 로컬은 Docker 없이 나머지 일곱을 돈다.
16. **`buf.yaml`의 `breaking:` 절이 파싱되는지는 PR 빌드에서만 확인된다.** push 빌드에는 기준선이 없어 검사 2번이 건너뛴다. 자기 자신과 비교하는 `--against .` 스텝을 지운 대가다.
17. **CI와 CLI 기본값이 `profile/fixtures/`를 본다.** 2단계에서 `profile/profiles/`가 생기면 **둘 다 고쳐야 하며**, 안 고치면 진짜 프로파일이 게이트를 지나지 않는다. 기준선 파일도 `basename`으로 평탄화하므로 디렉터리가 여럿이 되면 동명 파일이 조용히 덮인다.
18. **검사 6의 `optional_fields` 매칭이 접미사 일치다.** 같은 파라미터 key를 가진 다른 스킬에도 소견이 붙는다. 1단계 프로파일에는 스킬이 둘뿐이라 드러나지 않는다.
19. **`mimic`이 게이트가 거절할 프로파일로 기동할 수 있다.** `ProfileSource`는 JSON Schema만 보고 게이트 검사 3번의 구조 규칙 넷(발행 간격 뒤집힘, `(skill_type, major)` 중복, 스킬 내 `key` 중복, 어댑터 전용 `error_type`)은 보지 않는다. §10.2가 요구하는 것이 스키마 검증뿐이라 사양 위반은 아니지만, `--profile <path>`가 임의 경로를 받으므로 실제로 가능한 비대칭이다.
20. **proto 코드 생성과 디스크립터를 서로 다른 도구가 만든다**(ADR 30). Gradle protobuf 플러그인의 protoc와 `buf` 내장본이 버전이 달라 생성 코드와 디스크립터가 미세하게 다를 수 있다. 쓰는 것이 메시지 구성이라 실질 영향은 없다. 그리고 protobuf-gradle-plugin 0.9.4는 **Gradle 10에서 깨진다** — legacy `Usage` 속성과 다중 문자열 의존 표기가 플러그인 내부에서 나오므로 우리가 못 고친다. Gradle 10 이전에 플러그인 버전을 올려야 한다.
21. **`session_id`가 §5.5의 ULID가 아니라 기동 카운터다.** ULID의 난수부를 시드에서 뽑으면 §12.1의 결정성 규율("시드 + 가상 시계 고정 = 동일 이벤트 시퀀스")과 "재기동하면 새 세션"이 충돌하고, `UUID.randomUUID()`를 쓰면 결정성이 깨진다. 세션의 요건은 "온라인이 될 때마다 새것"이므로 프로세스 내 카운터로 족하다. **대가는 프로세스를 재기동하면 카운터가 0으로 돌아간다는 것**이다 — 같은 밀리초에 재기동하면 세션이 겹칠 수 있다. `mimic`은 PoC이므로 감수하고, 실물 어댑터는 ULID를 쓴다.
