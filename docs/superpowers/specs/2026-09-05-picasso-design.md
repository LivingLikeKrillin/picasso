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

설계 과정에서 추가된 넷:

| 항목 | 사유 |
|---|---|
| 능력 호환성 체계 | 제조사·버전에 따라 능력이 늘고 줄고 세분화된다. API·헤더·토픽·키 설계와 통신 시 검증 전략이 필요하다 |
| 레지스트리와 런타임 갱신 | 프로파일은 설계노트 §4.5 기준으로 데이터이며, 갱신을 위해 프로세스를 내리면 안 된다 |
| **운영 변경 체계** (§9) | 어댑터 추가·변경, 기능 추가·변경·삭제가 운영에서 실제로 벌어진다. 파급 범위와 반영 순서가 정립돼 있어야 하며, 상위 시스템까지의 순서가 특히 그렇다 |
| **상위 연계 층** (§9.6) | **2026-09-08 추가.** 표면만 두고 그것을 쓰는 층을 범위 밖에 두면 소비자 없는 선언이 된다([ADR 9](../../adr/0009-no-declaration-without-consumer.md)). 아래쪽 어댑터와 같은 모양으로 경계 안에 둔다 — 층은 우리 것, 인스턴스 소유는 배치 |

구현은 §13의 네 단계로 나눈다.

### 1.3 비목표

- **A-3 원자적 명령 전달의 대안 비교** — 파라미터와 실행을 단일 RPC로 묶어 경쟁 조건을 만들지 않는 쪽을 택한다.
- **A-5 이동·내비게이션 스킬 타입** — 스킬 타입 어휘를 늘리는 일이며 구조는 A-1이 이미 담는다.
- **A-6 위치 레지스트리** — 스키마가 이를 막지 않도록만 두고 구현하지 않는다.
- **B-1 무선 단절 대응의 집행** — ~~재기동 후 재개 지점 복원과 저장소 커밋~~ **2026-09-09 좁힘([ADR 38](../../adr/0038-mission-layer-schema-is-ours.md)): 재기동 후 저장소 복원만 밖이다. 접수 불명(`IN_DOUBT`)의 해소 경로는 범위 안이다.** 다만 **멱등성 키는 계약에 지금 넣는다.**
- **B-2 텔레메트리 경로 분리** — 따라서 프로파일은 텔레메트리 **항목**을 담지 않는다.
- **B-3 브리지 정책.**
- **C-3 적합성 역검증의 실행** — 실물이 도착해야 의미가 생긴다. 다만 **워크플로우상의 자리와 상태는 만든다**(§9.7 ④).
- **D-3 예외 구역 경계 집행** (사이트 분기 정적 검사).
- **미션 계층** — 배차·라우팅·자원 중재·다중 로봇 경합. 따라서 **동시성 정책 어휘(blocking type)도 담지 않는다.** **2026-09-09 좁힘([ADR 38](../../adr/0038-mission-layer-schema-is-ours.md)): 이 항목이 빼는 것은 배차·라우팅·자원 중재까지다. 논리적 능력의 조합과 실행 상태 관리는 범위 안이며, 그 스키마와 PoC 엔진은 [미들웨어 중앙 설계](2026-09-09-middleware-core-design.md)가 다룬다.** 한 낱말로 묶은 채 통째로 뺀 것이 시나리오마다 *"층 ③이 한다, 층 ③은 없다"* 를 반복하게 만들었다.
- **즉시 명령(instant action)** — 태스크 한 단계로 표현할 수 있으므로 별도 표면을 두지 않는다.
- **상위 시스템 어댑터(ACL) 구현 — 2026-09-08 이 목록에서 뺐다.** 원래 문장은 *"MES·WMS·WCS별 변환기는 만들지 않는다. 다만 상위가 붙을 표면과 순서 규약은 만든다"* 였다. 표면의 소비자를 영영 범위 밖에 두는 배치이므로 ADR 9와 어긋난다. **층은 경계 안으로 옮겼고 인스턴스의 소유는 배치에 둔다**(§9.6). 지운 자리에 이 줄을 남기는 것은, 이 결정이 처음부터 그랬던 것으로 읽히면 안 되기 때문이다.
- **물리 시뮬레이션** — 도달 가능성, 충돌, 파지 가능성.
- **안전 기능** — 비상정지·안전 정격 감속·사람 감지·보호 정지. 이 계약은 이것들을 나르지 않으며 안전 회로는 이 시스템의 가용성과 무관하게 동작한다([ADR 32](../../adr/0032-safety-boundary.md)). *"즉시 명령"을 뺀 논거(태스크 한 단계로 표현 가능)는 **작업 명령에만** 유효하다.*
- **지연 예산** — 명령 왕복 지연의 상한을 정하지 않는다. `publish_interval`의 30초는 **상태 발행 주기**이지 왕복 지연이 아니다.
- **인증·인가 체계** — §6.3에 전제를 적는다.
- **정책 설정 저장소** — 임계·상한을 데이터로 두는 일은 소비자가 생긴 뒤에 만든다.

### 1.4 완료 기준

§12.2에 **23행**으로 정리한다. 백로그 14행(A-1 1, A-2 4, A-4 6, C-1 2, C-2 1), D-1 1행, 능력 호환성 1행, 레지스트리·런타임 갱신 2행, 운영 변경 5행이다.

A-4가 여섯으로 늘어난 것은 실물 조사(§2.3)의 결과다 — 취소가 복구를 동반하고, 실패가 사람 개입 대기일 수 있고, 종착이 래치되지 않는 로봇이 있고, 제어권을 빼앗길 수 있다는 사실이 전부 태스크 생명주기에 걸린다. 백로그의 두 행(장기 실행, 취소·일시정지 불가)에 그 넷이 붙어 6·7·8·8b·8c·8d가 됐다.

*(2026-09-09 정정 — 이 절이 24행·A-4 7행이라 적고 있었는데 §12.2의 실제 A-4 행은 여섯이다. 표를 세어 숫자를 맞췄다. 반대로 행 하나가 빠진 것이라면 이 정정이 아니라 표에 행을 더하는 쪽이 맞다.)*

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
  capability/         능력의 투영과 판정 — 프로파일 → Capability, 요구 집합 대 Capability 협상.
                      mimic·어댑터 호스트 공유 (§15.98·§15.100). **전송을 모른다**
  uplink/             발신자의 위쪽 결선 — 브로커 발행(§3.5)과 레지스트리 적재(핸드셰이크·태스크·생존·폴백). mimic·어댑터 호스트 공유 (§15.99)
  registry/           개정판·어댑터·원장·변경 계획·카탈로그    8·9절
  mimic/              프로파일 주도 에뮬레이터 + 제어 채널      C-2
  client/             계약 소비자 — 완료 기준 증명용
  adapter-core/       어댑터들이 공유하는 계약 쪽 어휘와 `RobotAdapter`. **기종을 모른다** (ADR 33)
  adapter-host/       어댑터 하나를 계약의 gRPC 서비스 뒤에 세우는 서버. **기종을 모른다** (ADR 39)
  adapter-<vendor>-<model>/
                      실물 어댑터. **기종을 아는 유일한 자리** (ADR 33)
                      `adapter-boston-dynamics-orbit/` 만 기체가 아니라 **플릿**에 붙는다 (§15.102)
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
harness       → mimic, client, contracts, uplink
adapter-core  → contracts
capability    → contracts, profile-model   ※ grpc 를 안 쓴다 — 판정 불가는 타입이고 전송 매핑은 서비스가 한다
uplink        → contracts   ※ registry 는 모른다 — 그 방향은 HTTP 다(아래 표)
adapter-host  → contracts, adapter-core, profile-model, capability, uplink   ※ 어댑터 모듈은 모른다 — 조립은 기종을 아는 쪽(ADR 39)
mimic         → profile-model, capability, contracts, uplink   ※ 2026-09-10 에 투영·협상 판정은 capability 로, 발행·적재는 uplink 로 나갔다
adapter-<v>-<m> → contracts, adapter-core    ※ 아래 단서
```

**`adapter-*`는 기종마다 모듈 하나다(ADR 33).** 게이트 7번이 보는 셋
(`client`·`mimic`·`harness`) **밖**에 두는 것이 요점이며, 기종 지식이 갈 곳이
정확히 거기라서 나머지가 기종을 모를 수 있다. 의존이 `contracts` 하나인 것도
같은 이유다 — 어댑터가 `registry`를 알면 §3.2의 순환 회피 규칙이 깨지고,
`profile-model`을 알면 어댑터가 자기 프로파일을 읽는 쪽이 되어 선언과 구현이
같은 곳에서 나온다. **벤더 SDK도 아직 여기 없다**(§15.55).

**`adapter-core`는 두 번째 어댑터에서 생겼다.** ADR 33이 대가로 적어 둔 것 — *"두 번째 어댑터가 생길 때 중복이 보이면 공통을 뽑아야 하는데, 그 공통 모듈은 다시 기종을 몰라야 하고 그때 검사 7번의 목록에 더해야 한다"* — 이 그대로 일어났다. 게이트 7번이 이제 넷을 본다(`client`·`mimic`·`harness`·`adapter-core`). **어댑터 모듈 자체는 여전히 그 목록에 없다** — 기종을 아는 것이 그것의 일이다.

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
| `adapter-host` ⇢ `registry` | 핸드셰이크 결과·생존 보고·태스크 관측 적재 — 미믹과 **같은 `uplink` 결선**(§15.99·§15.100) | 발행만 하고 적재 없이 동작 |
| `adapter-host` ⇢ 브로커 | 상태·이벤트·연결 발행 — 토픽·헤더 열·`sequence` 축이 미믹과 같다 | — |
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
| `skill_catalog.proto` | **계약이 소유하는 스킬 타입 어휘.** `since_minor`·`is_optional`·`skill_type_max_minor`, 그리고 파라미터가 어느 이름 공간의 것인지를 말하는 `is_site_reference`(장소)·`is_object_reference`(대상)를 proto 커스텀 옵션으로 싣는다(§15.78) | A-1 |
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

**잔여 물리 상태 — `WatchTaskResponse.hold`(0.4.0).** 갱신마다 로봇이 **무엇을 들고 있는가**를 `HoldState{kind, object_ref, reason}`로 싣는다. `kind`는 넷 — `UNSPECIFIED`(옛 발신자) · `NOT_OBSERVABLE`(볼 수 없다, `reason`에 이유) · `EMPTY` · `HOLDING`(그것이 이 태스크의 대상이면 `object_ref`에 그 **이름**, §15.78). 넷인 이유는 `Support`가 3값인 이유와 같다 — 빈손과 볼 수 없음을 접으면 발신자가 거짓말을 하게 된다. **종착 갱신의 것이 취소·실패 뒤의 잔여 상태다.**

| 불변식 | 뜻 |
|---|---|
| `CANCELLED` ⇒ `kind ≠ HOLDING` | 복구까지 마쳤다는 말과 들고 있다는 말은 양립하지 않는다. 들고 있으면 `CANCELLED_RECOVERY_FAILED`다 |
| `CANCELLED_RECOVERY_FAILED` ⇏ `HOLDING` | 역은 성립하지 않는다 — 놓쳐서 실패했을 수 있고, 그때는 `PAYLOAD_LOST`가 같이 서고 손은 비어 있다 |

발신자마다 근거가 다르고 그 차이가 `kind`에 그대로 나타난다 — Spot은 `ManipulatorState.is_gripper_holding_item`(벤더 불리언), Digit은 `get-execution-state`의 노드 상태에서 **추론**(`action-pick` 성공 ∧ `action-place` 미완), G1은 `NOT_OBSERVABLE`(원시 압력값뿐), `mimic`은 대상을 **쥐는** 스킬(`grasps_object`, 0.5.0)이 도는 동안 `HOLDING` — 참조만 하는 `inspect`는 빈손이다(§15.87). **어댑터는 멈춘 뒤 이것을 보고 종착을 정한다** — 들고 있으면 `CANCELLED`로 적지 않는다. 스냅샷에는 넣지 않았다(§15.85). 상세는 §15.85.

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
  FailureClass failure_class          정준 실패 분류 — 상류는 이것으로만 분기한다 (0.6.0, §15.91)
  string   vendor_detail              벤더 원문 — 진단 동반, 분기 입력 아님 (0.6.0)
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

조작 열다섯: 기종 등록 / **기체 등록**(`POST /operations/robots`, ADR 37 — 발견은 적재 문 `POST /ingest/robots`) / **어댑터 등록** / **어댑터 버전 등록** / 개정판 올리기(DRAFT 생성·편집·재제출) / 시험 요청 적재 / 바인딩(카나리 포함) / 롤백 / 개정판 폐기 / 능력 차단·해제 / **계약 축 폐기 예고 설정**(`skill_type_deprecation`) / **소비자 요구 등록**(`POST /requirements`) / **변경 계획 생성** / **변경 계획 단계 실행** / **사이트 이름 등록 기록**(`POST /operations/site-names`, ADR 35).

전부 감사 로그 대상이다. 요구 등록은 소비자가 자기 것을 쓰는 조작이라 승인 경계 밖이지만 기록은 남긴다.

승인 경계 — **DRAFT 편집은 자유, ACTIVATE는 §8.4 ③의 세 조건, 변경 계획 단계는 §9.5의 전제 조건.**

진단은 **read-only JSON 엔드포인트 아홉**으로 낸다. 화면은 이를 그대로 표로 그리는 한 장이며 폴링(기본 10초)한다.

| # | 엔드포인트 | 답하는 것 | 출처 |
|---|---|---|---|
| 1 | `GET /diag/bindings` | 이 기체는 어느 어댑터·개정판인가 / 이 개정판을 쓰는 기체는 몇 대인가 | `robot_binding` |
| 2 | `GET /diag/diff?from=&to=` | 두 개정판의 능력 diff | `profile_skill*`, `profile_optional_field` |
| 3 | `GET /diag/epochs?robot_id=` | `capability_epoch` 이력과 사유, **관측된 개정판** | `capability_epoch_log` |
| 4 | `GET /diag/rejections` | 어떤 클라이언트가 어떤 요구로 거절당했는가 | `handshake_rejection` |
| 5 | `GET /diag/dependents?skill=` | **이 능력을 지금 누가 쓰는가** | `consumer_requirement` |
| 6 | `GET /diag/plans` | 진행 중인 변경 계획과 각 단계의 충족 여부 | `change_plan*` |
| 7 | `GET /diag/software` | **프로파일이 전제한 펌웨어와 기체가 보고한 것이 갈렸는가.** 판정은 셋이다 — 일치·불일치·미보고. 접으면 신원 질의가 없는 기종이 언제나 불일치로 보인다 | `robot_liveness`, `profile_revision.document` |
| 8 | `GET /diag/stalled` | **아무도 정리하지 않아 축소를 막고 있는 태스크.** `RETRIABLE`·`NEEDS_INTERVENTION`은 기다린다고 안 풀리므로 따로 표시한다 | `task`, `skill_type` |
| 9 | `GET /diag/robots` | **이 기체가 왜 여기 있는가**(ADR 37) — 출처(선언·발견)와 상태 넷(`CLAIMED`·`DISCOVERED`·`CONFIRMED`·`UNREGISTERED`). 오타 난 `robot_id`로 선언한 기체가 `CLAIMED`로 남는다 | `robot`, `robot_liveness` |

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

**두 조회 앞에 조건이 하나 더 있다 — 그 능력을 제공하는 바인딩된 기체 전부가 지금도 보고하고 있어야 조회가 답을 낸다.** 하나라도 조용하면 답은 개수가 아니라 "모른다"이며 축소가 막힌다. 전역 워터마크 하나로 판정하면 열 대 중 아홉이 보고하는 한 신선해 보이고, 조용해진 한 대가 아직 돌리는 능력이 "쓰는 사람 0명"으로 제거된다.

`HIBERNATING`은 예외다 — §4.7이 그것을 "침묵하지만 정상"으로 정의했으므로 살아 있는 것으로 센다. 다만 상한을 둔다(신선도 창의 배수). 어댑터가 `HIBERNATING`을 보고하고 죽으면 영원히 면제되는데 그 오답은 축소를 **여는** 쪽이다.

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

**단계의 종류(`kind`)는 다섯이다.**

| `kind` | 하는 일 | 가역성 |
|---|---|---|
| `ANNOUNCE` | 폐기 예고를 기입한다(§9.3의 표) | 가역 — 예고를 지우면 된다 |
| `OBSERVE_MIGRATION` | 아무것도 하지 않고 조건 충족만 기다린다 | 무해 |
| `DRAIN` | 아무것도 하지 않고 진행 중 태스크가 빠지기를 기다린다 | 무해 |
| `APPLY` | 실제 제거를 수행한다 | §9.1의 축별 되돌리는 법 |
| `VERIFY_WITHDRAWAL` | 아무것도 하지 않고 **축소가 어댑터까지 내려갔는지** 기다린다 | 무해 |

**`precondition`은 검사 목록이다.** 구조는 `{ checks: [ {type, params} ] }`이고 `type`은 여섯이다.

| `type` | 참이 되는 조건 |
|---|---|
| `NO_ACTIVE_CONSUMERS` | `consumer_requirement`에 대상 스킬을 요구하는 `active` 행이 0 |
| `NO_INFLIGHT_TASKS` | `task`에 대상 `skill_type_id`의 비종착 행이 0 |
| `DEPRECATION_PUBLISHED` | 대상의 `deprecated_after`가 채워져 있고 카탈로그에 반영됨 |
| `CAPABILITY_WITHDRAWN` | 대상 능력을 제공하던 기체 전부가 `APPLY` 시점 baseline보다 큰 `capability_epoch`로 보고를 마쳤다. **기체 사이에 epoch를 비교하지 않는다** — §8.2가 채번자를 발신자로 정했으므로 같은 기체의 baseline 대비로만 본다 |
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

### 9.6 업스트림 표면과 상위 연계 층

**2026-09-08 정정 — ACL은 비목표가 아니라 경계 안이다.** 이 절은 원래 *"상위 시스템 어댑터(ACL) 구현은 비목표다. 하지만 붙을 자리는 만든다"* 로 시작했다.

그 배치가 [ADR 9](../../adr/0009-no-declaration-without-consumer.md)와 어긋난다. 아래 표면들의 소비자는 상위 시스템인데, 그것을 붙이는 층이 영영 범위 밖이면 **읽는 코드가 없는 선언**이 된다. `BlockingType`을 뺄 때 적은 사유 — *"동시성 정책의 주인은 미션 계층이고 비목표다. 계약이 그것을 선언하면 아무도 안 읽는다"* — 와 같은 모양이다. ADR 9는 그 경우의 답을 둘로 적어 두었고(빼거나, 소비 표면을 함께 만들거나) 여기서는 **두 번째**를 택한다.

**층은 경계 안, 인스턴스의 소유는 배치다.** 둘을 섞으면 안 된다 — 층을 밖에 두면 위의 문제가 돌아오고, 인스턴스까지 제품이 소유하면 릴리스 주기가 고객사 MES에 묶인다. 배치는 아래쪽과 같은 모양이다.

| | 코어 | 인스턴스 | 소유 |
|---|---|---|---|
| 위 | `acl-core` — 시스템을 모른다 | `acl-{system}` × N | 배치 |
| 아래 | `adapter-core` — 기종을 모른다 | `adapter-{vendor}-{model}` × 3 | 제품([ADR 31](../../adr/0031-adapter-ownership.md)) |

아래쪽에서 기종 지식을 한 모듈에 가둔 것이 [ADR 33](../../adr/0033-adapter-module-shape.md)이고, 위쪽도 같은 이유로 같은 모양이어야 한다. **게이트 7번의 쌍둥이 검사(코어에 시스템 문자열 금지)가 그 선을 지켜야 하며, 아직 없다.**

아래 표면은 그대로 유지된다. 달라진 것은 **소비자가 경계 밖의 누군가가 아니라 `acl-{system}`이라는 것**이다.

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

**여섯 옆에 하나가 더 붙는다 — 사이트 이름 등록.** [ADR 35](../../adr/0035-site-names-live-in-the-robot.md)가 정한 대로 계약이 나르는 것은 사이트의 이름이고, 그 이름을 로봇이 알게 만드는 것은 배포 절차다(Spot은 지도 녹화 시의 웨이포인트 명명, Digit은 `add-object`). **④와 같이 막지 않고 상태로 보인다** — 진단 1번의 `siteNames`이며 등록 대상은 프로파일이 선언한 스킬에서 유도한다(§15.68).

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

물리·충돌·파지 가능성. 실물과의 적합성 역검증. 다중 로봇 자원 경합과 배차. 성능·부하. **`mimic`이 통과시킨 계약이 실물에서도 통과한다는 보장은 이 범위에서 나오지 않는다.**

**2026-09-08 — 이 목록에서 "상위 시스템 ACL의 실제 연동"을 뺐다.** 층이 경계 안으로 들어왔으므로 계약 스위트가 그 층도 봐야 한다. 상대가 서버이므로 도구는 스텁과 통합 시험이며, 아래쪽 `mimic`에 해당하는 것을 위쪽에 둘 이유는 없다 — `mimic`은 상대가 로봇이라서 있는 것이다. 다만 **목록에서 빼는 것과 시험이 생기는 것은 다른 일이고, 여기서 일어난 것은 앞의 것뿐이다.**

## 13. 구현 단계

| 단계 | 만드는 것 | 끝나면 증명되는 것 |
|---|---|---|
| **1** | `contracts/`, `profile/schema/`, `profile/fixtures/`의 픽스처 한 장, `gate/`(검사 1~6과 9의 해당 케이스), CI 배선 | D-1의 대부분. 계약과 프로파일이 어긋나면 PR이 막힌다 |
| **2** | `mimic`(파일 모드), `client`, `harness`(직접 실행 모드), 기종 프로파일 셋, 게이트 7~8과 9의 나머지 | **완료 기준 1~12, 그리고 13의 거절 절반.** `registry` 없이 핵심 주장이 CI 실패 조건이 된다. D-1 완결 |
| **3a** | `registry` 코어 — DB, 개정판·어댑터 수명주기, 바인딩과 합법성 검사, 시험 요청·보고, `skill_type` 동기화 잡, MQTT 구독기(`task` 적재 포함), 핸드셰이크·소비자 결함 수집, **`GET /catalog` 읽기 표면**, 진단 1~4. `mimic` 레지스트리 모드와 폴링 | 완료 기준 13~17, 20 |
| **3b** | 의존 원장, 변경 계획, **사이트 카탈로그 스트림**, 진단 5~6, 게이트 6번의 축소 판정 | 완료 기준 18~19 |

**13이 두 단계에 걸친다.** §12.2의 13번은 `Negotiate`가 거절 다섯을 사유와 함께 돌려주는 것과 §5.4의 "결과를 `registry`에 보고"를 함께 요구한다. 앞엣것은 `registry` 없이 증명되므로 2단계가 닫고 **보고는 3a**다. 한 행을 반만 닫는 것이 어색하지만, 나눠 적지 않으면 2단계가 증명한 것보다 많이 증명한 척하게 된다.

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
| [31](../../adr/0031-adapter-ownership.md) | **어댑터는 우리가 쓰고 유지한다** — §9.1의 어댑터 축에 주인이 없었다. 대가는 벤더 SDK 라이선스가 배포 형태를 제약하는 것 | — |
| [32](../../adr/0032-safety-boundary.md) | **이 계약은 안전 기능을 나르지 않고 왕복 지연 상한도 두지 않는다** — 비목표에도 범위에도 없어 침묵이던 것을 문장으로 | — |
| [33](../../adr/0033-adapter-module-shape.md) | **어댑터 모듈이 기종을 알아도 되는 유일한 자리** — ADR 31이 어댑터의 주인은 정했으나 둘 곳을 정하지 않았고, 그 공백이 게이트 7번과 부딪쳤다 | → §15.55·§15.56 |
| [34](../../adr/0034-semantic-binding-unowned.md) | **시맨틱 결속은 어댑터가 갖지 않는다** — 실물 둘을 재고 나서야 계약이 층 하나를 주인 없이 전제하고 있다는 것이 보였다 | → §15.59·§15.60 |
| [35](../../adr/0035-site-names-live-in-the-robot.md) | **사이트 이름은 로봇 안에 산다** — 계약은 사이트의 이름을 나르고 등록은 사이트 작업이며 `registry`는 그 사실을 상태로만 갖는다 | → §15.68 |

기록은 `docs/adr/`에 있고 번호가 이 표의 행 번호다. **이미 내려서 코드에 박힌 것만 쓴다** — 3a·3b가 만들 것(11~21)은 그때 쓴다. 결정하지 않은 것을 미리 적어 두면 그것이 결정처럼 보인다.

## 15. 알려진 한계

1. **버전 규칙은 벤더가 지킬 때만 작동한다.** 선언을 그대로 두고 거동만 바꾸면 게이트 6번 diff에 아무것도 나오지 않는다. `Pick`이 "잡았다"에서 "잡고 들어올렸다"로 바뀌는 경우다. 적합성 역검증에서만 드러나며, 그때까지의 방어는 "프로파일을 벤더 문서에서 파생시키고 괴리가 발견되면 고친다" 하나다.
2. **프로파일의 표현력에 한계가 있다.** 가반하중과 도달 범위는 담기지만 형상별 파지 가능성은 담기지 않는다. 어디까지를 선언으로 두고 어디부터를 시도해봐야 아는 것으로 남길지가 이 트랙의 진짜 설계 문제다.
3. **감사 로그가 부인방지 근거가 아니다.** 행위자 신원을 요청 헤더에서 그대로 받으므로 위조 가능하다. 조사 단서로만 쓴다.
4. **`contract_revision`의 다이제스트 부분은 경보 전용이다.** semver로 major 불일치는 차단하지만, 같은 semver 안에서 실제로 호환되지 않는 변경이 있었다면 사후 조사에만 쓰인다.
5. **런타임 갱신에 폴링 지연이 있다.** 기본 5초만큼 반영이 늦는다. push로 줄일 수 있으나 `registry → mimic` 간선이 생겨 §3.2가 깨지므로 지연을 받아들였다.
6. **게이트 7번이 문자열 검사다.** 기종 식별자를 계산해 만들면 우회된다. 우회를 막는 것이 아니라 실수를 막는 장치다.
7. **`mimic`이 계약을 정의해버릴 위험이 있다 — 그리고 그것이 관측되었다.**

    원래 문장은 *"실물이 없는 동안 계약이 에뮬레이터에 맞춰 굳을 수 있다"* 였다. 2026-09-08 Unitree G1 어댑터를 설계하면서 **이미 굳었다는 증거가 나왔다.**

    벤더의 `sport` 서비스(`LOCO_SERVICE_NAME`, API 7001~7111)와 계약의 스킬 셋을 대조하니 셋 중 하나도 맞지 않았다 — `navigate_to`는 이름→자세 해석을 요구하는데 SDK가 주는 것은 `SetVelocity(vx, vy, omega, duration)`이고, `pick_place`·`inspect`는 대상 시맨틱을 요구하는데 그 자리가 없다. **첫 실물이 계약 어휘를 하나도 만족하지 않는다.** 짐작이 아니라 `AllModelsTest`가 붙들고 있다 — `unitree-g1`의 면제를 지우면 스위트가 빨개진다(아래 58번).

    계약이 실물 쪽으로 한 칸 갔다(`move_relative@1`). 그것으로 갭이 닫힌 것은 아니다 — **나머지 셋은 그대로 실물과 먼 채로 있고**, 그것을 좁히는 것은 두 번째 기종의 어댑터다.

    방어책이었던 *"프로파일을 벤더 문서에서 파생시키는 규율"* 은 습관이라 지켜지는지 확인할 방법이 없었다. `profile/provenance/`가 그것을 검사로 바꿨다(아래 57번). 남는 것은 여전히 적합성 역검증이며 열려 있다.
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
22. **`contract_digest`가 `buf` 모듈 다이제스트가 아니라 디스크립터 셋의 SHA-256이다.** `buf`는 Docker 래퍼이고 게이트는 CI가 `buf build`를 먼저 돌리는 순서에 기대고 있는데, 런타임 헤더까지 거기 매달면 `mimic`이 Docker 없이 기동하지 못한다. `includeSourceInfo = false`라 주석만 고친 커밋에서는 변하지 않는다(실측). **역은 성립하지 않는다** — `includeImports = true`가 `descriptor.proto`를 끌고 오므로 protobuf나 플러그인 버전을 올리면 계약이 그대로여도 다이제스트가 바뀐다. **그리고 그 순서 의존이 로컬에서 물었다 (2026-09-09).** 디스크립터가 **둘**이다 — `contracts` 의 Gradle 이 `contract_digest` 용으로 만드는 `picasso.desc` 와, 게이트 시험이 읽는 `contracts/build/descriptor.binpb`. 뒤의 것은 `tools/buf build` 를 **손으로** 돌려야 갱신되고 Gradle 은 그 존재만 확인한다(`gate/build.gradle.kts`). `skill_catalog.proto` 에 옵션을 더하고 게이트 시험을 돌리니 빨개지지 않고 **옛 판정**이 나왔다 — 낡은 디스크립터는 낡은 코드와 사이좋게 초록이다. 재생성은 여전히 빌드 배선 밖에 있다.
23. **`MAJOR_MISMATCH`가 minor 부족까지 덮는다.** §12.2의 13번이 협상 거절을 다섯으로 못박았고 버전 불만족을 뜻하는 코드가 그것뿐이다. `detail`이 무엇이 부족한지 말하지만 코드 이름은 실제보다 좁다.
24. **`GetCapabilitiesResponse`에 `Rejection` 자리가 없다.** 그래서 그 RPC만 신원 불일치가 응답 `oneof`가 아니라 gRPC 상태로 나간다 — "요청을 해석하지 못했으면 gRPC 상태, 해석했는데 거절하면 `oneof`"라는 규칙의 유일한 예외다.
25. **`update_index`가 0부터 시작해 헤더에서 미설정과 구별되지 않는다.** `capability_epoch`는 1에서 시작해 피했지만 `task.proto`가 `update_index`를 0부터라고 못박았다. `schema_id`가 방향을 말하므로 실질 문제는 없다. **`sequence`도 같다**(§4.8이 0부터를 못박았다).
26. **일시정지 중에도 진행률이 흐른다.** `Progress`가 경과만 세므로 길게 멈췄다 재개하면 진행률이 튄다. §4.4는 `(task_id, revision, attempt)` 구간 안의 단조 비감소만 요구하므로 계약 위반은 아니다.
27. **`profile/profiles/`의 모든 기종이 공통 요구 집합을 만족해야 한다.** 완료 기준 11을 공허하지 않게 하려고 하네스가 그 디렉터리를 훑기 때문이다(A-1의 일반화). 제약은 다섯이며 `AllModelsTest`의 KDoc이 정본이다.
28. **검사 7이 능력 기반 분기를 못 본다.** `capability.skillsList.size > 2` 같은 분기는 기종 이름을 한 글자도 안 쓴다. 문자열 검사의 한계이며(§11.2가 의도라고 밝혔다) `harness`의 요청 바이트 동일성 시험이 그 자리를 메우지만 그것은 게이트가 아니다. 반대로 **탐색어가 약하면 거짓 실패**가 나므로 `profile/profiles/`의 좌표만 쓰고 8자 미만이거나 구분 기호가 없으면 실패시킨다.
29. **검사 8이 `git diff`의 상태 문자에 기댄다.** `--diff-filter=A --no-renames`로 뽑지 않으면 `git mv`가 "소스 변경 0으로 새 기종"으로 읽힌다. 그리고 `CHANGED_FILES`가 `--require`에 없으면 diff 생성이 깨져도 SKIP으로 넘어가 종료 코드가 0이다 — PR 빌드에서만 요구하므로 push 빌드에서는 여전히 건너뛴다.
30. **MQTT 발행이 실제 브로커로 검증된다(닫힘). 기본 스위트는 여전히 in-process다.** `MqttPublisher`(Paho v5)가 발행자 추상을 채우고, `mimic --broker <url>` 이 그것을 실제로 문다 — 기체마다 하나씩 접속해 Last Will을 그 기체의 `connection` 토픽으로 건다(§4.7). `MqttBrokerTest`가 컨테이너 브로커(Mosquitto)로 네 가지를 다 본다:

    - `retain` — 발행 뒤에 붙은 구독자가 받는가. 반대쪽(retain이 아니면 못 받는다)도 함께.
    - Last Will이 `CONNECTION_BROKEN`이라는 것 — 강제 절단에서 나오고 정상 종료에서는 안 나온다.
    - 토픽 문자열의 브로커 수준 합법성 — `Topics`가 막는 집합과 브로커가 거부하는 집합이 같은지.
    - **CLI가 브로커를 주면 발행이 실제로 나가는가** — `MqttPublisher`가 브로커와 말한다는 것과 `mimic`이 그것을 쓴다는 것은 다른 얘기다.

    **기본 스위트는 안 바뀐다.** §12.1의 결정성 — "시드 + 가상 시계 고정 = 동일 이벤트 시퀀스" — 이 브로커를 붙이면 깨지므로 `--broker` 없이는 아무 데도 안 나가고, `mimic`의 시험은 하나도 컨테이너를 안 쓴다. 브로커 시험을 `harness`에 둔 것도 그래서다(아래 39번).
31. **이벤트를 접는 소비자가 잠시 §4.5의 표에 없는 조합을 볼 수 있다.** 원인을 결과보다 먼저 내보내지만(스킬 `HALTED` → 태스크 `FAILED`), 두 사실을 원자적으로 발행할 수단이 없으므로 그 사이의 접힌 상태는 표 밖이다. 정착한 상태만이 §4.5의 대상이다.
32. **제어 채널의 proto가 `buf lint`·`buf breaking` 밖이다.** `contracts/buf.yaml`의 모듈이 `proto/` 하나이고, 제어 채널은 §10.5에 따라 `mimic/` 안에 있다. 필드 번호를 재사용해도 게이트 1·2번이 잡지 않으므로 **RPC 이름과 번호를 지금 다 예약해 둔다.** 두 번째 buf 모듈을 더하면 닫히지만 Docker 호출이 하나 는다.
33. **제어 채널이 인증하지 않는다.** 루프백 바인딩이 유일한 방어이며(§6.3), 같은 호스트의 다른 프로세스는 막지 못한다. `mimic`은 시험 도구이므로 감수한다.
34. **관측 적재에 구독기가 없다.** 3a-3은 `ObservationService`(헤더 하나를 세대 이력에, 거절 하나를 `handshake_rejection`에)까지 만들고 **그것을 부르는 MQTT 구독기는 만들지 않았다.** 이유는 위 30번과 같다 — 브로커를 붙이면 §12.1의 결정성이 깨진다. 그래서 운영에서 이 표를 채우는 것은 아직 아무도 아니며, 진단 3·4번은 **시험과 `harness`가 넣은 것만 본다.** 붙는 날 구독기가 `recordHeader`/`recordRejection`을 부르면 되고, 입력이 `MessageHeader`인 것이 그것을 가능하게 하는 경계다(`registry`가 `mimic`을 몰라도 된다).
35. **진단 2번이 선택 필드 차이를 본다(닫힘).** `profile_optional_field`가 없어 `optionalFields`가 `"not_tracked"`로 나갔다. §9.6의 상위 표면이 필수 선택 필드를 요구해 V7로 표를 만들었고, 진단 2번도 같은 표를 읽는다. 빈 배열로 내지 않았던 것이 옳았다 — 그랬으면 "선택 필드 차이가 없다"는 거짓말 위에서 축소가 승인됐을 것이다.
36. **세대 이력의 접기가 사유를 키에 넣는다.** `(robot_id, epoch, profile_ref, cause)`가 같으면 한 줄로 접히므로, **같은 사유로 두 번 일어난 같은 전이는 한 번으로 보인다.** 되풀이 횟수가 진단에 필요해지면 카운터를 더해야 하며, 지금은 마지막 시각만 남는다.
37. **`capability_epoch_log.robot_id`에는 FK가 있고 `handshake_rejection.robot_id`에는 없다.** 후자는 등록되지 않은 기체에도 거절이 날아올 수 있고 그 사실 자체가 운영자가 알아야 할 것이기 때문이다. 대가는 오타 난 `robot_id`가 조용히 적재된다는 것이다.
38. **HTTP 표면에 인증이 없다 — 있는 것은 역할을 가르는 거친 문 둘이다.** 진단은 read-only지만 바인딩·거절·요구 문자열이 그대로 나가며, 배포에서 망 경계로 막는 것을 전제한다.

    쓰기에는 문이 둘이고 **토큰이 서로 다르다.** 적재(`ingest` 이하·`requirements`)는 [`IngestToken`], 조작(`operations` 이하)은 [`OperatorToken`]이다. **합치면 안 되는 이유가 있다** — 적재 토큰은 어댑터마다 배포되어 현장에 나가 있으므로, 한 토큰이면 **기체 하나가 운영자 조작을 수행할 수 있다.** 그 승격은 코드 어디에도 안 적히므로 안 보이며, `OperationsEndpointTest`의 *"적재 토큰으로는 조작하지 못한다"* 가 그것을 붙든다.

    **그래도 인증은 아니다.** §6.3이 신원과 접근을 범위 밖으로 두었고, 이것은 역할 둘을 가르는 공유 비밀일 뿐이다 — 누가 조작했는지는 여전히 `X-Actor`의 자기 신고이고 위조 가능하다(§15.3). 감사 로그가 부인방지 근거가 아니라 조사 단서인 이유다.

    원래 이 항목은 *"조작 API가 붙는 순간 이것은 한계가 아니라 결함이 된다"* 로 끝났다. 조작 API가 붙었고, 결함이 되지 않게 문을 나눴다.
39. **`:harness:test`가 이제 Docker를 요구한다.** 완료 기준 20의 카나리 시험이 진짜 레지스트리를 쓰기 때문이다(반으로 쪼개면 두 표면을 서로 비교하게 된다). `:registry:test`는 이미 그랬으므로 경계가 새로 생긴 것은 아니지만, **Docker 없는 로컬에서 돌던 모듈 하나가 줄었다.** 음성 하네스(§15.15)처럼 건너뛰기로 만들지 않은 것은 의도다 — 건너뛴 카나리는 초록으로 보이고, 완료 기준 20은 초록이 아니라 관측이어야 한다.
40. **원장의 두 관측선을 `mimic` 프로세스가 든다(닫힘). 태스크 쪽은 브로커가 아니다.** `mimic --registry <url> --ingest-token <t> [--site] [--fallback-dir]` 가 넷을 한 번에 조립한다 — 핸드셰이크 보고(§5.4), 태스크 적재, 각각의 파일 폴백, 기동 시 재적재. **토큰 없이 `--registry`만 주면 기동을 거부한다**: 적재가 전부 401을 낼 텐데 그러면 관측이 통째로 폴백으로 가면서 "붙었다"고 보고된다.

    태스크 전이는 §3.2가 규정한 `registry ⇠ 브로커` 구독이 **아니라** `mimic ⇢ registry` HTTP다(위 30번의 이유로 브로커를 안 붙였다). 브로커가 붙는 날 사라지는 것은 `HttpTaskObservations` 하나이며, 레지스트리의 적재 표면과 계약 타입 입력은 그대로 남아 구독기가 같은 서비스를 부른다. 남는 것: **레지스트리가 돌아온 것을 감지하는 장치는 없다** — 재적재의 계기는 프로세스 재기동뿐이다.
41. **관측선 신선도 창이 24시간 상수이고 판정 범위가 기체 단위다.** 사이트별·스트림별 설정이 없고 코드에서만 주입된다. 짧아서 생기는 오답은 "모른다"라 축소를 **막고**, 길어서 생기는 오답은 죽은 구독을 살아 있다고 보아 축소를 **연다** — 그래서 애매하면 짧은 쪽으로 잡았다. 야간 정지가 24시간을 넘는 라인에서는 매번 막히며, 그때 필요한 것은 창을 늘리는 것이 아니라 **기체가 `HIBERNATING`을 정직하게 보고하는 것**이다 — 그러면 창의 배수까지 면제된다. 판정은 전역 워터마크가 아니라 **그 능력을 제공하는 바인딩된 기체 각각**을 보며(V8의 `robot_liveness`), 하나라도 조용하면 사유에 그 `robot_id`를 담아 막는다.
42. **사이트 카탈로그가 HTTP로 선다(닫힘). retain 스트림은 아직 없다.** §9.6의 `GET /catalog?site=`가 능력 단위로 답하며 다섯을 다 싣는다 — 스킬 타입, 최소·최대 minor, 가용 기체 수, `deprecated_after`(두 축 중 이른 쪽), 필수 선택 필드(합집합). `picasso/{major}/{site}/site/catalog` retain 스트림은 브로커가 없어(위 30번) 만들지 않았고, §9.6이 적어 둔 대로 **상위가 폴링으로 대체한다.** 폴링은 능력 변경을 즉시 알리지 못하므로 상위의 반응이 폴링 주기만큼 늦는다.
43. **`skill_type_deprecation`에 쓰기 경로가 생겼다(닫힘).** `DeprecationService.announce`·`withdraw`가 그 자리이며 §8.5의 조작 열넷 중 *"계약 축 폐기 예고 설정"*이 이제 실제로 있다.

    **동기화 잡이 아니라 운영자 조작으로 만들었다.** 처음에는 *"`skill_type`이 읽기 전용인 것과 같은 이유로 계약 소유자가 채우는 것이 옳다"*고 적었는데, 그 유추가 틀렸다. §8.1이 `skill_type`을 읽기 전용으로 둔 이유는 **계약이 그 값을 소유**하기 때문이고 — 이름·major·`introduced_in_semver`는 전부 proto에서 나온다 — **`deprecated_after`는 proto에서 나오지 않는다.** 계약에 그것을 적을 자리가 없고(`SkillDeclaration.deprecated_after`는 프로파일 축이다), 있다 해도 "언제까지 쓸 수 있는가"는 릴리스 사실이 아니라 운영 판단이다. 표가 `announced_by`와 `note`를 든 것이 그 판단의 흔적이며, 동기화 잡은 그 둘에 채울 값이 없다.

    **예고는 여전히 게이트가 아니다**(§9.3). 이 조작이 여는 것은 `DEPRECATION_PUBLISHED` 하나뿐이고 축소를 여는 것은 두 조회다. `withdraw`가 있는 것은 §9.5가 `ANNOUNCE`의 가역성을 *"예고를 지우면 된다"*로 규정했기 때문이다 — 지울 방법이 없으면 그 표의 "가역"이 거짓말이 된다.
44. **드레인 관측이 전이 이벤트를 함께 받는다(닫힘).** 처음에는 `state` 스트림의 주기 발행(§7.2의 `publish_interval`)만 적재했고, 그래서 **주기보다 짧은 태스크는 비종착으로 한 번도 안 실렸다** — 픽스처의 `navigate_to`가 20초이고 발행 상한이 30초라 실제로 그랬다. 틀리는 방향이 축소를 **여는** 쪽이라 위 41번의 보수성과 반대였다. `event` 스트림의 `TaskTransition`을 함께 적재해 닫았고, **계약이 그 경로를 예정해 두었다** — `event.proto`가 `skill_type`을 싣는 이유를 *"registry가 이 이벤트를 구독해 task 테이블을 적재하며 드레인 판정이 스킬 단위로 서려면"*이라 적어 두었다. 스냅샷 경로도 남긴다: 이벤트가 유실돼도 다음 주기에 상태가 맞춰지므로 둘은 서로의 반대 실패를 메운다(이벤트 유실은 축소를 막고, 주기 누락은 축소를 연다).
45. **적재 실패가 폴백 파일에 남는다(닫힘).** §5.4가 *"보고 실패는 핸드셰이크 결과에 영향을 주지 않는다"*고 정했으므로 삼키는데, **삼킨 것을 버리면 그 사이의 전이가 통째로 사라진다** — `task` 표는 그 태스크를 못 본 채로 남고, 뒤늦게 스냅샷이 와도 이미 종착한 태스크는 다시 안 실려 드레인이 조용히 틀린다. `FallbackTaskObservations`가 실패한 관측을 JSONL로 남기고 **사유를 함께 적는다** — 적재가 멈추면 워터마크가 늙어 축소가 막히는데(§15.41의 안전한 실패) 막힌 이유가 어디에도 없으면 운영자는 원장이 아니라 엉뚱한 것을 뒤진다. 파일이 존재한다는 사실 자체가 신호이며, 적재가 잘 되면 안 생긴다.
46. **폴백 파일을 다시 밀어 넣는다(닫힘).** `FallbackReplay`가 두 형식(`FileHandshakeReporter`·`FileTaskObservations`)을 읽어 적재 표면으로 되민다. **커서를 안 둔다** — 어디까지 밀었는지를 따로 적으면 그것이 세 번째 진실이 되고, 어긋난 날 밀지 않은 구간이 조용히 생긴다. 대신 적재가 멱등이라는 성질(요구는 upsert, 태스크는 upsert + 종착 래치)에 기대며, **그 성질이 이 도구의 전제이므로 시험이 그것을 붙든다.** 파일은 안 지운다 — 데이터를 지우는 판단을 도구가 대신하면 적재가 실은 실패했는데 파일이 사라진 날 되돌릴 것이 없다.

    **부르는 쪽은 CLI가 기동할 때다**(위 40번). 프로세스가 다시 뜨는 것이 가장 흔한 복구 계기이며, 그보다 이른 계기 — 레지스트리가 살아난 순간을 감지하는 것 — 는 없다. 그래서 레지스트리가 돌아와도 `mimic`을 재기동하기 전까지 그 구간은 안 밀린다.
47. **관측선을 붙이기 전에는 축소가 열리지 않는다(보이게 만듦).** V8의 `robot_liveness`가 판정 앞에 서므로, 바인딩만 되고 한 번도 보고한 적 없는 기체가 하나라도 있으면 §9.3의 두 조회가 `NOT_OBSERVABLE`이다. **의도한 방향이다**(§15.41의 보수성) — 하지만 대가가 있다: 하트비트를 보내지 않는 어댑터를 붙이면 그 사이트의 축소 기능이 통째로 멈춘다.

    **막는 것은 그대로 두고 보이게 했다.** 진단 1번(`GET /diag/bindings`)이 기체마다 `liveness`를 함께 낸다 — `REPORTING` / `SILENT` / `HIBERNATING` / `DISCONNECTED` / `NEVER`. `NEVER`가 그 상태이며, 붙이는 순간 화면에 나오므로 축소를 시도할 때에야 아는 일이 없다. §9.7 ④의 `UNTESTED`와 같은 판단이다 — *"우리는 실물 검증을 아직 안 했다가 화면에 보여야 정직하다."* 신선도 창은 `RobotObservability.DEFAULT_FRESHNESS`를 **그대로 쓴다**: 두 곳이 갈라지면 화면이 `REPORTING`이라 적은 기체 때문에 축소가 막히고, 운영자는 원장이 아니라 엉뚱한 것을 뒤진다.
48. **`HIBERNATING`으로 들어간 뒤 죽은 어댑터가 창의 배수만큼 면제된다.** §4.7이 정의한 정상 침묵을 차단자로 만들지 않으려는 것인데, 그 구간의 오답은 축소를 **여는** 쪽이다. 그래서 배수를 3으로 잡았고 크게 늘리지 않는다.
49. **생존 관측이 폴백에 안 남는다.** 태스크 관측(§15.45)과 달리 되밀 수 없다 — 시각의 주인이 registry이므로 나중에 밀면 **죽은 기체를 살아 있다고 적는다.** 실패하면 버리고 다음 발행에서 다시 온다. 그동안 워터마크가 늙어 축소가 막히며, 그것이 안전한 실패다.
50. **두 경로의 major 판정 범위가 달랐다(닫힘).** `RegistryLedgerQuery`(CI)는 `skill_type@major`를 짚는데 `Preconditions`(운영)는 이름만 봤다. 그래서 `@2`로 옮기라고 예고해 놓고 `@1`의 제거가 **옮긴 쪽 때문에** 막혔다. 개수 세는 것을 `LedgerService.activeConsumerCount(skill, major)`·`inflightTaskCount(skill, major)`로 내려 **두 경로가 공유한다** — 관측선(`RobotObservability`)에 이어 두 번째다. `major`가 없으면 조용히 "안 가림"으로 접지 않고 **던진다**: 접으면 이 한계가 다시 열리고 열린 것이 안 보인다. `DEPRECATION_PUBLISHED`도 함께 닫았다 — `skill_type_deprecation`의 PK가 `skill_type_id`이고 그것이 `(name, major)` 단위이므로 표는 이미 그 해상도를 갖고 있었다. 이름만 보면 `@2`에 낸 예고가 `@1`의 제거를 열어 준다: **아직 아무도 옮기라고 듣지 못한 판을 지우는 것이다.**
51. **펌웨어 대조가 문자열 동일성이다.** 벤더마다 표기가 달라 `4.1`과 `4.1.0`을 다르게 본다. 의미 비교는 벤더별 파서를 요구하며 그것은 어댑터 안의 일이다. 다르면 띄우고 판단은 사람이 한다. **막지도 않는다** — 어느 정도 차이까지 허용하는가는 정책이고 정책 저장소가 비목표다(§1.3).
52. **실물 기종의 능력 프로파일은 아직 만들 수 없다.** 처음에는 이 항목을 *"`derived_from`이 참조 프로파일의 합성 값이라 진단 7번이 아직 아무것도 안 잡는다"*고 적었는데, 실제로 만들어 보니 이유가 그보다 앞에 있었다.

    **§2.3 수준의 조사로는 프로파일을 채울 수 없다.** 스키마가 `skills`를 `minItems: 1`로, `protocol_limits`와 `publish_interval`을 필수로 요구하는데 §2.3의 대조표는 프로토콜 한계를 세 기종 모두 ❌로 적었고 스킬 어휘는 아예 조사하지 않았다. 그리고 "이 로봇이 우리 계약의 `pick_place`를 지원하는가"는 벤더 문서에서 직접 나오는 값이 아니라 **어댑터 설계 판단**이다. 억지로 채우면 §15.7이 방어책으로 든 *"벤더 문서에서 파생시키는 규율"*이 거짓말이 된다.

    그래서 **조사를 데이터로만 옮겼다** — `profile/vendors/`(스키마는 `vendor-survey.schema.json`)에 셋을 두고 `VendorSurveyTest`가 읽는다. 능력 프로파일이 아니므로 하네스가 `profile/profiles/`에 거는 제약(§15.27)을 안 받고, 그래서 못 채우는 항목이 `coverage.*.level = NONE`으로 **정직하게 남는다.** 이 문서들이 붙드는 것은 §4의 결정들이 선 근거다 — `Support` 3값(Spot `UNKNOWN` vs Digit `NO`), 래치 불변식(Digit만 `terminal_latches: NO`), §4.9의 배타 제어(셋 다 `YES`), 그리고 **프로토콜 한계를 주는 실물이 하나도 없다는 것.**

    **2026-09-08에 첫 실물 프로파일이 섰다** — `profile/profiles/unitree-g1.json`. 어댑터 설계가 먼저라던 것이 맞았고, 설계하면서 스킬 어휘(`move_relative`)와 프로토콜 한계와 발행 주기가 정해졌다.

    **`derived_from.software_version`은 채워졌으나 펌웨어가 아니다.** `unitree_sdk2_python master (2026-09-08)` 이며, 우리가 본 것이 SDK 소스이지 어느 기체의 펌웨어가 아니기 때문이다. 그래서 진단 7번의 대조는 어떤 실물 G1과도 불일치를 낼 것이고 **그것이 옳은 거동이다**(§15.51은 띄우기만 하고 막지 않는다). 실물을 한 번도 안 봤다는 사실이 화면에 남는다.

    못 채운 것이 남는다. `min_value`·`max_value`가 비어 있다 — URDF가 주는 것은 조인트 한계인데 `move_relative`의 파라미터는 기저 속도이고 그 상한은 벤더가 어디에도 선언하지 않는다. `failure_modes`도 비어 있다 — 스키마가 `rate`를 필수로 요구하는데 **우리는 어떤 비율도 모르고**, 지어낸 비율은 `mimic`의 추첨을 지나 시험 결과에 직접 들어간다. 비어 있는 것이 기록이며 그 사유가 출처 문서에 있다.

    조사 문서도 이 과정에서 하나 고쳐졌다. `parameter_ranges`를 `FULL`이라 적었던 것이 `PARTIAL`이 됐다 — **있는 근거와 필요한 근거가 달랐다.**
53. **생존 보고가 브로커 구독이 아니다.** §4.7의 `connection` 스트림이 같은 사실을 나르지만 브로커를 안 붙였으므로(위 30번) `mimic ⇢ registry` HTTP로 민다. 브로커가 붙는 날 사라지는 것은 `HttpLiveness` 하나이며, 그때 구독기는 `ConnectionMessage`만 갖고 `robot_software`는 비운 채 부른다 — `COALESCE`가 이미 읽은 값을 지키므로 원장이 퇴행하지는 않지만, **펌웨어를 어느 경로로 받을지는 그때 다시 정해야 한다.**
54. **`profile/vendors/`는 게이트 검사가 아니라 시험이 붙든다.** `VendorSurveyTest`가 스키마 검증과 §2.3의 사실 여섯을 단언하며 CI가 그것을 돌린다.

    **그 시험이 태스크 입력 없이 먼저 들어왔다(`431649f`).** `gate/build.gradle.kts`가 `profile/vendors`를 선언하지 않아 조사 문서를 고쳐도 `:gate:test`가 UP-TO-DATE로 넘어가 초록이 났다 — 그 파일의 주석이 *"이 저장소가 같은 방식으로 두 번 물렸다"* 고 적어 둔 바로 그것이며 세 번째다. 2026-09-08에 `profile/vendors`와 `profile/provenance`를 함께 배선했다. 게이트 검사를 열로 늘리지 않은 것은 §11.2의 "검사 아홉"과 그 음성 케이스 표가 함께 커지기 때문이고, 이 문서들은 배포물이 아니라 **설계 근거의 기록**이라 파괴 검사의 대상이 아니다.

55. **어댑터를 시뮬레이터에 붙여 돌리지 못한다.** 조사 문서가 처음에 *"이 기종만 공식 시뮬레이터가 SDK2 인터페이스를 그대로 노출한다"* 고 적었고 그것이 이 기종을 고른 이유였는데, **절반만 참이었다.**

    | 사실 | 출처 |
    |---|---|
    | `unitree_mujoco`는 **저수준만** 흉내낸다 — `LowCmd_`/`LowState_` | README: *"Current version only supports low-level development"* |
    | 스킬이 올라탈 `sport` 서비스는 `ai_sport`가 도는 실물에서만 답한다 | 같은 문서 |
    | CycloneDDS에 JVM 바인딩이 없다(C·C++·Python뿐) | CycloneDDS 문서 |
    | 시뮬레이터가 Ubuntu 전용이다 | README 설치 절차 |

    그래서 `G1Link.sport`가 **널일 수 있는 타입**이고, 널이면 어댑터가 태스크를 거절한다. 받아 놓고 아무것도 안 하는 것이 가장 나쁜 결과이며 그 상태는 초록으로 보인다.

    이것을 닫으려면 실물이거나, JVM이 그 wire에 서는 길(OpenDDS-Java RTPS 상호운용)을 뚫어야 한다. 후자는 그 자체가 미검증이고 RTPS 판이 다르다(OpenDDS 2.4 · CycloneDDS 2.1).

56. **어댑터의 남쪽 포트가 실물과 갈릴 수 있다.** `G1Link`는 SDK 소스를 읽고 만든 것이고 실제 wire와 같은지는 붙여 봐야 안다. **메서드 이름을 SDK 그대로 둔 것**이 그 대조를 싸게 하려는 것이다 — 우리 어휘로 옮기면 다음 조사에서 한 줄씩 맞대 보지 못한다.

    `FsmProfile.start`에 기본값을 두지 않은 것도 같은 자리다. 같은 뜻의 상태가 기체마다 다른 번호이며(500 · 501 · 801) 어느 것이 맞는지는 우리가 아는 것이 아니다. 기본값을 두면 잘못된 기체에서 조용히 다른 상태로 들어간다.

57. **출처 문서는 선언이 어디서 왔는지만 본다.** `profile/provenance/`와 `ProfileProvenanceTest`가 §15.7의 규율 — *"벤더 문서에서 파생시킨다"* — 을 약속에서 검사로 바꿨다. 조사가 `level: NONE`이라 적은 항목을 프로파일이 `ROBOT`·`VENDOR_DOC` 출처라 주장하면 실패한다.

    **값을 채우는 것은 막지 않는다.** 벤더가 안 주는 값을 어댑터가 정하는 것은 정당하고 불가피하다(§9.7). 막는 것은 어댑터가 정한 값이 로봇의 선언인 척하는 것이다.

    **선언대로 로봇이 행동하는지는 안 본다.** 그것이 §9.7 ④이고 C-3이며 열려 있다. 그리고 위 54번과 같은 이유로 게이트 검사가 아니라 시험이 붙든다.

58. **실물 기종이 공통 요구 집합을 못 만족한다.** §15.27이 `profile/profiles/`의 여섯 제약을 적으며 *"이 우리가 좁아 보이면 그것이 맞다"* 고 했는데, 첫 실물이 1번부터 못 넘었다 — `navigate_to@^1.0`이 없다.

    **면제를 `profile/common-set-exemptions.json`에 열거한다.** 조건으로 유도하면(예: *"`navigate_to`를 선언한 기종만 본다"*) `humanoid-a`에서 그 스킬을 지워도 스위트가 초록으로 남고 완료 기준 11이 조용히 공허해진다. 반대 방향은 `AllModelsTest`가 막는다 — **면제된 기종이 정말로 못 만족하는지까지 확인하므로** 통과할 수 있는 기종을 목록에 넣어 숨길 수 없다.

    대가는 `profile/profiles/`가 이제 두 가지를 담는다는 것이다 — 스위트가 도는 기종과 안 도는 기종. 면제 파일이 그 구분의 정본이며, 그것을 안 읽으면 디렉터리만 보고 잘못 짐작하게 된다.

59. **계약 카탈로그에 아무 실물도 선언하지 않는 스킬이 하나 있다.** `inspect`다. (원래 *"둘 있다 — `pick_place`와 `inspect`"* 였고, 2026-09-08에 Digit이 `pick_place`를 선언하면서 하나로 줄었다. 아래 65번의 정정에서 나왔다.) 게이트 4번은 **선언된** 스킬만 양방향으로 대조하므로 쓰이지 않는 카탈로그 항목을 잡지 않고, 잡을 이유도 없다 — 어휘가 실물보다 앞서는 것 자체는 정상이다.

    조용해지지 않게 하는 것이 `profile/distance/`다. 실물마다 **모든** 카탈로그 스킬에 판정과 근거를 요구하므로, 안 쓰이는 스킬이 *"아무도 안 쓴다"* 가 아니라 **"누구에게 무엇이 없어서 못 쓴다"** 로 남는다. `VocabularyDistanceTest`가 `ContractIndex`로 카탈로그를 훑어 빠진 측정을 잡는다.

60. **시맨틱 결속 층에 주인이 없다**([ADR 34](../../adr/0034-semantic-binding-unowned.md)). 계약의 파라미터 넷이 사이트 수준의 이름인데(`location`·`object_id`·`destination`·`target`) 벤더는 녹화된 웨이포인트 id·픽셀·3D 점·카메라 이름으로 말한다. `navigate_to`만 맞는 것은 GraphNav이 그 결속을 이미 갖고 있어서다.

    ADR 34가 후보 셋 중 어댑터를 뺐고, [ADR 35](../../adr/0035-site-names-live-in-the-robot.md)가 나머지를 정했다 — **계약은 사이트의 이름을 나르고 사이트가 그것을 로봇 안에 등록한다.**

    *"그래서 `pick_place`는 지금 어느 경로로도 서지 않는다"* 고 적었던 것은 **틀렸다.** Digit이 `action-pick{object: ObjectSelector}`·`action-place{reference_frame: ObjectSelector}`로 그것을 팔며 셋 중 그 기종에서 선다. 남는 것은 Spot·G1이고 이유는 그대로다 — 픽셀·3D 점, 또는 아무것도 없음.

61. **거리 측정은 관측이 아니라 판단이다.** `profile/vendors/`는 벤더 문서의 전사이고 `profile/provenance/`는 프로파일 한 장의 계보인데, `profile/distance/`는 **계약 쪽에서 내리는 판정**이다 — *"이 벤더 원시로 우리 스킬을 정직하게 들 수 있는가."* 다른 사람이 다르게 잴 수 있고, 그래서 `evidence`가 필수다.

    `reachable: YES`가 프로파일 선언과 양방향으로 묶여 있는 것이 이 판단의 유일한 기계적 제동이다. 판단이 느슨해지면 선언이 함께 틀리고, 그때는 하네스가 잡는다.

62. **Spot 어댑터도 붙여 보지 못했다.** 남쪽 포트가 미검증인 것은 G1과 같으나(§15.56) **이유가 다르다.** G1은 JVM이 그 wire에 설 길 자체가 없었다(CycloneDDS에 Java 바인딩 없음). Spot은 gRPC라 기술적 장벽이 없고 — 막는 것은 **실물과 라이선스**다. `20191101-BDSDK-SL` §2(c)가 BD 하드웨어 전용이므로 시뮬레이터를 만들어 붙이는 길도 벤더가 안 판다.

    그래서 §15.55의 *"시뮬레이터로 적합성을 못 본다"* 가 두 기종에서 서로 다른 이유로 참이며, **조사한 셋 어디에도 우리에게 필요한 API 시뮬레이터가 없다**는 것이 지금까지의 결론이다.

65. **Digit 측정을 한 번 통째로 다시 했다 — 근거 등급을 적어 두고도 그 위에 판정을 얹었다.**

    **아래는 그 실수의 기록이며, 이어지는 문단이 원래 적었던 것이다.** 2026-09-08 같은 날 벤더 SDK 원문(`agility/messages/json.py`, 릴리스 `2021.06.01`)을 찾아 전수 대조했고 **판정 셋이 뒤집혔다** — `navigate_to` PARTIAL→YES, `pick_place` NO→YES, 계층 COMMAND→MISSION. `cancel_support`도 NO→YES였다.

    원인이 하나다. 제3자 래퍼가 벤더 API의 **부분집합**이라 `action-duration`·`action-pick`·`action-place`·`remove-action`·`ObjectSelector.name`을 안 썼고, 안 쓰인 것을 **없는 것으로 읽었다.** 근거가 약하면 `NO`가 아니라 `UNKNOWN`이어야 했다 — §7.2의 `Support` 3값이 말하는 바로 그것이고, **조사 문서에서는 지키던 규율을 거리 문서에서 안 지켰다.**

    `VendorSurveyTest`의 시험 하나가 이 교훈을 든다(*"1차 근거를 읽자 취소 판정이 둘이나 뒤집혔다"*). Spot은 `UNKNOWN`에서, Digit은 `NO`에서 각각 `YES`로 갔다 — **접는 방향이 달랐을 뿐 둘 다 1차 근거를 안 읽어 생긴 것이다.**

    남는 한계는 릴리스가 2021년판이라는 것이다. **"있다"는 확실하고 "없다"는 그 시점 기준이며**, `inspect`의 `NO`가 그 영향을 가장 크게 받는다.

    ---

    *(아래는 정정 전 원문이다.)* **Digit 측정의 근거 등급이 다른 둘보다 낮다.** Spot은 벤더의 공개 proto 원문을 읽었고 G1은 공개 SDK 소스를 읽었는데, Digit은 그러지 못했다 — `docs.agilityrobotics.com`이 닿지 않고 공식 SDK가 공개 저장소에 없다. 대신 **원시 JSON을 그대로 쓰는 제3자 코드**(서브프로토콜 `json-v1-agility`)와 2026-09-05 조사(매뉴얼 직접 인용 포함)를 썼다.

    그래서 이 기종에 대해 *"없다"* 고 적은 것 중 일부는 ***"공개된 것에서 못 찾았다"***이다. 둘을 접으면 안 되는 것이 §7.2의 `Support` 3값과 같은 이유이며, 거리 문서의 `evidence`가 그 구분을 진다. 특히 `navigate_to`의 `PARTIAL`은 확인만 되면 `YES`로 갈 수 있는 자리다.

66. **철회 — `move_relative`의 벤더 중립 근거는 틀리지 않았다.** 한나절 동안 이 항목은 *"근거의 절반이 틀렸다: Digit의 `action-move`는 지속시간을 안 받는다"* 였고, 그에 맞춰 어댑터에 **정지 워치독**까지 만들었다. 둘 다 잘못이었다.

    `ActionDuration{action, duration, after_success}`가 아무 액션이나 감싸며 **로봇이 집행한다.** 지속시간이 필드가 아니라 **합성**일 뿐이고, 셋 다 그 값을 받는다. 어댑터가 시계를 들 이유가 없고 실패 방향도 다른 둘과 같다 — 워치독은 코드와 시험에서 걷어냈다.

    **남는 것은 회귀 방어 하나다.** `어댑터가 시계를 들지 않는다` 시험이 그 자리이며, 지속시간이 지나도 상태 스트림이 `success`를 주기 전에는 성공으로 안 적는 것을 붙든다. 없던 위험을 만들어 낸 코드가 다시 기어들어오면 거기서 빨개진다.

    `skill_catalog.proto`의 주석도 원래대로 되돌렸다 — 다만 *"셋 다 갖는다"*가 **필드로 셋, 합성으로 하나**라는 것은 적어 두었다.

67. **`vendor_layer`의 가운데 값(`COMMAND`)을 지금 쓰는 기종이 없다.** 한나절 동안 Digit이 그 자리라고 적었는데, 벤더 SDK를 읽으니 `add-sequential-actions`·`replace-action`·`remove-action`·`get-execution-state`와 합성 다섯과 지속 세션이 있었다. **액션이 별도 층으로 서 있고 생명주기 조작이 붙어 있으므로 `MISSION`이다** — 없는 것은 일시정지 하나이며 그것은 층의 부재가 아니라 능력의 부재다.

    그래서 지금 분포는 G1 `NONE` · Digit `MISSION` · Spot `MISSION`이고, **가운데는 비어 있다.** 값을 지우지 않는 이유는 명령 계층에 상태만 얹은 기종이 실재할 수 있어서인데, **아직 못 봤다는 것을 여기 적어 둔다** — 안 적으면 다음 사람이 그 값을 근거 있는 분류로 읽는다.

    이 열거의 쓸모는 기종을 분류하는 것이 아니라 *계약이 어느 층에 있는가*를 재는 것이다. 그 답은 세 기종에서 그대로다 — **계약은 명령 계층이 아니라 그 위에 있다.**

68. **사이트 이름을 로봇에 등록했는지가 이제 보인다(닫힘).**([ADR 35](../../adr/0035-site-names-live-in-the-robot.md)). 그 결정이 §9.7의 여섯 단계 옆에 새 운영 단계를 하나 만든다 — *"사이트 이름을 이 기체에 등록했는가."* 안 하고 바인딩하면 소비자가 `dock-3`을 보냈을 때 **로봇이 모르는 이름으로 실패**하고, 그 실패는 계약 위반처럼 보이지 원인처럼 보이지 않는다.

    **V10이 그 상태를 만들었다.** `robot_binding.site_names_registered_at`·`_by`가 그 자리이며, 진단 1번이 기체마다 `siteNames`(`NOT_REQUIRED`/`UNREGISTERED`/`REGISTERED`)와 `siteNameKeys`를 함께 낸다. **막지 않고 보이게 한다** — §9.7 ④의 `UNTESTED`, §15.47의 `NEVER`와 같은 판단이다.

    **`NOT_REQUIRED`를 따로 둔 것이 요점이다.** 접으면 이름을 쓸 일이 없는 기종(G1처럼 `move_relative`만 드는 경우)이 영원히 *"안 했다"*로 보이고, 그러면 화면이 언제나 빨개서 아무도 안 본다.

    미뤘던 이유는 *"무엇을 등록했다고 적을 것인가"* 였다. 집합이 기종마다 다르므로(Spot은 장소, Digit은 장소와 집을 물체와 놓을 곳, G1은 없음) 불리언 하나로는 *"등록했다"*가 기종마다 다른 것을 뜻하게 된다.

    **답은 유도였다.** 계약이 `is_site_reference`로 어느 파라미터가 사이트 이름인지 말하고(`skill_type_param.site_reference`), 프로파일이 어느 스킬을 드는지 말한다. 둘을 곱하면 그 바인딩이 알아야 하는 이름들이 나오고 **손으로 적는 목록이 없다** — 프로파일이 스킬을 하나 더하면 등록 대상이 저절로 는다.

    **어느 파라미터가 사이트 이름인지는 계약이 소유한다.** `registry`가 그 목록을 두 번째로 적으면 스킬을 더하는 날 갈리며, `TaskStates`를 계약에 둔 것과 같은 이유다. 값 타입으로 유도할 수도 없다 — `STRING`이 전부 사이트 이름은 아니다.

    `skill_type_param`은 V1이 *"요구하는 기준이 생길 때"* 만들기로 미뤄 둔 표였고, 이것이 그 기준이었다. `skill_type`과 같이 읽기 전용이며 `SkillTypeSync`가 채운다(§8.1).

    **HTTP 표면이 붙었다.** `POST /operations/site-names?robot=`가 기록하고 `GET`이 미리 본다. §8.5의 조작이 열다섯이 됐고, **적재와 다른 문·다른 토큰**이다(§15.38). 답이 셋으로 갈린다 — 기록됨(200) / 등록할 것이 없음(409) / 활성 바인딩 없음(404). 불리언 하나로 접으면 운영자가 왜 안 됐는지 모른 채 재시도한다.

    **그 질의가 이제 계약에 있다.** `SkillService.GetKnownSiteNames`가 그것이며, 기체가 아는 이름의 목록과 *"호스팅 못 한다"*를 구별해 답한다. 답은 생존 보고에 요약으로 실려 온다(§15.72).

    그래서 상태가 **다섯**이 됐다 — `NOT_REQUIRED` / `UNREGISTERED` / `CLAIMED`(사람은 했다는데 기체가 아직 답한 적 없다) / `CONFIRMED`(둘 다) / **`CONTRADICTED`(사람은 했다는데 기체는 아는 이름이 없다)**. 마지막이 이 확장의 이유이며, 질의가 생기기 전까지는 **그 상태를 표현할 수단 자체가 없었다.**

    **잡는 것과 못 잡는 것이 갈린다.** 통째로 안 했거나 엉뚱한 기체에 했으면 개수가 0이라 잡힌다. **이름 하나를 오타 냈으면 개수가 같아 못 잡는다** — 잡으려면 무엇을 등록했어야 하는지의 목록이 필요하고, 그 목록을 `registry`가 드는 순간 ADR 35의 결정(이름의 주인은 사이트다)이 깨진다. 그래서 레지스트리는 **개수와 "못 함"만** 받고 이름은 안 받는다.

69. **근거 등급이 이제 필드이고 시험이 강제한다 — 그리고 그것으로도 못 막는 것이 있다.**

    위 65번의 실수(근거 등급을 산문에 적어 두고 그 위에 `NO`를 얹었다)를 되풀이하지 않으려고 `evidence_grade`를 조사·거리 스키마에 넣었다. 값이 넷이다 — `VENDOR_PRIMARY`(벤더가 배포한 기계가독 정의) · `VENDOR_DOC`(벤더 문서 산문) · `THIRD_PARTY`(벤더 아닌 코드·글) · `INFERRED`(짐작).

    **규칙 하나다: 부재를 단정하려면 앞의 둘 중 하나여야 한다.** 거리의 `reachable: NO`와 조사의 `coverage.level: NONE`·3값 필드의 `NO`가 그 대상이며, 등급이 낮으면 새로 생긴 `UNKNOWN`까지만 적을 수 있다. 문서 단위 기본값에 항목이 덮어쓸 수 있다 — 실제로 Digit의 `model_identity`·`parameter_ranges`는 매뉴얼에서 왔으므로 `VENDOR_DOC`으로 낮춰 두었다.

    **못 막는 것은 등급을 잘못 적는 것이다.** `VENDOR_PRIMARY`라고 써 놓고 실제로는 안 읽으면 아무도 못 잡는다 — §15.6·§15.11의 문자열 검사와 같은 성질이며, 이 장치도 우회를 막는 것이 아니라 **실수를 막는다.** 다만 이번 실수는 우회가 아니라 정확히 그 실수였으므로 값이 있다.

    그리고 **`INFERRED`를 쓰는 문서가 지금 하나도 없다.** 그것이 좋은 상태는 아니다 — 넷 중 둘만 쓰이면 다음 사람이 나머지 둘을 죽은 값으로 읽고, 그러면 짐작을 `VENDOR_DOC`으로 올려 적게 된다. 조사 문서의 `UNKNOWN`에 같은 방어를 걸어 둔 것(*"아직 모르는 것이 남아 있다"*)처럼 여기도 필요해지면 그때 건다.

70. **범위는 옮겼는데 층은 아직 설계되지 않았다 — 그리고 그 중간 상태가 비목표보다 위험하다.**

    2026-09-08에 §1.2의 범위 표에 행이 하나 늘고 §1.3의 비목표에서 한 줄이 빠지고 §9.6이 다시 쓰였다. 그것으로 정해진 것은 **어디에 두는가**뿐이고, 그 층을 실제로 세우는 결정은 다섯 자리에서 비어 있다.

    | 비어 있는 자리 | 무엇이 없나 |
    |---|---|
    | §3.1 모듈 | `acl-core`·`acl-{system}`이 없다. `settings.gradle.kts`에도 없다 |
    | §3.2 의존 규칙 | 방향이 미정이다. `acl-core → contracts`만으로 되는지, `profile-model`이 필요한지, `registry`를 알아도 되는지 |
    | §11.2 게이트 | 쌍둥이 검사(코어에 시스템 문자열 금지)가 없다. 10번으로 세울지 7번의 대상 목록을 넓힐지도 정하지 않았다 |
    | §12.2 · §13 | 완료 기준에 행이 없고, 어느 단계에서 만드는지도 없다 |
    | ADR | 결정 기록이 없다. §9.6의 정정 문단은 근거를 적은 것이지 결정 기록이 아니다 |

    **비목표였을 때가 오히려 안전한 상태였다.** 비목표는 명시적 부재이므로 다음 사람이 "없다"로 읽는다. 지금은 범위 표에 행이 있고 §9.6에 배치가 적혀 있어서 **"있다"로 읽히는데 코드도 검사도 없다.** ADR 35가 *"(a)를 안 정하면 (b)가 공허하다"* 로 지적한 것과 같은 형태이며, 이 문서가 §11.2의 4번과 §12.2의 10번으로 막으려는 것 — 선언과 구현이 갈라지는 것 — 을 문서 자신이 하고 있는 셈이다.

    ADR 31·32가 **결정이 비어 있다는 사실 자체**를 설계 갭 점검에서 찾아 §14에 행을 더한 전례가 있다. 이 항목은 그 점검을 미리 적어 둔 것이고, **닫는 것은 위 다섯 자리를 채우는 일이지 이 문단을 지우는 일이 아니다.**

72. **사이트 이름 보고가 생존 보고에 얹혀 온다.** `GetKnownSiteNames`의 답이 별도 적재 경로가 아니라 `POST /ingest/liveness`의 쿼리로 실린다 — `robot_software`가 같은 길로 가는 것과 같은 자리이며(§15.53) 이유도 같다: 둘 다 *"기체가 자기에 대해 보고하는 사실"*이고, 경로를 늘리면 트리거와 폴백을 한 벌 더 관리해야 한다.

    **대가가 둘이다.** ① 보고 시점이 연결 전이·상태 발행에 묶인다 — 사이트 이름은 그 사이에도 바뀔 수 있고, 바뀐 것이 다음 발행까지 안 온다. ② 브로커가 붙는 날(§15.30) `HttpLiveness`가 사라지면 이 값도 함께 갈 곳을 잃는다. §15.53이 `robot_software`에 대해 적어 둔 것이 그대로 하나 더 늘었다.

    **둘이 함께 와야 답으로 친다.** `site_names_unsupported`와 `site_names_count` 중 하나만 오면 잘린 보고이므로 무시한다 — 반쪽으로 상태를 올리면 *"기체가 답했다"*가 거짓이 된다. 그리고 **안 물어본 보고가 이미 받은 답을 지우지 않는다**(`COALESCE`): 옛 어댑터가 섞여 도는 동안 그 보고마다 상태가 되돌아가면 확인이 영원히 안 선다.

73. **~~`GetKnownSiteNames`를 실물 어댑터 셋 중 아무도 구현하지 않았다.~~ 닫힘 (2026-09-08).** 셋 다 `knownSiteNames()`를 든다. 각자 다른 곳에 묻는다 — Spot은 `GraphNavService.DownloadGraph`의 `Waypoint.annotations.name`, Digit은 `notify-objects`로 id를 받고 `get-object`로 이름을 하나씩(**질의가 두 단계인 유일한 기종**이며 매뉴얼이 그 흐름을 직접 적어 두었다), G1은 세계 모델도 지도도 없어 `Unsupported`.

    **답이 셋으로 갈린다** — `Unsupported`(둘 자리가 없다) / `Known`(물어봤다, 빈 목록 포함) / `Unavailable`(**물어보지 못했다**). 마지막이 어휘의 이유다: 그래프 서비스가 죽었을 때 0으로 답하면 관측 실패가 사람의 태만처럼 보이고 원장이 `CONTRADICTED`를 띄운다 — 사실은 아무것도 관측되지 않았는데. 계약의 응답에는 그 자리가 없으므로 전송이 오류로 옮긴다. Digit에서는 **부분 실패**까지 이 자리로 접는다: 객체 하나를 못 읽었을 때 나머지로 답하면 개수가 작게 올라가 같은 거짓 경보가 된다.

    **닫으면서 Spot 어댑터의 오독 하나가 같이 드러났다.** `navigate_to`가 계약의 `location`을 `NavigateToRequest.destination_waypoint_id`로 **그대로** 넘기고 있었다. 벤더 원문은 둘을 나눈다 — `Waypoint.id`는 *"Unique across all maps"* 인 **로봇 생성 id**이고, 사람이 붙인 이름은 `Waypoint.Annotations.name`(*"Human-friendly name … For example, `Kitchen Fridge`"*)이다. 그대로 넘기면 **상위 시스템이 Spot이 만든 id를 들어야 하고 그것이 A-1 위반**이다. 어댑터가 매 호출 그래프를 받아 옮기도록 고쳤고, 모르는 이름과 동명 둘을 각각 거절한다(`SITE_NAME_UNKNOWN`·`SITE_NAME_AMBIGUOUS`). ADR 35의 결정 1과 근거 2, `profile/distance/spot-arm.json`, 그리고 뒤집힌 시험 하나가 함께 고쳐졌다.

    **ADR 34를 어긴 것이 아니다.** 표를 **드는 것**과 로봇의 표에 **묻는 것**은 다르다 — 어댑터는 아무것도 저장하지 않고, 대응을 정한 것도 지도를 녹화한 사이트다. 앞 판이 그 둘을 가르지 않아 *"통과시키는 것"* 을 결정의 내용으로 적었다.

    **남는 것.** ① 이름 조회 RPC가 없어 매번 그래프 **전체**를 받는다 — 캐시하면 사이트가 지도를 다시 올렸을 때 어댑터가 옛 이름으로 로봇을 몬다. ② `annotations.name`은 유일하지 않다(사람이 적는 문자열이다). ③ **셋 다 실물에 붙여 보지 못했다**(§9.7 ④·C-3) — 페이크를 상대로만 돈다. ④ 어댑터에는 gRPC 서버가 없으므로 이 답이 계약의 RPC로 나가는 배선은 아직 없다.

74. **벤더 원문을 짚은 것이 이제 시험이다 — 다만 이름까지만이다.** 남쪽 포트의 각 멤버가 `@VendorSurface`로 벤더의 이름을 짚고, 어댑터마다 `*VendorSurfaceTest`가 그것을 `src/test/resources/vendor-manifest.txt`와 대조한다. 매니페스트는 `tools/vendor-manifest/`의 추출기가 **벤더 원문에서 뽑은 것**이며 원본의 sha256이 헤더에 함께 적힌다.

    **원문은 저장소에 안 들어온다.** `adapter-boston-dynamics-spot/build.gradle.kts`가 적어 둔 *"벤더 SDK가 여기 없고 … SDK 없이 컴파일되고 시험이 돈다"*가 그대로 유지되며, 들어오는 것은 이름과 해시뿐이다(ADR 31의 격리를 다시 논하지 않아도 된다).

    **왜 생겼나 — 같은 층에서 사고가 셋 났다.** 전부 사람이 벤더 문서를 읽고 KDoc에 옮기는 단계였고 틀려도 아무것도 빨개지지 않았다: ① Digit 측정이 제3자 래퍼 때문에 통째로 뒤집힘, ② Spot이 `Waypoint.id`(로봇 생성)와 `annotations.name`(사람이 붙임)을 접음, ③ **Digit `ActionStatus`에 `failure`가 없어 어댑터가 로봇이 신고한 실패에 도달할 수 없었다** — 권한을 잃을 때만 태스크가 죽었고 액션이 막히면 영원히 `RUNNING`이었다. 셋째는 이 검사를 붙이는 중에 나왔고 코드와 시험을 함께 고쳤다. 같이 나온 작은 것 둘: Spot의 `GetStateResponse.Status`는 없는 이름이고(`State.status`다), `MissionStatus`에 벤더의 `STATUS_UNKNOWN`이 빠져 있었다.

    **검사가 둘인 것이 요점이다** — 짚은 이름이 매니페스트에 있는가, 그리고 **멤버마다 짚은 것이 있는가**. 앞의 것만 있으면 애너테이션을 안 붙이는 것으로 언제나 통과한다.

    **원본이 기종마다 다르고 그 차이가 곧 측정 결과다.** Spot은 `.proto` 12개로 메시지·필드·열거값·RPC를 전부 덮는다. Digit은 **원본이 둘**이다 — SDK `json.py`는 *보내는* 메시지의 빌더만 들어서 우리가 *읽는* `privileges`·`action-status-changed`는 매뉴얼이 1차 출처다. G1은 스키마 언어가 아예 없어 원본이 셋으로 흩어진다 — API ID 상수, 요청 본문의 JSON 키(헤더의 `Jsonize*`), IDL 필드. **응답의 모양은 어디에도 없다.**

    > **정정 (2026-09-08).** 이 문단은 *"인자 이름은 못 덮는다 — 스키마 언어가 없다"* 고 적혀 있었고 틀렸다. 벤더는 `json["velocity"]`·`json["duration"]` 으로 주고 있었고 **우리 추출기가 `const` 줄만 읽고 있었다.** 도구의 한계를 벤더의 부재로 적은 것이며 §15.65 가 경고한 그 실수다. 추출기를 고쳐 `setVelocity` 가 그 둘을 짚는다.

    **그래서 "못 덮는다" 를 세 종류로 갈라 적는다** — ① 추출기가 못 읽는다(고칠 수 있다) ② 벤더가 타입으로 안 적었다(Digit 의 일반 오류 봉투가 그것이고, 실물이나 시뮬레이터에서 받아 봐야 안다) ③ 검사의 성질(있는 이름이면 통과, 거동, 낡음). **①을 ②로 적는 것이 이 저장소가 반복해 물린 실수다.**

    **못 하는 것 넷.** ① **거동을 안 본다** — 그 메시지를 보냈을 때 로봇이 무엇을 하는지는 여전히 §9.7 ④·C-3이다. ② **있는 이름이면 통과한다**: `Waypoint.id` 대신 `Waypoint.snapshot_id`를 짚어도 둘 다 실재하므로 초록이다(주입으로 확인했고, 이 검사가 못 잡는 자리다). ③ **낡은 매니페스트는 낡은 코드와 사이좋게 초록이다** — 헤더의 릴리스와 해시가 그것을 드러내는 유일한 장치이고, 갱신은 사람이 절차대로 돌린다. ④ 검사할 타입 목록이 시험에 **손으로** 적혀 있다. ⑤ **인용의 완전성은 못 본다** — 멤버가 아무것도 안 짚으면 잡지만, 다섯을 짚어야 할 자리에 넷만 짚은 것은 통과한다(주입으로 확인).

    **추출기가 틀리는 두 방향은 위험이 다르다.** 이름을 빠뜨리면 시험이 빨개진다(시끄럽지만 안전하다). 이름을 더 만들면 없는 것을 짚어도 통과한다(조용히 검사가 약해진다). 실제로 안전한 쪽으로 두 번 틀렸고 둘 다 빨강으로 드러났다 — `.proto`의 `oneof`가 이름 있는 스코프를 조기에 닫아 `Graph.waypoints`가 사라졌고, `re.M`이 없어 G1의 API 상수가 통째로 안 나왔다.

    **이것은 C-3의 첫 층을 닫은 것이지 C-3을 닫은 것이 아니다.** 나머지 둘은 여전히 열려 있다 — 거동(기종마다 도달 가능한 층이 다르다: Digit은 벤더가 자기 제어 프로그램을 시뮬레이터로 배포하고, G1은 공식 시뮬레이터가 저수준만 흉내내며, Spot은 공개된 것을 못 찾았다 — 마지막은 **부재 주장이고 근거 등급이 낮다**)와 배치(네트워크·인증·시계·재접속).

75. **Spot을 서비스 54개 중 3개만 읽고 쟀다 (2026-09-09 전수 조사로 드러남).** 앞 판의 `profile/distance/spot-arm.json`은 `robot_command`·`mission`·`graph_nav`만 보고 작성됐고, 그 위에서 *"이 벤더에 무엇이 없다"*를 적었다. BD는 공개 SDK에 **서비스 54개, proto 152개**를 판다.

    **뒤집힌 것 셋.**

    | 앞 판 | 실제 |
    |---|---|
    | 층이 둘(명령·미션)이고 우리 계약은 미션 계층에 있다 | **넷이다** — 명령 · 미션(행동트리) · **워크(`AutowalkService`)** · 태블릿. 우리 계약과 가장 닮은 것은 미션이 아니라 Autowalk의 `Element{name, target, target_failure_behavior, action, action_wrapper, action_failure_behavior, is_skipped, battery_monitor, action_duration, id}`이며, 그것이 미션으로 **컴파일**된다. 우리는 미션과 워크 **사이**에 있다 |
    | Spot에는 대상 시맨틱이 없다 (픽셀·3D점뿐) | **`WorldObjectService`가 있다** — `WorldObject.name`이 *"A human readable name"*, `ListWorldObjects`로 열거, `MutateWorldObjects(ACTION_ADD)`로 클라이언트가 등록. Digit의 세계 모델과 구조가 같다 |
    | 취득 결과를 대상에 결속할 자리가 없다 | **`CaptureActionId{action_name, group_name, timestamp}`가 있다.** Autowalk이 *"replaces the action_name … with the element name"*로 채운다. 생명주기도 온전하다 — `AcquireData`·`GetStatus`·`CancelAcquisition`·`GetServiceInfo` |

    **`pick_place`는 하루에 두 번 고쳤다 — NO(이유 틀림) → NO(이유 고침) → PARTIAL.** 두 번째도 과했다. 고수준 놓기 *액션*이 없는 것은 맞지만(피드백 열거에 `MANIP_STATE_PLACE_*`가 있는데 요청 `oneof`에는 대응이 없고 `reserved 3, 6, 9`만 남았다) **놓기 자체는 `ArmCartesianCommand` + `ClawGripperCommand`로 되고, 그것은 우리가 Digit에서 이미 YES로 인정한 어댑터 합성과 같은 종류다.** 일관성상 NO로 둘 수 없다. `inspect`는 PARTIAL 그대로.

    **프레임 결속의 실제 모양(벤더 예제로 확인).** 객체 프레임 이름은 명령에 **안 들어간다** — `fiducial_follow.py`가 `get_a_tform_b(snapshot, VISION_FRAME_NAME, frame_name_fiducial)`로 스냅샷에서 꺼내 `vision`으로 옮기고 명령은 그 관성 프레임으로 나간다. **그래도 결속의 주인은 로봇이다** — 어댑터는 로봇이 준 프레임 트리를 읽을 뿐 표를 갖지 않으므로 ADR 34가 지켜진다. 그리고 사이트가 이름을 등록할 수 있다(`mutate_world_objects.py`가 `WorldObject(name='red_sphere_ball')`를 `ACTION_ADD`로 넣는다).

    **남는 비대칭이 진짜다.** Digit은 `ObjectSelector{name}`을 넘겨 **파지 시점에 로봇이 다시 풀고**, Spot은 질의 시점의 좌표로 굳는다. 움직이는 대상에서 둘의 행동이 갈리며 그것은 배관이 아니라 의미의 차이다.

    **그래서 `pick_place`는 이제 표본 하나가 아니다** — YES 하나(Digit) + PARTIAL 하나(Spot).

    **팔 유무 확인을 붙였다 — 다만 처음 든 이유가 틀렸었다.** 나는 *"팔 없는 기체에 팔 명령을 보내면 조용히 아무 일도 안 일어난다"*고 적었는데, **그 문장은 `BodyAssistForManipulation` 하나에만 붙어 있다**(proto 152개 전체에서 팔 부재를 말하는 유일한 자리다). 명령 일반에는 `RobotCommandResponse.Status.STATUS_UNSUPPORTED` — *"The robot does not understand this command"* — 가 있다. 한 문장을 표면 전체로 넓힌 것이었고, 같은 종류의 성급함이 이 항목에서만 세 번째다.

    **진짜 이유는 결속이 어긋난 것을 보이게 하는 것이다.** 어댑터가 `spot-arm` 프로파일에 묶였는데 기체가 팔을 보고하지 않으면 그 아래 선언이 전부 거짓 전제 위에 선다. 신호는 벤더가 준다 — `RobotState.manipulator_state`가 *"only populated if an arm is attached to the robot"*이다(`HardwareConfiguration.skeleton.links[].name`으로도 셀 수 있지만 **어느 링크가 팔인지 우리가 정해야** 해서 안 쓴다. 벤더가 `has_audio_visual_system`은 파는데 `has_arm`은 안 판다).

    **막지 않고 보이게 한다.** 팔이 없어도 `move_relative`·`navigate_to`는 돈다. 그래서 거절이 아니라 결함이며, **팔이 필요한 스킬이 들어오는 날 그 스킬이 이 답을 보고 거절해야 한다** — 지금은 그런 스킬이 없어 거절 경로를 안 만든다(ADR 9). 그리고 **"없다"와 "못 물어봤다"를 가른다**: 읽기 실패를 팔 없음으로 접으면 관측 실패가 결속 오류로 보이고 운영자가 멀쩡한 기체의 배포를 뒤진다. `X_BOSTONDYNAMICS_ARM_ABSENT`와 `X_BOSTONDYNAMICS_HARDWARE_UNKNOWN`으로 따로 낸다. 전제는 모델 이름을 뜯어 짐작하지 않고 **배포하는 쪽이 명시한다**(`expectsArm`).

    **매니페스트도 전수로 바꿨다.** 앞 판은 손으로 고른 proto 12개만 덮어서 `survey_scope`의 *"서비스 54개 전수"*와 앞뒤가 안 맞았고, 실제로 `robot_state.proto`를 안 덮어 이 인용을 못 붙일 뻔했다. 이제 152개 전부에서 8089개 심볼을 뽑는다.

76. **한 로봇이 서비스 54개를 파는데, 그것들은 독립적이지 않다 — 그리고 미션 계층은 그것들을 재정의하지 않고 참조한다.** 2026-09-09 조사의 결과이며, 여기 적는 것은 **사실뿐**이다. 우리 계약을 어떻게 할지는 아래 "열린 질문"이고 아직 안 정했다.

    **① 세 겹의 공통 골격.**

    | 층 | 측정 |
    |---|---|
    | 봉투 | 최상위 `*Request` 메시지 **256개 중 250개(98%)**가 `RequestHeader header`를 든다 |
    | 디렉터리 | 전부 `DirectoryService`에 등록되고 `service_name`으로 찾아진다 |
    | 구동 권한 | **34개만** `Lease`를 싣는다 |

    마지막 줄이 진짜 경계선이다. **`Lease`를 싣느냐가 "움직이는 것"과 "묻는 것"을 가르는 유일한 선**이며, 배타 제어(§4.9)의 대상은 서비스 54개가 아니라 그 34개 요청이다. 나머지는 질의·등록·저장이다.

    **② 미션 노드 49개 = 서비스 참조 29 + 미션 소유 20.**

    참조 29개는 **전부 같은 모양**이다 — `(string service_name, string host, <그 서비스의 요청 메시지 원본>)`. 예:

    ```proto
    message BosdynRobotCommand {
        string service_name = 1;
        string host = 2;
        RobotCommand command = 3;   // 벤더 요청을 그대로 싣는다
    }
    ```

    **노드가 의미를 하나도 안 더한다.** `RemoteGrpc`도 같은 모양을 일반화한 것이다(`host`·`service_name`·`timeout`·`lease_resources`). 미션 계층이 실제로 소유한 것은 20개뿐이다 — **조합 10**(`Node`·`Sequence`·`Selector`·`Switch`·`Repeat`·`Retry`·`ForDuration`·`SimpleParallel`·`ParallelAnd`·`Condition`), **상태 6**(블랙보드 넷 + `ConstantResult`·`CreateMissionText`), **나머지 4**(`Prompt`=사람 · `Sleep`=시간 · `RestartWhenPaused`·`DataAcquisitionOnInterruption`=정책).

    **③ 명령은 이미 벤더가 합성해 두었다.** `RobotCommand` → `SynchronizedCommand{arm_command, mobility_command, gripper_command}`. 그래서 팔 노드가 따로 없고 `BosdynRobotCommand` 하나가 팔·이동·그리퍼를 다 덮는다.

    **④ 노드를 안 받은 서비스들**(완전 열거에서의 부재이므로 근거가 세다) — `ManipulationApiService`(집기 자체는 노드가 없다. `SetGraspOverride`만 있다) · `WorldObjectService` · `ImageService` · `DoorService` · `InverseKinematicsService` · `ArmSurfaceContactService` · `AutowalkService` · `NetworkComputeBridge`. **BD는 조작을 미션 노드로 만들지 않았다.** 그것들은 `RemoteGrpc`로만 미션에 들어온다.

    **그래서 BD의 답은 이렇게 읽힌다 — 미션 계층은 무엇을 할 수 있는지 열거하지 않는다. 참조하고, 조합하고, 상태를 갖는다.**

    **열린 질문 (아직 안 정함).** 우리 계약은 정반대로 능력을 **열거**한다(`move_relative`·`navigate_to`·`pick_place`·`inspect`). 이 배치로 보면 `inspect`가 실물 셋 어디에도 안 닿는 것이 벤더 결손이 아니라 **그것이 원자가 아니라 조합**(`Element{target, action}`)이기 때문으로 읽히고, `pick_place`도 Digit에서는 벤더가(`action-sequential`) Spot에서는 어댑터가 조합한다.

    **다만 BD 배치를 그대로 베낄 수는 없다.** BD가 `(service_name, host, 벤더 요청 원본)`을 나를 수 있는 것은 **자기 로봇만 상대하기 때문**이다. 같은 것을 우리가 하면 소비자가 벤더 요청 메시지를 알아야 하고 그것은 A-1 정면 위반이며, 벤더 중립이 이 계약의 존재 이유다. 그러므로 질문은 *"베낄까"*가 아니라 **"벤더 중립을 지키면서 열거에서 참조+조합으로 갈 수 있는가"**이다. §15.74의 `pick_place` 1건·`inspect` 0건 문제와 같은 뿌리다.

    > **닫힘 (2026-09-09) — [ADR 36](../../adr/0036-work-first-assignment-vs-execution.md).** 답은 *"베낀다/안 베낀다"*가 아니라 **어휘가 두 일을 겸업하고 있었다**는 것이었다. 배정(추상)과 실행(정밀)을 한 벌로 두면 하나가 다른 하나를 망친다. 층을 넷으로 가르고(사이트 일감 / 능력·자격 어휘 / 배정과 결정 / 실행과 벤더 조합), **실행 계층을 상류에 비노출**로 두며, 등재 기준을 **발명 금지**(상류나 벤더 중 한쪽이라도 이미 갖고 있는 것만)로 잡았다. 조합은 계약이 갖는 것이 아니라 **능력 안에서 로봇마다 다르게** 산다 — ADR 34·35가 이름 없이 정해 둔 부패 방지 계층이 그 자리다.

    **ADR 35는 오히려 강해진다** — 표본 하나가 아니라 독립적인 벤더 둘이 같은 배치(이름은 로봇 안에 산다)를 골랐다. 약해지는 것은 *"Digit이 유일하다"*는 서술뿐이다.

    **`inspect`에 대해 이 조사가 새로 제기하는 것.** BD는 점검을 *"어디에 서서 어느 이름의 액션을 돌리는가"*(`Element{target, action}`)로 모델링하는데 우리 계약은 `inspect(target)`으로 **물체 신원**을 묻는다. `inspect`가 실물 셋 어디에도 안 닿는 것이 벤더의 결손이 아니라 **우리 형식이 틀렸을 가능성**이 처음으로 근거를 얻었다.

    **이름 공간이 둘이다.** 장소는 `Waypoint.annotations.name`, 대상은 `WorldObject.name`. 계약의 `is_site_reference`도 장소(`location`·`destination`)와 대상(`object_id`·`target`)으로 갈린다. ~~**그러므로 `GetKnownSiteNames`가 그래프만 보는 것은 반만 보는 것이다**(§15.73에 더한다).~~ **이 결론이 틀렸다 — §15.78 참조.** 공간이 둘이라는 관찰은 맞는데 그것을 **한 그릇의 결손**으로 읽었다. 장소를 묻는 질의가 장소만 답하는 것은 옳다. 틀린 것은 질의가 아니라 **불리언 하나가 넷을 한 덩어리로 묶고 있는 것**이다.

    **막는 장치.** 거리 스키마에 `survey_scope`를 **필수**로 더했다 — *"이 측정이 벤더 표면의 얼마를 봤는가"*. `evidence_grade`가 *출처가 얼마나 1차인가*라면 이것은 *얼마나 넓게 봤는가*이고, **둘은 다르다**: 1차 원문 세 개만 읽어도 등급은 `VENDOR_PRIMARY`다. 이 항목의 오류가 정확히 그 틈에서 났다. 셋 다 범위를 적었다.


77. **어댑터가 벤더의 *어느 층*에 붙든 상관없어야 한다 — 그리고 그것이 지켜지는지 우리는 모른다.**

    실물 어댑터 셋이 전부 **로봇에 직접** 붙는다(Spot gRPC · Digit WebSocket · G1 DDS). 붙는 자리가 다를 수 있다는 것은 추측이 아니다 — BD의 **Orbit**이 플릿 매니저로 실재하고 그 표면을 재어 `docs/vendors/orbit.md`에 적었다.

    **그런데 그것은 계약이 알 일이 아니다.** 어댑터가 할 일은 **원자적 명령으로 번역하는 것뿐**이고, 어느 층에 붙었는지는 어댑터 안에서 끝나야 한다. 그것이 ADR 34·35가 이름 없이 정해 둔 부패 방지 계층의 뜻이다. 계약에 `ROBOT_DIRECT | FLEET_MANAGER` 같은 필드를 더하는 것은 **벤더 배치를 상류로 새게 하는 것**이며(ADR 36 결정 5의 정면 위반), 이 항목의 앞 판이 정확히 그것을 제안했다가 되물렸다.

    층이 다를 때 흘러드는 것들은 **이미 있는 어휘로 흡수된다**:

    | 층 차이 | 흡수하는 자리 |
    |---|---|
    | 리스가 없다(Orbit) | 프로파일의 `exclusive_control_required` — 이미 기종마다 다르게 선언한다 |
    | 인스턴스 하나가 기체 N대를 든다 | 어댑터 인스턴스를 기체마다 두고 안에서 다중화한다. 계약은 이미 기체 단위다 |
    | 결과가 정수 하나로 온다 | 결함 어휘로 옮긴다. **못 옮기면 그것은 계약의 결손이 아니라 능력 판정이다** — 그 층으로는 이 계약을 만족하지 못한다 |

    **그러므로 여기 적는 한계는 "선언이 없다"가 아니라 "새는지 모른다"이다.** 플릿에 붙는 어댑터를 만들어 본 적이 없으므로, 위 표의 흡수가 실제로 되는지 확인된 바 없다. 확인되면 §15의 이 항목이 닫히고, 흡수 안 되는 것이 나오면 그때는 필드를 더할 것이 아니라 **그 층은 이 계약을 못 만족한다**고 적는다.

78. **공간을 구분할 수 있으면 구분한다 — 지금 계약은 이름과 주소를 뭉개고 있다.**

    셋이 다르다.

    | 공간 | 무엇 | 누가 소유 | 예 |
    |---|---|---|---|
    | **이름 공간** | 사람이 붙인 이름 | 사이트가 저작하고 로봇에 등록 | `dock-3`, `valve-A` |
    | **주소 공간** | 기계가 만든 식별자 | 로봇·플릿이 생성 | `destination_waypoint_id`, `WorldObject.id`(배터리 수명), Orbit `uuid`·`robotIndex` |
    | **논리적 공간** | 능력·일감 수준의 개념 | 도메인 | ADR 36의 층 ② |

    ADR 34·35가 *"계약은 이름을 나르고 어댑터가 주소로 옮긴다"*를 이미 정했다. **그런데 계약의 이름이 그것을 배반한다** — `object_id`는 *id*(주소)라고 불리면서 실제로는 **이름**을 나른다. Spot 어댑터가 사람 이름을 `destination_waypoint_id`로 그대로 넘겨 틀렸던 것(§15.73)이 그 혼동의 실물이고, 이름이 그렇게 붙어 있는 한 같은 실수가 다시 난다.

    **이름 공간 안에서도 또 갈린다.** 장소(`location`·`destination`)와 대상(`object_id`·`target`)은 **쓰임과 의도가 다르다**:

    - 장소는 사이트가 지도에 **저작**한 것이고 지속한다. 등록의 대상이다.
    - 대상은 **인지가 필요**하고(§1.3 비목표) 만료된다. Spot의 `WorldObject`를 벤더는 *"the currently perceived world objects"*라 부른다.

    그런데 `is_site_reference`가 **불리언이라 넷을 한 덩어리로 만든다.** 그 결과가 두 곳에서 이미 새고 있다 — 레지스트리가 `object_id`까지 **등록 대상으로 유도**하고(등록할 수 없는 것을 등록하라고 한다), `GetKnownSiteNames`가 평면 목록이라 **어느 공간의 답인지 말하지 못한다**.

    > **왜 지금 중요한가.** 여기서 잡탕이 되면 ADR 36의 **논리적 능력 매핑이 성립하지 않는다.** 층 ②의 자격 조건은 *"이 기체가 이 장소를 아는가"*, *"이 대상을 집을 수 있는가"* 같은 것을 말해야 하는데, 이름과 주소와 능력이 한 그릇에 있으면 그 문장을 쓸 수가 없다.

    **벤더가 하나로 두는 경우와 혼동하지 않는다.** Digit은 장소로 가는 것도 `object-selector.name`을 쓴다 — 이름 공간이 **기종에서** 하나인 것이며, 그것은 어댑터가 흡수할 벤더 사실이지 계약이 뭉갤 이유가 아니다. Spot은 둘로 갈려 있다(GraphNav 웨이포인트 / `WorldObject`).

    **앞 판의 결론을 뒤집는다.** §15.76은 *"그러므로 `GetKnownSiteNames`가 그래프만 보는 것은 반만 보는 것이다"*라고 적었는데 틀렸다. 공간이 둘이라는 관찰은 맞고, 그것을 **한 그릇의 결손으로 읽은 것**이 틀렸다. 장소를 묻는 질의가 장소만 답하는 것은 옳다.


79. **충분함은 계약이 알 것이 아니라 로봇이 답할 것이다 — 그런데 답할 어휘가 없다.**

    `GetKnownSiteNames`는 *"이 이름을 기체가 아는가"*까지 답한다(ADR 35). 일감은 더 요구한다 — *"1번 창고 6번 자리에 놓아라"*는 그 자리의 **높이·접근 방향**을 요구하고, 이름이 있다는 것과 그 이름이 그만큼을 문다는 것은 다르다.

    **벤더 원문이 그 간극을 보여 준다.** 이름은 `Waypoint.Annotations.name`에 붙고 벤더 예시가 *"Kitchen Fridge"*다. 웨이포인트가 무는 자세 `waypoint_tform_ko`(`SE3Pose`)는 **로봇이 서서 측위하는 자리**이며, `Annotations`의 나머지 스물몇 필드는 전부 항법·측위·GPS·루프 클로저·충돌 설정이다. **조작 대상의 자세는 없다.** 그러므로 같은 이름이 `navigate_to`에는 충분하고(웨이포인트가 정확히 그것을 위해 있다) `pick_place`의 `destination`에는 모자랄 수 있다.

    ### 앞 판이 이것을 우리 결손으로 적었고, 그것이 틀렸다

    *"충분함을 확인하지 못한다"*고 적었는데 **확인해서는 안 되는 것이다.** 확인하려면 계약이나 미들웨어가 6번 자리의 물리를 알아야 하고, 그 순간 §15.78이 어댑터 아래 가둔 주소 공간이 위로 샌다.

    선이 여기다 — **계약은 환경을 *참조*하되 *해석*하지 않는다.**

    | 계약이 나르는 것 | | |
    |---|---|---|
    | **참조** — 사이트가 저작한 이름 | ○ | 나르기만 하고 뜻을 묻지 않는다 |
    | **속성** — 높이·좌표·자세 | ✗ | 상류가 환경의 물리를 알아야 한다 |
    | **충분함 판정** | ✗ | 판정하려면 환경을 알아야 한다 |

    ### 그러면 누가 답하는가 — 로봇이다. 자리도 이미 있다

    `Negotiate`(§5.4, *"태스크를 보내보고 실패하는 것이 아니라 사전 협상이다"*)와 접수 시 `Rejection`이 그 자리다. **판정은 로봇이 하고 계약은 판정을 나른다** — 그러면 환경 정보가 한 톨도 안 올라온다.

    ### 진짜 빈 칸은 어휘다

    `RejectionCode` 열둘 중 **그 말을 할 값이 없다.** `SKILL_ABSENT`는 스킬이 없다는 뜻이고 `PARAMETER_INVALID`는 값이 선언을 어겼다는 뜻인데, 여기서 필요한 것은 *"파라미터도 스킬도 멀쩡한데 **이 이름으로는 이 스킬을 못 한다**"*이다. 없으면 어댑터가 둘 중 하나로 접게 되고, 그러면 운영자가 **이름을 고치려 든다 — 고칠 것은 등록이다.**

    ### 그 어휘를 더하려다 멈췄다 — 발신자가 없다 (2026-09-09)

    `RejectionCode`에 값을 하나 더하려 했는데 **ADR 9**(*"소비 표면이 없는 선언은 두지 않는다"*)에 걸린다. 반대 방향으로도 걸린다 — **낼 쪽이 없다.**

    - **Spot 어댑터는 `move_relative`와 `navigate_to` 둘만 든다.** `pick_place`가 없으니 `destination`의 결속을 물을 자리 자체가 없다.
    - **Digit은 `pick_place`를 드는데** 벤더 표면이 수락 시점에 *"이름은 아는데 이 스킬엔 못 쓴다"*를 말해 주지 않는다. 있는 것은 실행 후의 `action-status-changed.info`다.
    - **어댑터의 `Acceptance.Refused`는 아직 계약으로 가는 길이 없다.** 어댑터는 라이브러리이고 계약 뒤에 세워 주는 것이 없다. 지금 계약의 `RejectionCode`를 내는 것은 미믹뿐이며, 미믹은 이름은 갖되 자세를 안 갖는다 — 충분함을 모델링하게 만들면 **미믹이 환경을 갖게 되고** 그것이 이 항목이 막으려던 바로 그것이다.

    ### 그리고 더 중요한 것 — 벤더는 이것을 **런타임에** 말한다

    Spot의 `GRASP_PLANNING_NO_SOLUTION`·`GRASP_FAILED_TO_RAYCAST_INTO_MAP`은 `ManipulationFeedbackState`의 값이다. **피드백이지 수락 거절이 아니다.** 로봇은 시도해 보고 나서 안다.

    > ⇒ **"이름은 아는데 못 쓴다"는 대체로 거절이 아니라 실패다.** 그러면 이 항목의 빈 칸은 `RejectionCode`가 아니라 **결과 어휘**로 옮겨간다 — `Fault.error_type`이 자유 문자열이라 Spot의 열여덟 값을 정준화하지 못하는 그 문제와 **같은 칸**이다. 항목이 하나 줄고, 결과 어휘를 세워야 할 이유가 하나 늘었다.

    > **환경 참조를 가진 것들만 기종마다 갈린다.** `move_relative`의 파라미터 넷에는 환경 참조가 하나도 없고 그것만 3/3이다(§15.76). `navigate_to`·`pick_place`·`inspect`는 전부 환경을 참조하고 전부 기종마다 다르게 닿는다. **환경 결속이 곧 이식성의 경계**라는 뜻이며, ADR 36이 `move_relative`를 *"너무 저수준"*이라 판정한 것의 다른 얼굴이다.

    > **곁가지.** `Waypoint.Annotations.waypoint_source`가 `ROBOT_PATH`(녹화 중 자동 생성) · `USER_REQUEST`(사람이 놓음) · `ALTERNATE_ROUTE_FINDING`을 가른다. **벤더가 저작 여부를 선언해 준다.** 지금 Spot 어댑터는 *"이름이 비지 않았는가"*로 저작물을 짐작하는데(§15.73), 이 필드가 그 짐작을 관측으로 바꾼다. 아직 안 든다.


80. **대상을 무엇으로 지시하는가 — 그리고 분류는 원자가 아니다.**

    2026-09-09 조사. `pick_place(object_id, destination)`에서 `object_id`가 어떻게 물리적 대상에 닿는지를 물었더니 형식 자체가 흔들렸다.

    ### 신원 수단이 셋이고 값이 다르다

    | | 수단 | 벤더 근거 | 대가 |
    |---|---|---|---|
    | ① | **자리가 보증한다** | 없어도 된다 | 공정이 종류별로 제시해야 한다 |
    | ② | **AprilTag** | Spot `WorldObject.apriltag_properties`, Digit `ObjectSelector.april_tag_id` | 로트마다 붙이는 것은 비현실적. 재사용되는 용기·팔레트·지그에는 현실적 |
    | ③ | **외부 인지** | Spot `NetworkComputeBridge` (심볼 72) | 워커를 배포하고 유지해야 한다 |

    **바코드·QR·RFID는 Spot 공개 표면 8089 심볼에 0건이다.** *"제품에 QR을 붙인다"*는 애초에 벤더가 못 받는다. 마킹은 AprilTag 하나다.

    ③의 사슬은 끝까지 있다 — `NetworkComputeRequest{image, model_name, min_confidence}` → 외부 워커 → `NetworkComputeResponse.object_in_image`(`repeated WorldObject`, *"May include bounding boxes, image coordinates, 3D pose information"*) → `PickObjectInImage{pixel_xy}`.

    ### 조작이 요구하는 것은 인스턴스가 아니다

    `PickObject`는 **3D 점**(`Vec3 object_rt_frame`)이나 **픽셀**만 받는다. 이름도 타입도 안 받고, `GraspParams`는 `grasp_palm_to_fingertip`·`allowable_orientation` 같은 **전략**이며 *"아무것도 안 주면 로봇이 알아서 좋은 파지 방향을 찾는다"*고 적혀 있다. **로봇에 제품 타입이라는 개념이 없다.**

    | 일감 | 대상을 무엇으로 지시하나 | 결속 |
    |---|---|---|
    | 순서공급 | `source` — **자리** | 등록 (ADR 35) |
    | 분류 | **타입 라벨** | 워커가 선언 (`ModelLabels.available_labels`) |
    | 추적·이력 | `AA-1234` — 인스턴스 | **조작 밖.** 상류가 사후 결속 |

    > **`pick_place(object_id, destination)`은 이력 키를 조작 파라미터 자리에 놓고 있다.** `object_id`가 결속 경로를 못 갖는 이유가 *"아직 안 만들어서"*가 아니라 **거기 들어갈 것이 아니라서**일 수 있다. §15.76이 `inspect(target)`에 대해 제기한 *"우리 형식이 틀렸을 가능성"*이 `pick_place`에도 걸린다.

    ### 분류는 원자가 아니다 — 배치가 넷이고 기종이 갈린다

    분류는 `인지 → 라벨 판정 → 규칙 적용 → 집어 놓기 → 반복`이다. **누가 그 루프를 돌고 누가 규칙을 갖느냐**가 결정이다.

    | | 루프 | **규칙** | Spot | Digit |
    |---|---|---|---|---|
    | **A** 자리 기반 | 없음 | 상류 | ✓ | ✓ |
    | **B** 로봇이 판단 | 로봇 | 로봇의 미션 | **✓** | **✗** |
    | **C** 관제가 판단 | 관제 | 관제 | ✓ | **✓** |
    | **D** 로봇 루프 + 외부 판단 | 로봇 | 외부 호출 | **✓** | ✗ |

    **실측이 이 표를 만들었다.** Spot의 미션 노드에 `Repeat`·`Retry`·`Switch`·`Selector`·`Condition{EQ,NE,LT,LE,GT,GE}`·`DefineBlackboard`/`SetBlackboard`·`ParallelAnd`·`SimpleParallel`이 다 있고, `RemoteGrpc`·`RemoteMissionService`로 외부에 물을 수도 있다(D). **Digit은 `action-loop{action, count}`로 횟수 반복만 되고 조건·분기가 0건이다**(`condition`·`branch`·`switch`·`while` 전부 0). 대신 `add-sequential-actions{actions, append, reference-number}`가 있어 **실행 중인 순열에 액션을 밀어 넣을 수 있다** — 그것이 C다.

    > **둘 다 되는 유일한 배치가 C다.** 그리고 **ADR 36의 층 구분이 같은 답을 가리킨다** — 규칙은 층 ③(배정과 결정)이고 로봇은 층 ④(실행)다. 이식성과 층 구분이 일치하는 드문 경우이며, 그래서 이것은 취향이 아니라 근거 있는 방향이다.

    ### 그런데 계약이 C를 못 나른다

    - **관측을 올릴 길이 없다.** 위로 가는 것은 태스크 상태·결함·능력 변경뿐이고, 구조화된 관측을 실을 자리가 `WatchTaskResponse.partial_result` **문자열 하나**다. *"라벨 valve, 신뢰도 0.91, 픽셀 (412,308)"*을 실을 데가 없다.
    - **B를 고르더라도 나를 어휘가 없다.** *"분류해라"*가 카탈로그에 없고, 규칙을 로봇에 배포하는 경로도 없다.
    - C의 대가는 **왕복 지연**이다. 물건 하나마다 인지→상행→판단→하행이 돈다.

    ### 능력 어휘의 첫 벤더 근거

    `ModelLabels.available_labels`(`repeated string`, *"List of class labels returned by this model"*)가 **이 기체가 지시받을 수 있는 대상 종류**다. 워커가 스스로 선언하고, 프로파일의 `allowed_values`(§10.4 ③)가 그것을 나를 그릇이다. **ADR 36 층 ②의 첫 구체적 사례이며 발명이 아니다.**

    ### 안 정한 것

    **혼재로 오느냐 종류별 용기로 오느냐는 공정 설계의 결과**이고 우리는 그 라인을 모른다. 시연 영상은 연출이라 근거가 못 된다. 확실한 것은 **둘 다 지원해야 하고 둘이 요구하는 것이 다르다**는 데까지다 — 순서공급은 자리 결속만 있으면 되고, 분류는 인지 라벨·규칙·반복이 필요하다.


81. **능력에는 전제가 딸린다 — 능력은 기체의 성질이 아니라 (기체 × 현장)의 성질이다.**

    2026-09-09에 이것을 **네 번 다른 이름으로 다시 발견했다.** 그래서 여기 이름을 붙여 둔다.

    *"물건 나르기"*는 한 능력이 아니다. 세부 요구에 따라 갈리고, **갈린 것 중 일부는 현장이 뒷받침해 주어야만 성립한다** — 지정된 팔레트나 워크셀에 물건을 놓아 두는 공정이 있어야 한다는 식이다. **같은 기체가 현장의 제시 방식에 따라 되기도 하고 안 되기도 한다.**

    ### 오늘 나온 것 다섯이 전부 이것이었다

    | 어디서 | 그때 부른 이름 | 실제 정체 |
    |---|---|---|
    | ADR 35 | 사이트 이름 등록 | 환경 전제 |
    | §15.79 | *"등록의 충분함"* | 환경 전제 (그 자리가 놓기에 족한가) |
    | §15.80 ① | *"자리가 신원을 보증한다"* | 환경 전제 (종류별로 제시하는가) |
    | §15.80 ② | AprilTag | 환경 전제 (붙였는가) |
    | §15.80 ③ | 외부 인지 | 환경 전제 (워커를 배포했는가) |

    ### 확인 장치는 이미 있다 — 이름을 잘못 불렀을 뿐이다

    `GetKnownSiteNames`가 **환경 전제를 확인하는 첫 장치**다. 우리는 그것을 *"이름 확인"*이라 불렀지만 하는 일은 *"이 기체가 이 일감의 전제를 갖췄는가"*이고, CLAIMED / CONFIRMED / CONTRADICTED가 그 답이다. **일반화할 자리가 이미 열려 있다.**

    ### 프로파일이 전제를 말할 수 없다

    `capability-profile.schema.json`의 `skills`는 **무조건**이다 — `derived_from`·`publish_interval`·`protocol_limits`·`exclusive_control_required`·`durations`·`failure_modes` 어디에도 전제를 적을 자리가 없다. 그래서 프로파일은 *"이 기체는 `pick_place`를 든다"*까지만 말하고 *"종류별 용기로 제시되면"*을 못 말한다.

    > **그 침묵의 대가가 이 항목의 이유다.** 배정이 통과하고 현장에서 실패한다. 그리고 그 실패가 **기체 탓으로 보인다** — 실제로는 공정이 전제를 안 만든 것인데, 화면에는 그 로봇이 못 한 것으로 남는다. §9.7 ④가 *"안 했다가 보여야 정직하다"*고 한 것과 같은 자리이며, 여기서는 안 한 쪽이 사람이다.

    ### 배정이 두 쪽이 아니라 세 쪽 맞춤이 된다

    ADR 36은 `요구 ⊆ 제공` 두 쪽으로 적었다. 실제로는 셋이다 — **일감이 요구하는 것 / 기체가 제공하는 것 / 현장이 보증하는 것.** 셋째를 빼면 앞의 둘이 맞아도 일이 안 된다.

    ### 안 정한 것

    - **전제를 누가 선언하나.** 프로파일(기체마다)인가 사이트(현장마다)인가. 둘 다 아닐 수도 있다 — 전제는 **일감과 현장의 짝**에 붙는 것이라 어느 한쪽의 문서에 안 들어갈 수 있다.
    - **확인 못 하는 전제가 있다.** *"공정이 종류별로 제시하는가"*는 로봇이 관측할 수 없다. 그런 전제는 **영원히 CLAIMED**이며 CONFIRMED가 될 길이 없다. 그것을 CONFIRMED와 같은 칸에 두면 확인된 것과 사람이 말한 것이 구별되지 않는다.
    - **목록을 모으기 시작했다** — [`docs/environment-preconditions.md`](../../environment-preconditions.md). 거기서 이 항목의 목적이 하나 더 드러났다: **전제는 검사 명세가 아니라 설계 입력이다.** 로봇 쓰는 공장·창고를 짓는 쪽이 *"이런 일을 시키려면 물리 세계에 이런 것이 있어야 하는구나"*를 읽어 가는 문서이며, 제약이자 제안이다. 가장 선명한 사례가 `DoorCommand`다 — **문을 어느 쪽으로 달지가 로봇 능력을 바꾼다**(`hinge_side`·`swing_direction`, 그리고 미는 문이냐 잡는 문이냐가 팔의 필요를 가른다).
    - 이 항목은 **결정이 아니라 관찰**이다. 결정하려면 ADR이어야 하고, 그 전에 전제의 종류를 몇 개 더 모아야 한다 — 지금은 다섯이고 전부 오늘 나왔다.

82. **범위를 안 보고 단정했다 — 하루에 두 번째, 그리고 침묵을 부재로 읽을 뻔한 것이 셋이었다.**

    G1 의 벤더 매니페스트가 **44 심볼**이었다. `g1_loco_api.hpp` 와 `unitree_hg` IDL 둘(`LowState_`·`MotorState_`)이 전부였고, 그 위에서 *"G1 은 능력을 선언하지 않고, 결함도 안 알린다"* 를 적었다. 공개 SDK 에 서비스가 **넷**이다 — `loco`·`arm_action`·`agv`·`audio`. §15.75 가 Spot 을 54 개 서비스 중 3 개만 읽고 쟀다고 적은 바로 그 모양이고, 그 항목을 적은 다음 날 같은 실수를 다른 기종에서 했다. 넷 전부와 `common/terminations.hpp`, `unitree_hg` IDL 열하나, 그리고 C++ 전용 IDL 셋을 넣어 **161 심볼**이 됐다(`53aae87`·`fcd0884`).

    ### 침묵 셋 — 벤더가 안 준 것이 아니라 추출기가 못 읽은 것

    범위를 넓히자 원문 파일이 늘었는데 그중 심볼이 **하나도 안 나오는** 파일들이 있었다. 셋 다 *"이 벤더는 그것을 안 준다"* 로 적힐 뻔했다.

    | 원문 | 벤더가 준 모양 | 앞 판이 읽던 것 | 드러난 것 |
    |---|---|---|---|
    | `*_error.hpp` 넷 | `UT_DECL_ERR(NAME, 7303, "...")` 매크로 | `const` 줄만 | **에러 코드 11개** — `LOCO_ERR_{INVALID_FSM_ID, INVALID_TASK_ID, LOCOSTATE_NOT_AVAILABLE}` · `ARM_ACTION_ERR_{INVALID_ACTION_ID, HOLDING, ARMSDK, INVALID_FSM_ID}` · `G1_AGV_ERR_{NOT_INIT, EXEC_MOVE, EXEC_HEIGHT_ADJUST}` · `AUDIO_ERR_COMM` |
    | `terminations.hpp` | `inline bool bad_orientation(...)` 함수 | 상수·클래스만 | **종료 조건 7개를 코드로 준다** — `lost_connection`·`low_battery`·`bad_orientation`·`joint_vel_out_of_limit`·`ang_vel_out_of_limit`, 그리고 과열을 `motor_casing_overheat`/`motor_winding_overheat` 둘로 가른다 |
    | Cyclone DDS C++ IDL 셋 | `class SportModeState_ { … int32_t task_id_; }` | 파이썬 생성본만 | **`SportModeState_{fsm_id, fsm_mode, task_id, task_time}`** — 무엇이 도는지를 토픽으로 발행한다 |

    셋 다 결함 주입으로 확인했다 — 매크로 하나를 깨뜨리면 141→140, 필드 하나를 깨뜨리면 161→160, 클래스 줄을 깨뜨리면 161→156. **주입이 없었으면 "읽었다" 와 "0 개가 맞다" 를 구분할 길이 없었다.**

    > **추출기의 침묵을 벤더의 부재로 읽지 말 것.** `unitree_symbols.py` 의 머리말이 이미 한 번 그 실수를 적어 두고 있었고(`Jsonize*` 키), 그 문단이 있는 파일에 같은 실수가 셋 더 있었다. 침묵이 나오면 벤더를 의심하기 전에 추출기부터 의심하고, 판정은 주입으로 잠근 뒤에 적는다. `tools/vendor-manifest/README.md` 의 *"이름을 빠뜨리면 시험이 빨개진다"* 는 **누군가 그 이름을 짚고 있을 때만** 참이다 — 아무도 안 짚은 표면의 침묵은 아무것도 빨갛게 만들지 않는다. 오늘 셋이 정확히 그것이었다.

    ### 부재 판정은 벤더의 낱말로 다시 물어야 한다

    같은 날 다른 자리에서 같은 종류의 실수를 한 번 더 할 뻔했다. Digit 매니페스트에서 `nogo`·`no-go` 를 찾아 0 건이 나왔고 *"출입 금지 구역이 없다"* 고 적으려 했다. 벤더는 그것을 **`keep-out`** 이라 부른다. 우리 낱말로 안 나온 것은 부재가 아니라 **검색 실패**이며, 부재를 적으려면 벤더의 낱말로 한 번 더 묻고 나서다.

    ### 그래서 보인 것 — 그리고 판정이 어떻게 됐나

    - **G1 이 에러 어휘를 갖고 있다.** 호출의 반환값으로 11개. 조사 문서(`profile/vendors/unitree-g1.json`)의 `failure_modes: NONE` 은 *"결함 목록을 발행하지 않는다"* 로는 여전히 참이지만 *"결함을 안 알린다"* 로는 틀렸다 — `PARTIAL` 로 고쳤다. 이 11개는 §15.79 가 합쳐진 **결과 어휘 정준화**의 세 번째 입력이다(Spot 18값 · Digit 상태 · G1 11개).
    - **`arm_action` 에 `GET_ACTION_LIST` 와 `STOP_CUSTOM_ACTION` 이 있다.** 능력 열거와 정지가 팔에는 있다. `vendor_layer: NONE` 은 유지하되 읽는 법이 바뀐다 — *기종에 태스크 개념이 없다* 가 아니라 *우리가 쓰는 표면(`loco`)에 없다* 다.
    - **`go2` 네임스페이스는 G1 것이 아니다.** `HeightMap_`·`VoxelMapCompressed_`·`LidarState_`·`UwbState_` 는 `unitree_go` 이고 `hg` 에 대응물이 없으며 G1 예제 열여덟 어디에도 안 쓰인다. 그러므로 *"G1 에 지도가 없다"* 는 범위 인공물이 아니라 표면의 사실이다 — 범위를 넓혔는데도 그대로인 판정이 하나 있다는 것이 이번 재측정의 반대쪽 결과다.

    ### 어댑터가 `SportModeState_` 를 듣는다 (`66bc2f0`)

    G1 어댑터는 성공을 **시계로** 적는다(`SetVelocity` 의 `duration` 이 근거). `TERMINAL_STATE_VIOLATED` 가 *"끝났다고 적었는데 계속 걷는"* 쪽 구멍을 막고 있었는데, 반대쪽 구멍이 열려 있었다 — **명령은 받아들여졌는데 로봇이 `damp` 나 `sit` 에 있어 아무 일도 안 일어나고, 시간만 지나면 성공이 되는** 경우. `SportModeState_.fsm_id` 가 그 관측이다. 태스크가 도는 동안 `fsm_id` 가 기대(`fsm.start`)와 다르면 `X_UNITREE_FSM_UNEXPECTED` 를 낸다. **막지는 않는다** — 어느 FSM 에서 속도 명령이 듣는지를 벤더가 열거해 주지 않으므로 *"못 움직인다"* 고 단정할 근거가 없고, 아는 것은 기대와 관측이 어긋났다는 사실뿐이다.

    두 가지를 정직하게 적어 둔다. ① **이 관측이 `lowstate` 뒤에 갇혀 있다** — 저수준 상태를 못 받으면 `faults()` 가 통째로 `NotObservable` 을 내므로 운동 상태만 오는 대상에서는 이 결함이 안 보인다. 채널 둘이 독립인데 관측 가능성을 하나로 접은 것이며, 고치려면 `FaultObservation` 이 부분 관측을 표현해야 한다. ② **결함 주입 셋 중 하나를 시험이 못 잡았다.** `running.state == RUNNING` 조건을 지워도 초록이었다 — 종착한 태스크에 결함을 내는 경우를 겨냥한 시험이 없었다. 시험을 하나 더 만들고(`종착한 태스크에는 FSM 결함을 안 낸다`) 다시 주입해 잡히는 것을 확인했다. 못 잡음은 시험 집합의 구멍이고, 그것을 찾는 것이 주입의 용도다.

83. **부재를 적기 전에 벤더의 1 차 표면을 다 세었는가 — 스펙 하나가 전부가 아니었다.**

    Orbit 측정 노트(`docs/vendors/orbit.md`)가 *"즉시 실행 경로가 없다 — 작업을 넣는 유일한 문이 캘린더 항목이다"* 라고 적었다. 게시 OpenAPI 35 경로를 **전수로** 읽고 적은 문장이라 범위 인공물이 아니라고 믿었다. 틀렸다. 벤더가 배포하는 공식 파이썬 클라이언트 `bosdyn-orbit` 에 `post_dispatch_mission_to_robot()` 이 있고, 그것이 스펙에 없는 `POST calendar/mission/dispatch/{nickname}` 을 친다. 몸통에 **walk 를 인라인**으로 실을 수 있어 *"파라미터 자리 없음"* 도 그 경로에서는 거짓이다.

    §15.82 가 세운 규율 둘(추출기의 침묵을 벤더의 부재로 읽지 말 것 · 부재 판정은 벤더의 낱말로 다시 물을 것)과 같은 뿌리이고, 셋째 문장이 필요하다 — **부재를 적으려면 벤더가 준 1 차 표면을 먼저 다 세어야 한다.** 스펙 · 산문 문서 · **클라이언트 코드** · 예제. Orbit 은 그중 셋째를 빼고 쟀다. G1 때는 서비스 넷 중 하나만, Spot 때는 서비스 54 중 셋만 — 세 번 다 *"범위 안에서 전수"* 였고 범위가 틀렸다.

    이 정정은 외부 대조에서 나왔다. 같은 날 별도로 작성된 하류 API 조사 문서가 그 경로를 짚고 있었고, 벤더 클라이언트 원문(파일 해시는 측정 노트에)으로 확인했다. **독립적으로 잰 둘이 어긋나는 자리가 가장 싸게 틀림을 찾는 자리다** — 그 문서가 틀린 곳(Digit 의 JSON API 를 "공개 문서 없음" 으로 적음)도 같은 대조에서 보였다.

84. **시나리오 셋을 들였다 — 그리고 AMR 이 그 안에 있다. 경계를 그어서.**

    저장소 밖에서 별도로 작성된 설계 문서 셋(팩트체크 · 미들웨어 설계 보고서 · 하류 API 조사)과 대조해 일감 셋을 [`docs/scenarios.md`](../../scenarios.md) 로 들였다 — ① 용기 공급(AMR) · ② 부품 시퀀싱(휴머노이드, `pick_place`) · ③ 설비 점검(4족, `inspect`). 들인 것은 시나리오의 뼈대와 상황표이고, 근거는 ISA-95 Job Control 의 타입과 벤더 1 차 자료(전부 매니페스트와 대조됨)만이다. 외부 문서가 붙인 층 ② 의 이름들은 들이지 않았다(ADR 36).

    ### AMR 이 있는 이유와 경계

    **AMR 은 이 저장소의 주력이 아니다.** 그런데 시퀀싱 셀에 부품이 도착하는 경로를 빼면 ② 가 허공에 뜬다. 현실적인 시나리오에서 빠질 수 없으면 넣되 경계를 긋는다 — 규칙 셋. (1) picasso 는 AMR 을 어댑터로 감싸지 않는다 — 그 자리는 표준과 관제가 이미 채웠고, ADR 36 의 발명 금지는 *이미 있는 것을 다시 만들지 않는다* 로도 읽힌다. (2) AMR 구간의 결과가 들어오는 문은 둘뿐 — ② 의 **환경 전제**(§15.81, *"용기가 그 자리에 있다"*)이거나 층 ③ 이 읽는 **인계 설비 신호**. (3) 휴머노이드 일감의 파라미터에 AMR 식별자가 오지 않는다 — 장소의 이름과 대상의 이름뿐(§15.78). 이 셋을 지키면 시나리오에 AMR 이 있어도 계약과 어댑터에는 그 낱말이 없고, 지금 저장소가 그 상태다.

    ### 들이면서 보인 것

    - **완료 근거 등급(E0 로봇 보고 / E1 플릿 확인 / E2 독립 설비 / E3 업무)이 ADR 37 과 맞물린다.** E0 은 어댑터가 로봇에 직결된 경우이고 E1 은 플릿에 붙은 경우다. 계약은 지금 E0/E1 까지를 나르되 **둘 중 어느 것인지 결과에 싣지 않는다.** §15.81 이 *"현장이 보증하는 셋째 쪽이 계약 어디에도 없다"* 고 적은 그 자리의 이름이 E2 이고, 그것은 **계약 밖이 맞다** — 층 ③ 이 로봇 보고와 시간창 안에서 결합한다.
    - **외부 문서의 검증 시나리오 열 개 중 여섯을 `mimic` 위에서 이미 증명하고 셋은 의도적으로 계약 밖이며 하나만 반쯤 답한다** — 부분 완료 후 취소에서 **중단점과 잔여 파지 상태**가 없다. `partial_result` 자유 문자열뿐이다.
    - **접수 여부 불명은 계약 층에서 답한다.** 벤더 셋 다 클라이언트 참조 키를 안 받지만(Spot 은 `CaptureActionId` 만 예외) 같은 `(task_id, revision)` 재전송이 같은 핸들을 돌려주므로, 그 매핑을 드는 것이 어댑터의 일이 된다. 다만 어댑터가 재시작하면 메모리의 매핑이 사라지고, 그것은 §1.3 B-1 비목표와 맞닿는다.
    - **어댑터 가용성이 로봇 거동이다** — Spot `KeepaliveService.Policy.ActionAfter{AutoReturn, ControlledMotorsOff, ImmediateRobotOff, LeaseStale}`, E-Stop `timeout` → `SETTLE_THEN_CUT`, `cut_power_timeout` → CUT(전부 매니페스트에 있다). ADR 32 는 유지되지만 배치의 전제로 적어 둔다.

    ### 후보 다섯 — 결정이 아니다

    ① 도달 근거 등급을 결과에 · ② 취소·실패 시 잔여 물리 상태(발신자 있음 — Spot `is_gripper_holding_item`·`CarryState`, G1 `press_sensor_state`, Digit `get-execution-state`) · ③ `RUNNING` 갱신이 물리 상태를 만날 때(부품을 든 채 `Halt → Reset → Start`) · ④ 이벤트 쪽 옛 revision 규칙 · ⑤ 어댑터 재시작 후 매핑. ②는 발신자가 있어 ADR 9 를 통과하므로 가장 먼저 열 수 있고, ①과 함께 **결과 어휘 정준화**의 일부다. 계약에 넣지 않은 이유는 ADR 9 다. **→ ②는 §15.85에서 열었다**(`WatchTaskResponse.hold`, 0.4.0).

85. **잔여 물리 상태를 계약에 열었다 — 그리고 "취소가 내려놓기가 아니다"가 두 어댑터에서 드러났다.**

    §15.84 후보 ②. `WatchTaskResponse.hold`(`HoldState`, 계약 0.4.0)이 갱신마다 로봇이 무엇을 들고 있는지를 나른다. 발신자 넷(mimic · Spot · Digit · G1)과 소비자(하네스 `HoldOnCancelTest`, 완료 기준 8b의 나머지 반)가 함께 생겼으므로 ADR 9를 통과한다. 불변식은 §4.4에 있다 — `CANCELLED`와 `HOLDING`은 함께 오지 않는다.

    ### 발신자마다 답이 다르고, 그 차이를 값으로 남긴다

    | 발신자 | 근거 | 답 |
    |---|---|---|
    | Spot | `ManipulatorState.is_gripper_holding_item` — 벤더가 불리언을 준다. 세 기종 중 유일하다 | `HOLDING`/`EMPTY`. 팔 없음(`manipulator_state` 비어 있음)은 쥘 것이 없으니 `EMPTY`, 읽기 실패는 `NOT_OBSERVABLE`. `object_ref`는 늘 비어 있다 — 대상의 이름을 받는 스킬을 안 든다 |
    | Digit | `get-execution-state`의 노드 상태 — 벤더는 파지를 **발행하지 않는다**(SDK 메시지 전수) | **추론**: 최근 `action-pick`이 `success`이고 뒤에 `success`인 `action-place`가 없으면 `HOLDING`(지금 태스크가 `pick_place`면 그 `object_id`). 트리에 `action-pick`이 없으면 `NOT_OBSERVABLE` — 빈손이 아니다. 이전 시퀀스가 놓기에 실패한 채 트리가 비워졌을 수 있고 그것을 볼 표면이 없다 |
    | G1 | 없음 — `HandState_.press_sensor_state`는 원시 압력값 | 언제나 `NOT_OBSERVABLE`. 문턱을 우리가 정해 불리언으로 만들면 로봇의 답처럼 보이는 우리의 짐작이다(§15.65) |
    | mimic | 계약의 `grasps_object`(쥐는가)와 `is_object_reference`(든 것의 이름) — 생성 디스크립터에서 읽는다(`ObjectReferences`). 스킬 이름은 미믹 어디에도 없다. ~~대상의 이름을 받는 스킬~~ → 쥐는 스킬(§15.87 정정) | 쥐는 스킬이 `RUNNING`·`PAUSED`·`CANCELLING`·`CANCELLED_RECOVERY_FAILED`면 `HOLDING`, `ACCEPTED`·`SUCCEEDED`·`CANCELLED`면 `EMPTY`, 실패 셋은 직전 값 유지(실패가 물건을 내려놓지는 않는다), `PAYLOAD_LOST`가 서면 `EMPTY` |

    ### 열면서 드러난 것 — 멈추는 것과 내려놓는 것은 다르다

    Spot의 `StopMission`·`StopCommand`와 Digit의 `remove-action`은 **멈추는** 프리미티브이지 내려놓는 프리미티브가 아니다. 앞 판의 두 어댑터는 멈춤이 성공하면 `CANCELLED`를 적었다 — 계약이 `CANCELLED`에 건 뜻(복구까지 마쳤다)을 확인하지 않고. 이제 멈춘 뒤 `hold()`를 보고 들고 있으면 `CANCELLED_RECOVERY_FAILED`를 적는다. 못 봤으면 `CANCELLED`로 적되 `NOT_OBSERVABLE`을 함께 나른다 — **모름을 실패로 접지 않는다**(§15.65의 다른 얼굴). G1은 볼 수 없으므로 언제나 뒤의 경우다.

    ### 단순화와 미결

    - mimic은 도는 동안 **내내** 든 것으로 친다. 대상까지 걸어가는 구간을 프로파일이 선언하지 않고, 우리가 정하면 그것이 관측처럼 보인다. 불변식은 이 단순화 아래에서도 성립하며 소비자가 기대는 것은 그것뿐이다.
    - `RUNNING` 갱신(`Halt → Reset → Start`)도 든 채로 지난다 — 부품을 든 채의 `Reset`이 무엇인지는 §15.84 후보 ③ 그대로 열려 있다. 달라진 것은 그 질문을 **값으로** 볼 수 있게 됐다는 것뿐이다.
    - 스냅샷(`GetSnapshot`)에는 넣지 않았다. 잔여 상태가 필요한 자리는 종착이고 태스크 로그는 축출이 없어 `WatchTask(from_update_index=0)`로 되짚을 수 있다. 중간에 들어온 소비자가 *지금* 들고 있는지를 스냅샷 하나로 알아야 하는 날 다시 본다.
    - 후보 ①(도달 근거 등급)은 그대로다. `kind`는 E0/E1을 가르지 않는다 — Spot 직결의 `HOLDING`과 플릿 경유의 `HOLDING`이 같은 값이다.
    - Digit의 추론은 **근거 등급이 한 단계 낮다.** `action-pick` 성공이 곧 쥐고 있음이라는 것은 벤더 매뉴얼의 액션 의미에서 온 것이지 파지 센서에서 온 것이 아니다. 실물이 오면 `press`·`end-effector` 계열 표면에 묻는 것이 먼저다.

86. **시나리오 ②가 하네스 시험이 됐다 — 그리고 "모든 RPC가 tick을 부른다"가 처음으로 시험을 물었다.**

    `docs/scenarios.md` §4의 부품 시퀀싱을 `SequencingCellTest`(harness)로 옮겼다. 슬롯 넷 = `pick_place` 태스크 넷, 생산 순서 버전 = `revision`(17 → 18), 기체 하나라 차례로. 상황표에서 계약이 답하는 것을 전부 단언한다 — 완료 슬롯 보존(같은 revision 재발행은 같은 핸들, 새 태스크 없음) · 버전 18 세 갈래(종착 = `INVALID_TRANSITION`, 미시작 = 파라미터만 교체하고 `ACCEPTED` 유지·돌면 새 대상을 든다, 도는 중 = 다시 서며 진행률 0·`hold` 유지) · S03 파지 중 취소(복구 = 빈손, 같은 `task_id`의 v18은 거절, 재작업은 새 `task_id`) · 든 채 단절(같은 요청이 같은 핸들, `WatchTask(0)` 되짚기에 `HOLDING`, 복구 뒤 이어서 완주). 프로파일은 `CancellablePickPlace` — 픽스처의 `pick_place`가 취소 불가라 값 하나만 바꾼 사본이고, 그 값이 하나뿐임을 확인한다.

    ### 시험이 물어본 것 둘

    - **`ACCEPTED`에 머물게 할 수가 없었다.** §10.4의 *"모든 RPC가 tick을 부른다"*가 `WatchTask` 진입과 `DumpInternalState`에도 걸려, 접수한 태스크를 들여다보는 순간 `RUNNING`이 된다. *"미시작 슬롯은 파라미터만 교체"*를 보려면 §10.5의 단일 스텝(`SetSingleStep`)을 켜야 했다. 관측이 상태를 바꾸지 않는다는 §12.1의 규율은 **소요시간**에 대해서만 지켜지고 있고, 전이의 시점은 관측이 당긴다 — 결정적이라 문제는 아니지만 시험을 쓰는 사람이 알아야 한다.
    - **갱신 자체가 로그 한 줄이다.** `RUNNING` 갱신은 `Halt → Reset → Start`를 한 갱신 안에서 마치고 로그에 `RUNNING` 한 줄을 더 적는다(ACCEPTED · RUNNING · 다시 선 RUNNING). 시간이 더 흘러야 나오는 것이 아니다.

    ### 시험이 고정하지 않고 드러내는 것

    도는 슬롯의 버전 갱신(§15.84 후보 ③). 계약은 지금 든 채로 다시 세운다 — 진행률은 0부터, `hold`는 `HOLDING` 그대로. 그것이 맞는지는 열려 있고, 이 시험은 그 거동이 **조용히 바뀌지 않게** 붙들 뿐이다. 바꾸는 날 이 시험의 그 단언을 함께 고쳐야 하며, 그것이 의도다.

87. **시나리오 ③이 하네스 시험이 됐다 — 그리고 "참조하는 것과 쥐는 것은 다르다"를 첫 시험이 잡았다.**

    `docs/scenarios.md` §5의 설비 점검 순회를 `InspectionPatrolTest`(harness, 4족 픽스처 `quadruped-b`)로 옮겼다. 지점 셋 = `navigate_to` + `inspect` 여섯. 점검 일시정지·재개, 위치 상실 뒤 사람 개입과 재시도, 점검 취소, 그리고 **점검 결과를 실을 자리가 없다는 사실**(§15.76 — `partial_result` 문자열뿐, 아무도 안 채운다)을 `LongRunningTaskTest`와 같은 방식으로 고정했다.

    ### 첫 시험이 빨갰다 — 점검 중인 로봇이 대상을 든 채로 보고됐다

    §15.85의 미믹 규칙이 *"대상의 이름을 받는 스킬은 도는 동안 든 채"* 였다. `inspect(target)`의 `target`은 `is_object_reference`이므로 그 규칙대로면 점검 중인 4족이 펌프를 들고 있다. **참조와 쥠을 접은 것이다.** 카탈로그에 `grasps_object`(MessageOptions, 계약 0.5.0)를 두고 `PickPlaceV1`에만 켰다. 쥐는지는 계약이 말하고, 든 것의 이름은 여전히 `is_object_reference` 파라미터에서 온다. 미믹은 스킬 이름을 여전히 모른다.

    이것은 §15.78의 공간 셋과 같은 종류의 실수다 — 이름 공간 하나(`is_object_reference`)에 뜻 둘(참조/쥠)을 얹었다. 구분할 수 있으면 구분한다. 그리고 §15.84 후보 ②를 열 때 미믹 시험을 `pick_place`로만 써서 놓쳤다 — **한 스킬로 만든 규칙은 다른 스킬에 대 봐야 규칙이다.**

    ### 시험이 물어본 것

    - **점검 결과.** 시나리오 ③의 결과는 *항목별 수행 상태와 측정값 또는 증거 자료 참조*인데 계약에는 그 자리가 없다. Spot에서는 `DataAcquisition`의 `CaptureActionId`가 결속 자리이고(§15.75) Orbit에서는 `RunCapture`인데, 계약이 그것을 위로 올릴 길은 `partial_result` 문자열 하나다. **결과 어휘 정준화(후보 ①과 함께)의 범위에 "관측을 올리는 자리"가 들어간다.**
    - **결함은 인터록이 아니다.** 기체 수준 `LOCALIZATION_LOST`(`can_accept_new_task=false`)가 서도 계약은 다음 지점의 `navigate_to`를 받는다 — §4.6 *"판단은 밖으로, 사실은 안으로"*. 시나리오 ③이 점검 결과를 안전 인터록으로 쓰지 않는다고 그은 경계(ADR 32)가 계약 거동으로 확인된다.
    - **소비자가 점검 지점의 등록을 물을 수 없다.** 순회의 `location`들은 사이트가 로봇에 등록한 이름이어야 하는데(ADR 35), `client`에 `GetKnownSiteNames`를 부르는 메서드가 없다 — 레지스트리만 든다. 얇은 소비자가 그것을 들어야 하는지는 정하지 않았다. 지금은 시험에서 뺐다.

88. **미들웨어의 가운데가 생겼다 — 모듈 `picasso`, 그리고 "층 ③은 없다"가 끝났다.**

    ADR 38 과 [미들웨어 중앙 설계](2026-09-09-middleware-core-design.md)에 따라 모듈 `picasso` 를 세우고 시나리오 ②를 **미들웨어 층**에서 돌렸다(`SequencingRackTest` 여섯). 상류(예상 소비자 = 시험)가 ISA-95 모양의 JobOrder 를 넣으면 `PrepareSequencedRack` 이 슬롯마다 `pick_place` 로 조합하고, `Middleware` 가 계약(④)으로 mimic 에 돌리며, 셀 검증 장치(PLC/WCS Mimic)의 신호와 결합해 근거 등급을 매기고, JobResponse 를 아웃박스에 낸다. 두 축(`physical_state` × `upstream_ack`)이 코드가 됐다.

    ### 이 층이 생기고 나서야 답이 된 것

    - **부분 완료.** C형 부족은 계획 단계에서 드러난다(`NO_SOURCE_FOR_MATERIAL`) — 완료 슬롯은 보존되고 실행은 `PARTIAL` 로 통보되며, v18 이 공급을 채우면 **그 슬롯만** 다시 계획된다. 계약 층에서는 "슬롯마다 태스크 하나" 까지였고 그것을 모아 말하는 자리가 없었다(§15.86).
    - **근거 미달의 완료.** 로봇은 `SUCCEEDED` 인데 설비가 말이 없으면 `UNVERIFIED` 다 — 재작업이 아니라 운영자 확인. 설비가 다른 자재를 봤으면 `VERIFICATION_MISMATCH` 로 `FAILED`(보고서 12.3). 계약 층에서 "답하지 않는 것이 맞다" 고 적었던 E2 가 여기서 답이 된다.
    - **결과 통보와 그 확정.** JobResponse 는 아웃박스에 남고 `ack` 가 둘째 축을 닫는다. 통보 재전송이 명령 재전송이 아니라는 것(보고서 13.3)이 구조로 갈렸다.
    - **취소 응답.** 완료 단위·진행 중 단위·미시작 단위·잔여 `hold`·정리 결과. 미시작 단위는 자동으로 돌지 않는다.

    ### 정직하게 적어 둘 것

    - 근거 결합에 **시간창 δ 가 없다.** 지금은 "묻는 순간의 신호" 다. 보고서 12 의 시간창과 래치 비트는 계획의 단계 3 이다.
    - **어댑터가 아직 벤더 코드를 정준 분류로 안 옮기고**, mimic 은 `WatchTaskResponse.fault` 를 안 채운다. 그래서 단위의 `failureClass` 는 지금 상태 이름으로 남는다 — 지어내지 않고 비워 둔 것이며, 단계 5 다.
    - **IN_DOUBT 는 열거값만 있다.** 해소 순서(13.2)의 집행은 단계 6.
    - 구동이 당기기(`pump()`)라 시험은 비동기 팔로워를 잠깐 기다린다. 결정성은 지켰지만(가상 시계) 시험 코드에 `sleep(40ms)` 이 있다 — 미믹의 tick 과 같은 종류의 관측 구동이며, 운영 배치에서는 스케줄러가 그 자리다.
    - 결함 주입 다섯(불일치를 일치로 · 신호 없음을 완료로 · 완료 슬롯 재계획 · 취소 뒤 계속 · 통보 누락) 전부 잡혔다.

89. **시나리오 ①이 미들웨어 층에서 돈다 — 하류가 둘이 됐고, 둘을 가르는 것은 기종이 아니라 종류다.**

    `DeliverContainer` 와 두 번째 하류 포트 `AmrFleetPort` 를 더했다(계획 단계 4). 운반 전체를 **플릿에 D 수준으로 위임**하고 결과만 받는다 — 보고서 5장이 *"Fleet 시스템이 용기 운반 전체를 제공하면 하나의 작업으로 위임한다"* 고 적은 그 배치다. `AmrFleetMimic` 은 **프로젝트용 계약의 더블**이지 벤더 API 의 재현이 아니며(`scenarios.md` §1 규칙 1 — 벤더 AMR API·VDA 5050 은 구현하지 않는다), 세계 하나(자리 → 용기, 막힌 목적지)를 들고 `tick` 마다 접수 → 인수 → 이동 → (대기) → 인계로 나아간다. 인계 설비(`CellMimic`)는 플릿이 실제로 내려놓은 것을 **본다** — 시험이 침묵시키기 전까지.

    ### 이 시나리오가 계약에 요구한 것

    - **단위에 `route` 가 생겼다** — `ROBOT`(계약 ④의 원자 스킬) / `FLEET`(D 수준 위임). 미들웨어가 하류의 **종류**로 갈리는 것은 기종 분기가 아니다. 모듈에 기종 이름은 여전히 없고 게이트 7번이 그것을 지킨다.
    - **플릿의 완료는 E1 이다.** `DELIVERED` 의 뜻을 플릿 계약이 정한다 — 도착 ∧ 하역 ∧ 인수 ∧ **AMR 이 계속 보유하지 않음**. 그것이 보고서 10.2 의 *"Fleet C처럼 하류의 완료 조건이 계약과 일치하면 그대로 근거로 쓴다"* 이고, 그래서 요구가 E1 이면 설비를 묻지 않는다. 그 용기가 **그** 용기인지는 E2 다.
    - **플릿 계약은 클라이언트 참조를 받는다.** 같은 참조로 다시 맡기면 같은 운반이다 — 조사한 실물 하류 셋이 하나도 안 주는 것(보고서 13.2)을 우리 계약에는 넣었다. 접수 직후 단절의 `IN_DOUBT` 가 이것으로 풀린다(단계 6).
    - **`SlotSignal.material` 을 `identity` 로 고쳤다.** 설비가 읽는 것은 부품 타입 라벨이기도 하고 용기 태그이기도 하다 — §15.80 의 신원 수단 그대로다.

    ### 상황표 셋이 답이 됐다 (보고서 5장)

    | 상황 | 미들웨어 |
    |---|---|
    | 목적지에 이전 용기가 남아 있음 | 플릿 `WAITING_HANDOVER` → 단위 `note`, 실행은 `RUNNING`(실패가 아니다), **지연 보고 한 번**. 기다리는 동안 아무 데도 내려놓지 않는다. 목적지가 비면 이어서 인계 |
    | 출발 위치의 용기 ID 가 요청과 다름 | 플릿이 **접수 시점에** `REJECTED_AT_SOURCE` — 인수하지 않는다. 단위 `FAILED` `SOURCE_CONTAINER_MISMATCH`, 관측한 태그를 `note` 에, 운영자 필요. 출발지는 그대로다 |
    | 물리적 인계 뒤 WMS 응답 유실 | 같은 요청 재발행은 멱등이고 운반은 다시 맡기지 않는다(`dispatches` 1). 같은 통보가 아웃박스에 그대로 있다 — 그것을 다시 건네는 것이 통보 재시도다(보고서 13.3) |

    ### 정직하게 적어 둘 것

    - 실행 종착 규칙이 하나 늘었다 — 단위가 **전부** 실패면 `FAILED`, 일부면 `PARTIAL`. 앞 판은 단위 하나짜리 실행이 실패해도 `PARTIAL` 이었다.
    - 플릿에 맡긴 운반은 **도중에 갱신하지 않는다** — 플릿 계약에 갱신이 없다. 끝난 뒤 새 주문이다. 계약 ④의 `RUNNING` 갱신과 다른 점이며 그것을 코드에 적었다.
    - 취소의 정리 동작(출발지로 되돌림)은 **이 플릿 계약이 정한 것**이다. 실물 플릿이 그렇게 한다는 근거는 없다 — Mock 구간이다.
    - 결함 주입 다섯(대기를 완료로 · 불일치를 중단으로 · E2 요구를 E1 로 · 취소 미전달 · 지연 보고 반복) 결과는 아래 커밋에.

90. **근거 결합에 시간이 들어갔다 — 시간창 δ, PLC 래치, 불일치 표 12.3 의 세 행.**

    앞 판(§15.88)의 정직 항목 첫째가 *"시간창 δ 없음 — 묻는 순간의 신호"* 였다. 그것을 닫았다(계획 단계 3). 보고서 12.1 의 식 그대로다 — `physically_done := robot_report(FINISHED, t_r) ∧ plc_signal(present, tag, t_p) ∧ t_p ∈ [t_r − δ_before, t_r + δ_after]`. `Middleware` 는 시각을 `now: () -> Instant` 로만 읽고(시험은 하네스의 가상 시계를 넘긴다), 능력이 `EvidenceWindow(before, after)` 기본값을 든다 — **현장별 설정**이지 상수가 아니다. 하류 보고 시각 `t_r` 은 계약 헤더의 `state_as_of`(§5.5) 에서 읽고 못 읽으면 지금이다. 설비 신호는 `SlotSignal.observedAt` 을 얻었고, 시각을 안 주는 설비(폴링 PLC)는 **읽은 순간**이 `t_p` 다.

    ### 단위 상태가 둘 늘었다

    - **`VERIFYING`** — 하류는 끝났다는데 설비는 아직 말이 없다. 창이 닫힐 때(`t_r + after`)까지 `pump()` 마다 다시 묻고(`rechecks`), 그 안에 들어온 신호는 **보고보다 늦게 왔어도** 이 완료의 근거다. 앞 판은 한 번 묻고 `UNVERIFIED` 였다 — 폴링 지연 몇 초를 오인계와 같은 무게로 다룬 셈이다.
    - **`OPERATOR_HOLD`** — 12.3 둘째 행. 하류는 실패라는데 설비에는 기대한 것이 창 안에 있다. `FAILED` 로 적지 않고 세운다. 자동으로 다시 돌리지도 않는다(*물리 완료 가능성*). 운영자가 `resolve(CONFIRM_DONE | REWORK)` 를 내면 각각 E2 완료 / **새 정체성**(`attempt` 가 오르고 `task_id` 에 `@r1` 이 붙는다 — 같은 `task_id` 는 계약이 같은 종착 핸들로 돌려주므로 정체성이 갈려야 다시 돈다)으로 간다. 실행 상태 `OPERATOR_HOLD` 는 이때 상류에 한 번 통보된다.

    ### 12.3 의 네 행 중 셋

    | 하류 | 설비 | 앞 판 | 이 판 |
    |---|---|---|---|
    | 완료 | 없음 | 즉시 `UNVERIFIED` | 창이 닫힐 때까지 재확인 후 `UNVERIFIED`, 몇 번 물었는지 남김. 재작업 금지, 운영자 |
    | 실패 | 있음(창 안, 태그 일치) | `FAILED` | **`OPERATOR_HOLD`** → 운영자 판단 |
    | 무응답 | 있음 | — | **단계 6**(`IN_DOUBT`, 실행 조회로 확정) |
    | 완료 | 태그 불일치 | `FAILED` `VERIFICATION_MISMATCH` | 같음 + `note` 에 **무엇을 어디서** 봤는지(`observed=A at RACK-204.S02`) — 잘못 놓인 것의 위치 기록 |

    ### 12.2 가 왜 래치를 요구하라고 했는지를 재현했다

    `CellMimic` 에 신호 모양이 셋이다 — `program`(현재값, 읽는 순간이 `t_p`) · `pulse(at, lasting)`(짧게 켜졌다 꺼짐) · `latch(location)`(PLC 쪽 래치 비트 — 지나간 펄스가 **시각과 함께** 남음). 5초 폴링에 1초 펄스는 **놓친다** → `UNVERIFIED`. 같은 펄스에 래치를 두면 잡고, 근거의 시각은 폴링 시각이 아니라 **펄스의 시각**이다. 그리고 창 앞쪽 밖의 옛 신호(10분 전부터 있던 이전 부품)는 이 완료의 근거가 아니다 — `stale` 로 적고 `UNVERIFIED`. 이 셋이 시험(`EvidenceWindowTest` 8)이고, 결함 주입 여섯(앞쪽 경계 제거 · 마감 없음 · 실패 시 설비 안 물음 · 한 번만 물음 · 재작업 정체성 유지 · 불일치 위치 누락) 전부 **겨냥한 시험이** 잡았다.

    ### 정직하게 적어 둘 것

    - **`failureClass` 가 아직 상태 이름이다**(`TASK_STATE_RETRIABLE`) — 미믹이 `WatchTaskResponse.fault` 를 안 실어서다. 16장이 감추라는 하류 상태 이름이 지금은 새고 있고, 단계 5 가 정준 분류 15개로 바꾼다. 시험이 그 사실을 그대로 단언한다 — 바뀌는 날 빨개지게.
    - 실패 시 설비 확인은 **요구 등급이 E2 이상일 때만** 묻는다. E0·E1 요구에서 로봇이 실패라 하면 설비가 무엇을 보든 `FAILED` 다 — 상류가 설비를 안 믿기로 한 것이니 우리가 대신 믿지 않는다.
    - 시간창 안에서 폴링을 잘게 하려고 시험의 걸음이 5초다(앞 판 30초). 플릿 시험은 틱 하나를 10초로 친다. 둘 다 시험의 약속이지 설비의 값이 아니다.
    - `t_r` 을 헤더의 `state_as_of` 에서 읽는 것은 미믹이 그 자리에 응답 시각을 넣기 때문에 성립한다. 실물 어댑터가 무엇을 넣는지는 어댑터마다 확인할 일이다(§5.5).

91. **정준 실패 분류가 계약을 탄다 — 어댑터가 벤더 코드를 옮기고, 상류는 분류로만 분기한다(계약 0.6.0).**

    정준 모델의 넷(태스크·상태·**실패 분류**·능력 표현) 중 실패 분류가 마지막까지 비어 있었다. `Fault.error_type` 은 실패 *모드*의 이름(프로파일이 선언, 수명과 두 불리언, `X_` 벤더 이름공간)이지 *상류에 무슨 뜻인가* 가 아니었고, README 가 *"가장 큰 남은 공백은 결과 어휘다"* 라고 적고 있었다. 미들웨어 중앙 설계 §1.4 의 열다섯(2026-09-09 결정, 그대로)을 계약에 넣었다(계획 단계 5).

    ### 계약 — 두 필드, 한 열거

    `FailureClass` 열다섯 + `UNSPECIFIED`, `Fault.failure_class`, `Fault.vendor_detail`. **모드와 분류는 다른 것이라 두 필드다** — `LOCALIZATION_LOST`·`PAYLOAD_LOST`·`CONTROL_AUTHORITY_LOST` 셋은 이름이 같고 같은 뜻이며 미믹은 그 모드에서 분류를 유도한다. 원문(코드·상태 이름·메시지)은 `vendor_detail` 에 **동반**한다 — 로그와 사후 분석의 것이지 분기의 입력이 아니다(보고서 16장). ADR 9: 발신자 = 어댑터 셋 + 미믹, 소비자 = `picasso` 의 `ExecutionUnit.failureClass`. buf 파괴 검사는 추가라 통과.

    ### 옮기는 자리는 어댑터다 — 기종마다 코드가 **나오는 자리**가 달랐다

    | 기종 | 코드가 나오는 자리 | 새 남쪽 표면 | 옮김 |
    |---|---|---|---|
    | Spot | 미션 `State.status` 는 `FAILURE` 까지만 — **이유는 항법 서비스에 있다** | `GraphLayer.navigationFeedback()`(`NavigationFeedbackResponse.Status` 열넷) · `SpotLink.behaviorFaults()`(`BehaviorFault.Cause` 넷) | `STUCK`·`AREA_CALLBACK_ERROR`·`CONSTRAINT_FAULT` → `ROUTE_BLOCKED` · `NO_ROUTE` · `LOST`·`NO_LOCALIZATION`·`NOT_LOCALIZED_TO_ROUTE` → `LOCALIZATION_LOST` · `LEASE_ERROR` → `CONTROL_AUTHORITY_LOST` · `COMMAND_TIMED_OUT`/`OVERRIDDEN` · `ROBOT_IMPAIRED` → `HARDWARE_FAULT` · `CAUSE_FALL` → `ROBOT_FELL` · `CAUSE_HARDWARE` → `HARDWARE_FAULT` |
    | Digit | `action-status.failure` 하나 — 이유는 사람이 읽는 `info` 뿐 | (기존) 실행 트리 | **액션 종류로 가를 수 있는 데까지만** — `pick_place` 는 실패한 마디가 `action-pick` 이면 `GRASP_FAILED`, `action-place` 면 `PLACE_FAILED`; 이동은 벤더의 정의(*blocked from making progress*) 그대로 `ROUTE_BLOCKED`; 트리를 못 읽으면 `UNCLASSIFIED` |
    | G1 | 태스크는 시계로 성공한다 — 코드는 **보낼 때**만 나온다 | `UnitreeError`(`UT_DECL_ERR` 열하나) · `UnitreeApiException` | 종착이 아니라 **거절**에 붙는다: `Refusal.VENDOR_REJECTED` + `Refused.failureClass`. `LOCO_ERR_INVALID_FSM_ID`·`LOCOSTATE_NOT_AVAILABLE`·`ARM_ACTION_ERR_INVALID_FSM_ID`·`ARM_ACTION_ERR_HOLDING`·`G1_AGV_ERR_NOT_INIT` → `PRECONDITION_FAILED`, 그 밖 → `UNCLASSIFIED`. 기체 결함은 과열 → `HARDWARE_FAULT`, FSM 불일치 → `PRECONDITION_FAILED` |
    | 미믹 | 프로파일 모드 | 스키마에 `failure_class` 선택 필드 | 선언이 이긴다 · 이름이 같은 코어 셋만 유도 · 나머지 `UNCLASSIFIED`(스킬 이름으로 추측하지 않는다). 태스크를 실패로 보낸 결함을 `TaskRuntime.failure` 에 붙여 **실패 상태의 갱신에만** 실으므로 `WatchTaskResponse.fault` 가 채워진다 |

    새 남쪽 타입 셋(`NavigationStatus`·`BehaviorFaultCause`·`UnitreeError`)은 매니페스트 시험 목록에 들어갔다 — 짚은 이름 전부 원문에 있고 멤버마다 짚은 것이 있다.

    ### 미들웨어

    `Middleware` 는 `fault.failure_class` 로만 분기한다. 없으면 `UNCLASSIFIED` 이지 상태 이름이 아니다 — 앞 판(§15.90 정직 항목)이 새고 있던 `TASK_STATE_RETRIABLE` 이 상류 통보에서 사라졌다. 하류 상태 이름·모드 이름·벤더 원문은 단위의 `note` 에만 남는다(`downstream=TASK_STATE_RETRIABLE error_type=SKILL_EXECUTION_FAILED`). `EvidenceWindowTest` 가 그 둘을 나눠 단언한다.

    ### 정직하게 적어 둘 것

    - **거동은 여전히 안 봤다.** 매니페스트가 보증하는 것은 이름의 실재뿐이다(C-3). 특히 Spot `NavigationFeedback` 의 `command_id` 를 비우면 최근 명령의 것이라는 진술은 벤더 proto 주석에서 온 것이고 실물에서 확인한 바 없다.
    - Spot 명령 계층(`move_relative`)의 실패는 여전히 관측하지 않는다 — 시계로 성공한다. `RobotCommandFeedbackStatus` 가 있으니 자리는 있고, 소비하는 스킬이 그 표면을 요구하는 날 연다(ADR 9).
    - `PERCEPTION_FAILED`·`GRASP_PLANNING_FAILED` 는 열거에 있으나 **지금 발신자가 없다** — Spot 의 `ManipulationFeedbackState` 는 `pick_place` 를 이 어댑터가 아직 안 들어 읽을 자리가 없고, Digit 은 대상 부재를 보낼 때 오류 봉투로 답한다(모양이 어디에도 안 적혀 있다). 열거에 둔 근거는 §1.4 의 측정(벤더가 그 값을 낸다)이지 우리 어댑터가 낸다는 것이 아니다. ADR 9 의 발신자는 벤더이고 소비자는 미들웨어이며, 어댑터 경로가 비어 있음을 여기 적는다.
    - 플릿 계약(`AmrFleetPort`)은 분류를 안 싣는다 — 프로젝트용 계약에 남은 자리이고 지금은 `UNCLASSIFIED` 다.
    - 결함 주입 아홉(분류 무시 · 로그 미부착 · 선언 무시 · Spot 둘 · Digit 둘 · G1 둘) 전부 겨냥한 시험이 잡았다 — 처음 두 번은 주입 자체가 안 돌았다(CRLF 를 가로지른 앵커, 컴파일 안 되는 주입). **못 잡음이면 주입부터 의심**이 이번에도 맞았다.

92. **결과 미확정의 해소 순서와 옛 버전의 종착 — 13.2 를 집행하고 15.1 을 보존한다.**

    계획 단계 6. 보고서 17장의 3(접수 여부 불명)·6(버전 변경 중 지연 이벤트)·9(단절 후 재동기화)이 이 층에서 단언된다. 둘 다 계약이 이미 **재료**를 주고 있었고 — 같은 `(task_id, revision)` 재전송은 같은 핸들(§4.4), 갱신마다 `revision` 을 싣고 되짚어 보내는 `WatchTask(0)` — 없던 것은 그것을 **순서와 규칙으로** 쓰는 자리였다. `scenarios.md` §4.5 가 *"이벤트 쪽 규칙은 적혀 있지 않다 — §7 후보 ④"* 라 적었던 그것이며, 답은 계약이 아니라 실행 층에 산다.

    ### `IN_DOUBT` — 요청은 갔고 답만 없다

    응답 유실은 미들웨어와 하류 사이의 선에서 난다. 미믹의 gRPC 에는 장애 주입이 없고(`InjectTransportFault` 는 발행 축) 그것이 맞다 — 하류는 정상 접수했다. 그래서 시험은 그 선의 더블(`LossyRobotPort`·`LossyFleet`)로 **위임한 뒤에 던진다.** 미들웨어는 다시 보내지 않고 단위를 `IN_DOUBT` 로 세우며 상류에 그 사실을 통보한다(16장 — `JobResponse.inDoubtUnits`). 해소는 13.2 의 순서 그대로다:

    | 순서 | 무엇 | 이 층에서 |
    |---|---|---|
    | ① 클라이언트 참조로 기존 실행 조회 | 하류가 `ExecutionLookup.CLIENT_REFERENCE` 를 선언했으면 **같은 참조로 다시 묻는다** — 계약은 같은 핸들, 플릿은 같은 운반을 돌려준다. 돌아오면 이어서 추적한다(새 실행이 아니다). 처음부터 되짚으므로 중간 전이가 유실되지 않는다(17장 9번) | `resolveDoubt`, `pump()` 한 번에 한 번, 상한 `lookupRetries`(기본 3) |
    | ② 물리 상태 관측 | 하류에 물을 수 없으면 설비를 본다. 요청 시각부터 `inDoubtGrace`(능력 기본 60초) 안에 기대한 것이 목적지에 나타나면 **잠정 완료**(12.3 셋째 행) | `verification=MATCHED`, note 에 `provisional done` |
    | ③ 운영자 | `OPERATOR_HOLD`. **자동 재실행은 없다.** 재요청은 사람이 `REWORK` 로 명시적으로 낸다 — 13.3 이 가른 *명령 재시도*이고, 새 정체성(`@r1`)이다. 근거 있이 `CONFIRM_DONE` 이면 E2, 없이면 등급을 올리지 않고 그 사실을 남긴다 | |

    **포트가 조회 능력을 선언한다** — `RobotPort.executionLookup`(계약 ④는 언제나 `CLIENT_REFERENCE`) · `AmrFleetPort.executionLookup`. 조사한 실물 하류 셋이 전부 참조 키를 안 받으므로(13.2) `NONE` 인 플릿이 현실에 가깝고, 그 값이 상류에 `JobResponse.autoResolvesInDoubt=false` 로 드러난다 — 16장 *"실행 조회 가능 여부"*. 시험 다섯: 로봇 응답 유실 → 조회 한 번에 해소·재실행 0회·E2 완료 / 조회가 계속 안 되면 상한 뒤 운영자·태스크 1개 / 참조 있는 플릿은 같은 운반 / 참조 없는 플릿에서 용기가 도착해 있으면 잠정 완료 → 운영자 → `CONFIRM_DONE` E2, `dispatches` 1 / 참조도 신호도 없으면 운영자 → `REWORK` 가 두 번째 운반.

    ### 지연 이벤트 — v17 의 완료는 v18 을 닫지 않는다

    만드는 법이 곧 발견이었다. 슬롯 하나를 v17 로 돌려 미믹에서 종착시키되 미들웨어는 아직 안 읽고, 그 사이 v18 이 온다. 미들웨어는 그 슬롯을 도는 중으로 알고 계약의 갱신을 보내는데 **이미 종착한 태스크라 `INVALID_TRANSITION`** 이다 — 앞 판은 이것을 단위 `FAILED` 로 적었다. 그러면 로봇이 실제로 놓은 부품이 사라진다. 이제 갱신 거절 중 그것만은 실패가 아니라 *"옛 버전 아래 종착함"* 으로 남기고, `pump` 가 갱신을 **버전으로 가른다**: 단위의 지금 버전보다 낮은 종착은 `Execution.lateEvents` 에 **보존**(폐기하지 않는다 — 15.1)하고, 이 버전의 갱신만 상태로 옮긴다.

    옛 버전의 완료만 있고 새 버전의 갱신은 오지 않을 때 — 그것이 17장 6번이다 — 새 버전의 완료로 적지 않는다. 물리적으로 일어난 일은 옛 파라미터로 한 일이므로 **설비가 새 버전의 기대에 대고** 본다(`strict` 검증: 요구 등급이 E0 라도 묻는다). v18 이 그 슬롯에 B 를 원하는데 로봇은 v17 대로 A 를 놓았다 → `VERIFICATION_MISMATCH` + 운영자. 같은 A 를 원했다 → E2 로 닫힌다(지연 이벤트는 그래도 남는다). 설비가 말이 없다 → `UNVERIFIED`.

    ★ 그 시험이 구멍을 하나 더 찾았다 — **도는 단위에 새 버전을 붙일 때 단위의 기대(파라미터·기대 신원·출발·목적지)는 옛 것 그대로였다.** 하류에는 새 파라미터를 보내면서 우리 쪽 기대는 안 바꿨으니, v17 의 완료가 v17 의 기대에 맞아 v18 의 완료로 적혔다. `ExecutionUnit` 의 그 넷을 버전의 것으로 두고 갱신 때 함께 바꾼다. 15.3 *"새 버전은 확정된 단위 이후에만"* 의 반대편 — 확정 안 된 단위에는 새 버전이 **통째로** 붙어야 한다.

    ### 정직하게 적어 둘 것

    - `IN_DOUBT` 중의 취소는 핸들이 없어 하류에 못 닿는다 — 해소돼 핸들이 생기는 순간 보낸다. 조회가 끝내 안 되면 운영자에게 가고 취소 요청은 거기 함께 있다. 시험은 없다.
    - `inDoubtGrace`·`lookupRetries` 는 시험의 약속이지 현장의 값이 아니다(시간창 δ 와 같은 종류).
    - 플릿 쪽 지연 이벤트는 없다 — 플릿 계약에 갱신이 없어(§15.89) 옛 버전이 생기지 않는다.
    - 어댑터가 재시작하면 참조↔하류 ID 매핑이 사라진다는 것은 여전히 밖이다(§1.3 B-1).
    - 결함 주입 아홉(조회 없는데 재전송 · 상한 없음 · 잠정 근거 반전 · 유실을 실패로 · 버전 미분리 · strict 무시 · 갱신 거절을 실패로 · 옛 기대 유지 · 상류에 언제나 자동 해소) 전부 겨냥한 시험이 잡았다. 하나는 첫 형태가 컴파일되지 않아(상수 거짓이 스마트 캐스트를 깼다) 다시 놓았다.

93. **③ 회귀 — 세 번째 능력군이 공통 엔진에 분기 없이 들어갔고, 이 기체가 다르게 답하는 취소가 명세에 드러났다.**

    계획 단계 7, 보고서 17장 10번 — *"공통 실행 엔진 변경 없음, 시나리오 1~9 회귀 통과, 미지원 취소·조회 차이가 명세에 노출"*. 보고서 7장이 합격 기준을 정확히 적어 두었다: *'아무 코드도 수정하지 않는다'가 아니다. 새로운 점검 의미는 확장 지점에 추가하되, 공통 실행 엔진에 `if robot == SPOT` 같은 분기가 퍼지지 않고 기존 두 업무의 의미가 유지되는지를 본다.*

    ### 무엇이 어디에 더해졌나

    | 자리 | 변경 | 분기인가 |
    |---|---|---|
    | 확장 지점 `LogicalCapability.kt` | `InspectAsset`(+95줄): 점검 대상 = `EquipmentUse = inspection_target`(`location`·`item` 속성), 대상 하나 = `<대상>.travel`(`navigate_to`) + `<대상>`(`inspect`), 목록 순서가 순회 순서, `mode` 는 주문 파라미터에서. 최고 등급 **E0**. 살필 자리가 없으면 계획에서 `NO_LOCATION_FOR_TARGET` | 아니다 — 능력 하나가 늘었다 |
    | 엔진 `Middleware.kt` | 21줄 더하고 2줄 뺐다 — **일반 규칙 셋**: ① 요구 등급 > 능력 최고 등급이면 접수 거절(보고서 11.3 *확인 수단이 없으면 제공 불가* — 앞 판에는 이 검사가 없었고 E2 를 받아 UNVERIFIED 로 끝냈을 것이다) ② 종착의 `partial_result` 를 `ExecutionUnit.result` 로 (E0 의 내용) ③ 취소 거절 코드를 `CancelReport.refusal` 로. 기본 능력 목록에 `InspectAsset` 한 줄 | 아니다 — 셋 다 능력·기종을 모른다. ①은 ③이 드러낸 일반 결손이다 |
    | 모델 `Model.kt` | `ExecutionUnit.result` · `CancelReport.refusal` · `JobResponse.results` | 아니다 |

    기존 시험 전부(①②·시간창·IN_DOUBT·지연 이벤트) 손대지 않고 초록이다 — 17장 1~9 회귀.

    ### 취소가 다르게 답한다 — 그리고 그것이 응답에 있다

    4족 픽스처는 `navigate_to` 취소 `NO`, `inspect` 취소 `YES` 다. 이동 중 취소하면 하류가 `CANCEL_UNSUPPORTED` 로 거절하고 — 앞 판은 그 응답을 **버렸다** — 진행 중 단위는 끝까지 가서 다음 경계에서 멈춘다. 이제 `CancelReport.refusal = REJECTION_CODE_CANCEL_UNSUPPORTED`, `inProgressUnit = PUMP-01.travel`, `completedUnits` 에 그 단위, `notStartedUnits` 다섯 — 어디서 왜 멈췄는지가 취소 응답에 있다. 점검 중 취소는 `refusal = null`, `cleanup = done`, 빈손. 16장 *"취소 수준 지원 범위와 취소 가능 지점 제약"* 이 처음으로 실행 층의 값이 됐다. 지원하지 않는 것을 지원하는 것처럼 감추지 않는다(7장).

    ### 정직하게 적어 둘 것

    - **점검 결과를 실을 자리가 비어 있다.** 시나리오 ③의 결과는 *항목별 수행 상태와 측정값 또는 증거 자료 참조*인데, 상태는 오고 참조는 계약의 `partial_result` 하나뿐이며 아무 발신자도 채우지 않는다(§15.76). `JobResponse.results` 는 그 자리이고 지금 빈 채로 **시험이 고정한다** — 채우는 쪽이 생기면 빨개진다(하네스 `InspectionPatrolTest` 와 같은 모양). 결함 주입이 그 시험이 살아 있음을 확인했다(엔진이 참조를 지어내면 잡힌다).
    - **공통 엔진은 기체 수준 결함을 아직 안 읽는다.** 점검 중 `LOCALIZATION_LOST`(can_accept_new_task=false)가 서도 엔진은 다음 단위로 간다 — 그 항목은 `LOCALIZATION_LOST` 로 실패하고 나머지 다섯은 완료해 `PARTIAL` 로 끝난다. 결함은 인터록이 아니라는 계약 거동(§15.87·ADR 32)과 일관되지만, *"이 기체가 지금 새 태스크를 받을 수 있는가"* 를 실행 층이 보는 것은 열려 있다(이벤트·스냅샷 소비).
    - 이동 중 취소가 거절될 때의 `inProgressUnit` 은 그 단위가 **끝난 뒤**의 값이라 `completedUnits` 에도 같은 이름이 있다 — 중복이 아니라 *"이 단위에서 멈추려 했고 그것이 끝났다"* 는 뜻이다. 자리 하나로 두 사실을 말하고 있으니, 상류가 헷갈리면 `stopPoint` 를 따로 낼 일이다.
    - 경로 ③(Orbit)은 여기 없다 — 취소가 없는 하류이므로 어댑터가 `CANCEL_UNSUPPORTED` 로 답해야 하고, 그러면 위의 거절 경로가 그대로 그 차이를 드러낸다. 실물 없이 적는 예상이다.
    - 결함 주입 여섯(이동을 점검으로 · 능력이 E2 주장 · 자리 없음 미분류 · 엔진이 상한 무시 · 거절 기록 반전 · 결과 지어냄) 전부 겨냥한 시험이 잡았다. 새 시험 일곱이 첫 실행에 전부 초록이었던 것이 주입을 돌린 이유다.

    **계획 일곱 단계가 다 닫혔다.** 열린 것: 기체 수준 결함의 실행 층 소비 · 점검 결과 참조의 발신자 · `client` 의 `GetKnownSiteNames` · registry 와 실행 상태의 관계 · RB-Y1 별도 트랙 · 어댑터 재시작 후 매핑(B-1).

94. **기체 수준 결함을 실행 층이 읽는다 — "새 태스크를 받을 수 없다" 는 말을 듣고 다음 단위를 보내지 않는다.**

    §15.93 의 첫 정직 항목을 닫았다. 계약은 결함을 인터록으로 쓰지 않는다 — 기체 수준 `LOCALIZATION_LOST`(`can_accept_new_task=false`)가 서도 다음 `StartTask` 를 받는다(§15.87, ADR 32). 그것은 계약(④)이 정책을 갖지 않아서이고(§4.6 *"판단은 밖으로, 사실은 안으로"*), **그 밖이 실행 층(③)이다.** 앞 판의 엔진은 그 사실을 읽지 않고 다음 지점으로 로봇을 보냈다 — 위치를 잃은 4족에게 다음 방으로 가라고 한 셈이다.

    ### 문 하나, 일반 규칙

    다음 단위를 시작하기 전에 `RobotPort.faults()`(계약 `GetSnapshot.faults`, `client.snapshot()` 신설)로 활성 결함을 묻고, `can_accept_new_task=false` 인 것이 있으면 보내지 않는다. 실행은 `OPERATOR_HOLD`, 상류에는 `JobResponse.blockedBy`(정준 분류)와 `operatorRequired` — 16장 *"자동 복구가 불가능해 운영자 판단이 필요하다는 사실"*. **자동으로 넘어가지 않는다.** 결함이 사라지거나 사람이 `release()` 로 감수해야 다음 단위가 나간다. 감수는 결함을 지우지 않는다(계약에 지우는 표면이 없고, 그것은 기체 쪽 일이다) — 감수한 결함의 열쇠가 `acknowledgedFaults` 에 남아 같은 결함은 다시 막지 않고 **새 결함은 다시 막는다.** 능력도 기종도 모르는 규칙이라 `PrepareSequencedRack`·`DeliverContainer`(플릿 단위는 문을 안 거친다 — 기체 결함이 아니다)·`InspectAsset` 에 같은 문이 선다.

    **`null` 은 없음이 아니다.** 스냅샷을 못 받으면 막지 않는다 — 관측 실패로 현장을 세우지는 않는다 — 그러나 그 단위에 *"못 물어봤다"* 를 남긴다. 원장의 `Observed`/`NotObservable` 과 같은 규율이며, 시험은 결함을 못 보는 포트(`FaultBlindRobotPort`)로 그 경로를 따로 돈다.

    ### 정직하게 적어 둘 것

    - 문은 **시작 직전에 한 번** 묻는다. 도는 단위 중에 선 결함은 그 단위의 종착(대개 `NEEDS_INTERVENTION` → 실패)이 말하고, 다음 단위 앞에서 문이 다시 묻는다. 유휴 중에 선 결함은 다음 주문의 첫 단위 앞에서 본다 — 그 사이를 이벤트로 듣지는 않는다(스냅샷만, 이벤트 스트림 소비는 열림).
    - `can_continue_current_task=false` 는 여기서 안 쓴다 — 도는 태스크를 세우는 것은 하류(어댑터·미믹)가 이미 하는 일이고(§4.5 전파 규칙), 실행 층이 그 위에 또 세우면 두 층이 같은 판단을 한다.
    - 미믹에서 `UNTIL_CLEARED` 결함은 영영 남는다(지우는 제어 RPC 가 없다). 그래서 시험의 재개는 언제나 `release()` 다 — *결함이 사라져서* 풀리는 경로는 시험이 없다.
    - 결함 주입 다섯(문 제거 · 문 반전 · 감수 무시 · 상류 미보고 · 관측 불가 흔적 삭제) 전부 겨냥한 시험이 잡았다. 새 시험 둘이 첫 실행에 초록이었다.

95. **실행 층이 이벤트 스트림을 소비한다 — 스냅샷으로 세우고 재생으로 이어 붙이고 벗어나면 다시 세운다.**

    §15.94 의 첫 정직 항목(*시작 직전 스냅샷 한 번*)을 닫았다. 계약 §4.8 이 소비자에게 요구한 패턴 그대로다: `GetSnapshot` 으로 현재값(활성 결함·연결 상태·태스크 상태)과 **다음에 올 번호**를 받고, `ReplayEvents(from)` 로 그 사이의 사실을 이어 붙이며, `SEQUENCE_EVICTED` 면 스냅샷부터 다시 세운다. 이것이 보고서 17장 9번 *단절 후 재동기화 — 스냅샷 조회로 놓친 전이를 복원, 중간 상태 유실 없음* 이다.

    ### 무엇이 어디로 가나

    | 원천 | 쓰임 |
    |---|---|
    | 스냅샷의 결함 | 다음 단위 앞의 문(§15.94)의 **권위** — 현재값이다 |
    | 스냅샷의 연결 상태 | `CONNECTION_BROKEN`·`OFFLINE` 이면 도는 로봇 단위의 결과를 **미확정**(`IN_DOUBT`)으로 두고 상류에 알린다. `HIBERNATING` 은 침묵하지만 정상이다(§4.7). 돌아오면 스냅샷의 태스크 상태로 다시 세워 이어 간다 — 재실행이 아니다 — 그리고 다시 알린다 |
    | 재생의 이벤트 | 실행의 **자취**(`Execution.eventTrail`) — 태스크 전이는 그 태스크를 든 실행에, 결함 발생·해소와 능력 변경은 그 기체의 실행 전부에. 발행 열의 번호와 시각을 **다시 찍지 않고** 그대로 든다 |
    | 축출 | 스냅샷은 이미 세웠으니 거기서부터 — `RobotView.resyncs` 가 센다 |

    `JobResponse.connection` 이 늘었다(계약 `ConnectionState` 의 이름). 기체를 못 봤으면 `ONLINE` 이라 하지 않고 `UNSPECIFIED` 다 — 없음과 모름을 접지 않는다(§15.94 와 같은 규율).

    ### 미믹의 구멍 하나

    첫 연결 단절 시험이 바로 빨개졌다 — 미들웨어가 아니라 **미믹이** 틀려 있었다. `GetSnapshotResponse.connection_state` 는 계약에 있는데(필드 6) `EventServiceImpl.getSnapshot` 이 채우지 않아 소비자가 언제나 `UNSPECIFIED` 를 봤다. 연결 상태는 MQTT connection 스트림으로만 나가고 있었다. 채웠다(`events.connectionState`, 같은 값). **계약에 자리를 두고 채우지 않는 것은 자리가 없는 것보다 나쁘다** — 소비자가 그 자리를 믿고 분기를 짠다(ADR 9 의 반대편). 이 층이 처음 그 자리를 읽어서 드러났다.

    ### 정직하게 적어 둘 것

    - 펌프마다 RPC 둘(스냅샷·재생)이다. 운영 주기(5초)에서 문제될 크기는 아니지만, 이벤트만으로 현재값을 세우고 스냅샷을 축출 뒤에만 쓰는 쪽이 더 가볍다 — 결함의 발생·해소가 전부 이벤트로 나온다는 것을 믿을 수 있을 때의 일이다. 지금은 스냅샷을 권위로 둔다.
    - 연결 단절 중에도 `WatchTask` 는 흐른다(미믹의 단절은 발행 축의 사실이고 gRPC 는 산다). 그래서 미확정은 실행 상태의 표시이지 단위의 정지가 아니며, 단절 중 종착하면 그 종착은 선다. 실물에서 단절이 gRPC 까지 끊으면 `WatchTask` 스트림의 오류가 함께 오고, 그 경로는 `IN_DOUBT` 해소(§15.92)가 맡는다 — 둘을 합쳐 본 시험은 없다.
    - 연결 상태의 원천은 스냅샷이다. MQTT connection 스트림(retain, Last Will)은 이 층이 아직 안 듣는다 — 브로커 결선은 열려 있다.
    - 결함 주입 여섯(단절 판정 반전 · 축출 무시 · 커서 미전진 · 복귀 뒤 `linkBroken` 미해제 · 못 봤는데 ONLINE · **미믹이 다시 연결 상태를 안 채움**) 전부 겨냥한 시험이 잡았다. 둘은 처음에 빠져나갔다 — ① *복귀 뒤 상태를 IN_DOUBT 로 두기* 는 바로 뒤의 `pumpRobotUnit` 이 상태를 다시 정하므로 **동치 변이**였고, 진짜 결함(`linkBroken` 미해제)으로 바꿔 놓으니 잡혔다. ② *커서 미전진* 은 마지막으로 읽은 이벤트가 **스킬 전이**라 자취에 안 남아 중복이 안 보였다 — 스킬 전이도 자취에 남기니(감사로도 맞다) 같은 시험이 잡았다. **주입이 빠져나가면 시험이 아니라 관측을 먼저 의심한다** — 이번 둘 다 그랬다.

96. **정리 여섯 — 미결로 적어 두었던 작은 것들을 닫는다.**

    | 항목 | 어디서 미결이 됐나 | 무엇을 했나 |
    |---|---|---|
    | `client` 에 `GetKnownSiteNames` 없음 | §15.87 | `PicassoClient.knownSiteNames()`. 판단하지 않는다 — `unsupported` 와 빈 목록을 가르는 것도, `total_count` 로 잘림을 아는 것도 소비자의 일. 하네스 `KnownSiteNamesTest` 3(아는 이름 / 0개 vs 못 함 / 40개 중 잘림) |
    | buf 디스크립터 재생성이 빌드 배선에 없음 | §15.85 부터 손으로 | `./gradlew :contracts:bufDescriptor` — `tools/buf` 와 같은 컨테이너·인자로 Docker 를 **직접** 부른다(bash 래퍼는 Windows 의 CreateProcess 로 못 부른다). `build` 에는 안 건다 — 게이트가 `contracts` 에 빌드 의존을 안 거는 것은 설계의 결정이라(§3.2) 순서를 Gradle 이 강제하지 않되, proto 를 고친 뒤 어디서든 한 명령이면 되게 했다. README 에 적음 |
    | `EquipmentUse` 어휘가 코드 상수뿐 | §15.88·§15.93 | 중앙 설계 §3 에 표 — 값·속성·뜻·읽는 능력. 능력은 자기 열의 낱말만 본다 |
    | 프로파일 넷에 `failure_class` 미선언 | §15.91 | `humanoid-a`·`no-pause`(`SKILL_EXECUTION_FAILED`→`GRASP_FAILED`) · `quadruped-c`(`X_PICASSOREF_NAVIGATION_BLOCKED`→`ROUTE_BLOCKED`) · `spot-arm`(`X_BOSTONDYNAMICS_FALL`→`ROBOT_FELL`, `X_BOSTONDYNAMICS_HARDWARE`→`HARDWARE_FAULT`). `LOCALIZATION_LOST` 는 유도된다 |
    | `inProgressUnit` 이 완료 단위와 겹침 | §15.93 | `CancelReport.stoppedAfter` — 단위가 끝까지 갔으면(거절됐든 취소가 닿기 전에 끝났든) 중단된 단위가 아니라 **그 뒤에서 멈춘 경계**다. 그때 `inProgressUnit` 은 `null` |
    | `IN_DOUBT` 중 취소 시험 없음 | §15.92 | 둘 — 취소 가능한 프로파일 사본: 핸들이 생기는 순간 취소가 닿아 `CANCELLED`·재실행 0 / 픽스처(취소 `NO`): 거절이 기록되고 `stoppedAfter` 가 그 단위 |

    시험 하나가 앞 판의 빈틈을 하나 더 보였다 — 미확정이 풀리며 보낸 취소의 **거절을 기록하지 않았다**(`cancel()` 경로만 기록했다). 이제 두 경로가 같은 자리에 적는다.

    ### 정직하게 적어 둘 것

    - `bufDescriptor` 는 Docker 가 있어야 돈다. 없으면 실패하고 게이트 시험이 만드는 법을 찍는다 — 앞과 같다. Docker 없는 대안(protoc 가 이미 만드는 `contract-descriptor/picasso.desc` 를 쓰는 것)은 게이트의 입력을 바꾸는 일이라 여기서 안 했다.
    - `KnownSiteNamesTest` 의 잘림 시험은 `protocol_limits.max_array_length` 가 40 보다 클 수도 있어 조건부 단언이다 — 잘리면 `total_count` 가 말한다는 것만 고정한다.

97. **Spot 이 `inspect` 를 든다 — 취득 계층 위에서, 대상의 이름은 세계 모델에 묻고, 결과는 `DataIdentifier` 참조로.**

    ③의 실물 경로 ①(보고서 7장 — *`DataAcquisitionService`(C 수준, `request_id`·`STATUS_COMPLETE`·`data_saved[]`)*)을 어댑터가 실제로 든다. 지금까지 시나리오 ③은 미믹 위에서만 돌았고 결과 참조를 채우는 발신자가 없었다(§15.93 정직 항목). 이제 발신자가 하나 있다.

    ### 판정을 바꿨다 — PARTIAL → YES, 근거와 함께

    `distance/spot-arm.json` 은 `inspect` 를 PARTIAL 로 적고 *"진짜 결손은 모양의 어긋남 — 우리 계약은 물체 신원을 묻는데 BD 는 어디에 서서 어느 이름의 액션을 돌리는가"* 라 했다. 어댑터를 실제로 들면서 그 어긋남을 두 자리가 메운다는 것이 보였다.

    | 계약이 묻는 것 | Spot 의 자리 | 어댑터 |
    |---|---|---|
    | 대상의 신원(`target`) | `WorldObject.name` — 사이트가 `MutateWorldObjects` 로 등록한 사람의 이름. Digit 의 객체 모델에서 우리가 이미 결속으로 인정한 것과 같은 구조 | `ListWorldObjects` 로 **있는지 묻는다**(`navigate_to` 가 지도에 묻는 것과 같은 규율, ADR 34·35). 없으면 `SITE_NAME_UNKNOWN`, 둘이면 `SITE_NAME_AMBIGUOUS` — 취득하지 않는다 |
    | 관측을 대상에 묶기 | `CaptureActionId{action_name, group_name}` — 이 표면에서 클라이언트가 이름을 정해 넣는 유일한 자리(§15.75) | `action_name = target`, `group_name = 태스크`. 결과 `DataIdentifier.action_id` 에 그대로 돌아온다 |
    | 결과 참조 | `GetStatusResponse.data_saved[]` | `channel/data_name#id@action_name/group_name` 을 `;` 로 이어 `result()` 로 — 계약의 `partial_result` 가 될 것 |
    | 생명주기 | `AcquireData` → `GetStatus` 열하나 → `CancelAcquisition` 넷 | `ACQUIRING`·`SAVING`→RUNNING · `COMPLETE`→SUCCEEDED · `ACQUISITION_CANCELLED`→CANCELLED(쥔 것이 없다) · `CANCEL_ACQUISITION_FAILED`→CANCELLED_RECOVERY_FAILED · `TIMEDOUT`→`COMMAND_TIMED_OUT` · 데이터·내부 오류·요청 분실→`UNCLASSIFIED`+원문. 취소는 **답이 온다** — `FAILED_TO_CANCEL` 이면 거절이고 취득은 계속된다. 일시정지는 표면이 없다 |

    남는 것 — **어느 카메라가 그 대상을 보느냐** — 는 벤더가 못 주는 것이 아니라 현장이 정하는 것이다. `AcquisitionRequestList` 는 센서를 받지 대상을 안 받는다. 그래서 어댑터는 광고된 영상 원천 **전부**로 찍고, *그 자리(`navigate_to` 의 웨이포인트)에 서면 카메라가 대상을 본다* 는 것을 `missing` 이 아니라 **환경 전제**로 옮겼다(`environment-preconditions.md` B 에 둘 — 등록은 관측 가능, 시야는 보증). `pick_place` 의 *"이 자리에는 A형만 있다"* 와 같은 종류다(§15.81 — 능력은 기체×현장의 성질).

    **스킬 셋이 세 층에 올라탄다** — `move_relative` 명령, `navigate_to` 미션, `inspect` 취득. 층마다 일시정지·취소·피드백이 다르고 그 차이가 프로파일의 스킬 단위 선언으로 올라간다(`inspect`: 취소 YES, 일시정지 NO). 취득 계층이 없는 기체에서는 `inspect` 만 죽는다.

    ### 저장소가 함께 움직인 것

    `spot-arm.json` 에 `inspect`(revision 2) · `distance/spot-arm.json` YES + 재측정 근거 · `provenance/spot-arm.json` 두 항목 · `environment-preconditions.md` B 두 행 · 매니페스트 시험에 새 남쪽 타입 열(`WorldLayer`·`AcquisitionLayer`·열거 셋·데이터 클래스 넷) — 짚은 이름 전부 원문에 있다. 거리 시험의 *"닿는다고 적은 것과 선언한 것이 같다"* 가 프로파일과 거리 문서를 함께 움직이게 했다.

    ### 정직하게 적어 둘 것

    - **실물에서 확인한 바 없다**(C-3). 이름의 실재만 대조했다. 특히 `AcquireData` 가 리스 없이 받는지, 광고된 원천 전부를 한 요청에 넣어도 되는지는 벤더 주석의 진술이다.
    - **북쪽이 없다.** `result()`·`failure()` 는 어댑터의 답이지 아직 `WatchTaskResponse` 가 아니다. 미들웨어의 `JobResponse.results` 가 비어 있음을 고정한 시험(§15.93)은 그대로다 — 미믹은 안 채우고 Spot 어댑터는 계약 서버가 없다. 결선은 어댑터 인스턴스(§15.77 이후 열린 것)의 일이다.
    - 결과 참조의 문자열 모양(`channel/data_name#id@action/group`)은 우리가 정한 것이다. 계약의 `partial_result` 가 자유 문자열이라서이며, 구조화하려면 계약이 자리를 내야 한다(§15.76).
    - `WorldObject.name` 으로 대상을 찾는 것과 `knownSiteNames()`(그래프의 웨이포인트 이름)는 다른 이름 공간이다(§15.78) — 확인 질의는 장소만 답하고 대상은 아직 안 답한다.
    - 결함 주입 다섯(모르는 대상 수락 · 취득에 대상 이름 안 붙임 · 결과 참조 누락 · 시간 초과 미분류 · 거절된 취소가 상태 변경) 전부 겨냥한 시험이 잡았다. 앞 판의 *"드는 스킬이 아니면 받지 않는다"* 시험은 `inspect` 를 빼고 `pick_place` 만 남겼다.

98. **어댑터의 북쪽 — 계약 서버 하나가 어댑터 셋을 세우고, ③이 실물 어댑터 위에서 미들웨어까지 처음 이어졌다.**

    §15.77 이 *"어댑터 인스턴스가 없다"* 로 적어 둔 것을 닫는 첫 걸음이다(ADR 39). 어댑터 셋의 같은 메서드 열이 `RobotAdapter` 가 됐고, 기종을 모르는 모듈 `adapter-host` 가 그것 하나를 계약의 gRPC 서비스 셋 뒤에 세운다. 프로파일 → `Capability` 투영은 공유 모듈로 나가 미믹과 호스트가 같은 함수를 쓴다(그 모듈은 같은 날 `capability` 로 넓어졌다 — §15.100).

    ### 소비자가 보는 모양은 미믹과 같다

    | 계약 면 | 호스트 |
    |---|---|
    | `StartTask` | 프로파일 선언 대조(스킬·필수 파라미터·문자열 길이) → `adapter.accept` → 로그에 `ACCEPTED` 와 어댑터가 지금 말하는 상태. 같은 `(task_id, revision)` 은 같은 핸들이고 로그를 안 늘린다. 종착은 래치 |
    | `WatchTask` | 되짚기(`from_update_index`) + 열어 두고 밀기. `partial_result` 는 어댑터의 결과 참조, `fault` 는 실패 종착의 정준 분류, `hold` 는 잔여 물리 상태 |
    | 조작 넷 | 어댑터가 *수단이 없다* 하면 그 조작의 코드(`PAUSE_UNSUPPORTED`·`CANCEL_UNSUPPORTED`). 종착이면 `INVALID_TRANSITION` |
    | `GetCapabilities` | 프로파일의 투영 그대로. `robot_software` 는 아직 안 싣는다 |
    | `GetKnownSiteNames` | `Known`/`Unsupported` 는 계약대로, `Unavailable` 은 `UNAVAILABLE` — 0 개와 못 함을 접지 않는다 |
    | `GetSnapshot`·`ReplayEvents` | 태스크 전이·결함 발생/해소를 `sequence` 축에 쌓고, 버퍼 크기는 프로파일의 것, 벗어나면 `SEQUENCE_EVICTED`. 결함을 못 봤으면 스냅샷이 `UNAVAILABLE` |

    ### 끝에서 끝까지

    `SpotHostEndToEndTest`(Spot 모듈 — 기종을 아는 쪽이 조립한다): 상류 JobOrder(점검 대상 둘) → `InspectAsset` → 계약 → 호스트 → `SpotAdapter` → 가짜 벤더 표면(미션·지도·세계 모델·취득). 이동 둘은 미션으로, 점검 둘은 취득으로 갔고, `DataIdentifier` 가 대상의 이름을 달고 `partial_result` → `ExecutionUnit.result` → **`JobResponse.results`** 에 닿았다. §15.93 이 *비어 있다* 고 고정한 그 칸이 실물 어댑터 경로에서는 찬다 — 미믹 경로의 시험은 그대로 비어 있고, 그것이 맞다(미믹은 결과를 안 채운다).

    ### 정직하게 적어 둘 것

    - ~~**MQTT 발행·레지스트리 적재·`Negotiate` 가 없다.** 계약의 gRPC 면만 세웠다.~~ → 발행과 적재는 §15.99, 협상은 §15.100 에서 닫혔다. ADR 37 의 등록 절차는 열려 있다.
    - **도는 태스크의 갱신을 안 한다** — 어댑터 셋 중 아무도 Halt→Reset→Start 를 안 들어 `INVALID_TRANSITION` 에 사정을 붙여 거절한다. 미들웨어는 그 코드를 *이미 종착* 으로 읽고 지연 이벤트를 기다리므로(§15.92), 호스트 위에서 버전 갱신을 쓰면 그 단위는 원래 버전으로 끝나고 새 기대에 대고 검증된다. 시험은 없다.
    - 어댑터의 거절 중 *로봇이 지금 못 받는다*(`VENDOR_REJECTED`·`CONTROL_AUTHORITY_LOST`·`ALREADY_RUNNING`)는 계약에 자리가 없어 `INVALID_TRANSITION` 에 분류와 원문을 붙인다 — 거절 코드 하나가 후보다(ADR 9: 발신자가 이제 있다).
    - 진행률은 종착 전 0, 성공 1 이다. 어댑터가 진행률을 안 낸다.
    - 결함 주입 일곱(결과 참조 누락 · 멱등 재수신을 새 요청으로 · 못 본 결함을 없음으로 · 축출 망각 · 취소 거절 코드 뒤바꿈 · 종착 래치 제거 · 실패에 fault 미부착) 전부 겨냥한 시험이 잡았다. 첫 실행에서 하나가 *"모든 겨냥 시험이 잡지는 않음"* 으로 나왔는데 — e2e 시험이 컴파일되지 않은 채였고 러너가 *시험 0건* 을 초록으로 읽었다. 러너의 초록 판정에 *시험이 하나라도 돌았는가* 를 더했다.

99. **호스트의 위쪽 결선 — 레지스트리가 어댑터 호스트의 기체를 본다.**

    §15.98 이 *없다* 고 적은 셋 중 둘(발행·적재)을 닫았다. 방법은 새로 짓는 것이 아니라 **미믹의 것을 빼서 같이 쓰는 것**이었다: `Publisher`·`Publication`·`Topics`·`MqttPublisher` 와 `report/*`(핸드셰이크 보고·태스크 관측·생존 보고·파일 폴백·재적재·`RegistryLink`)가 새 모듈 **`uplink`** 로 나갔다. 투영을 뺀 이유와 같다(ADR 29) — 토픽 형식·발행 열 헤더·적재 경로가 두 벌이면 미믹과 실물이 *같은 모양* 이라는 주장이 발행 쪽에서 깨진다. 기종을 모르므로 게이트 7번 목록에 들어갔다(이제 여덟).

    ### 호스트가 내는 것

    | 스트림 | 언제 | 무엇 |
    |---|---|---|
    | `connection` | 포트가 열린 뒤 `ONLINE`, 정상 종료 때 `OFFLINE` — 둘 다 retain | 끊기면 브로커의 Last Will 이 `CONNECTION_BROKEN` 을 대신 낸다(그것은 `MqttPublisher` 의 일이고 호스트는 모른다) |
    | `event` | 전이·결함이 적힐 때마다 | 재생 버퍼의 것과 같은 객체 — 발행이 막히면 버퍼에 남았다가 다음 발행 때 **순서대로** 밀린다(§10.6 과 같은 규칙, 따로 큐 없음) |
    | `state` | 프로파일의 최대 발행 간격마다, **펌프가 판정**(스케줄러 없음) | 태스크 스냅샷과 활성 결함. 스킬 상태기계가 없어 `skills` 는 비운다 |

    **`sequence` 축은 기체 단위 하나이고 셋이 함께 쓴다**(§5.5 의 발행 열). 그래서 앞 판의 축출 시험이 가정한 *"이벤트가 0 번"* 이 틀렸다 — 0 번은 기동 발행 `ONLINE` 이다. 시험은 번호를 가정하지 않고 호스트가 *실제로 버린 번호* 로 다시 물었다. `GetSnapshot.connection_state` 도 이 값이다(§15.95 의 미믹 정정과 같은 자리).

    ### 레지스트리가 보는 것

    적재는 호스트가 모른다 — 발행자를 `IngestBridge` 로 감싼 쪽(조립하는 쪽)의 일이고, 그것이 미믹 CLI 의 `RegistryLink.wrap` 과 같은 자리다. 생존 보고에 실리는 둘은 **어댑터가 답한다**: 로봇 소프트웨어(`RobotAdapter.robotSoftware()`, 기본 `null` = 못 읽는다 — 셋 중 아직 아무도 안 읽는다)와 사이트 이름 요약(`HostUplink.siteNames` — `Known`=개수 · `Unsupported`=못 함 · `Unavailable`=**널**, 셋을 접지 않는다; 레지스트리가 널을 *이미 받은 답을 지우지 않는다* 로 다루므로 못 물어본 펌프가 앞의 답을 되돌리지 않는다).

    `HostIngestEndToEndTest`(harness, Postgres): 바인딩된 기체를 원장이 *한 번도 보고한 적 없다* 로 보다가 → 호스트가 뜨자 `Live`(소프트웨어·이름 개수 2 가 `robot_liveness` 에 앉음) → 태스크가 돌자 `task` 표에 비종착 → 종착하자 드레인 → 호스트가 닫히자 `OFFLINE` 이 남고 원장이 *연결이 끊긴 기체* 로 판정한다. `LedgerIngestEndToEndTest` 가 미믹으로 본 것과 같은 판정이 실물 어댑터 경로에서 난다 — 갈리는 것은 발행의 출처뿐이다.

    ### 정직하게 적어 둘 것

    - **등록 절차(ADR 37)는 여전히 없다.** 이 시험도 기체 행을 SQL 로 넣는다. 증명한 것은 *등록된* 기체의 관측이 호스트에서 흘러온다는 것까지다.
    - **배치 런처가 없다.** 실 포트·프로파일 파일·어댑터 조립·펌프 스케줄러·MQTT 접속을 한 자리에서 엮는 CLI 는 만들 수 있으나, **띄울 실물 링크가 저장소에 없다** — 어댑터 셋의 남쪽(`SpotLink`·`DigitLink`·`G1Link`)은 인터페이스이고 구현은 시험의 가짜뿐이다(벤더 원문을 저장소에 안 들이는 규칙, §15.55). 아무것도 못 띄우는 런처를 만들지 않았다. 런처는 첫 실물 링크와 함께 온다.
    - **못 보낸 구간이 버퍼에서 밀려나도 세션을 새로 안 낸다.** 미믹은 그때 세션을 바꿔 소비자를 스냅샷부터 다시 세우게 하는데(§10.6), 호스트의 세션은 불변이다. 축출 경계는 `ReplayEvents` 가 말하므로 gRPC 소비자는 안전하고, MQTT 소비자만 그 구간을 결손으로 본다.
    - `HIBERNATING`·`CONNECTION_BROKEN` 은 호스트가 안 낸다. 남쪽 링크의 단절은 어댑터가 결함·거절로 말한다.
    - ~~핸드셰이크 보고는 `Negotiate` 가 없어 없다.~~ → §15.100 에서 닫혔다.
    - 결함 주입 여섯(드레인 뒤 이중 발행 · 발행 간격 무시 · 기동 발행 누락 · 이벤트 발행 누락 · 종료 OFFLINE 누락 · 사이트 이름 요약의 셋 접기) 중 **첫째는 주입이 아니라 구현에서 나왔다** — 드레인이 이미 민 이벤트를 `send` 가 또 냈고, 새 시험이 잡았다. 미믹의 `EventStream.send` 주석이 *"둘 다 하면 마지막 하나가 두 번 나간다 — 실측으로 걸렸다"* 고 적어 둔 바로 그것을 같은 자리에서 다시 밟았다. 같은 규칙을 두 벌로 갖는 대가가 이렇게 나타나므로, 이 부분도 다음에 `uplink` 로 올릴 후보다(§10.6 의 단절 중 버퍼링을 미믹과 호스트가 각자 든다).

100. **협상 — 판정 함수 하나를 미믹과 호스트가 나눠 쓰고, 그 둘이 같은 답을 낸다는 것을 밖에서 확인한다.**

    §15.98 이 남긴 마지막 계약 면이다. 호스트의 `Negotiate` 는 `UNIMPLEMENTED` 였고, 그동안 **협상하는 소비자는 실물 경로에서 아무것도 못 했다** — `client` 가 이미 그 RPC 를 부르므로 *"엔드포인트만 바꿔 미믹과 실물을 오간다"* 가 그 자리에서 거짓이었다.

    ### 판정은 옮기고, 전송은 두고 왔다

    미믹의 `Negotiator` 가 `profile-projection` 으로 갔고 그 모듈은 **`capability`** 로 이름이 넓어졌다 — 이제 프로파일 → `Capability` 투영과 요구 집합 대 `Capability` 판정 둘을 든다. 같은 질문의 두 면이고, 모듈을 또 만드는 것보다 이름이 정확해지는 편이 낫다(게이트 7번 목록도 안 길어진다).

    옮기면서 **gRPC 의존을 걷어냈다.** 앞 판은 요구 문자열을 못 읽으면 `Status.INVALID_ARGUMENT` 를 던졌는데, 그러면 판정 모듈이 전송을 안다. 이제 `Negotiation` 이 둘이다 — `Judged(rejections)` 와 `Unparseable(problems)`. **거절과 판정 불가는 다른 것이다**: 앞은 *로봇이 요구를 못 맞춘다* 는 답이고 뒤는 *요구를 읽지 못해 답 자체가 없다* 이다. 접으면 오타 난 설정 파일이 "능력 부족" 으로 보인다. 계약으로 옮기는 것(`INVALID_ARGUMENT`)은 서비스 둘이 각자 하되 **문구는 `Unparseable.detail` 하나**다 — 서비스마다 지으면 같은 오류가 미믹과 호스트에서 다른 말로 나온다. 발행 추상이 MQTT 라이브러리를 모르는 것과 같은 규율이다.

    ### 같은 답이라는 것을 무엇으로 아는가

    `HostParityTest`(harness): 같은 프로파일로 미믹과 호스트를 나란히 세우고 같은 요구 집합 여섯을 양쪽에 물어 **`accepted` 와 거절 목록 전체**(코드·사유·참조)를 댄다. 코드만 대면 사유가 갈리는 것을 놓치고, 사유는 운영자가 읽는 것이다. 마지막 경우가 여럿을 한꺼번에 어긴다 — 한쪽만 첫 거절에서 끊는 구현이면 하나씩 어기는 시험은 전부 통과하고 거기서만 갈린다.

    **`harness` 에 있는 이유**: 둘을 동시에 아는 모듈이 그것뿐이다. 미믹도 호스트도 서로를 모르는 것이 §3.2 이고, 그래서 나란히 세워 보는 자리는 계약 스위트의 주인이다.

    ### 원장의 세 번째 관측선

    호스트가 `uplink` 의 `HandshakeReporter` 를 받는다(`AdapterHost(robot, builder, reporter)` — 미믹의 `MimicServer` 와 같은 자리). 그래서 §9.3 이 세는 소비자 요구가 실물 어댑터 경로에서도 원장에 앉는다. `HostIngestEndToEndTest` 의 둘째 시험이 그것을 본다: 협상 전 0 → 수락하면 요구 둘이 `consumer_requirement` 에 빠짐없이 → **거절된 협상은 원장이 아니라 거절 표로**(오르면 그 소비자 때문에 축소가 영영 안 열린다). 성공·실패를 **모두** 보고하는 것이 §5.4 이고, 수락만 보내는 구현은 거절 표가 비는 것으로만 드러난다.

    ### 정직하게 적어 둘 것

    - **판정 기준은 프로파일의 투영이다.** 어댑터가 그 선언을 실제로 다 드는지는 협상이 확인하지 않는다. 갈리면 협상을 통과한 소비자가 `StartTask` 에서 `SKILL_ABSENT` 를 받는다 — 선언과 구현의 거리(C-3)가 그대로 남아 있고, 계약에는 *"어댑터에 물어보라"* 는 질의가 없다.
    - **동치 시험의 픽스처는 하나다**(`profile/fixtures/minimal.json`). 기종 프로파일 셋으로 돌리지 않았다 — 판정 함수가 하나이므로 갈릴 자리가 배선뿐이고, 배선은 픽스처 하나로 드러난다.
    - ★**동치 시험은 판정 자체의 결함을 못 잡는다.** 주입으로 확인했다: 공유 함수를 망가뜨리면 미믹과 호스트가 **함께** 틀리고 두 답은 여전히 같다. 동치가 지키는 것은 *갈리지 않는다* 이지 *맞다* 가 아니며, 맞는지는 양쪽의 자기 시험(`NegotiateTest`·`AdapterHostTest`)이 지킨다. 판정을 하나로 모으면서 얻은 것과 잃은 것이 이 한 줄이다.
    - 결함 주입 여섯(수락 여부를 거절 목록과 따로 계산 · 거절이 하나 생기면 나머지를 안 모음 · 판정 불가를 빈 거절로 · 거절은 보고 안 함 · 보고에 site 누락 · 호스트가 자기 능력 대신 빈 `Capability` 로 판정) 전부 겨냥한 시험이 잡았다. 뒤엣것만 동치 시험이 잡았고 — 그것이 배선의 결함이라서다 — 앞의 다섯은 양쪽의 자기 시험이 잡았다.
    - ★**첫 판의 주입 둘이 등가 변이였다.** *수락 여부를 `SKILL_ABSENT` 유무로 계산* 은 내 시험의 거절이 전부 그 코드를 포함해 답이 안 갈렸고, *신원 거절 뒤 조기 반환* 은 신원이 늘 맞아 그 줄에 닿지 않았다. **못 잡으면 주입부터 의심한다**는 규율이 또 맞았다. 앞엣것은 시험을 하나 더해 닫았다(한계만 어긴 요구 — 거절의 종류와 무관하게 거절이 있으면 수락이 아니다).

101. **기체를 들이는 문 둘 — 등록 절차의 절반(ADR 37).**

    설계 §8.5의 조작 목록에 *기체 등록* 이 처음부터 있었는데 **엔드포인트가 없었다.** 그래서 기체 행은 시험이 `INSERT INTO robot` 으로 직접 넣었고 미믹의 기체 목록은 기동 인자로 손으로 적었다 — ADR 37 이 *"우리 하네스가 스스로 결정 5의 비싼 사분면에 앉아 있다"* 고 적어 둔 그 상태다. 이제 발신자가 둘이니(미믹·어댑터 호스트) 그 문을 쓸 쪽이 실재한다.

    ### 문이 곧 출처다

    | 출처 | 문 | 토큰 | 뜻 |
    |---|---|---|---|
    | **선언** | `POST /operations/robots` | 조작 | *"사람이 판단을 기록한다"* |
    | **발견** | `POST /ingest/robots?site=` | 적재 | *"기체가 자기 관측을 보고한다"* |

    **본문에 `origin` 이 없다**(결정 3). 컨트롤러가 자기 경로를 보고 정하며, 요청이 그것을 실어 보내도 아무 일도 일어나지 않는다. 본문이 정하게 두면 **적재 토큰을 든 현장의 기체가 스스로 *"사람이 선언했다"* 고 적을 수 있고**, 그 승격은 `OperatorToken` 이 막으려던 바로 그것이다(§15.38). 서비스 시험은 메서드를 직접 부르므로 이 구멍을 못 본다 — HTTP 시험이 그 자리를 지킨다.

    **다른 문으로 다시 들이지 않는다.** 선언된 기체를 발견으로, 발견된 기체를 선언으로 올리면 거절한다(조작 문은 409, 적재 문은 목록의 사유). 출처가 두 번째 진실이 되면 *"이 기체가 왜 여기 있는가"* 에 답할 수 없다. 반대 방향을 함께 보는 것이 요점이다 — 한쪽만 보면 문을 하나로 합쳐도 통과한다.

    ### 사람의 말은 기체가 답할 때까지 CLAIMED다

    ADR 35가 사이트 이름에서 세운 규율을 그대로 쓴다. 상태가 넷이다.

    | 상태 | 뜻 |
    |---|---|
    | `CLAIMED` | 사람이 선언했고 **기체가 우리 계약으로 답한 적이 없다** |
    | `DISCOVERED` | 어댑터가 플릿에서 봤고, 아직 우리 계약으로 닿은 적은 없다 |
    | `CONFIRMED` | **그 기체가 생존을 보고했다.** 출처와 무관하다 |
    | `UNREGISTERED` | 행은 있는데 **어느 문도 안 지났다** |

    **확인을 열로 저장하지 않는다** — `robot_liveness` 행이 있으면 확인이다. 열로 두면 두 번째 진실이 되고, 생존 보고가 끊긴 기체를 누가 되돌려 놓을지가 정해져 있지 않다. `UNREGISTERED` 는 기본값을 안 준 결과다: 주면 SQL 로 넣은 픽스처가 *"사람이 선언했다"* 고 거짓말하고 진짜 선언과 구별되지 않는다. 스물다섯 자리가 지금 그 상태이며 **보이는 편이 덮는 것보다 낫다.**

    `HostIngestEndToEndTest` 가 그 전이를 끝에서 끝까지 본다 — 선언 직후 `CLAIMED`, 어댑터 호스트가 뜨자 `CONFIRMED`. **오타 난 `robot_id` 로 선언했다면 영영 `CLAIMED` 이고**, 진단 9번(`GET /diag/robots`)이 그것을 보여 준다. ADR 37 이 대가의 마지막 줄로 적어 둔 *"화면은 초록인데 기체가 안 붙는다"* 가 그 자리에서 닫힌다.

    ### 미결 둘을 닫았다

    - **발견된 기체의 사이트 귀속** — 어댑터가 배포된 사이트다. 플릿은 우리 `site_id` 를 모르지만 어댑터는 안다. `/ingest/handshake` 의 `?site=` 와 같은 자리이고 같은 성질(어댑터의 신고)이다.
    - **접속 설정의 비밀** — 안 받는다. `robot.endpoint` 는 **주소만**이고 §6.3이 신원·접근을 범위 밖에 둔 이상 이 표를 자격증명 저장소로 만들지 않는다.

    발견된 기체의 접속 정보는 **받아서 거절한다**(결정 4 — 플릿이 갖는다). 필드를 아예 없애면 보낸 쪽은 우리가 저장했다고 믿는다. 스키마에도 같은 제약을 둔다(`CHECK`) — 서비스를 한 줄 고쳐 새는 것을 표가 막는다.

    ### 정직하게 적어 둘 것

    - **문은 생겼지만 그 문으로 올리는 어댑터는 없다.** 발견 경로에서 시험이 어댑터 노릇을 한다. 어댑터의 남쪽에 플릿 흉내가 없다는 ADR 37 의 미결이 그대로 남는다.
    - **어댑터 인스턴스를 안 만들었다**(결정 2). 인스턴스는 접속 설정을 갖는 것인데, 직결의 설정은 기체의 것이라 `robot.endpoint` 가 들고 플릿 경유의 설정은 들 어댑터가 없다. 그래서 `robot_binding` 은 여전히 **빌드**를 가리킨다(미결 1 유지).
    - **픽스처 스물다섯 자리는 여전히 SQL 이다.** 닫은 것은 `HostIngestEndToEndTest` 하나다 — ADR 37 이 이름을 집어 적은 자리가 그것이고, 나머지는 등록이 시험의 주제가 아니라 배경이다. 그 행들은 진단에서 `UNREGISTERED` 로 보인다.
    - 결함 주입 여섯(출처를 본문에서 읽음 · 다른 문 거절을 갱신으로 · 확인을 선언 시점에 켬 · 발견의 접속 정보를 저장 · 부분 성공을 전부 실패로 · 감사 로그 누락) 전부 겨냥한 시험이 잡았다.

102. **플릿의 남쪽 — Orbit 을 재고 그 인용을 검사 아래 뒀다(트랙 A 의 A1·A2).**

    어댑터 넷째다. 앞의 셋(Spot·Digit·G1)은 전부 **기체 하나**에 직결하고, 이것만 **기체 여럿을 아는 서버**에 붙는다. ADR 37 의 사분면 넷 중 *플릿 경유* 가 표본이 없어서 발견 경로를 실증할 수 없었고(§15.101 이 *"그 문으로 올리는 어댑터는 없다"* 로 남겼다), 이 모듈이 그 자리다.

    **Orbit 을 고른 이유 셋** — 발견이 게시 스펙 안에 있고(`GET /robots`), REST 라 **벤더 SDK 를 안 들여도 붙고**(Spot proto·G1 DDS 와 다르다 — ADR 31 의 격리가 여기서는 저절로 지켜진다), 이미 재어 둔 표면이 있다(`docs/vendors/orbit.md`).

    ### 출처가 재현됐다

    2026-09-08 에 적어 둔 두 원본을 다시 받아 **sha256 이 같았다**(문서 페이지·클라이언트). 조사 노트에 취득 시각과 해시를 적어 둔 것이 값을 한 첫 사례다 — 같은 판을 다시 재고 있다는 것을 증명 없이 믿지 않아도 됐다.

    ### 추출기가 하나 더 생겼다

    `tools/vendor-manifest/openapi_symbols.py`. 원본의 성질이 앞의 셋과 또 다르다 — **스펙이 파일로 배포되지 않는다.** 문서 페이지에 JS 객체로 인라인돼 있고 키에 따옴표가 없어 JSON 파서가 안 먹는다. **정규식으로 키에 따옴표를 씌우지 않았다**: 그 방법은 문자열 안의 `foo:` 도 키로 바꿔 **없는 이름을 만들어 내고**, 그것이 README 가 적은 두 방향 중 조용한 쪽이다. 작은 파서를 직접 두면 문자열을 문자열로 알기 때문에 그 실수를 할 수 없고, 모르는 문법에서는 **터진다**(시끄러운 쪽). 실제로 수 키(`responses: { 200: … }`)에서 한 번 터졌고 그것이 옳은 실패였다.

    **클라이언트 경로는 AST 로 읽는다.** 첫 판이 정규식이었고 **한 판에서 두 방향으로 틀렸다** — 벤더가 쿼리까지 한 문자열에 담아 쓰는 파견 경로를 놓쳤고(`f'…/dispatch/{nickname}?currentDriverId={id}'`), 동시에 `application/json` 을 경로로 읽었다. **놓친 쪽은 §15.83 이 적어 둔 사실과 안 맞아서 드러났다** — 새 추출기의 첫 시험은 *이미 아는 이름 하나가 나오는가* 다.

    ### 이름 공간이 근거 등급을 나른다

    게시 스펙의 것은 `GET /robots`, 클라이언트에만 있는 것은 `bosdyn-orbit:…` 이다. **벤더의 표기를 그대로 쓰는 원칙(§15.56)의 유일한 예외**이며 이유가 근거 등급이다: 후자는 벤더가 공개 API 로 약속한 적이 없어 언제 사라져도 벤더 잘못이 아니고, 같은 이름 공간에 섞으면 그 차이가 사라진다.

    ### 재면서 나온 것 셋

    1. **게시본이 불완전하다는 판정이 숫자를 얻었다.** 앞 판은 사례 하나(파견)였는데, 클라이언트가 치는 경로 40 개를 전부 세니 **아홉**이 게시 스펙에 없다.
    2. **`Robot` 에 일련번호가 없다** — 발견으로 얻는 신원이 주소와 별명뿐이다. Orbit 이 모르는 것은 아니다(`Run.robotSerial`). **우리 원장의 `robot.serial_number` 가 필수라 발견 경로와 부딪친다**(§15.101 이 만든 문과 이 벤더가 주는 것이 안 맞는다 — A4 에서 다룬다).
    3. **`Run.missionStatus` 가 값 집합 없는 자유 문자열이다.** 같은 스키마의 `Run.runType` 에는 `enum` 이 있으므로 추출기가 못 읽는 것이 아니다. Spot 에 직결하면 `MissionStatus` 열거를 받는데 같은 로봇을 Orbit 뒤에서 보면 문자열 하나이고, 실패는 `RunEvent.error` 정수 하나다 — **층이 하나 늘 때 결과 어휘가 얼마나 얇아지는지**가 이 측정의 값이다(ADR 36 의 실행 층).

    ### 정직하게 적어 둘 것

    - **어댑터가 아직 없다.** 이번 것은 남쪽 포트(`OrbitLink`)와 그 인용의 검사까지다. `RobotAdapter` 구현과 발견의 결선은 A3·A4 다.
    - **HTTP 구현이 없다.** 포트는 인터페이스이고 실제로 Orbit 에 붙는 클라이언트는 안 지었다. 붙여 본 적도 없다.
    - **층 셋이 다 널일 수 있게 뒀다.** 특히 파견은 게시 스펙에 없어 배포본에 있으리라는 보장이 없다 — 있는 척하면 못 붙는 인스턴스에서 어댑터가 아무것도 안 하면서 초록으로 보인다(G1 의 `sport` 가 시뮬레이터에서 널인 것과 같은 자리).
    - 추출기 결함 주입 일곱 전부 잡혔다(중첩 필드 상실 · 열거값 상실 · 클라이언트 POST 경로 누락 · 호스트 접두사 잔류 · 자리표시자만인 경로 · 스펙 검색 실패 · 문자열을 문자열로 안 봄). 셋은 남쪽 시험이 함께 빨개졌고 둘은 매니페스트 diff 로만 보였다 — **아무도 안 짚는 이름**이라 그렇다. ★그 라운드가 **죽은 가지**를 드러냈다: 열거값을 두 곳에서 모으고 있었는데 재귀가 이미 같은 이름을 만들어, 한쪽을 지워도 매니페스트가 그대로였다. 주입이 안 잡히면 주입을 의심하되 **코드가 죽었을 수도 있다.**
