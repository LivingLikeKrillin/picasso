# picasso — 이기종 로봇 표준 I/F 계약과 운영 변경 체계

설계 사양서 · 2026-09-05

## 1. 목적과 범위

### 1.1 목적

이기종 모바일 로봇(휴머노이드 및 4족 보행 로봇)을 공장 운영 시스템에 통합할 때 요구되는 **표준 인터페이스 계약**을 정의하고, 해당 계약을 **실물 기체 없이 독립적으로 검증할 수 있는 가상화 환경**을 구축하며, **운영 환경에서의 변경 파급 범위를 결정론적으로 계산**하는 아키텍처를 수립한다.

본 아키텍처는 다음 두 가지 핵심 명제를 기반으로 설계되었다:

1. **이기종 로봇 대응은 소스 코드 수정이 아닌 프로파일 교체로 완결되어야 한다.**
2. **운영 환경 변경의 파급 범위는 사전에 계산 가능해야 한다.** 영향도를 예측할 수 없는 변경은 운영 리스크를 초래하며, 변경에 대한 심리적 저항은 시스템의 기술 부채와 경직성을 심화시킨다.

두 명제 모두 단순한 개념 증명에 그치지 않고, CI 실패 조건 또는 운영 제어 거부 조건으로 시스템에 엄격히 강제한다.

**핵심 설계 원칙: 확장은 안전하지만 축소는 파급을 수반한다.** §9의 모든 운영 변경 규칙은 이러한 비대칭성을 기반으로 도출된다.

### 1.2 범위

근거 문서 `이기종-로봇-공장-연계-설계노트` §5 작업 백로그 기준:

| 항목 | 내용 |
|---|---|
| A-1 | 스킬 모델의 Protocol Buffers 이식 |
| A-2 | 이벤트 계약 (상태/이벤트 분리, 스냅샷, 순서 보장, 멱등성, 결함 모델) |
| A-4 | 장기 실행 작업 모델 (goal/progress/result/cancel/retry) |
| C-1 | 능력 프로파일 스키마 |
| C-2 | 프로파일 주도 가상화 에뮬레이터 (MiMic) |
| D-1 | 스키마 호환성 검증 게이트 |
| D-2 | 아키텍처 결정 기록 (ADR) — 목록은 §14 |

설계 과정에서 확장된 4개 항목:

| 항목 | 사유 |
|---|---|
| 능력 호환성 체계 | 제조사 및 펌웨어 버전에 따른 능력의 확장·축소·세분화에 대응하기 위한 API, 헤더, 토픽, 파라미터 키 설계 및 통신 검증 전략 수립 |
| 레지스트리 및 런타임 갱신 | 프로파일은 설계노트 §4.5 기준 데이터로 취급되며, 갱신을 위해 프로세스를 재시작하지 않는 핫 리로드 구조 확립 |
| **운영 변경 체계** (§9) | 어댑터 추가·변경 및 기능 추가·변경·삭제 시 파급 범위와 반영 순서를 정립하고, 상위 연계 시스템까지의 정합성 보장 |
| **상위 연계 계층** (§9.6) | **2026-09-08 추가.** 인터페이스 표면만 정의하고 소비 계층을 배제할 경우 소비자 없는 무효한 선언이 됨([ADR 9](../../adr/0009-no-declaration-without-consumer.md)). 하위 어댑터와 대칭되는 형태로 아키텍처 경계 내에 수용 (계층 프레임워크는 프로젝트 범위, 특정 현장 인스턴스는 배포 환경 소유) |

구현은 §13의 4단계 마일스톤으로 분할하여 진행한다.

### 1.3 비목표 (Non-Goals)

- **A-3 원자적 명령 전달의 대안 비교**: 파라미터 전달과 실행 트리거를 단일 RPC로 통합하여 경쟁 조건을 근본적으로 차단하는 방식을 채택한다.
- **A-5 이동·내비게이션 스킬 타입 확장**: 구체적 스킬 타입 어휘 확장 작업이며, 기본 구조는 A-1에서 포괄한다.
- **A-6 위치 레지스트리**: 스키마 차원에서 확장을 저해하지 않도록 인터페이스만 유지하고, 구체적 구현은 배제한다.
- **B-1 무선 단절 대응의 지속성 집행**: **2026-09-09 범위 조정([ADR 38](../../adr/0038-mission-layer-schema-is-ours.md)): 프로세스 재기동 후 저장소 기반 상태 복원만 제외하며, 접수 불명(`IN_DOUBT`) 상태의 해소 경로는 범위 내에 포함한다.** 단, **멱등성 키는 계약 사양에 현 시점에 포함한다.**
- **B-2 텔레메트리 경로 분리**: 고주파수 텔레메트리 스트림 분리는 제외하며, 프로파일은 텔레메트리 항목을 정의하지 않는다.
- **B-3 브리지 정책**: 외부 네트워크 브리지 구성 정책은 배제한다.
- **C-3 적합성 역검증 실행**: 실물 하드웨어 도입 시 수행되는 검증이며, 본 프로젝트에서는 워크플로우 상의 상태 전이 및 인터페이스 자리를 사전에 구축한다(§9.7 ④).
- **D-3 예외 구역 경계 집행**: 사이트 분기 정적 검사는 배제한다.
- **미션 계층**: 배차, 동적 라우팅, 자원 중재, 다중 로봇 간 경합 제어는 비목표로 둔다. 따라서 **동시성 정책 어휘(blocking type)도 계약에 포함하지 않는다.** **2026-09-09 범위 조정([ADR 38](../../adr/0038-mission-layer-schema-is-ours.md)): 제외 대상은 전역 배차·경로 제어·자원 중재에 한정되며, 논리적 능력의 합성 및 실행 상태 전이 관리는 범위 내에 포함한다. 해당 스키마 및 PoC 엔진은 [미들웨어 코어 설계](2026-09-09-middleware-core-design.md)에서 정의한다.**
- **즉시 명령(Instant Action)**: 단일 단계 태스크로 표현 가능하므로 별도의 전용 인터페이스 표면을 두지 않는다.
- **상위 시스템 어댑터(ACL) 구현**: **2026-09-08 비목표 목록에서 제외 및 경계 내 수용.** 코어 계층 프레임워크는 프로젝트 아키텍처 경계 내로 편입하고 인스턴스 소유는 배포 환경으로 분리한다(§9.6, [ADR 9](../../adr/0009-no-declaration-without-consumer.md)).
- **물리 시뮬레이션**: 도달 가능성(reachability), 기하학적 충돌, 파지 역학 시뮬레이션은 배제한다.
- **안전 기능**: 비상정지(E-Stop), 안전 정격 감속, 인체 감지 보호 정지 등 안전 계통 신호는 본 계약을 경유하지 않으며, 안전 회로는 본 시스템 가용성과 독립적으로 동작한다([ADR 32](../../adr/0032-safety-boundary.md)). 즉시 명령 배제 논리는 작업 제어 명령에만 유효하다.
- **지연 예산**: 종단 간 명령 왕복 지연의 상한은 규정하지 않는다. `publish_interval`의 30초 규정은 **상태 발행 주기**이며 지연 상한이 아니다.
- **인증·인가 체계**: §6.3의 신뢰 네트워크 전제를 적용한다.
- **정책 설정 저장소**: 임계값 및 상한 설정의 데이터베이스화는 실제 소비자가 정의된 이후로 유예한다.

### 1.4 완료 기준

§12.2에 **23개 행**으로 체계화하여 관리한다. 백로그 14개 행(A-1 1건, A-2 4건, A-4 6건, C-1 2건, C-2 1건), D-1 1건, 능력 호환성 1건, 레지스트리 및 런타임 갱신 2건, 운영 변경 5건으로 구성된다.

A-4 항목이 6개로 확장된 것은 실물 로봇 인터페이스 조사(§2.3) 결과에 근거한다. 취소 시의 물리적 복구 절차, 운영자 개입 요구 상태, 터미널 상태 비래치 기종 대응, 제어권 탈취 등 실물 제어 환경의 제약 조건들이 태스크 수명주기 모델에 직접 반영되어 6·7·8·8b·8c·8d로 구체화되었다.

*(2026-09-09 정정 — 이전 문서에서 24개 행·A-4 7개 행으로 기술되었으나, §12.2 표의 실제 A-4 행 수는 6개이며 총 23개 행으로 정합성을 일치시켰다.)*

## 2. 배경과 근거

### 2.1 근거 문서

`이기종-로봇-공장-연계-설계노트`(38쪽): L3~L5 계층 구조, 6개 운영 국면, 변경 수용 전략, 기술 스택 선정 기준, API 규격 및 페이로드 카탈로그, §5 작업 백로그가 정의되어 있다.

### 2.2 벤치마킹 분석

오픈소스 표준 저장소 분석: `Labs/[oss]/opentcs`, `Labs/[oss]/VDA5050`(v3.0.0, MIT 라이선스). 코드를 직접 복제하지 않고 검증된 아키텍처 패턴만을 선별 수용하였으며, 각 설계 결정의 근거는 ADR에 명시한다.

| 차용 패턴 | 출처 | 적용 위치 |
|---|---|---|
| 실패 등급을 "잔여 역량"으로 정의 | VDA5050 `state.schema` `errorLevel` | §4.6 |
| `RETRIABLE` 상태 모델 | VDA5050 `actionStatus` | §4.4 |
| 결함 수명주기, `errorHint`, `errorReferences` | VDA5050 에러 카탈로그 | §4.6 |
| 연결 상태 4값 모델 (통신 침묵 원인의 유형화) | VDA5050 `connection.schema` | §4.7 |
| 상태 발행 상한 주기 (최대 30초) | VDA5050 §6.6 (`VDA5050_EN.md:1034`) | §7.2 |
| `(orderId, orderUpdateId)` 단조 시퀀스 쌍 = 멱등성 키, 재수신 4케이스 분기 | VDA5050 §6.1.4 | §4.4 |
| 능력 프로파일 구조 전반 | VDA5050 `factsheet.schema` | §7.2 |
| `pauseAllowed` / `cancelAllowed` 필수 선언 체계 | VDA5050 `mobileRobotActions` | §7.2 |
| `optionalParameters {parameter, support}` 구조 | VDA5050 `protocolFeatures` | §5.3 |
| `protocolLimits` — 제약 한계의 선언 데이터화 | VDA5050 `protocolFeatures` | §7.2, §10.4 |
| 토픽 경로에 Major 버전 포함 | VDA5050 §4 | §5.5 |
| 거절 사유를 명시하는 `ExplainedBoolean` 패턴 | openTCS SPI | §5.4 |
| 비동기 취소 전이 모델 (`WITHDRAWN`) | openTCS `TransportOrder.State` | §4.4 |
| 에뮬레이터 제어 채널 및 단계적 실행 제어 | openTCS loopback | §10.5 |
| 계약 모듈 무의존 원칙 | openTCS `opentcs-api-base` | §3.2 |
| 송신 메시지 검증 게이트 | openTCS | §6.2 |

**안티패턴 및 반례 분석:**

- openTCS의 VDA5050 어댑터는 factsheet를 수신하지만 이를 실제 제어에 반영하지 않고 폐기한다(`v2_0/CommAdapterImpl.java:529`). 능력 정의는 수동 입력 문자열에 의존하며, 미선언 항목에 대해 fail-open으로 동작한다. "선언 메커니즘이 존재하더라도 런타임에서 소비되지 않으면 무효하다"는 교훈에 따라, 본 설계에서는 정적 검증(§11.2 검사 4)과 **런타임 투영 일치 검증(§12.2, `GetCapabilities` 응답과 프로파일 파생 능력의 완전 일치 검증)**을 이중으로 배치한다.
- openTCS의 스키마 검증 플래그 `VALIDATE_INCOMING_MESSAGES`를 비활성화하면 송신 검증까지 일괄 비활성화되는 결함이 존재한다. 본 설계는 §6.2에서 수신 및 송신 검증 플래그를 완전히 분리한다.
- openTCS loopback은 단 6개의 제어 파라미터만 제공하며 구체적 거동이 코드로 하드코딩되어 있어 기종 확장이 코드 수정으로 이어진다. 본 설계의 프로파일 주도 가상화(C-1/C-2)는 이를 극복하기 위해 설계되었다.

### 2.3 실물 로봇 인터페이스 조사 (2026-09-05)

공개 SDK 및 공식 기술 문서가 확보된 3개 기종을 전수 조사하여 본 인터페이스 계약의 포용성을 검증하였다.

**Boston Dynamics Spot** — 4족 보행 로봇. gRPC + Protocol Buffers 기반. 본 설계의 표준 참조 모델 역할을 한다. `DirectoryService.ListServiceEntries`를 통한 동적 서비스 탐색, `RobotIdService.GetRobotId`를 통한 고유 식별자(`serial_number`, `species`, `software_release`) 조회, `GetRobotHardwareConfiguration`을 통한 불리언 기반 하드웨어 기능 판별 체계를 제공한다. 결함 진단은 `SystemFault{severity, dtc, attributes}` 및 `BehaviorFault{Cause ∈ FALL|HARDWARE|LEASE_TIMEOUT, Status ∈ CLEARABLE|UNCLEARABLE}`로 구성되어 로봇 스스로 복구 가능성을 판정한다. 제어권 소유권은 `Lease{resource, epoch, sequence[]}` 벡터 클럭으로 관리된다.
⚠️ **라이선스 제약**: `20191101-BDSDK-SL` §2(c)는 BD 하드웨어 전용 사용을 명시하며 §2(b)는 재라이선스를 금지한다. 따라서 Spot protobuf 스키마 및 생성 코드를 벤더 중립 공통 모듈에 포함할 수 없으며, 모든 코드는 전용 Spot 어댑터 내부로 완전 격리한다.

**Unitree G1** — 휴머노이드 로봇. BSD-3-Clause 라이선스로 개방된 SDK를 제공하며, CycloneDDS 기반으로 ROS2 RMW 레이어에 직접 참여한다. 그러나 고수준 인터페이스의 완성도가 낮다. API 대다수가 정수형 FSM ID 기반의 원시 래퍼(`Damp()=SetFsmId(1)`, `Start()=SetFsmId(500)`)이며, **로봇 신원 질의 인터페이스가 부재**하고 결함 상태를 능동 보고하지 않아 클라이언트 측에서 과열 및 자세 이상을 자체 판정(`terminations.hpp`)해야 한다. 관절 한계 정보는 SDK가 아닌 URDF 파일(`<limit lower upper effort velocity>`)에만 기술되어 있으며, 인증 메커니즘이 없어 네트워크 연결 자체가 전권을 의미한다.

**Agility Digit / Arc** — 휴머노이드 로봇. 로봇 기체는 WebSocket JSON API(`ws://<ip>:8080`, 서브프로토콜 `json-v1-agility`, 메시지 포맷 `["type", {...}, refnum]`)를 제공한다. 본 표준 계약과 충돌하는 4가지 제약 조건:
- `change-action-command` 권한은 전체 시스템에서 단 하나의 클라이언트만 독점하며, 권한 상실 시 로봇은 즉시 `action-idle` 상태로 리셋된다 (→ §4.9 제어권 상실 대응).
- 태스크 취소 및 일시정지 프리미티브가 부재하며, 중단은 신규 액션 덮어쓰기 방식으로만 가능하다 (→ §7.2의 `Support` 3값 모델).
- 공식 매뉴얼에 명시된 대로 종착 상태가 영구 고정되지 않는다(*"This status does not latch once reached"*). `success` 상태에서 다시 `running`으로 역전될 수 있다 (→ §4.4 터미널 래치 불변식 및 `TERMINAL_STATE_VIOLATED` 결함).
- 실패 사유가 비구조화된 텍스트(`info`)로만 제공되어 어댑터 레이어에서 정형화된 `error_type` 어휘로의 변환이 필수적이다.

**Agility Arc(클라우드 오케스트레이션)**는 본 아키텍처와 동일한 설계 결론을 채택하고 있다. `organization → facility → workcell → device` 계층과 `workflow`/`skill`/`intervention` 도메인 모델을 공유하며, 능력 선언을 `(deviceModelIds, oasVersion) → 블록 집합`으로 해석하고 워크플로우를 특정 버전에 고정(pinning)한다. UI 상의 "No model guarantee" 경고는 §15.1의 한계와 일치하며, 워크플로우 상태로 `CANCELED_WITH_RECOVERY`, `CANCELED_RUNNING_RECOVERY`, `CANCELED_FAILED_RECOVERY` 및 1급 `INTERVENTION` 개념을 채택하고 있어 본 설계의 `NEEDS_INTERVENTION` 및 `CANCELLED_RECOVERY_FAILED` 규격과 정합한다.

**프로파일 요구 항목과 실물 기종 지원 현황 대조:**

| §7.2 항목 | Spot | Unitree G1 | Digit |
|---|---|---|---|
| 기종 좌표 | ✅ `RobotId` | ❌ 신원 질의 부재 | △ `robot-info` 4개 필드 |
| 지원 스킬 및 버전 | ✅ `ListServiceEntries` | △ 상지 전용 `GetActionList` | ✅ 고정 액션 어휘 |
| 취소·일시정지 가능 여부 | △ 선언 메커니즘 부재 | ❌ 미지원 | ❌ 개념 부재 |
| 파라미터 범위 및 단위 | △ `Skeleton` | ✅ URDF `<limit>` | △ 문서상 정적 5 kg |
| 프로토콜 한계 | ❌ 미선언 | ❌ 미선언 | ❌ 미선언 |
| 실패 모드 분류 | ✅ 정형화된 결함 보고 | ❌ 클라이언트 자체 판정 | ❌ 비정형 문자열 |
| 상태 발행 주기 | △ `liveness_timeout_secs` | ❌ 미지원 | ✅ `query-group{period}` |

어떤 실물 로봇도 프로파일을 완전하게 채우지 못하며, 이는 프로파일이 기체의 능동 보고가 아닌 벤더 사양 문서로부터 분석·파생되는 데이터 모델이기 때문이다(§15.1). 이러한 대조를 통해 "미지원(NO)"과 "근거 부재로 인한 미확인(UNKNOWN)"을 명확히 분리하는 `Support` 3값 체계가 도출되었다.

**산업 표준 동향:**
- **ISO 21423** (산업용 모바일 로봇 상호운용 표준, MQTT+JSON, MassRobotics와 VDA5050 통합): Stage 60.00(2026-07) 단계로 발행되었으며 `capabilities` 객체를 정의하고 있다.
- **IDTA 02020 Capability Description** (AAS 능력 서브모델, 2026-04 발행): §1.8.4에서 범용 스킬 서브모델의 표준화 미비점을 명시하고 있어, 능력 메타데이터와 구체적 스킬 실행 규격 간의 간극을 드러내고 있다.
- **NVIDIA `isaac_mission_control`**: VDA5050 규격 외 확장을 통해 AGV 클래스에 `MANIPULATOR`와 `HUMANOID`를 자체 추가하였다. 이는 기존 표준이 휴머노이드 및 다관절 로봇을 온전히 수용하지 못하고 있음을 실증적으로 입증한다.

## 3. 아키텍처

### 3.1 모듈 구성

```
picasso/
  contracts/          proto. 스킬·태스크·이벤트·결함           A-1 A-2 A-4
  profile/
    schema/           능력 프로파일 JSON Schema                C-1
    profiles/         기종 프로파일 문서들 (*.json)
    fixtures/         게이트·시험 전용 픽스처 (프로파일·요구 집합)
  profile-model/      프로파일 문서의 읽기 전용 모델 — gate·mimic 공유 (ADR 29)
  capability/         능력의 투영과 판정 — 프로파일 → Capability, 요구 집합 대 Capability 협상.
                      mimic·어댑터 호스트 공유 (§15.98·§15.100). 전송 계층 무의존
  uplink/             발신자 상향 결선 — 브로커 발행(§3.5)과 레지스트리 적재(핸드셰이크·태스크·생존·폴백). mimic·어댑터 호스트 공유 (§15.99)
  registry/           개정판·어댑터·원장·변경 계획·카탈로그    §8, §9
  mimic/              프로파일 주도 가상화 에뮬레이터 + 제어 채널 C-2
  client/             계약 소비자 — 완료 기준 증명용
  picasso/            미들웨어 코어 — 정준 모델 및 공통 실행 엔진. 기종 무의존 (ADR 38)
  adapter-core/       어댑터 공유 계약 어휘 및 RobotAdapter. 기종 무의존 (ADR 33)
  adapter-host/       개별 어댑터를 gRPC 계약 서비스로 노출하는 서버. 기종 무의존 (ADR 39)
  adapter-<vendor>-<model>/
                      실물 기종 어댑터. 기종별 지식이 격리되는 유일한 영역 (ADR 33)
                      adapter-boston-dynamics-orbit/ 만 단일 기체가 아닌 플릿 제어에 연동 (§15.102)
  gate/
    src/              검증 라이브러리. CI 및 registry 공통 호출 D-1
    negative/         네거티브 테스트 케이스 데이터 (비정상 proto 조각, 합성 diff)
  harness/            계약 검증 스위트 실행기                  §12
  docs/adr/           아키텍처 결정 기록                        D-2
```

`profile/profiles/**`와 `profile/fixtures/**`의 분리는 엄격한 규약이다. §11.2 검사 8이 전자의 경로만으로 "프로파일만 변경된 PR"을 판정하므로, 테스트용 픽스처가 혼입되면 변경 판정 로직이 왜곡된다.

### 3.2 의존 규칙

**`contracts/`는 프로젝트 내 모듈에 대한 의존성을 전혀 갖지 않는다(의존성 0).** 이는 openTCS `opentcs-api-base`의 아키텍처 원칙과 동일하며, §11.2 검사 5가 CI 빌드 파이프라인에서 이를 강제한다.

**빌드 의존성**: 정본 사양은 `docs/architecture.md` §4b에 기술되어 있으며, `DocumentClaimsTest`가 각 모듈의 `build.gradle.kts`와 지속적으로 정합성을 기계 검증한다. 주요 비대칭성 및 격리 원칙은 다음과 같다:

- `capability`: gRPC 라이브러리에 직접 의존하지 않으며 판정 불가 상태는 순수 도메인 타입으로 표현된다. 전송 매핑은 서비스 계층이 담당한다.
- `uplink`: `registry`에 빌드 의존하지 않으며 상향 적재는 표준 HTTP 인터페이스를 경유한다.
- `adapter-host`: 개별 기종별 어댑터 모듈에 의존하지 않으며 런타임 조립은 기종 인식 계층에서 수행된다(ADR 39).
- `mimic`: 투영 및 협상 판정 로직은 `capability`로, 메시지 발행 및 레지스트리 적재는 `uplink`로 위임 분리되었다(2026-09-10).
- `client` 및 `adapter-<vendor>-<model>`: 하위 격리 원칙을 준수한다.

**`adapter-*`는 기종마다 모듈 하나다(ADR 33).** 게이트 7번이 보는 여덟 (`client` · `mimic` · `harness` · `adapter-core` · `picasso` · `capability` · `adapter-host` · `uplink`) **밖**에 두는 것이 요점이며, 기종 지식이 갈 곳이 정확히 거기라서 나머지가 기종을 모를 수 있다. 로봇 어댑터 셋(`adapter-agility-digit`·`adapter-boston-dynamics-spot`·`adapter-unitree-g1`)의 의존성이 `adapter-core`·`contracts`뿐인 이유도 동일하다: 어댑터가 `registry`를 알면 순환 회피 규칙이 위반되고, `profile-model`을 알면 구현과 선언의 원천이 결합되기 때문이다. 반면 `adapter-boston-dynamics-orbit`는 플릿 단위 연동 및 배치 런처 특성상 `adapter-core`, `adapter-host`, `contracts`, `profile-model`, `uplink`를 참조한다(ADR 37, 39).

**`adapter-core`의 태동**: ADR 33에서 예고한 바와 같이, 다수 어댑터 간의 공통 어휘 추출 요구에 따라 신설되었다. 추출된 공통 모듈은 기종 무의존성을 유지해야 하므로 게이트 7번 검사 목록에 편입되었다. 검사 7번 대상 모듈은 현재 총 8개이다 — `client` · `mimic` · `harness` · `adapter-core` · `picasso` · `capability` · `adapter-host` · `uplink`. 기종별 어댑터 모듈 자체는 기종 지식을 캡슐화하는 목적을 가지므로 이 목록에서 제외된다. 정본 목록은 코드(`Check07ModelBranching.MODULES`)에 선언되어 있으며 `DocumentClaimsTest`에 의해 지속 검증된다.

**`profile-model` 신설 (ADR 29)**: 프로파일 문서 파싱 로직이 `gate`와 `mimic` 양쪽에 필요함에 따라, `mimic`이 `gate`의 buf 실행기 및 9개 검사 규칙에 불필요하게 결합되는 것을 차단하기 위해 공통 모듈로 분리하였다.

**`client → profile-model` 의존성 (Chunk 3a)**: 요구 사양이 코드가 아닌 설정 파일로 취급됨에 따라, 설정 파싱을 위해 클라이언트 클래스패스에 `profile-model`이 추가되었다. `ClientBoundaryTest`를 통해 클라이언트 계층이 기종 전용 형식을 직접 참조하지 않도록 경계를 통제한다.

**`gate`와 `contracts`의 의존성 분리**: `gate`는 `contracts`에 직접 빌드 의존하지 않으며, `buf build` 및 Gradle protoc 태스크가 생성한 `FileDescriptorSet` 바이너리 바이트(`Resource.CONTRACT_DESCRIPTOR`, `picasso.desc`)를 런타임 리소스로 수신한다. 태스크 실행 순서는 Gradle 의존성(`:gate:test` depends on `:contracts:generateProto`)으로 보장되며, 클래스패스 오염 없는 순수 검증을 유지한다.

**런타임 통신 경로 (HTTP/MQTT. 독립 기동 지원):**

| 방향 | 내용 | 비가용 시 거동 |
|---|---|---|
| `mimic` ⇢ `registry` | 프로파일 로드 및 변경 폴링 (§10.2, §10.3) | 로컬 파일 모드로 자립 동작 |
| `mimic` ⇢ `registry` | 핸드셰이크 결과 보고 (§5.4) | 로컬 파일 기록 후 정상 진행 |
| `mimic` ⇢ 브로커 | 상태·이벤트·연결 발행 | 브로커 버퍼링 또는 재시도 |
| `adapter-host` ⇢ `registry` | 핸드셰이크 결과, 하트비트, 태스크 관측 적재 (`uplink` 공통 결선) | 발행만 수행하고 무적재 동작 |
| `adapter-host` ⇢ 브로커 | 상태·이벤트·연결 발행 (토픽, 헤더, 시퀀스 규격 통일) | 브로커 버퍼링 또는 재시도 |
| `registry` ⇠ 브로커 | 이벤트 구독 — 능력 변경, 태스크 상태 전이 적재, 결함 | 관측 테이블 누락 및 드레인 판정 불가 |
| `registry` ⇢ 브로커 | 사이트 단위 능력 카탈로그 스트림 발행 (§9.6) | 상위 시스템이 폴링 엔드포인트로 대체 |
| `harness` ⇢ `registry` | 시험 요청 폴링 및 결과 보고 (§8.4 ②) | 로컬 직접 실행 모드로 대체 |
| `client` ⇢ `mimic` | gRPC 명령/질의 및 MQTT 상태 구독 | 통신 실패 처리 |
| `client` ⇢ `registry` | 요구 등록 (§9.2) 및 클라이언트 측 결함 보고 (§6.2) | 원장 누락 (변경 영향 계산 유예) |

**순환 방지 아키텍처 규칙:**
1. **`registry`는 `mimic`과 `harness`를 직접 호출하지 않는다.** 시험 요청은 `revision_test_request` 레코드로 적재되며, `harness`가 비동기 폴링으로 작업을 인출하여 실행한 후 결과를 보고한다.
2. **`registry`는 하위 모듈에 직접 푸시하지 않는다.** 갱신 사항은 각 기체가 능동적으로 폴링하며, 관측 데이터는 브로커 토픽 구독을 통해 비동기 수신한다.

### 3.3 모듈별 핵심 책임

- **`contracts/`**: 런타임 로봇 인터페이스의 메시지 스키마 및 헤더 규격, 계약 식별자(`contract_digest`, `contract_semver`)를 정의한다. 구체적 수치 제약이나 비즈니스 로직은 배제한다.
- **`profile/`**: 기종별 하드웨어 사양 및 제약 조건을 표현하는 선언 형식과 실제 기종 프로파일 문서를 보관한다.
- **`capability/`**: 프로파일의 `Capability` 투영 및 클라이언트 요구 사양과의 협상 판정을 수행한다. 전송 계층과 완전히 독립적이다.
- **`uplink/`**: 발신 기체의 상향 인터페이스를 통합하여 브로커 메시지 발행 및 레지스트리 비동기 적재를 처리한다.
- **`registry/`**: 개정판 및 어댑터 생명주기 관리, 바인딩 매핑, 의존 원장, 변경 계획 실행 엔진, 사이트 카탈로그 및 진단 API를 제공한다.
- **`mimic/`**: 프로파일을 동적으로 해석하여 표준 계약을 구현하는 가상화 에뮬레이터 서비스를 제공한다.
- **`client/`**: 표준 계약을 구동하여 완료 기준을 실증하는 경량 클라이언트 라이브러리다.
- **`picasso/`**: 미들웨어 코어 엔진으로서 정준 모델, 실행 상태머신, 증적 결합, 작업 취소 및 결과 처리를 총괄한다.
- **`adapter-core/`**: 기종 독립적인 공통 어댑터 인터페이스(`RobotAdapter`) 및 표준 어휘를 정의한다.
- **`adapter-host/`**: 임의의 어댑터 인스턴스를 표준 gRPC 계약 서버로 호스팅하는 런타임 셸이다.
- **`gate/`**: CI 파이프라인 및 레지스트리 등록 시 공통으로 사용되는 단일 검증 라이브러리다.
- **`harness/`**: 가상화 환경(`mimic`) 및 클라이언트를 조율하여 자동화된 계약 검증 스위트를 실행한다.

### 3.4 구현 기술 스택

설계노트 §4.9를 준수한다. 전체 모듈은 **Kotlin** 기반으로 구현되며 Spring Boot 프레임워크를 활용하되, 도메인 핵심 로직은 프레임워크 비종속적으로 설계한다. Spring MVC + Virtual Threads 조합을 채택하며 WebFlux 리액티브 스택은 배제한다.

`mimic` 또한 Kotlin으로 구현하여 sealed class 및 exhaustive `when` 패턴 매칭을 통해 스킬 상태머신과 실패 분류 처리를 컴파일 타임에 철저히 검증한다.

영속화 저장소로는 **PostgreSQL**(JSONB 지원)을 표준으로 사용한다.

### 3.5 전송 계층 경계

| 전송 프로토콜 | 담당 영역 |
|---|---|
| **gRPC** | 동기식 명령 및 질의 — RPC 전체, `Negotiate`, `GetSnapshot`, `GetCapabilities` |
| **MQTT** | 비동기 발행 — 로봇 상태, 이벤트 스트림, 연결 상태 라이프사이클 |

§4.7에 정의된 모든 이벤트는 MQTT `event` 토픽으로 브로드캐스트된다. 태스크 상태 전이 또한 이벤트 스트림에 포함되며, `WatchTask` 스트림은 이에 더해 미세 진행률 및 부분 결과를 요청 클라이언트에 독점 스트리밍한다. 따라서 제3의 관측자는 `event` 스트림과 스냅샷 결합만으로 전체 상태를 재구성할 수 있다.

**이중 시퀀스 카운터 분리:**

| 카운터 | 식별 범위 | 주요 목적 |
|---|---|---|
| `sequence` | **단일 기체 단위**, 세션 내 단조 증가 | MQTT `state` 및 `event` 스트림의 결손 감지 및 순서 재정렬 |
| `update_index` | **단일 태스크 단위**, 0부터 단조 증가 | gRPC `WatchTask` 스트림의 재접속 시 스트리밍 재개 지점 식별 |

카운터를 분리함으로써 gRPC 스트림 전용 메시지가 MQTT 소비자의 결손 감지 로직에 오탐을 유발하는 문제를 근본적으로 차단한다.

토픽 스트림 유형(`{stream}`)은 `state`, `event`, `connection` 3종으로 엄격히 제한된다.

**순서 및 재정렬 보장**: MQTT 프로토콜 자체는 파티션 기반 전역 순서를 보장하지 않으므로, 기체별 전용 토픽과 `sequence` 번호를 통해 수신 측에서 계약 기반 재정렬을 수행한다. 소비자는 불연속 시퀀스 감지 시 **재정렬 윈도우**(기본값: 후속 이벤트 8건 수신 또는 2초 경과 중 선착) 동안 판정을 유예하여 네트워크 지터에 대응한다.

## 4. 계약 (`contracts/`)

### 4.1 파일 구성 및 의존성

| 파일명 | 정의 대상 | 백로그 연계 |
|---|---|---|
| `common.proto` | **공통 헤더 규격**, `Reference`, `Lifetime`, `ProfileRef`, `Support`, `Resolution`, `RejectionCode`, `Rejection`. 외부 의존성 없음 | — |
| `fault.proto` | 결함 상태 및 진단 모델 | A-2 |
| `skill.proto` | 스킬 상태머신, `Capability`, `GetCapabilities`, `Negotiate` | A-1 |
| `skill_catalog.proto` | **계약 소유의 표준 스킬 어휘 사전.** `since_minor`, `is_optional`, `skill_type_max_minor`, 파라미터 네임스페이스 식별용 `is_site_reference`(위치) 및 `is_object_reference`(대상) 커스텀 옵션 정의 (§15.78) | A-1 |
| `task.proto` | 장기 실행 태스크 제어 RPC 및 상태 수명주기 | A-4 |
| `event.proto` | 이벤트 모델, `StateMessage`, `GetSnapshot`, `ReplayEvents`, `CapabilityChanged`, 연결 수명주기 | A-2 |

공통 헤더를 `common.proto`로 격리한 것은 protobuf import 간의 순환 참조를 방지하기 위함이다. 헤더가 특정 도메인 메시지와 결합될 경우 전체 프로토콜 계층에 걸쳐 컴파일 순환이 발생한다.

`skill_catalog.proto`의 분리는 §5.2의 스킬 어휘 진화 규칙(Major별 독립 정의 및 Minor 도입 버전 추적)과 §8.3의 읽기 전용 스키마 투영 요구사항을 충족하기 위한 설계다. 스킬 어휘를 Protobuf에 정형화함으로써 `buf breaking` 도구를 통해 파라미터의 무단 삭제나 타입 변조를 컴파일 단계에서 차단한다.

`max_minor`와 `optional`은 유도 계산이 아닌 명시적 선언 방식을 취한다. 파라미터 추가 없는 Minor 개정판의 발행이나, 최초 버전부터 선택적인 파라미터의 존재성을 정밀하게 표현하기 위해 전용 옵션을 부여한다.

### 4.2 스킬 상태머신

OPC UA 기반 Fortiss / VDMA·OPC Foundation SOArc 스킬 상태 모델의 구조를 언어 중립적 사양으로 이식하였다.

| 상태 | 정의 |
|---|---|
| `READY` | 실행 대기 상태. 초기 기본 상태이자 정상 완료 후 복귀 상태 |
| `RUNNING` | 스킬 동작 실행 중 |
| `SUSPENDED` | 일시정지 상태 |
| `HALTED` | 비정상 중단 상태. 재기동을 위해서는 `Reset` 전이가 필수 |

| 전이 | 시작 상태 | 전이 상태 | 유발 조건 |
|---|---|---|---|
| `Start` | `READY` | `RUNNING` | `StartTask` 또는 `RetryTask` (실행 파라미터 전달) |
| `Suspend` | `RUNNING` | `SUSPENDED` | `PauseTask` (`pause_support ≠ NO`인 스킬에 한함) |
| `Resume` | `SUSPENDED` | `RUNNING` | `ResumeTask` |
| `Halt` | `RUNNING`, `SUSPENDED` | `HALTED` | 취소 확정, 또는 `can_continue_current_task=false` 결함 발생 |
| `Complete` | `RUNNING` | `READY` | 정상 완료 |
| `Reset` | `HALTED` | `READY` | **엔진 내부 자동 전이.** 태스크 종착 진입, `RETRIABLE` 전이, 갱신에 따른 스킬 재시작 시 자동 수행 (§4.4) |

상기 정의 외의 모든 상태 전이는 엄격히 거부되며 `INVALID_TRANSITION` 오류를 반환한다. 이는 Kotlin sealed class와 exhaustive `when` 매칭에 의해 컴파일 타임 및 런타임에 이중 보장된다.

`Reset`을 외부 RPC로 노출하지 않는 것은 시스템의 상위 정책을 계약에 침투시키지 않기 위함이다. 리셋의 인가 권한은 미션 제어 계층의 책임이며 본 계약의 비목표다.

파라미터 전달과 실행 트리거는 단일 RPC 호출로 원자적으로 결합하여 동시성 경쟁 조건을 방지한다.

### 4.3 값 어휘 체계

| 어휘 | 정의 및 허용 값 |
|---|---|
| `SkillState` | `READY`, `RUNNING`, `SUSPENDED`, `HALTED` |
| `TaskState` | §4.4 수명주기 상태 참조 |
| `ConnectionState` | `ONLINE`, `OFFLINE`, `HIBERNATING`, `CONNECTION_BROKEN` |
| `ValueType` | `BOOL`, `INTEGER`, `NUMBER`, `STRING`, `ENUM` |
| `Reference` | `{key, value}` 형태의 구조체 (`key` ∈ `task_id`, `skill_id`, `robot_id`, `parameter_key`) |
| `Lifetime` | `UNTIL_CLEARED`, `UNTIL_NEW_TASK`, `UNTIL(timestamp)` |
| `Support` | `YES`, `NO`, `UNKNOWN` (실물 제약 반영 3값 모델) |
| `Resolution` | `SELF_RETRIABLE`, `NEEDS_INTERVENTION`, `TERMINAL` (결함 해결 절차 분류) |
| `RejectionCode` | 하위 거절 코드 체계 참조 |

Protobuf wire 전송 시에는 `ENUM_VALUE_PREFIX` 규칙에 따라 접두사가 부여되며, 미지정 기본값 `_UNSPECIFIED`가 0번에 배치된다 (예: `CONNECTION_STATE_CONNECTION_BROKEN`, `SUPPORT_YES`).

`RejectionCode`는 인터페이스 표면별로 유형화된다:

| 인터페이스 표면 | 거절 코드 |
|---|---|
| `Negotiate` (5개) | `MAJOR_MISMATCH`, `SKILL_ABSENT`, `REQUIRED_OPTIONAL_MISSING`, `LIMIT_EXCEEDED`, `IDENTITY_MISMATCH` |
| 태스크 RPC (9개) | `CAPABILITY_WITHDRAWN`, `CANCEL_UNSUPPORTED`, `PAUSE_UNSUPPORTED`, `OUTDATED_REVISION`, `INVALID_TRANSITION`, `SKILL_ABSENT`, `IDENTITY_MISMATCH`, `PARAMETER_INVALID`, `UPDATE_UNSUPPORTED` (0.7.0 반영, §15.109) |
| `ReplayEvents` (1개) | `SEQUENCE_EVICTED` |

`CAPABILITY_WITHDRAWN`은 핸드셰이크 이후 런타임에 능력이 상실된 스킬에 대해 `StartTask`가 호출되었을 때 반환되며, 응답에 최신 `capability_epoch`를 동반하여 소비자가 캐시를 갱신하도록 유도한다. 반면 최초부터 선언되지 않은 스킬의 호출은 `SKILL_ABSENT`로 구분된다.

`PARAMETER_INVALID`는 파라미터가 프로파일 사양을 위반한 경우 발생한다(미인식 코어 키의 fail-closed 처리, 필수 파라미터 누락, 값 범위 및 허용치 초과 등).

**판정 위치 원칙**: 요청 자체를 해석할 수 없는 경우 gRPC 전송 계층 상태 코드(`INVALID_ARGUMENT`, `NOT_FOUND` 등)로 응답하며, 요청은 유효하지만 비즈니스 규칙에 의해 거절된 경우 응답 본문의 `Rejection` 메시지로 명시적 반환한다. 유일한 예외는 `GetCapabilities` 응답의 구조적 한계로 인한 `INVALID_ARGUMENT` 반환뿐이다.

**`error_type` 명명 규칙**: `SCREAMING_SNAKE_CASE` 포맷을 따르며, 표준 코어 값은 계약이 소유하고 벤더 확장은 `X_<VENDOR>_` 접두사를 의무적으로 사용한다. 표준 코어 8종:

| `error_type` | 원인 및 정의 |
|---|---|
| `LOCALIZATION_LOST` | 로봇 위치 추정(Localization) 실패 |
| `PAYLOAD_LOST` | 이송 중인 물리 화물(Payload) 이탈 또는 분실 |
| `SKILL_EXECUTION_FAILED` | 스킬 내부 로직 수행 중 오류 발생 |
| `PARAMETER_OUT_OF_RANGE` | 프로파일 선언 제약 범위를 벗어난 파라미터 전달 |
| `CONTRACT_REVISION_MISMATCH` | 계약 개정판 불일치 경보 (§5.5) |
| `INTERNAL_ERROR` | 기타 내부 런타임 결함 |
| **`TERMINAL_STATE_VIOLATED`** | **종착 확정 후 기체의 무단 재기동 감지 (래치 불변식 위반)** |
| **`CONTROL_AUTHORITY_LOST`** | **제어권이 외부 클라이언트에 의해 탈취됨 (§4.9)** |

뒤의 2종은 프로파일에 정적으로 선언되는 실패 모드가 아니며 어댑터가 런타임에 발행하는 전용 상태이다.

### 4.4 장기 실행 태스크

```
StartTask(TaskRequest)                   -> TaskHandle    // 접수 응답 (비종착)
WatchTask(TaskHandle, from_update_index) -> stream TaskUpdate
RetryTask(TaskHandle)                    -> Ack           // RETRIABLE 상태에서만 인가
PauseTask(TaskHandle)                    -> Ack           // pause_support=NO 시 PAUSE_UNSUPPORTED
ResumeTask(TaskHandle)                   -> Ack
CancelTask(CancelRequest)                -> CancelAck     // cancel_support=NO 시 CANCEL_UNSUPPORTED
GetSnapshot(robot_id)                    -> Snapshot      // 현재 기체 상태 및 당시 sequence
ReplayEvents(robot_id, from_sequence)    -> stream Event  // 재생 버퍼 기반 이벤트 재전송
GetCapabilities(robot_id)                -> Capability    // 유효 능력 투영 스키마 (§7.3)
```

| 상태 | 종착 여부 | 정의 |
|---|---|---|
| `ACCEPTED` | 아니오 | 작업 접수 완료, 실행 대기 |
| `RUNNING` | 아니오 | 스킬 실행 진행 중 |
| `PAUSED` | 아니오 | `PauseTask`에 의해 일시정지됨 |
| `CANCELLING` | 아니오 | 취소 요청 접수 후 물리적 안전 복구 진행 중 |
| `RETRIABLE` | 아니오 | 실행 실패했으나 로봇 자율 재시도가 가능한 상태 |
| `NEEDS_INTERVENTION` | 아니오 | 실행 실패했으며 작업자 개입이 필수적인 상태 |
| `SUCCEEDED` | **예** | 작업 정상 완결 |
| `FAILED` | **예** | 회복 불가능한 최종 실패 |
| `CANCELLED` | **예** | 취소 요청에 따른 물리 복구가 정상 완료됨 |
| `CANCELLED_RECOVERY_FAILED` | **예** | 취소 절차 중 물리 복구에 최종 실패함 |

취소 절차는 즉각적 완료가 아니며 반드시 물리적 복구 시퀀스를 동반한다. `CancelTask` 요청 시 태스크는 `CANCELLING` 상태로 진입하여 하드웨어를 안전한 상태(예: 휴머노이드의 화물 안전 거치)로 전이시킨다. 복구 완료 시 `CANCELLED`, 복구 실패 시 `CANCELLED_RECOVERY_FAILED`로 종착한다.

**잔여 물리 상태 (`WatchTaskResponse.hold`, v0.4.0)**: 로봇의 물리적 파지 상태를 `HoldState{kind, object_ref, reason}`로 명시한다. `kind`는 4가지 상태를 가진다: `UNSPECIFIED`, `NOT_OBSERVABLE`, `EMPTY`, `HOLDING`.
- 불변식 1: `CANCELLED` ⇒ `kind ≠ HOLDING` (정상 취소는 빈손 상태를 요구한다).
- 불변식 2: `CANCELLED_RECOVERY_FAILED` ⇏ `HOLDING` (화물 낙하 분실로 인한 실패 시 손은 빈 상태일 수 있다).

**`CancelTask`는 비종착 6개 상태 전역에서 유효하다.** 특히 `RETRIABLE` 및 `NEEDS_INTERVENTION` 상태에서도 취소가 가능해야 작업 포기 태스크가 시스템에 영구 체류하여 축소 드레인(Drain)을 영구 차단하는 문제를 방지할 수 있다.

`RetryTask`는 `RETRIABLE` 및 `NEEDS_INTERVENTION` 상태에서만 인가되며, 스킬을 동일 파라미터로 재기동하고 시도 횟수(`attempt`)를 증가시킨다. 자동 재시도와 작업자 수동 개입의 분리는 불필요한 자원 낭비를 차단하는 핵심 기준이다.

**터미널 상태의 영구 래치(Latch) 불변식**: 종착 상태에 도달한 태스크는 어떤 상황에서도 비종착 상태로 역전될 수 없다. 비래치 기종(예: Agility Digit)의 경우 어댑터 계층에서 래치 책임을 전담하며, 기체의 부적절한 추가 상태 보고는 `TERMINAL_STATE_VIOLATED` 결함으로 감지하여 시스템 무결성을 방어한다.

진행률은 0.0부터 1.0까지의 부동소수점이며, `progress_basis`(`ProgressBasis{kind, basis, reason}`)를 동반하여 단순 미측정 상태와 정체 상태를 엄격히 구분한다. 단조 비감소 불변식은 동일 `(task_id, revision, attempt)` 구간 내에서만 유지된다.

**멱등성 키: `(task_id, revision)` 단조쌍**:

| 수신된 revision | 처리 정책 |
|---|---|
| 신규 `task_id` | 신규 태스크 핸들 발급 및 접수 |
| 현재와 동일 | 기존 핸들 반환 (네트워크 재전송에 대한 멱등성 보장) |
| 현재보다 낮음 | `OUTDATED_REVISION` 거절 |
| 현재보다 높음 | 런타임 동적 파라미터 갱신 수행 |

**파라미터 갱신 규칙**: 실행 중인 태스크에 대한 파라미터 변경 시 태스크 자체를 재기동하지 않고 상태별 정밀 전이를 수행한다 (`RUNNING` 상태의 경우 스킬 `Halt` → `Reset` → 새 파라미터 `Start`, 불가 시 `UPDATE_UNSUPPORTED` 반환). 갱신은 태스크 개정판 고정(Pinning)을 변경하지 않는다.

### 4.5 두 상태머신의 관계

태스크는 순차적 스킬 호출의 상위 수명주기이며, 스킬 FSM은 현재 활성화된 하위 실행 상태를 대변한다.

| 태스크 상태 | 현재 스킬 FSM 상태 |
|---|---|
| `ACCEPTED` | 스킬 미할당 |
| `RUNNING` | `RUNNING` |
| `PAUSED` | `SUSPENDED` |
| `CANCELLING` | `RUNNING` 또는 `SUSPENDED` (복구 동작 진행 중) |
| `RETRIABLE`, `NEEDS_INTERVENTION` | `HALTED` → 자동 `Reset` → `READY` (`RetryTask` 대기) |
| `FAILED`, `CANCELLED`, `CANCELLED_RECOVERY_FAILED` | `HALTED` → 자동 `Reset` → `READY` |
| `SUCCEEDED` | `READY` (`Complete` 완료 후) |

**전파 규칙 3대 원칙**:
1. 스킬 `Halt` 발생 시 태스크 종착 상태는 결함의 `Resolution`에 의해 결정된다 (`SELF_RETRIABLE` → `RETRIABLE`, `NEEDS_INTERVENTION` → `NEEDS_INTERVENTION`, `TERMINAL` → `FAILED`).
2. 복합 단계 태스크에서 특정 하위 스킬의 `Halt`는 태스크 전체의 종착으로 전파된다.
3. `CANCELLING` 진행 중 발생하는 스킬 `Halt`는 물리 복구 실패로 간주되어 `CANCELLED_RECOVERY_FAILED`로 확정된다.

### 4.6 결함 모델

처방적 3분류(재시도/이관/개입)를 계약에서 배제하고, 잔여 하드웨어 역량을 직접 기술하는 객관적 모델을 구축한다.

```protobuf
message Fault {
  string   error_type                 = 1; // §4.3 표준 명명 규칙
  bool     can_continue_current_task  = 2;
  bool     can_accept_new_task        = 3;
  repeated Reference references       = 4;
  string   error_hint                 = 5; // 작업자 조치 안내
  Lifetime active_until               = 6;
  FailureClass failure_class          = 7; // 정준 실패 분류 (상위 라우팅 기준)
  string   vendor_detail              = 8; // 벤더 원시 진단 문자열
}
```

잔여 역량 불리언 2종을 통해 상위 오케스트레이터가 결정론적으로 후속 조치를 도출할 수 있도록 지원하며, `references`에 특정 `skill_id`를 명시하여 기체 전체가 아닌 개별 스킬 단위의 결함을 표현한다.

### 4.7 상태와 이벤트

상태(현재 스냅샷)와 이벤트(상태 전이 사실)를 분리하여 독립 발행한다.

발행 이벤트 4종:
- `SKILL_TRANSITION`: `{skill_type, from, to, task_id?}`
- `TASK_TRANSITION`: `{task_id, skill_type, from, to, revision, attempt}` (레지스트리의 `task` 테이블 적재 및 드레인 판정에 필수)
- `FAULT_RAISED` / `FAULT_CLEARED`: 결함 발생 및 해소
- `CAPABILITY_CHANGED`: `{robot_id, capability_epoch, added[], removed[], cause}`

연결 상태 스트림 (MQTT Retain 및 Last Will 활용):
- `ONLINE`: 연결 수립 및 정상 통신 중
- `OFFLINE`: 정상적인 셧다운 종료
- `HIBERNATING`: **연결을 유지하되 전력 절감 등을 위해 상태 발행을 의도적으로 중단한 상태**
- `CONNECTION_BROKEN`: 비정상 통신 단절 감지

### 4.8 세션, 시퀀스, 재생 버퍼

모든 통신 단위는 기체 식별자(`robot_id`)를 기준으로 격리된다.
- `session_id`(ULID)는 기체 온라인 연결 시 신규 발급되며, `sequence`는 세션 내에서 0부터 단조 증가한다.
- 세션 전환 시 소비자는 기존 시퀀스를 폐기하고 `GetSnapshot`을 통해 상태를 재동기화한다.
- 발신 기체는 최근 N개의 이벤트를 인메모리 순환 버퍼에 보관하며, 범위 초과 요청 시 `SEQUENCE_EVICTED`로 응답한다.

### 4.9 제어 권한 상실 (CONTROL_AUTHORITY_LOST)

실물 모바일 로봇은 예외 없이 단일 클라이언트에 대한 배타적 제어권을 요구한다. 제어권의 정책적 협상 및 스케줄링은 미션 계층의 비목표이지만, **권한 상실이라는 물리적 사건은 태스크의 실패를 직접 유발하므로 계약 내부로 수용**한다.

제어권 상실 감지 시 `CONTROL_AUTHORITY_LOST` 결함(`can_continue_current_task=false`, `can_accept_new_task=false`)을 발행하여 작업 실패가 로봇 하드웨어 고장과 혼동되지 않도록 명확한 관측성을 제공한다.

## 5. 능력 호환성

### 5.1 문제 정의

능력은 고정된 속성이 아닌 `(제조사 × 모델 × 펌웨어 버전)`의 동적 함수이며, 능력의 확장 및 정밀화 과정에서 하위 호환성을 유지하기 위한 엄격한 버전 규율이 요구된다.

### 5.2 동일성 규율

식별자는 `(skill_type, major.minor)` 구조를 취하며, 기능적 호환성은 Major 버전이 보장한다.
- **Minor 증가**: 하위 호환 가능한 선택적 파라미터 추가에 한정. 기존 클라이언트는 수정 없이 동작 보장.
- **Major 증가**: 필수 파라미터 추가, 기존 파라미터의 의미·단위 변경, 성공/실패 판정 기준의 근본적 변경.
- **파라미터 키의 불변성**: 파라미터의 정의가 변경될 경우 기존 키를 폐기하고 신규 키를 신설한다.

### 5.3 파라미터 키 설계

| 유형 | 예시 | 미인식 키 수신 시 거동 |
|---|---|---|
| 코어 파라미터 | `grip_force` | **실패 반환** (fail-closed) |
| 벤더 확장 파라미터 | `x-<vendor>.<key>` | **무시 후 정상 진행** (fail-open) |
| 필수 요구 선택 필드 | `{parameter, support: REQUIRED}` | 핸드셰이크 단계에서 사전 차단 |

### 5.4 핸드셰이크 메커니즘

```protobuf
message CapabilityRequirement {
  string client_id               = 1;
  string robot_id                = 2;
  repeated string requirements   = 3; // 예: "pick_place@^1.2"
  repeated string optional_fields_used = 4;
  ProtocolLimits limits_needed   = 5;
}
```

핸드셰이크(`Negotiate`)를 통해 클라이언트 요구 사양과 로봇의 선언 능력을 대조하여 호환성을 검증한다. 거절 시 `ExplainedBoolean` 패턴에 따라 명확한 `RejectionCode`를 반환한다. 핸드셰이크 성공 데이터는 레지스트리의 의존 원장에 실시간 적재된다.

### 5.5 경로 및 헤더 체계

토픽 경로 포맷:
```
기체 스트림:  picasso/{major}/{site}/robot/{robot_id}/{stream}
             {stream} ∈ state | event | connection
사이트 스트림: picasso/{major}/{site}/site/catalog
```

메시지 헤더 전송 사양:

| 필드 | MQTT 발행 | gRPC 요청 | gRPC 응답 |
|---|---|---|---|
| `schema_id` | ● | ● | ● |
| `contract_digest` | ● | ● | ● |
| `contract_semver` | ● | ● | ● |
| `robot_id` | ● | ● | ● |
| `capability_epoch` | ● | — | ● |
| `sequence` | ● | — | — |
| `session_id` | ● | — | ● |
| `update_index` | — | — | ● (`WatchTask`) |
| `profile_ref {id, revision}` | ● | — | ● |
| `event_id` / `occurred_at` / `state_as_of` | ● | — | ● |
| `client_id` | — | ● | — |

`contract_revision`은 Major 불일치 시 통신을 즉시 차단하고, 단순 패치 불일치 시에는 경보 이벤트를 발행한다. `capability_epoch`는 기체의 유효 능력이 변경될 때마다 증가하는 ETag 역할을 수행한다.

## 6. 검증 전략

### 6.1 보장 수준과 비용 분격

| 시점 | 검증 대상 | 수행 주기 및 비용 | 한계 |
|---|---|---|---|
| 빌드 타임 | 정적 구조 (필드, 타입, 태그 번호) | CI 파이프라인 1회 수행 | 런타임 의미 검증 불가 |
| 핸드셰이크 타임 | 의미적 호환성 (스킬 버전, 필수 파라미터) | 세션 연결 시 1회 수행 | 기체의 정직한 선언에 의존 |
| 메시지 전송 타임 | 런타임 정합성 (개정판 일치, 시퀀스 단조성, 에포크) | 매 메시지마다 O(1) 검증 | 페이로드 심층 검증 불가 |

검증 비용을 런타임으로 전가하지 않고 상위 단계에서 선제 차단하는 것이 아키텍처 원칙이다.

### 6.2 검증 수행 체계

| 검증 단계 | 검사 대상 | 실패 시 조치 |
|---|---|---|
| CI 게이트 | §11.2 9개 검사 규칙 | PR 머지 차단 |
| 레지스트리 등록 | 프로파일 JSON Schema, proto 교차검증, 어휘 파괴 검증 | `VALIDATED` 상태 진입 거부 |
| 핸드셰이크 | 클라이언트 요구 사양과 선언 능력 대조 | 연결 거부 및 사유 반환 |
| 메시지 수신 | 토픽 Major 버전, 시퀀스 단조성, 세션 일치 | 패킷 드롭 및 결함 기록 |
| 송신 직전 | 프로파일 기반 파라미터 제약 검증 | 발행 차단 |

검증 플래그는 `validate.inbound` 및 `validate.outbound`로 독립 분리되며, `mimic` 가상화 환경에서는 두 플래그 모두 `1.0`(100% 전수 검증)으로 고정된다.

### 6.3 보안 및 접근 전제

- 레지스트리 API는 내부 신뢰 네트워크 환경을 전제하며, 호출 주체는 헤더의 `actor` 필드로 식별한다 (부인 방지 감사 로그는 범위 외).
- `mimic`의 원격 제어 채널(§10.5)은 루프백 인터페이스(`127.0.0.1`)에만 바인딩된다.
- 현장 통신 암호화 및 TLS 인증은 배포 인프라스트럭처의 책임으로 분리한다.

## 7. 능력 프로파일 (`profile/`)

### 7.1 JSON Schema 채택 사유

프로파일이 다루는 핵심은 단순한 메시지 구조가 아닌 **물리적 제약 조건**(값 허용 범위, ENUM 열거셋, 최대 문자열 길이, 발행 주기 등)이다. Protocol Buffers는 데이터 구조 정의 언어이므로 제약 조건을 표현하려면 커스텀 옵션이나 주석에 의존해야 하며, 이는 검증 로직이 애플리케이션 코드로 누출되는 결과를 초래한다. JSON Schema를 채택함으로써 선언적 제약 검증을 분리하고, 스키마와 Protobuf 간의 정합성은 §11.2 검사 4의 양방향 교차 검증 게이트로 엄격히 관리한다.

### 7.2 프로파일 명세 구조

VDA5050 `factsheet.schema`의 계층 구조를 참조하여 설계하였다. 소비자의 의사결정에 필요한 **공개 투영 항목**과 발신 기체 내부에서만 소비되는 **내부 설정 항목**을 엄격히 분리한다.

**공개 투영 항목 (`Capability` 메시지로 노출):**

| 항목 | 상세 내용 |
|---|---|
| 기종 식별 좌표 | `vendor`, `model`, `revision` |
| 지원 스킬 목록 | 스킬 타입 및 `major.minor` 버전 |
| 스킬 제어 플래그 | `pause_support`, `cancel_support` — **`Support` 3값 체계** (`YES` / `NO` / `UNKNOWN`), 필수 선언 |
| 배타 제어 요구 여부 | `exclusive_control_required` — 배타적 단일 제어권 필요 여부 (§4.9) |
| 파라미터 제약 선언 | 키 식별자, `ValueType`, 필수 여부, 수치 범위 및 단위, ENUM 허용 목록, 최대 문자열 길이 |
| 필수 요구 선택 필드 | `{parameter: 점표기 경로, support: SUPPORTED\|REQUIRED}` |
| 상태 발행 주기 규격 | 최소 및 최대 발행 주기 (기본 최대 30초, 소비자의 통신 신선도 판정 기준) |
| 프로토콜 제약 한계 | 문자열 및 배열의 최대 허용 길이 (`protocolLimits`) |
| 폐기 예고 정보 | 스킬별 `deprecated_after` 선언 (운영 변경 예고 단계에서 활용) |

**발신 기체 내부 설정 항목 (외부 비투영):**

| 항목 | 격리 사유 |
|---|---|
| `schema_version` | 프로파일 문서 자체의 메타데이터이며 기체의 능력 속성이 아님 |
| 동작 시간 상수 및 지터 | 가상화 환경의 진행률 계산용 파라미터이며 소비자는 실제 진행률만을 관측함 |
| 실패 모드 및 발생 확률, Resolution | 가상화 시뮬레이션용 수치이며 실제 런타임 결함은 `Fault` 이벤트로 객관화됨 |
| 이벤트 재생 버퍼 크기 N | 발신 기체 내부의 자원 할당 파라미터 |

`Capability` 메시지는 상기 투영 항목만을 정확히 변환하여 전달하며, §12.2의 투영 일치 테스트를 통해 구현의 임의 하드코딩 여부를 검증한다.

### 7.3 계약과의 상호 검증 관계

프로파일은 정적 저작 형식이며 `Capability`는 런타임 투영 모델이다. 프로파일은 계약(Protobuf)에 정의되지 않은 스킬 타입이나 파라미터를 임의로 확장할 수 없으며(참조 무결성), 계약에 정의된 필수 파라미터를 누락해서도 안 된다.

### 7.4 정의 대상 프로파일 목록

초기 가상 기종 2종에서 출발하여 현재 총 **6종의 기종 프로파일**이 정의되어 있다:
- 가상 참조 기종 3종: `humanoid-a`, `quadruped-b`, `quadruped-c` (`vendor: picasso-ref`)
- 실물 벤더 파생 기종 3종: `agility-digit`, `spot-arm`, `unitree-g1` (공식 1차 사양 분석 기반 파생, ADR 31 연계)

| 기종 간 차이 요인 | 검증 목적 |
|---|---|
| 공통 스킬 (`major.minor` 일치) | 동일한 클라이언트 코드로 이기종 로봇 제어 검증 |
| 특정 기종 전용 스킬 | 미지원 스킬 호출 시 `SKILL_ABSENT` 거절 검증 |
| 스킬의 Minor 버전 차이 | 신규 선택 파라미터를 인지하지 못하는 구형 클라이언트 호환성 검증 |
| `cancel_support` 차이 (`YES` vs `NO`) | 취소 미지원 기종에 대한 `CANCEL_UNSUPPORTED` 거절 검증 |
| `pause_support` 차이 (`YES` vs `UNKNOWN`) | `UNKNOWN` 상태에서의 일시정지 시도 및 기체 자율 거절 처리 검증 |
| 프로토콜 제약 한계 차이 | 버퍼 초과 요청에 대한 `LIMIT_EXCEEDED` 거절 검증 |
| 필수 선택 필드 선언 차이 | 필수 옵션 누락 시 `REQUIRED_OPTIONAL_MISSING` 거절 검증 |

상기 7가지 이종성이 코드 수정 없이 순수 데이터 프로파일 교체만으로 시스템에 완전히 수용된다.

## 8. 레지스트리와 저장 체계 (`registry/`)

### 8.1 데이터와 코드의 경계 기준

설계노트 §4.5의 원칙을 적용한다: **단일 값의 유효성을 다른 문맥 참조 없이 판정할 수 있다면 데이터, 복합적인 순서·조건 분기·실행 결과를 평가해야 한다면 코드로 분류한다.**

| 런타임 데이터 영역 (DB) | 배포 코드 영역 (바이너리) |
|---|---|
| 능력 프로파일 정의 문서 | 계약 Protobuf 사양 |
| 기체·어댑터 등록 및 바인딩 매핑 | 스킬 및 태스크 상태머신 해석 엔진 |
| 의존 원장 및 변경 계획서 | 조건 평가, 파급 계산, 파이프라인 엔진 |

### 8.2 권한 소재 및 상태 채번

능력 축소를 유발하는 2가지 이벤트:

| 이벤트 | 발생 주체 | 전달 경로 | 원인 코드 (`cause`) |
|---|---|---|---|
| 기체 하드웨어 장애 (예: 그리퍼 고장) | 로봇/어댑터 | `CapabilityChanged` 발행 → 레지스트리 비동기 적재 | `RUNTIME_DEGRADED` |
| 운영자에 의한 능력 인위적 차단 | 운영자 | 레지스트리 API → 어댑터 폴링 반영 → `CapabilityChanged` 발행 | `OPERATOR_BLOCKED` |

`registry`는 `runtime_capability_override`의 유일한 승인 기록자 역할을 수행한다. 단, `capability_epoch`의 채번 주체는 발신 기체 자신이며, 레지스트리는 수신된 에포크를 관측 로그(`capability_epoch_log`)로 영속화한다. 이를 통해 레지스트리 일시 장애 시에도 기체 자체의 에포크 전이는 정상 유지된다.

### 8.3 관계형 데이터베이스 스키마 (PostgreSQL)

```sql
-- 계약 축 — proto 디스크립터에서 동기화되는 읽기 전용 뷰
skill_type(skill_type_id PK, name, major, max_minor,
           introduced_in_semver, contract_revision, synced_at,
           removed_from_contract BOOL DEFAULT false,
           UNIQUE(name, major))

skill_type_deprecation(skill_type_id PK FK, deprecated_after,
                       announced_by, announced_at, note)

skill_type_param(skill_type_id FK, key, value_type, optional, since_minor,
                  PK(skill_type_id, key))

-- 프로파일 축
capability_profile(profile_id PK, vendor, model, UNIQUE(vendor, model))

profile_revision(profile_revision_id PK, profile_id FK, revision INT,
                 document JSONB, document_hash, schema_version,
                 status, created_by, created_at,
                 activated_by NULL, activated_at NULL,
                 UNIQUE(profile_id, revision))

profile_skill(profile_revision_id FK, skill_type_id FK,
              minor, pause_support, cancel_support, deprecated_after NULL,
              PK(profile_revision_id, skill_type_id))

profile_skill_param(profile_revision_id, skill_type_id, key,
                    value_type, optional,
                    min_value, max_value, unit,
                    allowed_values JSONB, max_length,
                    PK(profile_revision_id, skill_type_id, key))

profile_optional_field(profile_revision_id, parameter_path,
                       support, PK(profile_revision_id, parameter_path))

-- 어댑터 축
adapter(adapter_id PK, vendor, name, UNIQUE(vendor, name))

adapter_version(adapter_version_id PK, adapter_id FK, version,
                contract_semver, conformance_status,
                registered_by, registered_at,
                UNIQUE(adapter_id, version))

-- 바인딩 축
robot(robot_id PK, site_id, serial_number, display_name,
      UNIQUE(site_id, serial_number))

robot_binding(robot_id FK, adapter_version_id FK, profile_revision_id FK,
              bound_at, unbound_at NULL, bound_by, reason)
CREATE UNIQUE INDEX ON robot_binding(robot_id) WHERE unbound_at IS NULL;

-- 시험 실행 축
revision_test_request(request_id PK, profile_revision_id FK,
                      requested_by, requested_at,
                      claimed_by NULL, claimed_at NULL, claim_expires_at NULL)

revision_test_run(run_id PK, profile_revision_id FK, request_id FK NULL,
                  suite, result, ran_by, ran_at, detail JSONB)

-- 관측 로그
capability_epoch_log(robot_id, epoch, cause, profile_ref JSONB,
                     detail JSONB, occurred_at)

runtime_capability_override(robot_id, skill_type_id,
                            state, cause, reason, occurred_at)

handshake_rejection(rejection_id PK, robot_id, client_id,
                    requirement JSONB, reason_code, detail JSONB, at)

-- 의존 원장
consumer(consumer_id PK, kind, site, display_name, registered BOOL, first_seen)

consumer_requirement(consumer_id FK, skill_type_name, source, version_range,
                     first_seen, last_seen, active,
                     PK(consumer_id, skill_type_name, source))

-- 변경 계획
change_plan(plan_id PK, intent, target JSONB, target_key, site,
            status, created_by, created_at, applied_at NULL)
CREATE UNIQUE INDEX ON change_plan(target_key, site)
  WHERE status NOT IN ('APPLIED','ABANDONED');

change_plan_step(plan_id FK, seq, kind, precondition JSONB,
                 satisfied BOOL, satisfied_at NULL,
                 PK(plan_id, seq))

task(task_id PK, robot_id FK, profile_revision_id FK,
     skill_type_id FK, revision INT, attempt INT,
     state, started_at, ended_at)

audit_log(actor, action, target_type, target_id, plan_id NULL,
          before JSONB, after JSONB, at)
```

**핵심 데이터 모델링 결정 6가지:**
1. **프로파일 개정판의 불변성**: `VALIDATED` 진입 이후 레코드는 절대 수정할 수 없다. 상태 전이는 `DRAFT → VALIDATED → TESTED → ACTIVE → SUPERSEDED / REVOKED` 흐름을 준수한다. 롤백은 과거 `SUPERSEDED` 개정판의 재활성화를 통해 수행된다.
2. **원본 JSONB와 정규화 테이블의 공존**: 진실의 원천(Single Source of Truth)은 원본 `document`이며, 정규화 테이블은 인덱싱 및 빠른 조회를 위한 투영 뷰다.
3. **런타임 능력 축소의 비침습성**: 프로파일 자체를 변조하지 않고 `runtime_capability_override` 오버레이를 적용하여 유효 능력(`Capability = Profile - Override`)을 동적 산출한다.
4. **계약 메타데이터의 읽기 전용 동기화**: `skill_catalog.proto`의 커스텀 옵션에서 스킬 메타데이터를 추출하여 초기 구동 시 upsert한다.
5. **어댑터와 프로파일 바인딩의 다대다 분리**: 어댑터 업그레이드와 프로파일 변경을 독립적인 차원으로 통제한다.
6. **의존 원장의 이중 소스 체계**: 선언적 등록(`DECLARED`)과 런타임 협상 관측(`OBSERVED`)을 복합 키로 관리하여 원장의 누락을 방지한다.

### 8.4 프로파일 개정판 런타임 갱신 절차

```
① 등록:   DRAFT 접수 → JSON Schema + proto 교차검증 + 어휘 파괴 검증 → VALIDATED
② 시험:   시험 요청 적재 → harness 폴링 인출 → 가상화 계약 검증 스위트 완주 → TESTED
③ 활성화: 바인딩 전환 승인 (카나리 배포 가능)
④ 반영:   기체가 레지스트리 폴링으로 바인딩 변경 감지 → capability_epoch 증가 및 CapabilityChanged 발행
⑤ 전파:   소비자가 헤더의 epoch 변경을 감지하고 능력 캐시 무효화 및 재동기화
⑥ 롤백:   필요 시 이전 SUPERSEDED 개정판 재활성화 (추가 시험 불필요)
```

활성화 승인 3대 조건: `status ∈ {TESTED, SUPERSEDED}`, 3개 시험 스위트(`CONTRACT`, `NEGATIVE`, `DETERMINISM`) 최신 실행 결과 전체 `PASS`, 유효한 `activated_by` 서명.

진행 중인 태스크는 접수 시점의 프로파일 개정판에 영구 고정(Pinning)되어 동작하며, 신규 접수 태스크부터 새 개정판이 적용된다.

### 8.5 운영 인터페이스

모든 조작은 단순 DB 수정을 넘어 트랜잭션과 감사 로그가 보장되는 16개 표준 운영 엔드포인트로 제공된다:
- 기종 등록, 기체 등록(`POST /operations/robots`), 어댑터 인스턴스 등록(`POST /operations/adapter-instances`), 어댑터 등록, 어댑터 버전 등록, 개정판 제출, 시험 요청, 바인딩 전환, 롤백, 개정판 폐기, 능력 차단/해제, 계약 폐기 예고 등록, 소비자 요구 등록(`POST /requirements`), 변경 계획 수립, 변경 계획 단계 실행, 사이트 이름 등록 기록(`POST /operations/site-names`).

**진단 조회 전용 JSON 엔드포인트 10종:**

| # | 엔드포인트 | 제공 정보 | 데이터 소스 |
|---|---|---|---|
| 1 | `GET /diag/bindings` | 기체별 어댑터 및 프로파일 개정판 바인딩 현황, 개정판별 기체 수 | `robot_binding` |
| 2 | `GET /diag/diff?from=&to=` | 두 개정판 간의 세부 능력 diff 분석 | `profile_skill*`, `profile_optional_field` |
| 3 | `GET /diag/epochs?robot_id=` | 기체의 에포크 전이 이력, 사유, 관측된 개정판 | `capability_epoch_log` |
| 4 | `GET /diag/rejections` | 클라이언트별 핸드셰이크 거절 이력 및 사유 코드 | `handshake_rejection` |
| 5 | `GET /diag/dependents?skill=` | 특정 스킬을 의존하는 활성 소비자 목록 | `consumer_requirement` |
| 6 | `GET /diag/plans` | 활성 변경 계획 목록 및 각 단계별 전제 조건 충족 여부 | `change_plan*` |
| 7 | `GET /diag/software` | 프로파일 선언 펌웨어와 기체 실측 펌웨어 간의 정합성 판정 (일치/불일치/미보고) | `robot_liveness`, `profile_revision.document` |
| 8 | `GET /diag/stalled` | 종료되지 않아 축소 드레인을 차단하고 있는 정체 태스크 감시 | `task`, `skill_type` |
| 9 | `GET /diag/robots` | 기체 등록 출처 및 상태 수명주기 (`CLAIMED`, `DISCOVERED`, `CONFIRMED`, `UNREGISTERED`) | `robot`, `robot_liveness` |
| 10 | `GET /diag/adapter-instances` | 현장에 배포된 어댑터 인스턴스 현황 및 실기체 연결 수, 적합성 상태 | `adapter_instance`, `adapter_version`, `robot` |

## 9. 운영 변경 체계

변경 파급 범위를 사전 계산하여 운영 위험을 제어하는 결정론적 메커니즘을 정의한다.

### 9.1 독립적인 4대 변경 축

| 변경 축 | 대상 | 변경 메커니즘 | 롤백 정책 |
|---|---|---|---|
| **계약 축** | `contracts/` Protobuf | 신규 릴리스 배포 (SemVer) | **비가역 (소비자 코드 기생성)** |
| **프로파일 축** | 기종별 능력 선언 사양서 | 개정판 발행 및 런타임 활성화 | `SUPERSEDED` 재활성화 |
| **어댑터 축** | 벤더 하드웨어 연동 드라이버 | 드라이버 바이너리 재배포 | 구형 버전 재배포 |
| **바인딩 축** | 기체 매핑 (`기체 = 어댑터 + 프로파일`) | 런타임 동적 매핑 전환 | 이전 바인딩 맵으로 복귀 |

바인딩 시점의 계약 호환성 검증 수식:
```
required = max( skill_type.introduced_in_semver for 프로파일 선언 스킬 전체 )
is_legal = (adapter_version.contract_semver.major == required.major)
        && (adapter_version.contract_semver >= required)
```
어댑터가 프로파일에 요구되는 모든 스킬 어휘를 수용할 수 있는 계약 버전으로 빌드되었는지를 엄격히 검증하여 런타임 호환성을 보장한다.

### 9.2 의존 원장 체계

소비자가 자신의 스킬 요구 사양을 능동 등록(`POST /requirements`)하거나 핸드셰이크 성공 시 런타임 관측(`OBSERVED`)하여 원장을 구축한다.

이를 통해 특정 능력의 삭제 시 영향받는 상위 시스템을 사전에 정확히 식별한다. 30일 이상 비활성 상태인 관측 항목은 `active=false`로 전환되되, 이력 보존을 위해 물리 삭제하지 않는다.

### 9.3 방향성 규율: 확장·이행·축소

**확장은 하향식(어댑터 → 미들웨어 → 카탈로그 → 상위), 축소는 상향식(상위 → 카탈로그 → 미들웨어 → 어댑터)으로 진행한다.**

| 변경 국면 | 주요 활동 | 전파 방향 |
|---|---|---|
| **확장 (Expansion)** | 신규 능력을 배포하되 기존 능력과의 병행 제공 유지 | 어댑터 → 미들웨어 → 카탈로그 → 상위 시스템 |
| **이행 (Migration)** | 상위 시스템이 신규 능력으로 전환. 구형 능력 의존도 0 관측 | 상위 시스템 주도 |
| **축소 (Contraction)** | 의존성 및 실행 태스크 부재 확인 후 구형 능력 제거 | 상위 시스템 → 카탈로그 → 미들웨어 → 어댑터 |

**축소 단계 진입을 위한 2대 필수 조건 (조회 기반 판정):**
1. `consumer_requirement`에 해당 능력을 요구하는 활성(`active`) 소비자가 **0건**일 것.
2. `task` 테이블에 해당 스킬을 실행 중인 비종착 태스크가 **0건**일 것 (드레인 완료).

추가 전제 조건: 해당 능력을 제공하는 바인딩된 기체 전체가 현재 정상 하트비트를 보고하고 있어야만 상기 조회가 유효하게 인가된다. 단 하나의 기체라도 통신 침묵 상태라면 상태 불명으로 처리되어 축소가 안전하게 차단된다.

### 9.4 6대 운영 변경 시나리오

| 시나리오 | 계약 축 | 프로파일 축 | 어댑터 축 | 파급 영향도 | 적용 절차 |
|---|---|---|---|---|---|
| **어댑터 최초 추가** | 무변경 | 신규 프로파일 | 신규 등록 | 없음 | §9.7 신규 절차 |
| **어댑터 패치 버전업** | 무변경 | 무변경 | 재배포 + 신규 버전 | 없음 | 바인딩 전환 |
| **선택 파라미터 추가** | Minor 증가 | 신규 개정판 | 재빌드 | 없음 (하위 호환) | §8.4 절차 |
| **신규 스킬 타입 신설** | 신규 정의 | 신규 개정판 | 재빌드 | 없음 | §8.4 + 카탈로그 |
| **스킬 의미론 변경** | **Major 증가** | 신규 개정판 | 재빌드 | **원장 기반 영향 분석** | **확장·이행·축소** |
| **기존 기능 완전 삭제** | 폐기 예고 | 개정판에서 제외 | 무변경 가능 | **원장 조회 + 드레인** | **축소 절차 강제** |

앞의 4개 시나리오는 파급 영향이 없어 즉시 진행되며, 뒤의 2개 시나리오는 원장 검증 및 단계적 변경 계획 집행이 필수적이다.

### 9.5 1급 객체로서의 변경 계획 (Change Plan)

운영자의 인위적 개입에 따른 실수를 방지하기 위해 변경 계획을 데이터베이스 1급 엔티티로 관리한다.

```json
POST /change-plans
{
  "intent": "REMOVE_CAPABILITY",
  "target": { "skill": "pick_place", "major": 1 },
  "site": "A"
}
```

단계 유형(`kind` 5종): `ANNOUNCE`, `OBSERVE_MIGRATION`, `DRAIN`, `APPLY`, `VERIFY_WITHDRAWAL`.
전제 조건 검사(`precondition` 6종): `NO_ACTIVE_CONSUMERS`, `NO_INFLIGHT_TASKS`, `DEPRECATION_PUBLISHED`, `CAPABILITY_WITHDRAWN`, `NO_ACTIVE_BINDINGS`, `SUCCESSOR_ACTIVE`.

전제 조건은 실행 시점에 원자적으로 재평가되며, 조건 불충족 시 작업 집행이 엄격히 거절된다.

### 9.6 상위 연계 계층 (ACL) 아키텍처

상위 MES/WMS 시스템과의 연계를 담당하는 Anti-Corruption Layer(ACL)는 아키텍처 경계 내에 정식 편입된다([ADR 9](../../adr/0009-no-declaration-without-consumer.md)). 하위 어댑터 계층과 완전한 대칭 구조를 형성한다:

| 계층 구분 | 프레임워크 코어 | 인스턴스 모듈 | 소유권 경계 |
|---|---|---|---|
| 상향 연계 (Northbound) | `acl-core` (시스템 무의존) | `acl-{system}` × N | 배포 현장 소유 |
| 하향 연계 (Southbound) | `adapter-core` (기종 무의존) | `adapter-{vendor}-{model}` × 3 | 제품 코어 소유 (ADR 31) |

상위 시스템 노출 인터페이스:
- `GET /catalog?site=`: 사이트 단위 가용 능력 카탈로그 조회
- `POST /requirements`: 상위 시스템의 의존 사양 능동 등록
- `picasso/{major}/{site}/site/catalog`: 사이트 카탈로그 변경 알림 스트림 (MQTT Retain)

### 9.7 어댑터 수명주기 단계

```
① 어댑터 등록:    vendor, name, version, 대상 계약 semver 메타데이터 기입
② 프로파일 제출:  벤더 사양 기반 프로파일 파생 및 스키마 검증 (§8.4 ①)
③ 계약 시험:      가상화 환경(mimic) 기반 계약 적합성 스위트 통과 (§8.4 ②)
④ 적합성 시험:    실물 하드웨어 어댑터 적합성 검증 (C-3 자리, 비목표)
⑤ 바인딩:         기체 등록 및 단계적 카나리 배포
⑥ 카탈로그 반영:  사이트 카탈로그 갱신 및 상위 시스템 스트림 통지
```

④번 적합성 검증은 실물 도입 전까지 `UNTESTED` 상태로 유지되며, 진단 1번 엔드포인트에 투명하게 관측된다. ADR 35에 따라 로봇 내부의 지리 공간 좌표 및 사이트 명칭 등록 상태 또한 진단 표면에 함께 표시된다.

## 10. `mimic` 내부 아키텍처

### 10.1 핵심 원칙

**거동은 데이터 프로파일에서 주입되며 소스 코드는 범용 인터프리터로 동작한다.** 기종별 전용 클래스를 일체 작성하지 않으며, 신규 기종 추가는 프로파일 JSON 1건 추가로 완결된다.

```
mimic/
  profile/     프로파일 로드(파일 또는 레지스트리) · 유효성 검증 · 능력 투영 · 폴링
  engine/      스킬 및 태스크 상태머신 실행기 · 논리 시계 · 이벤트 재생 버퍼
  fault/       선언된 결함 주입 · 전송 장애 시뮬레이션 · 프로토콜 한계 집행
  control/     원격 제어 채널 (루프백 전용 포트)
  transport/   gRPC 서버 인터페이스 및 MQTT 비동기 발행기
```

### 10.2 프로파일 로드 및 구동 모드

| 구동 모드 | 기동 인자 | 주요 활용 영역 |
|---|---|---|
| 로컬 파일 모드 | `--profile <path>` (기체별 지정) | §13 2단계 마일스톤, 단위 및 계약 적합성 테스트 |
| 레지스트리 연동 모드 | `--registry <url> --robot <id>...` | 3단계 이후, 실운영 환경 통합 |
| 후보 개정판 검증 모드 | `--registry <url> --profile-revision <id>` | `harness`에 의한 신규 개정판 CI 자동 시험 (§8.4 ②) |

단일 가상화 프로세스가 다수의 가상 기체를 동시 호스팅하며, 요청 헤더의 `robot_id`로 개별 기체를 라우팅한다.

### 10.3 동적 폴링, 가상 시계, 결정론적 시드

- **동적 폴링**: 레지스트리 모드에서 바인딩 및 오버라이드 상태를 주기적(기본 5초)으로 조회하여 유효 능력을 재계산하고 `capability_epoch`를 증가시킨다. 진행 중인 태스크는 기존 개정판을 유지한다.
- **가상 시계**: `REAL`(실시간) 및 `VIRTUAL`(가상 시계) 모드를 지원한다. `VIRTUAL` 모드에서는 `AdvanceClock(duration)` RPC를 통해서만 시계가 전진하여 테스트의 완벽한 결정론을 보장한다.
- **의사 난수 시드**: 실패 모드의 확률적 발현과 작업 소요 지터를 시드 기반으로 통제하여 재현 가능성을 보장한다.

### 10.4 3대 실패 주입 경로

1. **선언된 결함 발현**: 프로파일에 명시된 실패 모드와 발생 확률에 따른 결정론적 주입.
2. **전송 계층 장애 모사**: 패킷 지연, 연결 단절, 이벤트 유실, 중복 수신, 순서 역전 등 네트워크 장애 주입.
3. **프로토콜 한계 집행**: 프로파일 선언 수치 범위 및 허용치 위반 시 `PARAMETER_INVALID` 등의 거절 코드 발현.

### 10.5 원격 제어 채널 API

루프백 인터페이스(`127.0.0.1`) 전용 포트로 격리 노출된다.

| RPC 메서드 | 주요 기능 및 용도 |
|---|---|
| `SetSeed(seed)` | 난수 생성기 시드 고정 (결정론적 재현) |
| `SetClockMode(REAL\|VIRTUAL)` / `AdvanceClock(duration)` | 가상 시계 모드 전환 및 시계 전진 |
| `ForceFault(robot_id, error_type, task_id?)` | 특정 결함 강제 주입 (`task_id` 생략 시 기체 레벨 결함) |
| `SetSingleStep(bool)` / `Step()` | 상태 전이의 단계별 단일 스텝 실행 제어 |
| `InjectTransportFault(kind)` | `DISCONNECT`, `DELAY`, `EVENT_LOSS`, `DUPLICATE`, `REORDER` 주입 |
| `SetConnection(robot_id, ConnectionState)` | 기체의 연결 수명주기 상태 강제 전환 |
| `RemoveCapability(robot_id, skill)` / `RestoreCapability(...)` | 런타임 능력 상실 및 복원 시뮬레이션 |
| `ForceTerminalViolation(task_id)` | 종착 확정 후 비인가 재기동 상황 강제 주입 (래치 불변식 검증) |
| `ForceControlAuthorityLoss(robot_id)` | 외부 제어권 탈취 상황 주입 (§4.9) |
| `DumpInternalState(robot_id)` | 엔진 내부 상태 덤프 (테스트 검증 오라클 제공) |

### 10.6 외부 의존성 결손 시 거동

| 장애 상황 | 시스템 대응 거동 |
|---|---|
| 레지스트리 연결 불가 (기동 시) | 즉시 기동 실패 (비정상 상태 표면 노출 차단) |
| 레지스트리 연결 불가 (폴링 중) | 직전 캐시된 유효 능력을 유지하고 경보 로그 기록 |
| MQTT 브로커 단절 | 기존 세션을 유지하며 최대 N개까지 인메모리 버퍼링 후 재연결 시 순차 재생. 버퍼 초과 시 신규 `session_id` 발급 |
| `WatchTask` 수신 클라이언트 지연 | gRPC 백프레셔 적용 후 임계 초과 시 해당 스트림만 강제 종료 (태스크 본체는 무영향) |

### 10.7 가상화 한계

물리적 기하 충돌, 파지 역학, 도달 가능성 시뮬레이션은 배제한다. 본 아키텍처는 프로토콜 및 인터페이스 계약의 적합성을 검증하는 데 집중한다.

## 11. 게이트 체계 (`gate/`)

### 11.1 아키텍처 원칙

검증 게이트는 단순한 CI 셸 스크립트가 아닌 **재사용 가능한 단일 검증 라이브러리**로 구현된다. CI 빌드 환경과 레지스트리 런타임 등록 파이프라인이 동일한 검증 엔진을 호출한다.

| 호출 맥락 | 비교 기준선 (Baseline) |
|---|---|
| CI 빌드 게이트 | 메인 브랜치의 원본 프로파일 (`git show origin/main:<path>`) |
| 레지스트리 등록 게이트 | 동일 `profile_id`의 직전 `ACTIVE` 개정판 문서 |

### 11.2 10대 검사 규칙

| # | 검사 항목 | 방어 대상 | 판정 메커니즘 |
|---|---|---|---|
| 1 | `buf lint` | Protobuf 명명 규칙 및 스타일 위반 | Buf 린터 |
| 2 | `buf breaking` | 필드 번호 변경, 타입 불일치 등 파괴적 변경 | Buf 하위 호환성 분석 |
| 3 | 프로파일 JSON Schema + 구조 규칙 | 비정상 프로파일, 미등록 `error_type`, 역전된 범위 | JSON Schema 및 프로그램적 정적 분석 |
| 4 | 프로파일 ↔ Protobuf 교차검증 | 미정의 스킬 선언, 버전 초과, 필수 파라미터 누락 | 양방향 참조 무결성 분석 |
| 5 | `contracts/` 무의존성 | 계약 모듈의 내부 프로젝트 의존성 유입 차단 | 빌드 의존성 그래프 분석 (의존수 == 0) |
| 6 | 능력 어휘 파괴 검사 및 변경 분류 | §5.2 버전 규칙 위반 및 미인가 능력 축소 | 프로파일 diff 분석 및 SemVer 정합성 평가 |
| 7 | 기종 분기 금지 | 기종별 코드 분기 하드코딩 유입 차단 | 기종 무의존 모듈에 대한 벤더/모델 문자열 검색 |
| 8 | 프로파일 전용 변경 검증 | 프로파일 외 코드 변경 혼입 차단 | git diff 파일 목록 분석 |
| 9 | 결속 타입의 어댑터 경계 | 자리 이름 결속 타입이 공통 계층으로 유출되는 것을 차단 | 결속 선언 파일에서 유도한 타입명 문자열 검색 |
| 10 | 네거티브 스위트 | 검사 규칙 1~9의 탐지 유효성 지속 검증 | 격리된 결함 케이스 주입 테스트 |

**검사 3 상세**: JSON Schema 검증 후 프로그램적 4대 추가 검증을 수행한다:
- `min_value ≤ max_value` 수치 범위 역전 검증
- `publish_interval.min_seconds ≤ max_seconds` 주기 역전 검증
- 동일 프로파일 내 `(skill_type, major)` 중복 선언 차단
- 스킬 내 파라미터 `key` 중복 선언 차단

**검사 4 양방향 교차 검증**: 프로파일이 참조하는 스킬이 Protobuf에 실재하는지 검증할 뿐만 아니라, 반대로 계약 카탈로그에 선언된 `since_minor ≤ 선언 minor`인 필수 파라미터(`is_optional=false`)가 프로파일에 누락 없이 존재하는지를 엄격히 검증한다.

**검사 7 기종 분기 차단**: 공통 계층 모듈(`client` · `mimic` · `harness` · `adapter-core` · `picasso` · `capability` · `adapter-host` · `uplink`)의 소스 코드 내에 특정 벤더명이나 모델 식별자 문자열이 하드코딩되는 것을 원천 차단한다. 정본 목록은 코드베이스의 `Check07ModelBranching.MODULES`에 정의되어 있으며 기계적으로 검증된다.

**검사 9 결속 경계**: 자리 이름이 어느 기종의 무엇에 묶이는지는 어댑터 경계의 지식이다(ADR 34 · 35). 검사 7이 기종 *좌표*를 막는다면 이 검사는 기종 *결속*을 막는다. **탐색어는 결속 선언 파일이 대는 최상위 타입명에서 유도**하므로 타입이 늘면 금지도 함께 는다 — 훑는 모듈 목록은 코드가 정본이다(`Check09BindingScope.MODULES`).

**검사 10 네거티브 테스트 데이터셋 (`gate/negative/`):**

| 대상 검사 | 주입 결함 케이스 |
|---|---|
| 검사 1 | 명명 규칙을 위반한 메시지 및 필드명 |
| 검사 2 | 필드 번호 재사용 및 호환 불가능한 타입 변경 |
| 검사 3 | 미등록 `error_type` 및 역전된 수치 범위 |
| 검사 4 | proto에 정의되지 않은 스킬 선언 및 필수 파라미터 누락 |
| 검사 5 | `contracts/`에 내부 모듈 프로젝트 의존성 추가 |
| 검사 6 | 파라미터 삭제 후 Minor만 증가, 소비자가 존재하는 능력 임의 삭제 |
| 검사 7 | 기종 무의존 공통 모듈에 기종명 문자열 삽입 |
| 검사 8 | 프로파일 변경 커밋에 소스 코드 변경 혼입 |
| 검사 9 | 기종 무의존 공통 모듈에 자리 결속 타입 참조 삽입 |

각 네거티브 케이스가 CI 실패를 유발하지 못할 경우 해당 테스트 케이스 자체가 실패로 처리된다.

## 12. 시험 전략

### 12.1 테스트 계층 구조

단위 테스트(상태머신 전이 망라성) → 계약 테스트(`mimic` ↔ `client`) → 결정론적 검증 → 네거티브 스위트 → 운영 시나리오 통합 검증.

**결정론 최우선 원칙**: 난수 시드와 가상 시계를 결합하여 동일 이벤트 시퀀스의 재현성을 100% 보장한다.

### 12.2 완료 기준, 검증 방법, 아키텍처 메커니즘 (23개 검증 항목)

| # | 완료 기준 | 증명하는 테스트 | 실현 메커니즘 |
|---|---|---|---|
| 1 | **A-1** 이기종 로봇의 동일 클라이언트 제어 | 2개 기종 프로파일의 `mimic`에 대해 동일 클라이언트 코드로 태스크 완주 | 요구 사양 설정 파일화 (§5.4) 및 게이트 7번 |
| 2 | **A-2** 중간 구독자의 상태 재구성 | 임의 시점 신규 구독 시 스냅샷과 이벤트 결합으로 내부 상태 완벽 복원 | `GetSnapshot`, `DumpInternalState`, 이벤트 재생 버퍼 |
| 3 | **A-2** 전송 장애 복원력 | 결손 감지, 중복 메시지 무시, 역전 패킷 재정렬을 통해 최종 일관성 달성 | `InjectTransportFault`, 시퀀스 번호, 재정렬 윈도우 (§3.5) |
| 4 | **A-2** 멱등성 및 개정판 규칙 | 동일 revision 재전송 시 멱등 응답, 구형 revision 거절, 신규 revision 파라미터 갱신 | 4-케이스 멱등성 처리 표, `ReplayEvents`, 재생 버퍼 |
| 5 | **A-2** 통신 침묵 원인 판별 | `OFFLINE`, `HIBERNATING`, `CONNECTION_BROKEN` 강제 주입 시 정확한 분기 판정 | `SetConnection`, 연결 상태 스트림, 최대 발행 주기 |
| 6 | **A-4** 장기 실행 태스크 제어 | 가상 시계 기반 30초 이상 태스크의 단조 증가 진행률 및 취소-복구 시퀀스 검증 | `AdvanceClock`, 진행률 정합성 불변식 (§4.4) |
| 7 | **A-4** 취소·일시정지 불가 기종 제어 | 미지원 기능 호출 시 `CANCEL_UNSUPPORTED`, `PAUSE_UNSUPPORTED` 명시적 거절 | 프로파일 `Support` 플래그 선언 (§7.4) |
| 8 | **A-4** 자율 재시도 및 수동 개입 분기 | 결함 `Resolution`에 따라 `RETRIABLE`, `NEEDS_INTERVENTION`, `FAILED` 전이 검증 | `RetryTask`, 프로파일 `Resolution`, 전파 규칙 1 |
| 8b | **A-4** 취소와 물리 복구 검증 | 복구 성공 시 `CANCELLED`, 복구 실패 시 `CANCELLED_RECOVERY_FAILED` 전이 | `CancelTask`, 전파 규칙 3, `ForceFault` |
| 8c | **A-4** 터미널 래치 불변식 검증 | 종착 확정 후 기체 무단 재기동 시 `TERMINAL_STATE_VIOLATED` 발행 및 상태 유지 | `ForceTerminalViolation`, 래치 불변식 (§4.4) |
| 8d | **A-4** 제어권 상실 격리 검증 | 제어권 탈취 시 `CONTROL_AUTHORITY_LOST` 발행 및 하드웨어 고장과의 분리 관측 | `ForceControlAuthorityLoss`, §4.9 규격 |
| 9 | **C-1** 단일 스키마 기반 이종성 표현 | 2개 프로파일이 동일 JSON Schema를 통과하며 7대 차이점이 데이터로 표현됨 | 게이트 3번 및 7번 검사 |
| 10 | **C-1** 런타임 투영 일치 검증 | `GetCapabilities` 응답이 프로파일 투영 규칙 파생값과 100% 일치 | 투영 함수 기계 대조 (하드코딩 원천 차단) |
| 11 | **C-2** 신규 기종의 무수정 추가 | 제3의 기종 `quadruped-c` 추가 시 소스 코드 변경 0건으로 전체 스위트 통과 | 게이트 8번 검사 (프로파일 외 변경 차단) |
| 12 | **D-1** 자동화된 비호환 변경 차단 | 네거티브 스위트 주입 시 무단 호환성 파괴 PR이 CI에서 자동 차단됨 | 게이트 9번 네거티브 스위트 |
| 13 | 능력 호환성 협상 검증 | 핸드셰이크 시 5대 거절 코드가 사유와 함께 반환되고 레지스트리에 적재됨 | `Negotiate`, 거절 코드 체계, 의존 원장 적재 |
| 14 | 런타임 능력 동적 축소 검증 | 장애 주입 시 해당 스킬만 `CAPABILITY_WITHDRAWN` 반환되고 타 스킬 정상 유지 | `CapabilityChanged`, 에포크 무효화 |
| 15 | 런타임 무중단 개정판 갱신 | 활성화 시 기존 태스크는 완주(Pinning), 신규 태스크는 새 개정판으로 실행 | 동적 폴링, 개정판 고정 불변식 (§8.4) |
| 16 | 어댑터 신규 등록 시나리오 | 6단계 등록 절차 통과 후 카탈로그에 반영되고 미검증 상태가 투명 노출됨 | 어댑터 수명주기 파이프라인 (§9.7) |
| 17 | 다대다 변경 축 분리 검증 | 어댑터 버전업 시 프로파일 유지 관측, 비호환 계약 조합에 대한 바인딩 거절 | 바인딩 호환성 검증 수식 (§9.1) |
| 18 | 의존 원장 실시간 구축 검증 | 미등록 클라이언트의 핸드셰이크 성공 데이터가 원장에 자동 적재되고 유지됨 | `consumer_requirement` 이중 소스 체계 (§9.2) |
| 19 | 비인가 기능 축소 차단 검증 | 활성 소비자 또는 실행 태스크 체류 시 `REMOVE_CAPABILITY` 계획 승인 거부 | 변경 계획 전제 조건 검증 엔진 (§9.5) |
| 20 | 카나리 점진 배포 검증 | 일부 기체에 대한 신규 개정판 배포 시 이종 개정판의 안정적 병행 구동 관측 | 기체 단위 바인딩 매핑 및 `profile_ref` 헤더 |

### 12.3 시험 비목표

실물 하드웨어 역검증, 물리 역학 시뮬레이션, 다중 로봇 트래픽 중재 등은 본 시험 스위트의 범위에서 제외된다.

## 13. 구현 마일스톤

| 마일스톤 | 핵심 개발 범위 | 달성 검증 목표 |
|---|---|---|
| **1단계** | `contracts/`, `profile/schema/`, 픽스처, `gate/` (검사 1~6, 9 일부), CI 파이프라인 | 계약 및 프로파일 정합성 게이트 확립 (D-1 기반) |
| **2단계** | `mimic` (파일 모드), `client`, `harness`, 기종 프로파일 3종, 게이트 7~8 및 9 완결 | **완료 기준 1~12 및 13번 절반 달성.** 레지스트리 없이 핵심 아키텍처 명제 입증 |
| **3a단계** | `registry` 코어 — DB 스키마, 어댑터 수명주기, 바인딩 검증, 시험 오케스트레이션, 카탈로그 읽기 API, 진단 표면 | 완료 기준 13~17, 20 달성 |
| **3b단계** | 의존 원장, 변경 계획 엔진, 사이트 카탈로그 스트림, 진단 5~6, 게이트 6번 축소 게이트 | 완료 기준 18~19 달성 및 운영 변경 체계 완결 |

## 14. 아키텍처 결정 기록 (ADR · D-2)

| 번호 | 아키텍처 결정 내용 | 한계 및 후속 과제 |
|---|---|---|
| 1 | 계약은 Protobuf, 프로파일은 JSON Schema로 이원화하고 정합성은 게이트 4번으로 통제 | — |
| 2 | 결함 처방 3분류를 계약에서 배제하고 잔여 역량 불리언 2종으로 객관화 | — |
| 3 | 능력 동일성을 Major 버전으로 결정하는 규율 확립 | §15.1 |
| 4 | 토픽 경로·헤더·페이로드의 관심사 분리 및 SemVer 병기 | §15.4 |
| 5 | 검증 시점을 3단계로 분격하고 런타임 검증 오버헤드 최소화 | — |
| 6 | gRPC와 MQTT의 전송 경계 분리 및 독립 시퀀스 카운터 채택 | — |
| 7 | 세션 단위 시퀀스 단조성과 인메모리 순환 재생 버퍼 채택 | §15.8 |
| 8 | `Reset`을 외부 RPC로 노출하지 않고 엔진 내부 자동 전이로 캡슐화 | — |
| [9](../../adr/0009-no-declaration-without-consumer.md) | 즉시 명령 및 동시성 어휘 배제 — 소비자가 정의되지 않은 선언은 배제 | — |
| [10](../../adr/0010-projection-boundary.md) | 공개 투영 항목과 발신자 내부 설정 항목의 명확한 분리 기준 수립 | — |
| 11 | 프로파일 개정판의 불변성 보장, `SUPERSEDED` 재활성화 롤백, 활성본 직접 삭제 금지 | — |
| 12 | 실행 중 태스크의 개정판 고정(Pinning) 및 동적 파라미터 갱신의 무영향성 | — |
| 13 | 능력 축소의 이원화 경로 수립 및 발신 기체 중심의 에포크 단조 채번 | — |
| 14 | 4대 운영 변경 축 분리 및 비가역 축(계약)의 최소화 격리 | — |
| 15 | 의존 원장 구축 — 선언과 관측의 병행 수집 및 비활성 레코드의 논리 삭제 원칙 | §15.10 |
| 16 | 기능 축소의 진입 장벽을 시간 경과가 아닌 관측 데이터(소비자 0, 태스크 0)로 통제 | — |
| 17 | 변경 계획을 1급 엔티티로 격리하고 단계별 전제 조건 원자적 재평가 보장 | — |
| 18 | 카탈로그를 개별 기체가 아닌 사이트 단위의 논리적 능력 집합으로 추상화 | — |
| 19 | 기체의 레지스트리 단방향 폴링 구조 채택을 통한 양방향 결합 순환 차단 | §15.5 |
| 20 | `harness` 분리 및 시험 요청의 비동기 레코드 적재를 통한 결합도 해소 | — |
| 21 | 단일 프로세스 다중 가상 로봇 호스팅 채택 (PoC 목적의 가상화 구조) | — |
| [22](../../adr/0022-gate-as-library.md) | 게이트를 독립 라이브러리로 구축하여 CI 및 레지스트리에서 공통 호출 | — |
| [23](../../adr/0023-string-checks-over-ast.md) | 게이트의 구조적 검사(5·7번)를 복잡한 AST 대신 결정론적 문자열 매칭으로 구현 | §15.6, §15.11 |
| 24 | 보안 인증·인가를 배포 인프라스트럭처로 위임하고 내부 신뢰 네트워크 전제 | §15.3 |
| 25 | 실물 도입 전 가상화 기반 계약 우선 확정 방법론 채택 | §15.7 |
| 26 | 사이트 축을 경로 및 카탈로그 스키마에만 반영하고 네트워크 브리지는 비목표 처리 | §15.9 |
| 27 | 물리적 도달성 및 기하 충돌 판정을 프로파일 및 가상화 범위에서 배제 | §15.2 |
| 28 | openTCS 및 VDA5050 표준의 선별적 패턴 차용 및 반례 분석 근거화 | — |
| [29](../../adr/0029-shared-profile-model.md) | `profile-model` 공통 모듈 추출을 통한 게이트와 에뮬레이터 간 결합도 해소 | — |
| [30](../../adr/0030-codegen-outside-buf.md) | Gradle protoc 기반 코드 생성 체계 단일화 | §15.20 |
| [31](../../adr/0031-adapter-ownership.md) | 어댑터 모듈의 코어 제품군 편입 및 벤더 SDK 라이선스 격리 방안 수립 | — |
| [32](../../adr/0032-safety-boundary.md) | 안전 회로 계통의 표준 계약 경유 배제 및 비상정지 독립성 보장 | — |
| [33](../../adr/0033-adapter-module-shape.md) | 기종별 전용 지식을 캡슐화하는 어댑터 모듈의 격리 구조 확립 | §15.55, §15.56 |
| [34](../../adr/0034-semantic-binding-unowned.md) | 시맨틱 결속의 어댑터 외주화 차단 및 미들웨어 계층 편입 | §15.59, §15.60 |
| [35](../../adr/0035-site-names-live-in-the-robot.md) | 사이트 명칭 및 지리 공간 정보의 기체 내부 저장 원칙 수립 | §15.68 |
| [36](../../adr/0036-work-first-assignment-vs-execution.md) | 일감 배정 어휘와 실행 계약 인터페이스의 명확한 분리 | — |
| [37](../../adr/0037-registration-is-discovery-or-declaration.md) | 기체 등록 경로의 이원화: 선언적 등록과 런타임 발견 수용 | — |
| [38](../../adr/0038-mission-layer-schema-is-ours.md) | 미션 계층 스키마 및 코어 오케스트레이션 엔진의 프로젝트 범위 수용 | — |
| [39](../../adr/0039-adapter-host.md) | 기종 무의존 공통 어댑터 호스트(`adapter-host`) 아키텍처 확립 | — |
| [40](../../adr/0040-remedy-is-a-query-not-a-contract.md) | 사건 번들과 대안 탐색을 계약이 아닌 조회 결과로 배치, 탐색 깊이 세 걸음 제한 | §15.149, §15.150 |
| [41](../../adr/0041-assignment-policy-outside-decision-inside.md) | 배정 정책의 외부 배치 허용, 채택 결정은 소유자가 tick 경계 안에서 수행 | §15.164 |
| [42](../../adr/0042-no-command-without-an-owner.md) | 소유자 미확정 자원에 대한 명령 금지, 자리는 자원 소유 대장에 존치 | §15.154, §15.162 |
| [43](../../adr/0043-entitlement-declared-outside-judged-inside.md) | 자동 승인 자격의 외부 선언과 내부 판정, 선언 목록의 위치는 배치가 결정 | §15.165 |

기록은 `docs/adr/`에 있고 번호가 이 표의 행 번호다. **이미 내려서 코드에 박힌 것만 쓴다** — 3a·3b가 만들 것(11~21)은 그때 쓴다. 결정하지 않은 것을 미리 적어 두면 그것이 결정처럼 보인다.

> 마지막 대조: 2026-09-19 · sha256:e26362c8b632 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87

## 15. 알려진 한계

> **이 절은 대장이 아니라 일지다.** 앞쪽(1~45)이 한계 목록이고 뒤쪽은 무엇을 지었고 무엇이 드러났는지의 기록이라,
> *"지금 무엇이 열려 있나"* 를 물으면 처음부터 읽어야 한다. 그 답은 [`docs/limits.md`](../../limits.md) 가 한 장으로 준다
> — **닫힌 것은 거기 없고**, 열린 것마다 *무엇이 있어야 닫히나* 가 한 줄로 적혀 있다. 새 한계는 여기 먼저 적히고 대장은 그것을 색인한다.

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
12. ~~**`gate`는 `contracts`에 빌드 의존을 걸지 않아 `buf build`가 먼저 돌아야 한다.**~~ **(닫힘, §15.111)** 라이브러리 의존은 여전히 없지만 `:gate:test` 가 `:contracts:generateProto` 에 **태스크로** 매달렸고, 디스크립터를 만드는 것이 buf 가 아니라 protoc 이라 순서가 Gradle 안에 있다.
13. **proto 파일 하나가 나쁘면 `ContractIndex`가 통째로 실패한다.** 게이트는 빨간불이 되므로 안전한 방향이지만, 나머지 스킬들의 대조 결과를 함께 잃는다.
14. **게이트 6번이 분류하지 않는 파괴가 있다.** 허용값 축소·문자열 길이 축소·수치 범위 축소는 §5.2가 열거하지 않아 **경고로만** 낸다. 실제로 클라이언트를 깰 수 있으므로 사양이 이를 분류하면 그때 오류로 올린다. 반대로 §5.2의 "성공 판정 기준 변경"은 프로파일에 담기지 않아 6번이 볼 수 없다 — 그것은 위 1번과 같은 성격이다.
15. **음성 하네스의 케이스 1·2는 Docker가 있어야 돈다.** 없으면 건너뛰며, `picasso.negative.strict=true`(CI)일 때만 실패한다. 로컬은 Docker 없이 나머지 일곱을 돈다.
16. **`buf.yaml`의 `breaking:` 절이 파싱되는지는 PR 빌드에서만 확인된다.** push 빌드에는 기준선이 없어 검사 2번이 건너뛴다. 자기 자신과 비교하는 `--against .` 스텝을 지운 대가다.
17. ~~**CI와 CLI 기본값이 `profile/fixtures/`를 본다.**~~ **(닫힘)** CI 가 `--profiles` 를 둘 다 주고(`profile/fixtures`·`profile/profiles`) 기준선도 디렉터리별로 나눠 뽑는다 — 평탄화로 덮이던 자리가 사라졌다. 아래는 그때의 기록이다.

    **CI와 CLI 기본값이 `profile/fixtures/`를 본다.** 2단계에서 `profile/profiles/`가 생기면 **둘 다 고쳐야 하며**, 안 고치면 진짜 프로파일이 게이트를 지나지 않는다. 기준선 파일도 `basename`으로 평탄화하므로 디렉터리가 여럿이 되면 동명 파일이 조용히 덮인다.
18. **검사 6의 `optional_fields` 매칭이 접미사 일치다.** 같은 파라미터 key를 가진 다른 스킬에도 소견이 붙는다. 1단계 프로파일에는 스킬이 둘뿐이라 드러나지 않는다.
19. **`mimic`이 게이트가 거절할 프로파일로 기동할 수 있다.** `ProfileSource`는 JSON Schema만 보고 게이트 검사 3번의 구조 규칙 넷(발행 간격 뒤집힘, `(skill_type, major)` 중복, 스킬 내 `key` 중복, 어댑터 전용 `error_type`)은 보지 않는다. §10.2가 요구하는 것이 스키마 검증뿐이라 사양 위반은 아니지만, `--profile <path>`가 임의 경로를 받으므로 실제로 가능한 비대칭이다.
20. **proto 코드 생성과 디스크립터를 서로 다른 도구가 만든다**(ADR 30). Gradle protobuf 플러그인의 protoc와 `buf` 내장본이 버전이 달라 생성 코드와 디스크립터가 미세하게 다를 수 있다. 쓰는 것이 메시지 구성이라 실질 영향은 없다. 그리고 protobuf-gradle-plugin 0.9.4는 **Gradle 10에서 깨진다** — legacy `Usage` 속성과 다중 문자열 의존 표기가 플러그인 내부에서 나오므로 우리가 못 고친다. Gradle 10 이전에 플러그인 버전을 올려야 한다.
21. **`session_id`가 §5.5의 ULID가 아니라 기동 카운터다.** ULID의 난수부를 시드에서 뽑으면 §12.1의 결정성 규율("시드 + 가상 시계 고정 = 동일 이벤트 시퀀스")과 "재기동하면 새 세션"이 충돌하고, `UUID.randomUUID()`를 쓰면 결정성이 깨진다. 세션의 요건은 "온라인이 될 때마다 새것"이므로 프로세스 내 카운터로 족하다. **대가는 프로세스를 재기동하면 카운터가 0으로 돌아간다는 것**이다 — 같은 밀리초에 재기동하면 세션이 겹칠 수 있다. `mimic`은 PoC이므로 감수한다. **어댑터 호스트도 같은 카운터를 쓴다**(`host-N`) — 계획은 실물에서 ULID 였으나 그렇게 하지 않았고, 그래서 이 한계는 발신자 **둘**의 것이다(`docs/limits.md`).
22. **`contract_digest`가 `buf` 모듈 다이제스트가 아니라 디스크립터 셋의 SHA-256이다.** `buf`는 Docker 래퍼이고 게이트는 CI가 `buf build`를 먼저 돌리는 순서에 기대고 있는데, 런타임 헤더까지 거기 매달면 `mimic`이 Docker 없이 기동하지 못한다. `includeSourceInfo = false`라 주석만 고친 커밋에서는 변하지 않는다(실측). **역은 성립하지 않는다** — `includeImports = true`가 `descriptor.proto`를 끌고 오므로 protobuf나 플러그인 버전을 올리면 계약이 그대로여도 다이제스트가 바뀐다. **그리고 그 순서 의존이 로컬에서 물었다 (2026-09-09).** 디스크립터가 **둘**이었다 — Gradle 이 `contract_digest` 용으로 만드는 `picasso.desc` 와, 게이트 시험이 읽는 `contracts/build/descriptor.binpb`. 뒤의 것은 `tools/buf build` 를 **손으로** 돌려야 갱신됐고, `skill_catalog.proto` 에 옵션을 더하고 게이트 시험을 돌리니 빨개지지 않고 **옛 판정**이 나왔다 — 낡은 디스크립터는 낡은 코드와 사이좋게 초록이다. **→ 디스크립터가 하나가 되면서 닫혔다(§15.111).** 남은 것은 다이제스트의 계산 방법이 `buf` 모듈 다이제스트가 아니라는 것뿐이고, 그것은 그대로다.
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

    - ~~`bufDescriptor` 는 Docker 가 있어야 돈다. Docker 없는 대안(protoc 가 이미 만드는 `contract-descriptor/picasso.desc` 를 쓰는 것)은 게이트의 입력을 바꾸는 일이라 여기서 안 했다.~~ → **그 대안이 답이었다**(§15.111). 태스크는 지웠다.
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
    - ~~도는 태스크의 갱신을 안 한다.~~ → §15.109 에서 닫혔다. **그 진술의 전제도 틀렸다** — 어댑터 셋 중 셋이 그 합성을 든다.
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
    - ~~못 보낸 구간이 버퍼에서 밀려나도 세션을 새로 안 낸다.~~ → §15.107 에서 닫혔다.
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

103. **발견이 실증됐다 — 플릿에서 본 기체가 사람의 말 없이 원장에 앉는다(트랙 A3·A4).**

    ADR 37 이 미결로 남긴 것이 *"발견 경로를 무엇으로 실증하는가 — 어댑터의 남쪽에 플릿 흉내가 필요한데 공용 도구가 없다"* 였고, §15.101 이 문을 만들면서도 *"그 문으로 올리는 어댑터는 없다"* 고 적었다. `OrbitAdapter`(A3)와 `OrbitDiscovery`(A4)가 그 둘을 잇는다.

    ### 어댑터가 드는 것과 안 드는 것

    계약의 스킬 넷 중 **`navigate_to` 하나만**, 그것도 `PARTIAL` 이다. 계약의 `location` 을 **저작된 미션의 이름**으로 읽어 그 미션을 파견한다 — **ADR 35 를 한 겹 옮긴 것**이며(*사이트 이름은 로봇 안에 산다* 가 여기서는 *플릿 안에 산다*), 주인이 사이트라는 것은 그대로다. 나머지 셋은 프리미티브가 없다. **억지로 옮기지 않았다**: `pick_place` 를 미션 이름으로 흉내내면 상류는 조작을 시켰다고 믿는데 실제로는 누가 저작해 둔 무엇이 돈다. §15.77 이 *"못 옮기면 그 층으로는 계약을 못 만족한다고 적는다"* 고 정한 그 적음이 `profile/distance/orbit.json` 이다 — **프로파일을 안 가리키는 첫 측정**이고(Orbit 은 기종이 아니라 층이다), 스키마가 `profile` 을 선택으로 둔 자리가 여기서 처음 쓰였다.

    ★**`move_relative` 가 처음으로 `NO` 가 됐다.** 실물 셋에서 3/3 이던 유일한 스킬이다. ADR 36 이 *"3/3 인 것은 좋은 계약요소가 아니라 너무 저수준이라는 증거"* 라 적었고, **층이 하나 오르자 그 문장의 반대쪽 증거가 나왔다** — 낮은 층에서만 보편적인 어휘였다.

    ### 종착을 자유 문자열로 판정하지 않는다

    `Run.missionStatus` 는 값 집합 없는 문자열이다. 어떤 문자열이 성공인지 우리가 짐작해야 하고, 짐작한 매핑은 벤더가 문구를 바꾸는 날 조용히 틀린다. 그래서 **스펙이 뜻을 적어 둔 둘만** 쓴다 — `Run.endTime` 이 종착을, `RunEvent.error` 가 성패를 정한다. 원문은 버리지 않고 진단에 싣는다. 사건을 못 읽으면 **성공으로 접지 않고** `NEEDS_INTERVENTION` 이다(§15.41 의 틀리는 방향).

    ### 발견 — 별명이 신원이다

    플릿이 주는 후보가 둘이고 **별명**을 골랐다. `hostname` 은 주소라 바뀌고, 계약의 `robot_id` 는 기체의 신원이라 바뀌면 안 된다(§15.78 의 이름과 주소). 파견도 별명으로 지목하므로 벤더 안에서도 그것이 신원 노릇을 한다. **대가**: 별명은 Orbit 인스턴스 안에서만 유일하다 — 사이트가 여럿이고 별명이 겹치면 `robot_id` 가 부딪친다. 접두사를 붙여 피하지 않는 것은 그러면 파견이 쓰는 이름과 계약이 쓰는 이름이 갈리기 때문이다.

    `OrbitDiscoveryEndToEndTest`(harness, Postgres): 가짜 Orbit → `OrbitDiscovery` → 적재 문 → 원장. **사람이 아무것도 안 적었는데 기체가 있고, 상태가 `CLAIMED` 가 아니라 `DISCOVERED` 다.** 다시 훑어도 안 쌓이고(주소가 바뀌어도 같은 기체다), **사람이 선언한 기체를 발견이 덮지 않는다**(출처는 두 번째 진실이 되지 않는다 — 일련번호도 접속 정보도 그대로다).

    ### 스키마가 한 칸 열렸다 — 플릿은 일련번호를 안 준다

    Orbit 의 `Robot` 에 일련번호가 없다. Orbit 이 모르는 것은 아니고(`Run.robotSerial`) **기체 자원에 안 싣는다.** 우리 `robot.serial_number` 가 필수라 부딪쳤고, 고를 수 있는 것이 셋이었다 — ① 호스트명을 그 자리에 넣는다(**거짓말**: 주소는 바뀌고 일련번호는 안 바뀐다) ② 발견을 거절한다(이 벤더에서는 발견이 아예 안 된다) ③ 없을 수 있게 한다. **③ 을 골랐다**(V13). 선언 경로에는 여전히 요구한다 — 사람이 적는 자리에는 그것을 아는 사람이 있고, 표의 `CHECK` 가 그것을 든다. `robot_liveness` 가 *"안 물어봤다"* 를 널로 가른 것과 같은 규율이다: **모르는 것은 모른다고 적는다.**

    ### 정직하게 적어 둘 것

    - **HTTP 구현이 없다.** `HttpRobotDiscovery` 는 적재 문으로 보내는 쪽이고, **Orbit 에 붙는 클라이언트는 안 지었다.** 남쪽은 여전히 인터페이스이고 시험의 가짜뿐이다 — 진짜 Orbit 에 붙여 본 적이 없다.
    - **주기가 없다.** `sweep()` 은 한 번 훑는다. 스케줄러는 배치(A6)의 일이며, 여기서 스레드를 돌리면 시험이 벽시계에 매인다.
    - **플릿에서 사라진 기체를 아직 안 다룬다.** ADR 37 의 미결 그대로다 — 훑을 때마다 있는 것만 올리고, 없어진 것은 원장에 남는다.
    - **어댑터 인스턴스는 여전히 없다**(A5). 그래서 *"이 발견이 어느 어댑터의 것인가"* 에 원장이 못 답한다.
    - 결함 주입 A3 여섯(자유 문자열로 종착 판정 · 오류 0 을 실패로 · 겹치는 이름을 첫 번째로 · 못 읽은 사건을 성공으로 · 모든 스킬 접수 · 못 보는 결함을 없음으로)과 A4 여섯(신원을 주소에서 · 주소를 일련번호 자리에 · 못 닿은 플릿을 빈 목록으로 · 연계 없음을 조용한 성공으로 · 널 일련번호 거절 · 거절을 답에서 버림) 전부 겨냥한 시험이 잡았다.

104. **남쪽이 와이어를 지난다 — 그리고 게시 스펙대로 짜면 거절당한다(트랙 A5).**

    §15.102~§15.103 동안 `OrbitLink` 의 구현은 **시험의 가짜뿐**이었다. 직렬화도 상태 코드도 인증 헤더도 경로 규약도 검사받은 적이 없었고, 그 사실이 *"진짜 서버에 붙여 본 적 없다"* 는 문장 안에 뭉뚱그려져 있었다. 그 문장을 쪼개면 셋이고 성질이 다르다 — **HTTP 클라이언트가 없다**(안 지은 것) · **가짜조차 와이어를 안 지난다**(안 지은 것) · **배포된 인스턴스에 못 붙는다**(못 짓는 것). 앞의 둘을 닫았다.

    ★**이 단계가 계획에 없었다.** 트랙 A 의 런처 항목이 증명으로 *"가짜 Orbit HTTP"* 를 적어 놓고, 그 클라이언트를 **만드는 단계를 안 넣었다.** 사용자가 *"이제 만들어야지, 계획에 없었어?"* 라고 물어서 드러났다. 원인은 계획을 세울 때 **증명 칸이 전제하는 것이 만드는 칸에 있는지 안 본 것**이고, 그 확인이 계획 문서의 새 규율이다.

    ### 스펙과 클라이언트가 어긋나고, 어긋나면 클라이언트를 따른다

    벤더가 배포한 파이썬 클라이언트가 **서버가 실제로 받는 모양의 증거**다. 게시 스펙은 그것과 네 자리에서 다르다.

    | | 게시 스펙 | 벤더 클라이언트 |
    |---|---|---|
    | 파견 경로 | **없다** | `calendar/mission/dispatch/{nickname}?currentDriverId=…` |
    | `schedule.timeMs` | `type: integer` | **`{low, high, unsigned}`** |
    | 미션 지목 | `task.missionId` | **`task.dispatchTarget.missionId`** |
    | GET 경로 | `/robots` | **`/api/v0/{path}/`** — 언제나 뒤에 슬래시 |

    **뒤의 셋은 스펙만 보고 짰으면 전부 틀렸을 것이고, 아무 시험도 안 빨개졌을 것이다.** 그래서 추출기를 두 번 넓혔다 — 클라이언트가 보내는 **본문 키**(`payload` 딕셔너리)와 경로에 **인라인으로 적힌 응답 봉투**(`GET /runs/#resources`)를 이름으로 들인다. 심볼이 251 → 323 이 됐고, 이제 그 넷을 짚을 수 있다. 앞 판은 `components.schemas` 만 읽어서 **봉투의 `resources` 를 짚을 수 없었고**, 짚을 수 없으면 구현이 그 이름을 하드코딩하게 되며 벤더가 바꿔도 아무것도 안 빨개진다.

    ### 인증이 두 단계다

    토큰만으로는 안 된다. ① 루트에 GET 해서 **`x-csrf-token` 쿠키**를 받아 같은 이름의 헤더로 되돌리고 ② `Authorization: Bearer` 를 얹고 ③ `api_token/authenticate` 로 확인한다. **①을 빼면 토큰이 맞아도 거절당하는데 그 실패가 인증 실패처럼 보인다.** ③ 이 실패하면 **링크를 아예 안 만든다** — 모든 요청이 401 로 답하는 링크를 쥐여 주는 것보다 붙는 시점에 실패하는 편이 낫다.

    ### 안 접는 것 셋

    - **응답 봉투가 둘이다.** `/robots`·`/missions` 는 배열 그대로, `/runs/`·`/run_events/` 는 `{limit, offset, total, resources}`. 모르는 모양이 오면 **빈 목록으로 접지 않고 실패**다 — 접으면 *"플릿에 미션이 없다"* 가 되고 그 위에서 이름 조회가 조용히 실패한다.
    - **오류 코드 0 과 없는 것이 다르다.** 0 은 벤더가 *오류 없음* 으로 쓰는 값이고 없는 것은 안 실린 것이다.
    - **404 는 답이다.** 이 배포본에 그 경로가 없다는 플릿의 말이지 못 닿은 것이 아니다 — 거절이고, 전송 실패만 못 닿음이다.

    ### 정직하게 적어 둘 것

    - **여전히 진짜 인스턴스가 아니다.** 스텁이 답하는 모양은 우리가 벤더 문서에서 읽어 적은 것이다. 배포본이 그와 같은지, 파견 경로가 실재하는지, `missionStatus` 의 실제 값이 무엇인지는 붙어 봐야 안다(C-3).
    - **정렬을 벤더에게 안 맡긴다.** `orderBy` 질의 파라미터가 있지만 값 집합이 스펙에 없어 무엇을 넣어야 하는지 모른다. 별명으로 거르고 창 20 개를 받아 `startTime` 으로 우리가 고른다 — **창 안에 최신 실행이 없으면 못 본다.**
    - **`https` 를 강요하지 않는다.** 벤더 클라이언트는 `https://{hostname}` 을 박아 두고, 우리는 기저 URL 을 받는다. 스텁 서버를 상대로 와이어를 지나기 위해서이며 **벤더와 다른 유일한 자리**다.
    - **TLS 검증·클라이언트 인증서를 안 다룬다.** 벤더 클라이언트에는 `verify`·`cert` 가 있다. 기본 신뢰 저장소를 쓰며, 사설 CA 를 쓰는 배포본에는 그대로 못 붙는다.
    - 결함 주입 아홉(스펙대로 `timeMs` 정수 · 스펙대로 `task.missionId` · GET 뒤 슬래시 제거 · CSRF 헤더 누락 · 봉투 안 벗김 · 모르는 모양을 빈 목록으로 · 거절을 못 닿음으로 · 나쁜 토큰에도 링크 발급 · 오류 0 을 없음으로) 전부 겨냥한 시험이 잡았다.

105. **배포가 모델에 들어왔다 — 그리고 바인딩은 빌드를 계속 가리킨다(트랙 A6).**

    ADR 37 결정 2 를 지었다. 축이 셋이 된다: `adapter`(제품) · `adapter_version`(빌드) · **`adapter_instance`(배포된 것 — 접속 설정을 갖는 것)**.

    **왜 이제야 만드나.** §15.101 이 이 표를 미뤘고 이유를 적어 뒀다 — *"직결의 설정은 기체의 것이라 `robot.endpoint` 가 들고, 플릿 경유의 설정은 들 어댑터가 없다. 아무도 안 쓰는 표를 먼저 만들지 않는다."* 이제 플릿에 붙는 어댑터가 있다(§15.102~§15.104). **소비자가 생겨서 만들었다.**

    ### 미결 1 을 정했다 — 바인딩은 빌드다

    ADR 37 이 *"바인딩이 빌드를 가리키는가 인스턴스를 가리키는가"* 를 미결로 뒀다. **빌드 그대로 둔다.**

    바인딩이 답하는 질문은 *"이 기체가 어느 능력으로 도는가"* 이고 그것은 **빌드의 성질**이다(계약 semver·적합성). 인스턴스는 **배포의 사실**이라 같은 빌드로 프로세스를 다시 띄우기만 해도 바뀐다. 둘을 한 열로 접으면 **재배포마다 바인딩 이력이 한 줄씩 늘고 진단 1번이 그것을 *"능력이 바뀌었다"* 로 보여 준다** — 아무 능력도 안 바뀌었는데.

    대신 *"이 발견이 누구의 것인가"* 는 **기체 쪽**에 적는다(`robot.discovered_by`). 그 질문의 주어가 바인딩이 아니라 기체이기 때문이다. §15.103 이 *"어댑터 인스턴스가 없어서 원장이 못 답한다"* 로 남긴 자리가 그것으로 닫힌다.

    ### 인스턴스가 실재해야 그 발견을 받는다

    발견이 자기 인스턴스를 신고한다(`?instance=`, 자기 신고 — `site`·`X-Actor` 와 같은 성질). **밝히면 실재해야 하고, 모르는 이름이면 목록 전체를 거절한다.** 기체마다 거절하면 사유가 N 번 반복되고 **원인이 기체가 아니라 발신자라는 것이 안 보인다.** 그것이 ADR 37 의 절차(③ 접속 정보를 입력하고 ④ 띄우면 로봇이 흘러 들어온다)를 표가 드는 방법이다.

    **안 밝혀도 받는다.** 옛 배포가 섞여 돌 수 있고, 그때는 널로 남아 진단이 *"어느 어댑터인지 모른다"* 로 보여 준다. 그리고 **안 밝힌 보고가 이미 아는 것을 지우지 않는다**(`COALESCE`) — 생존 보고의 사이트 이름 요약이 같은 규율이다.

    ### 접속 설정은 한 갈래만 여기 있다

    플릿 주소만 든다(결정 4). 직결이면 로봇의 주소가 `robot.endpoint` 에 있고 인스턴스의 그 칸은 비어 있다 — 같은 사실을 두 곳에 두면 하나가 바뀐 날 어느 쪽이 맞는지 정해져 있지 않다. **자격증명은 어느 쪽도 안 받는다**(§6.3).

    ### 정직하게 적어 둘 것

    - **인스턴스가 살아 있는지는 모른다.** 등록은 사람이 적은 것이고(조작 문), 그 프로세스가 지금 떠 있는지 원장은 안 본다. 기체의 생존은 `robot_liveness` 가 보지만 어댑터의 생존을 보는 것은 없다.
    - **플릿에서 사라진 기체를 여전히 안 다룬다**(ADR 37 미결). 훑을 때마다 있는 것만 올리고 없어진 것은 원장에 남는다.
    - **진단 열 번이 배포를 보여 주지만 그것을 고치는 문은 없다.** 잘못 적은 인스턴스를 지우는 경로가 없다 — 재등록으로 덮을 수만 있다.
    - 결함 주입 여섯(모르는 인스턴스 수락 · 안 밝힌 보고가 아는 것을 지움 · 올린 쪽을 아예 안 적음 · 모르는 빌드로 배포 기록 · 재배포를 새 등록으로 · 어댑터가 자기를 안 밝힘) 전부 겨냥한 시험이 잡았다.

106. **배치가 선다 — 런처가 플릿에 붙어 발견하고, 발견한 기체들을 실 포트의 계약 뒤에 세운다(트랙 A7).**

    §15.98 이후 *"배치 런처 없음"* 이 계속 열려 있었다. 조립은 시험 안에만 있었고 — `HostedRobot` 을 손으로 만들어 in-process 전송으로 붙였다 — **운영에서 그것을 세우는 코드가 없었다.** 트랙 A 의 마지막이 그것이다.

    ### 조립 순서가 곧 ADR 37 의 절차다

    1. **플릿에 붙는다.** 못 붙으면 거기서 끝난다 — **붙지도 못하면서 포트를 열면 소비자가 아무것도 못 하는 기체를 본다.**
    2. **발견한다.** 플릿이 아는 목록을 얻어 적재 문으로 올린다. 실패하면 세우지 않는다: **무엇을 세울지가 그 답에서 온다.**
    3. **기체마다 어댑터를 세운다.** 계약은 기체 단위이므로 어댑터도 기체마다 하나이고 링크(플릿 서버)만 공유한다.
    4. **포트를 열고 그 다음에 `ONLINE` 이 나간다**(§10.2).

    ### 호스트가 기체 여럿을 든다

    플릿 뒤에는 기체가 여럿이다. 라우팅이 `robot_id` 로 그중 하나를 지목하며, **미믹이 한 프로세스에 여럿을 호스팅하는 것과 같은 편의이고 같은 단서가 붙는다** — PoC 의 편의이지 아키텍처 주장이 아니다(ADR 21). 기체 하나짜리 배치(직결 어댑터·시험)는 편의 생성자로 그대로 남는다.

    ### 무엇이 처음 지나는가

    `OrbitLauncherTest`: 런처가 조립하고 **실 TCP 포트가 열린다.** 남쪽만 스텁이고 그 사이 전부가 진짜다 — `PicassoClient` → gRPC/netty → `AdapterHost` → `OrbitAdapter` → `OrbitHttpLink` → HTTP. 소비자가 능력을 읽고, 일을 시키고(계약의 `location` 이 저작된 미션 이름으로 읽혀 파견된다), 종착을 `WatchTask` 로 받는다.

    ★**그 시험이 계약 면의 사실 하나를 드러냈다** — 플릿 뒤의 기체는 **`GetSnapshot` 이 `UNAVAILABLE`** 이다. 플릿이 기체 결함을 안 나르므로 어댑터가 *못 본다* 고 답하고, 호스트가 그것을 *없다* 로 접지 않기로 한 결정(§15.99)이 여기서 계약 면에 그대로 나타난다. **소비자는 이 기체의 스냅샷을 영영 못 받는다** — 그것이 층이 하나 는 대가다.

    ### 주입이 규칙 하나를 시험 밖에서 찾아냈다

    ★*"포트를 열고 그 다음에 `ONLINE`"* 이라는 §10.2 의 순서를 **아무 시험도 안 보고 있었다.** 순서를 뒤집는 주입이 안 잡혀서 알았다. 그 순서를 묻는 유일한 방법이 실 포트다 — `Server.getPort()` 가 기동 전에는 던지고 기동 뒤에 답하므로, 발행 시점에 포트를 물어 답이 오는지로 판정한다. in-process 전송에는 포트가 없어 물을 수 없다.

    ### 정직하게 적어 둘 것

    - **프로파일이 하나다.** 발견된 기체 전부에 같은 프로파일을 적용한다. 플릿 뒤의 기종이 섞여 있으면 틀리며, **우리가 그것을 알아낼 방법이 없다** — Orbit 의 `Robot` 자원에 기종을 말하는 칸이 없다(일련번호가 없는 것과 같은 자리).
    - **발견이 한 번이다.** `sweep()` 은 기동 때 한 번 돌고, 뒤에 플릿에 기체가 늘어도 이 프로세스는 모른다. 주기 발견은 안 지었다.
    - **펌프 스레드가 데몬인 것을 시험이 안 본다.** 프로세스 밖에서만 관측되는 성질이라 주입이 잡지 못했고, 그것을 안 잡힌 채로 적어 둔다.
    - **CLI 가 아직 없다.** `OrbitLauncher` 는 라이브러리이고 `main` 함수와 인자 파싱은 없다. 미믹의 `MimicCli` 가 하는 것(인자 검증·기동 거부 메시지·프로세스 유지)이 여기 없다.
    - **여전히 진짜 Orbit 인스턴스가 아니다**(C-3).
    - 결함 주입 여섯 중 다섯을 겨냥한 시험이 잡았다(플릿 거절에도 포트 개방 · 빈 플릿에도 포트 개방 · 첫 기체만 호스팅 · 포트보다 먼저 알림 · 라우팅이 아무 기체나 답함). 여섯째는 위의 데몬 스레드이며 **의도적으로 시험이 없다.**

107. **못 보낸 구간을 버렸으면 세션을 새로 낸다 — 그리고 같은 규칙을 두 벌로 가진 대가를 또 치렀다(트랙 B1).**

    §15.99 가 남긴 정직 항목이다. 호스트의 세션이 불변이라, 재생 버퍼가 넘쳐 **아직 브로커에 못 보낸** 이벤트를 버려도 소비자가 그 사실을 알 방법이 없었다 — gRPC 로 되짚는 소비자는 `SEQUENCE_EVICTED` 로 알지만 **발행만 듣는 소비자는 조용한 결손을 본다.** 세션을 바꿔 스냅샷부터 다시 세우게 하는 것이 그것을 알리는 유일한 방법이고(§10.6), 그래서 [`HostedRobot.sessionId`] 가 `val` 을 그만두었다.

    **바꾸는 조건이 좁은 것이 요점이다.** 단절만으로, 또는 **다 나간** 구간을 버린 것으로 바꾸면 버퍼링이 무의미해진다 — 소비자가 매번 스냅샷부터 다시 세우게 되고 버퍼가 값을 하지 않는다. 넘칠 때, 그리고 버린 것이 **못 보낸 것일 때만** 바꾼다. 반대쪽 시험(`이미 나간 구간이 밀려나는 것으로는 세션을 안 바꾼다`)이 없으면 *언제나 새 세션* 이 앞 시험을 통과한다.

    ### 미믹에서 같은 자리의 결함 둘이 나왔다

    ★호스트에 붙이려고 미믹의 `EventStream.evict` 를 읽었더니 **거기서 두 가지가 틀려 있었다.** 규칙을 두 벌로 갖는 대가가 §15.99 의 이중 발행에 이어 두 번째로 나타난 것이다.

    - **비우는 것도 버리는 것이다.** 세션을 새로 내면 옛 구간을 다시 밀 뜻이 없으므로 버퍼를 통째로 비우는데, 축출 경계는 **첫 하나**에만 올려 두고 있었다. 그래서 그 뒤의 번호들은 버퍼에도 없고 축출로도 안 적힌 채 사라졌다 — 되짚기가 그 번호에 대고 *잃은 것 없다* 고 답한다. 새 시험이 이것을 **번호를 하나씩 세어** 잡는다: 버퍼에도 없고 브로커에도 안 갔고 축출 경계보다 뒤인 번호가 하나도 없어야 한다.
    - **비운 버퍼에 손을 넣고 있었다.** 축출 거절의 사유 문구가 `버퍼=[처음, 마지막]` 을 적는데, 세션 재발급이 버퍼를 비우고 간 직후에는 *마지막* 이 없다. 그 자리에서 예외가 나므로 소비자는 거절이 아니라 **정체**를 본다 — 스트림이 안 닫히면 결함이 실패가 아니라 멈춤으로 나타난다는 규율(§15.87)의 반대쪽 사례다.

    ### 그리고 미믹은 브로커 없이는 기동조차 못 했다

    ★위 시험을 세우려 발행을 막아 보니 **기동이 실패했다.** §10.6 은 *"발행 실패가 태스크 실행을 막으면 브로커가 죽은 날 로봇이 함께 멈춘다"* 고 적고 이벤트 경로가 그 규율을 지키는데, **기동 발행(`announceOnline`)과 상태 발행만 예외를 그대로 올려보내고 있었다.** 현재값 발행 하나로 모아 삼킨다 — 이벤트와 달리 상태·연결은 *현재값* 이라 못 보내면 버리고 다음 것이 대신한다(큐에 쌓으면 재연결 순간 낡은 현재값이 줄줄이 나가고 마지막 것만 참이다). 호스트는 처음부터 이 규율을 지키고 있었다.

    ### 정직하게 적어 둘 것

    - **세션 번호는 여전히 카운터다**(미믹과 같은 이유 — §5.5 는 ULID 라 한다).
    - **소비자 쪽 대응은 시험하지 않는다.** 세션이 바뀌면 스냅샷부터 다시 세우는 것은 소비자의 일이고, 이 저장소의 소비자는 미들웨어다 — 그 경로(§15.95 의 재세움)는 단절로 트리거되고 **세션 변경으로 트리거되는 재세움은 아직 없다.** 다음 후보다.
    - 결함 주입 일곱을 겨냥한 시험이 전부 잡았다(세션 안 냄 · 항상 냄 · 옛 구간 안 비움 · 경계를 첫 하나에 둠[호스트·미믹 각각] · 빈 버퍼에 손 넣기 · 현재값 발행이 예외를 올려보냄).

108. **진행률 — 셀 수 있는 기종만 낸다. 갈리는 자리는 *국면이냐 개수냐* 가 아니라 *분모가 서느냐* 였다(트랙 B2).**

    호스트는 `progress` 를 `종착이면 1.0, 아니면 0.0` 으로 내고 있었다. 미믹은 상태기계가 있어 분수를 내므로, 소비자가 미믹에서 움직이는 막대를 보고 실물에서 얼어붙는 막대를 본다 — *"엔드포인트만 바꿔 오간다"* 가 이 칸에서 거짓이었다.

    ### 벤더 넷을 다시 세었다

    | 기종 | 근거 | 판정 |
    |---|---|---|
    | **Orbit** | `Run.actionCount` · `Run.pendingActionCount` | **YES** — 벤더가 개수 둘을 준다 |
    | **Digit** | `execution-state-node.status` 가 마디마다 온다 | **YES** — 잎을 센다 |
    | **Spot** | 명령 피드백 `STATUS_IN_PROGRESS`/`STATUS_COMPLETE`, 취득 `GetStatusResponse.Status` 열한 값 | **NO** — 국면이지 분수가 아니다 |
    | **G1** | 심볼 161 전수에 없다(퍼센트는 배터리뿐) | **NO** |

    ★**계획이 두 칸을 틀리게 적어 뒀다.** 증명 칸에 *"Spot 취득의 11 상태, Orbit `RunEvent`"* 라 써 있었는데, 조사해 보니 **Spot 은 못 내고**(11 은 국면이다) **Orbit 은 낼 수 있되 `RunEvent` 가 아니라 `Run` 이 준다.** 계획을 세울 때 근거를 이름까지 확인하지 않고 *"있을 법한 자리"* 를 적은 것이 원인이다.

    ### 갈리는 자리

    Spot 도 마디 상태를 준다 — 미션 층의 `State.NodeStatesAtTick.node_states` 다. 그런데 판정이 Digit 과 갈린다. **`Repeat`·`Switch`·`Condition` 이 있어 분모가 안 서기 때문이다**(§15.80 이 *"Digit 은 조건·분기 0 건"* 이라 적은 그 차이). Digit 의 트리는 평평한 순서열이라 잎의 수가 곧 분모다. 그래서 이 조사의 축은 *벤더가 진척을 말하는가* 가 아니라 **분모가 고정되는가** 이고, 그것을 `progress_support` 로 조사 스키마에 넣었다(필수 4번째 3값).

    ### 규칙은 호스트에 모았다

    성공 종착은 1.0, 값은 `(task_id, revision, attempt)` 안에서 **단조 비감소**(앞의 값이 바닥이다), 범위 밖은 자른다. 바닥이 필요한 이유가 실측으로 나왔다 — 실행이 끝나면 플릿은 셀 것을 잃고 어댑터가 *못 잰다* 로 돌아가는데, 바닥이 없으면 **실패 종착에서 진행률이 0 으로 되감긴다.** 실패는 *거기서 멈춘 것*이지 아무것도 안 한 것이 아니다.

    ### 계약이 *못 잰다* 를 못 싣는다 — 그리고 지금은 안 고친다

    `WatchTaskResponse.progress` 는 맨 `double` 이라 [ProgressObservation.NotObservable] 이 호스트에서 `0.0` 으로 접힌다. 못 재는 기체와 아직 아무것도 안 한 기체가 계약 면에서 같아 보이고, **진행 정체로 개입을 판단하는 소비자는 그 둘을 갈라야 한다.** 그런 소비자가 아직 없으므로 계약을 안 고친다 — §15.79 가 발신자 없는 거절 코드를 안 만든 것과 같은 규율의 반대쪽(여기는 발신자가 둘 있고 소비자가 없다). **닫는 조건**: 진행률로 무언가를 판단하는 소비자가 생기면 `HoldState` 와 같은 모양의 자리를 계약에 만든다.

    ### 정직하게 적어 둘 것

    - **Digit 의 분모는 우리 것이 아닐 수 있다.** [DigitAdapter.hold] 가 적어 둔 것과 같은 한계다 — 로봇이 든 실행 트리가 우리가 보낸 것 전부인지 확인할 표면이 없어, 이전 시퀀스가 남아 있으면 그 마디까지 센다. 그래서 `basis` 에 *실행 트리 잎* 이라 적지 *이 태스크의 행동* 이라 하지 않는다.
    - **Orbit 의 개수는 실행이 생긴 뒤에만 있다.** 파견 직후 첫 폴 전까지는 *못 잰다* 이고, 그것을 0 으로 접지 않는다.
    - Spot·G1 의 `progress()` 는 기본값과 같은 답을 내므로 **거동이 아니라 사유만 다르다.** 그래서 시험이 사유의 벤더 심볼을 확인한다 — 그러지 않으면 재어 본 판정과 안 재고 기본값에 기댄 것이 구별되지 않는다.
    - 결함 주입 열을 겨냥한 시험이 전부 잡았다. ★그중 둘이 **시험 하나가 약하다는 것을 먼저 알려 줬다** — 각본 어댑터가 실패 뒤에도 같은 분수를 계속 답해서, 바닥을 없애는 주입이 통과했다. 실물의 거동(실행이 끝나면 셀 것이 없어진다)을 각본에 넣고서야 그 시험이 일을 했다.

109. **도는 태스크의 갱신 — 조사부터 했더니 계획의 전제가 반대였다(트랙 B3, 계약 0.7.0).**

    계획은 이 단계를 *"먼저 조사다. 셋(넷) 중 그 합성을 드는 기종이 있는지 확인하고, **없으면 짓지 않고** 계약 쪽 제안으로 돌린다"* 로 적어 뒀다. 세어 보니 **셋이 든다.**

    | 기종 | 갱신을 만드는 방법 | 판정 |
    |---|---|---|
    | **Spot**(미션) | `StopMission` → `LoadMission` → `PlayMission` | **YES** — 벤더가 셋을 이름으로 다 준다 |
    | **Spot**(명령) | `se2Velocity` 새 값 하나 | **YES** — 아래 |
    | **Spot**(취득) | `CancelAcquisition` 이 **거절할 수 있다** | **NO** |
    | **Digit** | `remove-action` → `add-sequential-actions` | **YES** |
    | **G1** | `SetVelocity` 새 값 하나 | **YES** |
    | **Orbit** | 도는 미션을 멈추는 문이 없다 | **NO** — 넷 중 하나 |

    ### 갈리는 자리는 *멈춤이 보장되는가* 다

    §4.4 는 갱신을 `Halt` → `Reset` → `Start` 의 **합성**으로 적었다. 그 첫 칸이 없으면(Orbit) 갱신이 성립하지 않고, 첫 칸이 **거절될 수 있으면**(Spot 취득의 `STATUS_FAILED_TO_CANCEL`) 갱신이 둘을 돌린다. 그래서 조사 스키마의 `update_support` 가 묻는 것은 *멈출 수 있는가* 이지 *다시 시킬 수 있는가* 가 아니다.

    ★**그리고 지시값 층에서는 셋이 한 칸으로 접힌다.** `se2Velocity`·`SetVelocity` 는 지시값이라 새 값이 곧 지금의 지시다 — 멈췄다 다시 시킬 필요가 없고, 거절되면 앞 지시가 그대로 산다. §4.4 의 세 칸은 **미션·액션 층의 모양**이었고 계약이 그것을 일반 규칙처럼 적어 둔 것이다(ADR 36 의 층 차이가 또 나왔다). G1 에서는 이 사실이 이미 코드에 있었다 — 그 어댑터의 **취소가 `SetVelocity(0,0,0)`** 이다. 갈아서 멈출 수 있으면 갈아서 다르게 갈 수도 있다.

    ### 계약이 자란다 — `UPDATE_UNSUPPORTED`(0.7.0)

    계획은 *아무도 안 들면* 거절 코드를 제안하려 했는데, 실제로는 **드는 기체가 있어서** 코드가 필요해졌다. 못 드는 기체(Orbit)의 거절을 `INVALID_TRANSITION` 에 접으면 소비자가 *지금 상태가 안 받는다*(정리 중·종착)와 *이 로봇은 원래 못 한다* 를 구별하지 못한다 — 앞은 기다렸다 다시 보내면 되고 뒤는 취소한 뒤 새 `task_id` 로 가야 한다. `CANCEL_UNSUPPORTED`·`PAUSE_UNSUPPORTED` 가 따로 있는 것과 같은 이유이고, ADR 9 를 통과한다: 발신자는 Orbit 어댑터이고 소비자는 미들웨어다(§15.92 가 갱신 거절을 *이미 종착* 으로 읽고 지연 이벤트를 기다리는데, 원인이 *못 하는 기체* 이면 그 이벤트는 영원히 안 온다).

    ### 호스트가 규칙을 든다

    `ACCEPTED`·`RUNNING` 만 어댑터로 보낸다. **실물에서 `ACCEPTED` 는 이미 명령이 나간 상태다** — 어댑터의 `accept` 가 곧 벤더 호출이라, 미믹의 *아직 시작 전* 과 다르다. 갱신은 같은 핸들의 `revision` 을 올리고 **로그에 한 줄을 더 적으며**(상태는 그대로), 진행률은 새 구간에서 0 부터 다시 센다 — 단조 비감소의 바닥이 `(task_id, revision, attempt)` 안에서만 유효하다는 §4.4 를 B2 의 바닥 규칙과 맞물리게 고쳤다. 스킬 교체는 막는다(그것은 다른 태스크다).

    **미믹과 답이 같은지 밖에서 확인한다** — `HostParityTest` 에 협상 밖의 첫 동치 시험이 생겼다(갱신·낮은 개정판·스킬 교체 셋의 답).

    ### 합성은 원자가 아니다

    ★멈춤은 됐는데 적재나 재시작이 거절되면 **로봇은 멈춰 있고 아무것도 안 돈다.** 조용히 `RUNNING` 으로 두면 상류가 가고 있다고 믿으므로 `NEEDS_INTERVENTION` + `X_*_UPDATE_HALF_APPLIED` 로 사람을 부른다(취소가 반만 될 때 `CANCELLED_RECOVERY_FAILED` 를 적는 것과 같은 규율). 순서도 규칙이다 — Spot 은 **이름을 먼저 옮기고** 그 다음에 멈춘다. 반대로 하면 모르는 이름 하나에 로봇이 멈춰 선다. Digit 은 지운 뒤 `action-status` 가 아직 `running` 이면 새 액션을 **안 얹는다**(매뉴얼이 예고한 컨테이너 거동 — 얹으면 둘이 겹쳐 돈다).

    ### 정직하게 적어 둘 것

    - **`PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` 의 갱신은 아직 안 든다.** §4.4 는 *파라미터만 갈아 두고 재개·재시도 때 적용하라* 고 하는데, 그러려면 호스트가 대기 파라미터를 들고 어댑터의 `resume`·`retry` 에 실어야 하고 그 자리가 포트에 없다. **로봇이 못 하는 것이 아니므로 `UPDATE_UNSUPPORTED` 로 답하지 않는다** — `INVALID_TRANSITION` 에 그 사정을 적는다. **닫는 조건**: 포트의 `resume`·`retry` 가 파라미터를 받고, 그것을 쓰는 소비자가 생기면.
    - **`Applied.Refused` 에는 정준 분류 칸이 없다.** 조작의 거절은 계약에서 코드와 사정 문자열로만 나가므로 G1 이 로봇에게 받은 에러 코드를 사정에 붙인다. 접수 쪽 거절(`Acceptance.Refused`)과 비대칭이고, 그것을 고칠 소비자가 아직 없다.
    - **Spot 명령 계층의 갱신은 실물에서 확인한 바 없다** — 지시값이 대체된다는 것은 API 의 모양에서 읽은 것이다.
    - 결함 주입 열일곱을 겨냥한 시험이 전부 잡았다. ★그중 하나가 **시험 하나가 약하다는 것을 먼저 알려 줬다** — G1 의 시계 시험이 갱신에서 *지속시간을 늘려* 있었고, 그러면 시각을 안 다시 세도 늘어난 길이만으로 통과한다. 길이를 같게 두고서야 그 시험이 일을 했고, 같은 구멍이 Spot 명령 계층에도 있어 함께 메웠다.

110. **마감 — 세어서 적은 것을 다시 세고, 열린 것을 한 장으로 모았다(트랙 C).**

    ### C1 — 시나리오 검증 열을 다시 셌다

    §15.84 가 *"6 증명 · 3 의도적 밖 · 1 반"* 이라 적은 뒤로 여덟 단계가 지났다. 다시 대 보니 **아홉이 증명된다** — 계약 층에서 일곱, **층 ③ 에서 둘**. 올라간 셋의 이유가 다르다: 6 번(버전 변경 중 지연 이벤트)은 §15.92 가 규칙을 실행 층에 지어 반에서 온전이 됐고, 7·8 번(설비 신호)은 **계약 밖인 채로** 증명됐다.

    ★그 구분이 이 재대조의 요점이다 — **계약이 안 나르는 것과 아무도 안 하는 것은 다르다.** 앞의 표는 둘을 같은 칸(*계약 밖 — 정상*)에 적고 있었고, 그래서 ADR 38 이 한 일이 표에서 안 보였다.

    후보 다섯 중 **넷째가 닫혔다** — *이벤트 쪽 옛 revision 규칙* 의 답은 계약이 아니라 실행 층이었다(§15.92). 셋째(든 채의 갱신)는 갱신 자체가 지어져 **질문이 좁아졌다**: 이제 발신자가 갈린다는 것이 보인다 — 지시값 층은 멈춤이 따로 없어 든 것을 놓을 자리도 없고, 액션 층은 지우기가 곧 멈춤이다.

    ### C2 — 낡은 진술은 사람이 훑지 않는다

    README 가 검사를 아홉이라 적고(여덟이다) 어댑터를 셋이라 적고(넷이다) 있었다. 설계 문서는 게이트 7번이 **넷**을 본다고 적었는데 코드는 **여덟**을 본다. 고치는 것은 쉽고 **다시 낡는 것이 문제**라, `DocumentClaimsTest` 가 코드에서 세어 문서와 댄다 — 모듈 나무 · 검사 수 · 어댑터 수 · 거리 문서 수 · 계약 개정판 · 진단 수 · 게이트 7번 목록.

    **게이트 검사로 만들지 않았다.** 게이트는 PR 을 막는 것이고, 산문의 숫자 하나로 남의 PR 을 막으면 그 검사는 곧 꺼진다. 그리고 `gate` 는 `contracts` 에도 `registry` 에도 의존하지 않으므로(§3.2) **소스를 문자열로 읽어** 센다 — 의존을 만들어 시험을 편하게 하면 그것이 §3.2 의 첫 예외가 된다.

    같은 훑기에서 **거짓이 된 진술 둘**을 잡았다: §15.21 이 *"실물 어댑터는 ULID 를 쓴다"* 고 적었는데 어댑터 호스트도 카운터를 쓴다(계획이 사실로 적혀 있었다), §15.17 은 CI 가 이미 두 디렉터리를 다 보게 되어 닫혔다. ADR 39 의 *"어댑터 셋 중 아무도 갱신을 안 든다"* 와 *"어댑터가 진행률을 안 낸다"* 도 이번 트랙이 뒤집은 것이라 후속 표시를 붙였다.

    ### C3 — 한계 대장

    §15 는 번호가 106 까지 갔고 앞은 한계 목록, 뒤는 일지다. *"지금 무엇이 열려 있나"* 를 물으면 처음부터 읽어야 했다. [`docs/limits.md`](../../limits.md) 가 그 답을 한 장으로 준다 — **의도적 밖** / **안에서 닫는다** / **밖에서 닫는다** 로 갈리고, 열린 것마다 **무엇이 있어야 닫히나** 가 한 줄이다.

    갈래를 셋으로 나눈 것이 요점이다. 앞의 §15 는 *"게이트 7번이 문자열 검사다"*(닫지 않기로 한 것)와 *"디스크립터 재생성이 빌드 밖이다"*(지으면 닫히는 것)를 같은 목록에 나란히 적고 있었고, 그러면 읽는 사람이 **무엇을 해야 하는지** 를 못 고른다.

    ★대장의 첫 줄이 **`buf` 디스크립터**인 것은 우연이 아니다 — 이 저장소가 실측으로 두 번 물린 자리이고(*낡은 디스크립터는 낡은 코드와 사이좋게 초록이다*), 닫는 조건과 그 대가(`:gate:test` 에 Docker 의존이 번진다)가 둘 다 분명하다. 그것은 결정이 필요한 일이라 이 트랙에서 짓지 않았다.

    ### 정직하게 적어 둘 것

    - **대장이 §15 를 대신하지 않는다.** 일지가 정본이고 대장은 색인이다 — 두 곳에 같은 상태를 적으면 곧 어긋난다. 그 규칙을 대장 마지막 절에 적어 뒀다.
    - **대장에 없는 한계가 있을 수 있다.** §15 뒤쪽 일지의 *정직하게 적어 둘 것* 불릿을 전수로 옮기지는 않았다 — 옮긴 것은 지금도 참인 것으로 확인한 것들이다.
    - `DocumentClaimsTest` 는 **셀 수 있는 것만** 본다. *"어느 어댑터도 실물에 붙여 보지 못했다"* 같은 진술은 여전히 사람이 지킨다.

111. **디스크립터가 하나가 됐다 — 그리고 게이트 시험이 proto 를 다시 본다(대장 1순위).**

    `docs/limits.md` 의 첫 줄이었다. 게이트 시험이 읽는 `contracts/build/descriptor.binpb` 는 `tools/buf build` 를 **손으로** 돌려야 갱신됐고, 안 돌리면 **낡은 디스크립터가 낡은 코드와 사이좋게 초록이었다**(§15.22 의 실측: `skill_catalog.proto` 에 옵션을 더하고 시험을 돌렸는데 옛 판정이 나왔다).

    ### 대가를 치를 줄 알았는데 안 쳐도 됐다

    대장은 닫는 법을 *"Gradle 태스크가 만들고 `:gate:test` 가 거기 의존하면"* 이라 적고 대가를 *"Docker 의존이 `:gate:test` 로 번진다"* 라 적어 뒀다. **그 대가가 필요 없었다.** 디스크립터가 **둘**이라는 것이 문제의 절반이었기 때문이다 — `protoc`(Gradle)이 만드는 `picasso.desc` 는 이미 있었고, 런타임 신원(`contract_digest`)과 `registry` 의 교차검증이 그것을 읽고 있었다. 게이트만 buf 의 것을 읽었다.

    그래서 **protoc 의 출력을 `contracts/build/descriptor.binpb` 로 내보내고 buf 로 만들던 태스크를 지웠다.** 이제 디스크립터가 하나이고, 셋(런타임 신원 · `registry` · 게이트)이 같은 바이트를 본다. `:gate:test` 는 `:contracts:generateProto` 에 매달린다 — **Docker 는 늘지 않았다.**

    ★**§3.2 를 어긴 것이 아니다.** 태스크 의존은 `project(":contracts")` 의존이 아니다 — `gate` 의 클래스패스에는 아무것도 안 들어오고 게이트 5번이 그 자리를 계속 지킨다. §3.2 가 막는 것은 *게이트가 계약의 생성 코드를 쓰는 것* 이고, 여기서 생긴 것은 *게이트 시험이 최신 바이트를 본다* 는 순서 하나다.

    ### 닫혔다는 것을 어떻게 아나

    §15.22 가 적어 둔 **그 실측을 그대로 다시 했다** — `skill_catalog.proto` 에 아무 프로파일도 선언하지 않는 스킬을 하나 더하고 `:gate:test` 만 돌렸다. 예전에는 초록이었다. 지금은 **셋이 빨개진다**: `ContractIndexTest`(카탈로그 스킬 넷 → 다섯) · `VocabularyDistanceTest` 둘(계약의 모든 스킬을 실물마다 재는데 안 잰 것이 생겼다, 대조 횟수 20 → 16). 손으로 돌린 명령은 없다.

    ### 정직하게 적어 둘 것

    - **`buf` 가 필요 없어진 것이 아니다.** 게이트 1·2 번(lint·breaking)과 음성 하네스가 여전히 쓴다. 사라진 것은 *디스크립터를 buf 로 만드는 자리* 하나다.
    - **`contract_digest` 의 계산 방법은 그대로다**(디스크립터 셋의 SHA-256, `buf` 모듈 다이제스트가 아님, §15.22 의 앞부분). 바이트가 바뀌지 않았으므로 다이제스트도 안 바뀌었다 — 소비자에게 경보가 안 간다.
    - **protoc 과 buf 의 미세한 차이(§15.20)가 게이트 경로에서 사라졌다.** 두 도구가 만든 디스크립터를 서로 다른 곳이 읽던 자리가 없어졌기 때문이고, 그 차이가 실제로 문제였던 적은 없다.

112. **진행률에 근거가 붙었다 — 그리고 그것을 읽는 소비자를 함께 지었다(계약 0.8.0).**

    §15.108 이 열어 둔 자리다. 계약의 `progress` 는 맨 `double` 이라 **못 재는 기체와 아직 아무것도 안 한 기체가 `0.0` 으로 같아 보였다.** 그때 안 고친 이유는 ADR 9 였다 — 그 구분을 읽고 **다르게 행동하는** 소비자가 없었다.

    ### 소비자를 먼저 정했다

    §15.108 이 닫는 조건으로 적어 둔 것이 *"진행 정체로 개입을 판단하는 소비자"* 였고, 그것을 지었다. 미들웨어가 도는 단위의 진행률을 보다가 능력 단위 설정(`stallWindow`, 기본 5 분)만큼 안 움직이면 **자취에 적고 상류에 알린다.**

    **정체는 실패가 아니다.** 상태를 안 바꾸고 자동 조치도 없다 — 느린 것과 멈춘 것을 이 층이 못 가르기 때문이고, 그래서 하는 일은 *보이게* 하는 것까지다.

    ★**그리고 못 재는 기체에는 그 판정을 아예 안 한다.** 이것이 계약의 새 필드가 있는 이유 전부다 — `0.0` 을 정체로 읽으면 진행률을 안 내는 기종(Spot·G1)이 **언제나 멈춰 있는 것으로** 보이고, 그러면 운영자가 이 경보를 곧 무시한다. 대신 *왜 판정이 없는지* 를 자취에 **한 번** 적는다.

    ### 값을 두 곳에 두지 않았다

    분수는 `progress`(필드 5) 하나이고, 새 `progress_basis`(필드 9)는 그 분수를 **자격 짓는다** — `HoldState.kind` 가 `object_ref` 를 자격 짓는 것과 같은 모양이다. 같은 값을 두 필드로 내면 어긋나는 날 무엇이 참인지 아무도 모른다.

    `ProgressKind` 는 셋이다. `UNSPECIFIED`(옛 발신자) · `NOT_OBSERVABLE`(근거가 없다, `reason`) · `MEASURED`(유효하다, `basis` 가 *무엇을 세었는지*). **옛 발신자를 측정으로 읽지 않는 것**이 요점이고 그것이 막는 방향이다(§15.41) — 그러지 않으면 0.8.0 이전 발신자 전부가 정체 경보를 받는다.

    ### `basis` 가 문자열인 이유

    *"행동 3/7"* · *"실행 트리 잎 1/2"* · *"경과 시간 비율"*. **기종마다 세는 단위가 다르다는 사실을 숨기지 않으려는 것**이고, 그것을 열거로 만들면 계약이 벤더의 세는 법을 알게 된다(ADR 36 결정 5). 사람이 읽는다.

    ### 정직하게 적어 둘 것

    - **미믹에는 *못 잰다* 가 없다.** 프로파일이 선언한 소요시간 대비 경과 비율이 언제나 근거이므로 언제나 `MEASURED` 다. 그 값은 실물에서만 나오고, 그래서 미들웨어 시험은 **계약의 자격 필드를 갈아 끼우는 포트 더블**(`ProgressPort`)로 두 갈래를 만든다 — 값을 지어내는 것이 아니라 실물이 낼 모양을 미믹 위에 씌우는 것이다.
    - **정체가 `operatorRequired` 를 켜지 않는다.** 통보의 미완 단위에 사정으로 실릴 뿐이다. 켜면 *사람이 반드시 판단해야 하는 것* 과 *봐 두면 좋은 것* 이 같은 칸에 들어간다.
    - **`stallWindow` 의 기본값 5 분에 근거가 없다.** 현장별 설정 항목이며 여기는 자리만 만들었다(시간창 δ 와 같은 성격).
    - 결함 주입 여섯 중 **다섯을 겨냥한 시험이 잡았다.** 여섯째(*호스트가 모든 진행률을 측정으로 낸다*)는 **주입할 자리가 없었다** — `when` 이 `ProgressObservation` 둘을 다 덮으므로 한쪽을 지우면 컴파일이 안 된다. 구조로 막은 것은 주입이 안 되고, 그것 자체가 신호다(§15.101 과 같은 자리).

113. **멈춘 태스크도 파라미터를 받는다 — §4.4 의 갱신 표가 다 찼다.**

    §15.109 가 남긴 마지막 칸이다. `PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` 의 갱신을 호스트가 거절하고 있었고, 이유는 로봇이 못 해서가 아니라 **우리 포트에 자리가 없어서**였다 — 어댑터의 `resume`·`retry` 가 파라미터를 안 받으니 *갈아 두고 나중에 적용* 을 할 수가 없었다.

    ### 포트를 한 칸 넓혔다

    `resume(parameters)` · `retry(parameters)`. 호스트가 태스크의 **지금 파라미터**를 들고 그것을 실어 준다. 갱신이 없었으면 접수 때의 것이 그대로 가므로 **호출자가 두 경우를 가르지 않는다** — 가르게 두면 그 분기가 곧 틀린다.

    어댑터 넷 중 아무도 `resume`·`retry` 를 안 든다(전부 기본 거절). 그래서 **이 확장은 지금 아무 벤더 코드도 안 바꿨다** — 넓힌 것은 계약과 호스트 사이의 자리이고, 그 자리가 없어서 계약의 규칙을 못 지키고 있었다는 것이 요점이다.

    ### 상태에 따라 보내거나 갈아만 둔다

    | 상태 | 갱신을 받으면 |
    |---|---|
    | `ACCEPTED`·`RUNNING` | 어댑터의 갱신 합성으로 **보낸다**(§15.109) |
    | `PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` | **갈아만 둔다.** 로봇에 아무것도 안 간다 — 그래서 **갱신 합성을 못 드는 기체도 이 경로는 된다** |
    | `CANCELLING`·종착 | 자리가 없다. `INVALID_TRANSITION` |

    ★**`NEEDS_INTERVENTION` 이 여기 있는 것이 요점이다.** §4.4 가 *"개입한 사람이 파라미터를 고쳐 넣는 경로가 이것이다 — 막으면 이 상태의 존재 이유와 어긋난다"* 고 적어 뒀는데, 호스트가 정확히 그것을 막고 있었다. 사람이 현장을 고치고 파라미터를 고쳐 다시 시키는 것이 그 상태의 쓸모 전부다.

    갈아만 두는 경로도 **로그 한 줄을 적는다**(같은 상태, 오른 `revision`). 미믹이 그 자리에서 자기 자신으로의 전이를 알리는 것과 같은 이유다 — 안 알리면 이벤트를 접는 소비자가 옛 `revision` 을 영원히 든다.

    ### 정직하게 적어 둘 것

    - **재개·재시도를 드는 어댑터가 아직 없다.** 이 경로는 계약 면과 호스트까지 검사받고, 벤더 쪽은 그 프리미티브를 드는 기종이 나올 때 열린다. 넓힌 포트가 **되는 척하지는 않는다** — 기본값은 그대로 *없다* 다.
    - **파라미터의 주인이 호스트다.** 어댑터는 받은 것을 쓸 뿐 저장하지 않는다. 두 곳에 두면 갱신 뒤 재개에서 어느 쪽이 참인지 알 수 없다.
    - 결함 주입 여섯 전부 겨냥한 시험이 잡았다.

114. **읽는 사람의 문 — 검증 근거 표와 아키텍처, 그리고 모듈의 문 다섯.**

    ### 검증 근거 표 (`docs/verification.md`)

    미들웨어 중앙 설계 §5 가 *"실제 API 에 연결한 구간과 Mock 구간을 표로 구분한다"* 고 적어 두고 **안 만든 것**이다. 지금 만든 이유는 기능이 아니라 **시간** 때문이다 — 어느 구간이 실물이고 어느 구간이 대역이었는지를 아는 것은 이 저장소를 지은 쪽뿐이고, 그 사실은 §15 의 백 몇 개 항목에 흩어져 있다. 나중에 붙이는 값이 같은 다른 항목들과 정반대로 **이것만 지금 아니면 비싸진다.**

    등급 다섯(실물 · 실 와이어·세운 상대 · 실 코드·전송 없음 · 대역 · 없음)으로 열세 구간을 갈랐다. ★그리고 **계약을 누가 지었는가**를 열로 따로 뒀다 — 등급이 높아도 **상대 계약이 우리 것이면 그 구간의 증명은 우리 가정 안에서만 참**이기 때문이다.

    표가 드러낸 것: **아래로 갈수록 진짜다.** 레지스트리(실 HTTP + 실 PostgreSQL)·게이트(실 buf)·발행(실 mosquitto)은 실물에 대고 돌고, 로봇 계약은 실 코드이되 전송이 대부분 in-process 이며, 벤더는 넷 중 **하나만** 와이어를 지난다. 그리고 위쪽 둘 — **설비와 플릿은 계약을 우리가 지었다.** 가장 조심해서 읽어야 할 줄이 그 둘이다.

    ### 아키텍처와 모듈의 문

    `docs/architecture.md` 는 모듈이 무엇인지가 아니라 **경계가 왜 거기인지**를 적는다 — 층 넷, 데이터의 두 방향(질의는 당기고 발행은 민다), 상태기계 둘이 서로를 대신하지 않는 것, 의존 규칙, 그리고 **여기 없는 것**.

    모듈 문 다섯(`picasso`·`registry`·`mimic`·`adapter-host`·`gate`)은 **KDoc 을 옮기지 않는다.** 옮기면 두 곳에 같은 상태가 생기고 곧 어긋난다. 대신 KDoc 이 못 답하는 것만 적는다 — *이 모듈의 규칙 하나* · *경계* · *여기 없는 것* · *어느 시험이 무엇을 증명하나*. 나머지 모듈은 작아서 KDoc 으로 족하고, 그 판단도 적어 둔다.

    ### ★같은 구멍에 네 번째로 물렸다

    문서 링크가 실재하는지 보는 시험을 더하고 **깨진 링크를 넣어 봤더니 초록이었다.** `:gate:test` 의 `inputs` 에 문서가 없어 **UP-TO-DATE 로 넘어갔기 때문**이다 — `gate/build.gradle.kts` 의 주석이 *"이 저장소가 같은 방식으로 두 번 물렸다"* 고 적어 둔 그것이고, 조사 문서에서 세 번째였고, 이번이 네 번째다.

    **세는 시험을 만들어 놓고 그 시험이 안 도는 상태였다.** §15.110 에서 만든 `DocumentClaimsTest` 는 다른 입력(계약 개정판·모듈 목록)이 바뀔 때만 함께 돌고 있었다 — 문서만 고친 변경에서는 아무 일도 안 일어났다. 입력에 문서를 넣고 같은 주입을 다시 하니 빨개진다.

    이 구멍의 모양이 매번 같다: **검사를 만들면 그 검사가 도는지를 따로 확인해야 한다.** 결함 주입이 그 확인이고, 주입이 안 잡히면 먼저 의심할 것은 시험이 아니라 **시험이 돌았는가** 다.

115. **문지기 — 시험이 선언 안 한 파일을 읽으면 그 자리에서 멈춘다. 그리고 만들자마자 둘을 찾았다.**

    §15.114 가 *"검사를 만들면 그 검사가 도는지를 따로 확인해야 한다"* 로 끝났다. 그것을 **습관이 아니라 코드**로 옮긴다.

    ### 왜 습관으로는 안 되나

    이 구멍의 모양이 네 번 같았다 — 시험이 저장소 파일을 읽는데 그 경로가 `:gate:test` 의 `inputs` 에 없어서, 그 파일만 고친 변경에서 **시험이 UP-TO-DATE 로 안 돌고 빌드가 초록**이었다. 픽스처에서 한 번, 검사 7번의 모듈 목록에서 한 번, 조사 문서에서 한 번, 문서 링크에서 한 번. **매번 고친 방법이 *`build.gradle.kts` 에 줄을 하나 더한다* 였고, 매번 다음 사람이 그것을 잊었다.**

    훅으로 막는 것은 더 약하다. 훅이 볼 수 있는 것은 `UP-TO-DATE` 줄인데 **그 줄은 대부분 정상**이고, 어느 것이 문제인지는 *그 태스크가 선언 안 한 파일을 읽는가* 에 달렸다 — 훅은 그것을 모른다. 오탐이 섞이면 곧 무시되고, 그것은 이 저장소가 경보에 대해 반복해 적은 실패 방식이다.

    ### 기억할 것을 없앴다

    `gate/build.gradle.kts` 의 **`repoInputs` 하나**가 두 곳에 쓰인다 — Gradle 의 입력 선언과, 시험 쪽으로 넘어가는 목록(시스템 속성, **저장소 상대 경로**로만). 시험은 `Repo.read`/`Repo.path`/`Repo.list` 로만 저장소를 읽고, **그 문이 읽는 순간 선언 안인지 확인한다.** 선언 안 한 파일을 읽으면 그 자리에서 던진다.

    새 시험이 새 파일을 읽어도 잡히고, 누가 선언 줄을 지워도 잡힌다. `upToDateWhen { false }`(항상 다시 돌리기)와 비교해 **48 초를 매 빌드에 물지 않는다** — 그 값이 음성 하네스가 Docker 로 buf 를 도는 값이다.

    **게이트 자신이 읽는 것은 이 문을 안 지난다.** `Check05`·`Check07`·음성 하네스는 저장소 트리를 넘겨받아 훑는 것이 일이고(그것이 검사의 내용이다), 그 경로를 막으면 검사를 못 돌린다. 이 문은 **시험이 단언하려고 읽는 파일**의 것이다.

    ### ★만들자마자 다섯째를 찾았다

    문을 붙이고 처음 돌리자 **선언 밖에서 읽던 파일이 둘** 나왔다 — `settings.gradle.kts`(모듈 수를 세는 자리)와 `registry/…/DiagController.kt`(진단 수를 세는 자리). 둘 다 **§15.110 에서 내가 만든 `DocumentClaimsTest` 가 읽던 것**이고, 그래서 **모듈을 더하거나 진단을 더해도 그 시험이 안 도는 상태**였다. 두 커밋 전에 만든 *세는 시험* 이 절반만 배선돼 있었다.

    같은 구멍의 다섯째이고, **이번에는 사람이 아니라 문지기가 찾았다.** 그것이 이 장치의 값이다.

    ### 정직하게 적어 둘 것

    - **`gate` 밖의 모듈에는 이 문이 없다.** 다른 모듈의 시험이 저장소 파일을 읽는 자리가 생기면 같은 구멍이 열린다. 지금은 게이트가 그런 시험을 거의 다 들고 있어서 여기부터 막았다.
    - **선언 목록은 여전히 손으로 쓴다.** 없어진 것은 *목록과 시험이 어긋나는 것* 이지 목록 자체가 아니다. 다만 어긋나면 **빌드가 빨개진다** — 그것이 전부다.
    - 결함 주입 셋 전부 겨냥한 시험이 잡았다(선언 하나 제거 ×2 · 목록 전달을 끊음).

116. **`verify_grasp` 를 붙였다 — 그리고 그 한 줄이 셋을 드러냈다.**

    시나리오 ② 를 명세에 대고 세어 본 결과 딱 하나가 어긋나 있었다. §4.2 의 요청 예시에 `verify_grasp: true` 가 있는데 `PrepareSequencedRack` 은 `object_id`·`destination` 둘만 보내고 있었다. 붙이라는 지시였고, **한 줄이면 될 줄 알았다.**

    ### ① 선택 파라미터는 아무에게나 보내면 안 된다

    `verify_grasp` 는 계약의 **선택** 파라미터다. 그리고 §5.3 이 *모르는 코어 키는 fail-closed* 로 못 박았다 — 안 드는 기종에 보내면 **태스크가 통째로 `PARAMETER_INVALID` 로 죽는다.** 실측으로 조사한 실물 중에 `pick_place` 를 들면서 이 필드는 선언하지 않는 기종이 있다.

    그래서 *언제나 보낸다* 가 아니라 **능력이 원하고, 로봇이 선언한 것만 보낸다** 가 됐다. `LogicalCapability.preferredOptionals` 가 원하는 것을 적고, 미들웨어가 `GetCapabilities` 로 확인해 거른다(`RobotPort.capabilities`, 널이면 **안 붙인다** — 모름은 없음이 아니다).

    ★**뺐다는 사실을 적는다.** 조용히 빼면 *파지 확인을 요구했다* 와 *못 해서 안 했다* 가 같아 보인다 — 진행률(§15.112)·결함·파지에서 반복해 거절한 접기다. 기체마다 **한 번만** 적는다.

    ### ② 미들웨어의 파라미터가 전부 문자열이었다

    붙이고 돌리니 **`picasso` 시험 열여덟이 빨개졌다.** `RobotPort.start` 가 `Map<String, String>` 이고 `ClientRobotPort` 가 전부 `string_value` 로 보내는데, `verify_grasp` 는 계약이 `BOOL` 로 선언한 첫 파라미터였다. 타입이 다르니 로봇이 거절하고 태스크가 죽었다.

    문자열인 것 자체는 이유가 있다 — **상류가 문자열이다**(ISA-95 의 `Value`). 없던 것은 **그 경계에서 계약의 타입으로 옮기는 자리**였고, 이제 `ClientRobotPort` 가 선언된 `ValueType` 을 보고 옮긴다. 타입을 모르면 문자열로 보낸다 — 선언에 없는 코어 키는 어차피 로봇이 거절하고, 그 거절이 조용한 변환보다 낫다.

    ### ③ 게이트 7번이 나를 잡았다

    측정 결과를 주석에 적으면서 **기종 이름을 `picasso/src/main` 에 썼다.** 게이트 7번(기종 분기 금지)이 그 문자열을 잡아 `:gate:test` 가 빨개졌다 — **주석이라도 안 된다.** 검사가 문자열 검사인 것이 무디다고 적어 뒀는데(ADR 23), 그 무딤이 여기서는 정확했다: 기종 이름이 이 모듈에 있으면 다음 사람이 그 옆에 분기를 쓴다.

    이름은 시험과 문서로 갔다. 시험은 **실물 프로파일 둘로 두 갈래를 만든다** — 선언하는 쪽과 안 하는 쪽. 지어낸 픽스처가 아니다.

    ### 정직하게 적어 둘 것

    - **아무도 `verify_grasp` 를 안 읽는다.** 미믹도 어댑터 넷도 이 필드를 안 본다 — 계약이 *물어볼 수 있다* 고 정한 자리이고 발신자가 생겼을 뿐이다. **미믹이 이것에 반응하게 만들지 않았다**: 그러면 미믹이 계약을 정의하게 된다(§15.7).
    - **협상을 안 지난다.** §5.4 의 `optionalFieldsUsed` 로 핸드셰이크에서 가르는 것이 원래 자리인데, 미들웨어는 협상을 안 하고 런타임에 능력을 보고 판정한다. 같은 사실을 다른 시점에 쓰는 것이며, 협상을 붙이는 날 둘 중 하나는 지워야 한다.
    - 결함 주입 일곱 전부 겨냥한 시험이 잡았다(안 붙임 · 아무에게나 붙임 · 모름을 지원으로 · 조용히 뺌 · 되풀이해 적음 · 필수를 덮어씀 · BOOL 에 문자열).

117. **안 읽히는 필드 셋과 이어지지 않던 시나리오 둘 — 시나리오 대조가 남긴 나머지.**

    §15.116 이 시나리오 ②의 어긋난 칸 하나(`verify_grasp`)를 닫았고, 대조가 남긴 것이 둘이었다.

    ### ① 값만 있고 아무도 안 읽던 것 셋

    `JobOrder` 가 상류의 모양을 흉내내면서 **쓰지 않는 값을 셋** 들고 있었다 — `request_id` · `due_by` · `materialRequirements`. 이 저장소가 계약에는 ADR 9 로 엄격히 막아 온 것을 **우리 상류 모델 안에서는 하고 있었다.**

    셋의 처분이 갈렸다.

    | 값 | 처분 | 왜 |
    |---|---|---|
    | `materialRequirements` | **쓴다** | 선언한 수량과 배정된 단위 수가 타입마다 같은지 본다. 어긋나면 **접수 자체를 거절**한다 |
    | `request_id` | **뺀다** | 재전송 구분은 `(jobOrderId, version)` 이 이미 한다. **두 키로 같은 질문에 답하면 어긋나는 날이 온다** |
    | `due_by` | **뺀다** | 납기는 배차의 입력이고 배차는 밖이다(§1.3). 쓰려면 정책을 발명해야 한다 |

    ★**자재 검사는 정책이 아니라 정합성이다.** 재고 판단은 WMS 의 일이고, 여기서 거절하는 것은 *스스로 어긋난 주문* 뿐이다 — 상류가 A형 둘이라 해 놓고 슬롯이 셋을 요구하는 경우. 받아 놓고 돌리면 그 어긋남이 **로봇이 실패한 뒤에야** 보이고, 그때는 이미 물리적으로 움직인 뒤다. 자재 선언이 아예 없는 주문(운반 ①)은 검사하지 않는다 — **없는 것과 어긋나는 것은 다르다.**

    ### ② 문서는 이었는데 코드는 안 이어져 있었다

    `scenarios.md` 가 *"① 이 남기는 것은 한 줄이다 — `SEQ-IN-02` 에 `HU-1042` 가 있다, ② 의 환경 전제"* 라고 이어 적었는데, **시험은 각각 독립이었고 ② 는 그 전제를 픽스처로 다시 세웠다.** 두 시나리오가 이름 규약(`SEQ-IN-02` → `SEQ-IN-02.BIN-A`)으로만 이어져 있었다.

    `ScenarioChainTest` 가 그것을 잇는다. 한 미들웨어가 ① 을 돌려 용기를 셀에 넣고, **② 의 출발 자리를 ① 의 목적지에서 유도해** 이어 돌린다. 그리고 반대쪽을 같이 본다 — **① 없이 ② 를 돌리면 로봇은 여전히 성공이라 말하고(미믹은 세계를 모른다) 설비가 확인할 것이 없어 `UNVERIFIED` 로 끝난다.** 갈리는 것이 E2 이고, 그것이 둘이 **근거로** 이어져 있다는 증거다.

    ### 정직하게 적어 둘 것

    - **연결 시험의 세계 모형은 거칠다.** 설비 대역이 *입고 자리에 용기가 있으면 슬롯에서 그 부품을 본다* 로 단순화돼 있다. 실제로는 로봇이 놓아야 보인다. **여기서 이은 것은 물리가 아니라 전제**이고, 물리를 흉내내면 그것은 우리가 지은 또 하나의 세계가 된다.
    - **`request_id` 를 뺀 것은 시나리오 표와 어긋난다.** §3 의 표에는 그 값이 있다. 표는 상류가 **보내는** 것을 적고, 우리는 그중 **쓰는** 것만 든다 — 그 차이를 `scenarios.md` 에 적었다.
    - 결함 주입 셋 전부 겨냥한 시험이 잡았다(어긋난 주문 접수 · 빈 선언을 불일치로 · 전제 없이도 설비가 봄).

118. **모듈마다 문을 세우고, 시나리오마다 시퀀스를 그렸다.**

    §15.114 에서 모듈 문을 **다섯만** 쓰고 *"나머지는 작아서 KDoc 으로 족하다"* 고 넘어갔다. 열여섯 중 다섯이었다. 사용자가 물어서 나머지 열하나를 마저 썼다.

    ### 문을 열여섯으로 만들고, 그것을 세는 것을 코드에 맡겼다

    각 문은 같은 네 칸이다 — *이 모듈의 규칙 하나* · *경계* · *여기 없는 것* · *어느 시험이 무엇을 증명하나*. **KDoc 을 옮기지 않는다.**

    ★기종별 어댑터 넷의 문은 **판정을 옮겨 적지 않는다.** 거리·조사 문서를 가리키고 요점만 적는다 — 숫자를 두 곳에 두면 어긋나고, 그 숫자는 `DocumentClaimsTest` 가 셀 수 없는 종류다.

    그리고 목록을 **손에서 뺐다.** `gate/build.gradle.kts` 가 모듈 문 목록을 `rootProject.subprojects` 에서 만들고, `DocumentClaimsTest` 가 *모듈마다 문이 있는가* 를 본다. 모듈이 늘면 문이 없어서 빨개진다 — 사람이 기억할 것이 하나 줄었다(§15.115 와 같은 규율).

    ### 시퀀스 다이어그램 넷

    `scenarios.md` 에 ①·②·③ 과 **①→② 가 만나는 자리**를 그렸다. mermaid 로 그린 이유는 하나다 — **텍스트라 낡지 않는다.** 이미지로 두면 코드가 바뀌어도 그림이 그대로이고, 아무도 그것을 안 잡는다.

    그림이 드러내는 것이 각각 다르다. ①은 **플릿의 완료가 곧 물리 완료가 아니라는 것**(E1 → 설비 → E2)과 통보 재시도가 운반 재실행이 아니라는 것. ②는 **슬롯 하나가 지나는 길**과 설비의 세 갈래(맞음·다름·침묵). ③은 **결과를 실을 자리가 없다**는 사실. 그리고 ①→②는 ★**로봇이 두 경우에 똑같이 성공이라 말하고 갈리는 것은 설비뿐**이라는 것 — 두 시나리오가 근거로 이어져 있다는 그림이다.

    ### 정직하게 적어 둘 것

    - **다이어그램은 시험이 아니다.** 그림이 코드와 어긋나도 빨개지지 않는다 — 문서 링크와 숫자는 `DocumentClaimsTest` 가 보지만 **그림의 내용은 사람이 지킨다.**
    - 모듈 문 열여섯 중 **적을 것이 적은 것도 있다**(`profile-model`·`capability`). 짧은 것이 낡은 것보다 낫다고 보고 짧게 뒀다.

119. **그림을 손으로 훑었다 — 셋이 틀렸고, 그중 하나가 그림의 요지를 배반하고 있었다.**

    §15.118 이 *"다이어그램은 시험이 아니다 — 그림이 코드와 어긋나도 빨개지지 않는다. 그건 사람이 지킨다"* 로 끝났다. **그 사람의 몫을 바로 했다.** 네 그림의 주장을 한 줄씩 코드에 대고 봤다.

    | 무엇 | 그림이 적었던 것 | 코드 | 왜 틀렸나 |
    |---|---|---|---|
    | **③ 점검 결과** | `SUCCEEDED + partial_result(결과 참조)` | **비어 있다** — `InspectionPatrolTest` 가 *아무도 안 채운다* 를 단언한다 | ★**그림이 제일 하고 싶었던 말을 그림이 뒤집고 있었다.** 이 시나리오의 요지가 *결과를 실을 자리가 없다* 인데 그림은 실려 온다고 그렸다 |
    | **② 능력 질의** | 슬롯 루프 **앞에서** 한 번 | 첫 `StartTask` **안에서**(그 뒤로는 세대 단위 캐시) | 효과는 같지만 위치가 다르다. 읽는 사람이 *접수 때 미리 묻는다* 로 읽는다 |
    | **① 통보 재시도** | 미들웨어가 다시 **민다** | 아웃박스에 **남아 있고** 상류가 `ack` 로 닫는다 | 방향이 반대다. 미는 것과 남아 있는 것은 재시도의 주인이 다르다 |

    ### 무엇을 배웠나

    **텍스트 다이어그램이라 낡지 않는다는 것과, 처음부터 맞다는 것은 다른 문제다.** §15.118 은 앞의 것만 보고 mermaid 를 골랐는데, 정작 이번에 나온 셋은 전부 **낡음이 아니라 처음부터 틀린 것**이었다. 그림은 기억으로 그렸고 코드는 안 봤다.

    ★그리고 세 오류의 성격이 갈린다. ②·①은 **정밀도**의 문제이고(위치·방향), ③은 **주장**의 문제다 — 그 그림을 그린 이유 자체를 부정한다. 산문이었으면 눈에 띄었을 텐데 **그림에서는 화살표 하나에 묻혔다.**

    ### 이 자리를 어떻게 지키나

    자동으로는 못 지킨다 — 시퀀스의 순서와 방향을 코드에서 뽑아 대조하는 것은 이 저장소가 지을 물건이 아니다. 대신 `scenarios.md` §5.1 에 **이 검토를 했다는 사실과 무엇이 틀렸었는지**를 적어 뒀다. 다음 사람은 최소한 *이 그림은 한 번 검토받았고 그때 셋이 틀려 있었다* 는 것을 안다.

120. **교체 지점을 문서로 세우고 검사로 묶었다 — 그리고 그 검사를 시험하다 *잡았다는 착시* 를 먼저 잡았다.**

    "상류와 하류가 서로를 모르고 각각 갈아 끼워진다" 는 주장은 **그 자리가 소스에 구체적으로 있고 빠짐없이 적혀 있을 때만** 참이다. `docs/seams.md` 가 그 답이다 — 자리 아홉(상류 · 설비 · 플릿 · 로봇 소비자 · 로봇 발신자 · 벤더 링크 · 발행 · 적재 · 프로파일 출처)에 각각 **인터페이스 / 지금 꽂힌 것 / 바꾸려면 / 안 고치는 것** 을 적고, 끝에 기계가 읽는 색인 열다섯 줄을 뒀다.

    ★**여기만 인터페이스가 아니다** — 상류의 교체 지점은 `Middleware` 의 공개 API 다. 포트가 없는 이유는 **상류가 우리를 부르기 때문**이고(ADR 36 의 방향), 그래서 인바운드 ACL 은 우리 코드가 아니라 그쪽 코드다.

    ### 검사가 문서를 코드에 묶는다

    이름을 바꾼 사람은 문서를 안 본다. `DocumentClaimsTest` 가 색인의 모든 줄에 대해 **파일이 있는지** 와 **그 파일에 그 인터페이스가 있는지** 를 본다.

    ### ★러너가 안 도는데 전부 "잡음" 으로 보였다

    결함 주입 넷을 돌렸더니 **네 개 다 잡혔다.** 그런데 실제로는 `gradlew.bat` 을 `cmd` 가 못 찾아 **시험이 한 번도 안 돌았고**, 내가 종료 코드만 보고 있었다 — 안 도는 러너의 exit 1 과 빨간 시험의 exit 1 이 같은 숫자다.

    §15.115 의 규율은 *"안 잡히면 시험이 돌았는지부터 의심하라"* 였는데, **거울상이 하나 더 있다: 잡혔다고 나올 때도 그것을 잡은 것이 시험인지 봐야 한다.** 고친 방법은 판정을 종료 코드에서 **XML 결과의 실패한 시험 이름**으로 옮긴 것이다. 주입 전에 러너를 한 번 맨몸으로 돌려 *돈 시험 수 > 0 · 실패 0* 을 확인하는 것도 같이 넣었다.

    ### 진짜 결과는 3/5 였고, 구멍 둘이 같은 뿌리였다

    - **하한이 검사 범위를 가렸다.** 앞 판은 정규식에 맞는 줄만 골라 세고 *"12줄 이상"* 만 봤다. 한 줄에서 **백틱만 빼면** 그 줄이 조용히 빠지고 하한은 그대로 넘는다. 고침 = 색인 구역의 **모든 표 줄이 읽혀야 한다**; 못 읽은 줄은 건너뛰는 것이 아니라 실패다.
    - **한쪽만 맞대고 있었다.** 본문이 이름 지은 인터페이스가 색인에 있는지만 봤으므로, **색인에만 있는 줄은 지워도 안 걸린다.** 실제로 `RobotAdapter` 한 줄이 그랬다 — 색인에 있는데 본문이 설명을 안 하고 있었다. 고침 = 본문에 그 자리를 적고(발신자 교체와 기종 추가는 다른 자리다), 대조를 **양방향**으로 바꿨다.

    둘 다 **하한은 내가 손으로 적은 숫자이고 목록은 문서가 스스로 댄다** 는 차이에서 나온다. 손으로 적은 숫자는 문서가 줄어드는 것을 못 본다.

    다시 돌린 주입 일곱 중 여섯을 겨냥한 단언이 잡았다(없는 타입 · 없는 파일 · 백틱 빠짐 · 코드 쪽 개명 · 본문이 이름 지은 줄 삭제 · 색인에만 있던 줄 삭제). 일곱째는 표 머리글 문구 교체이고 **등가 변이다** — 머리글은 아무것도 지탱하지 않는다.

121. **계약이 무엇을 담보하는지 적고, 담보마다 그것을 지키는 시험을 대게 했다.**

    *"여기가 계약이다"* 는 말은 두 질문에 답이 있을 때만 값이 있다 — **무엇이 이 면에 들어오는가**, **들어온 것이 무엇을 약속하는가.** `docs/contract.md` 가 그 답이다.

    ### 들어오는 문이 둘이고 둘 다 지나야 한다

    **소비자가 있어야 하고**(ADR 9), **발명이 아니어야 한다**(ADR 36 — 상류나 벤더 중 한쪽이 이미 가진 것만). 두 관문이 실제로 무언가를 막은 기록이 있다: 파지 실패를 거절 코드로 더하려다 멈춘 것은 첫째 관문(벤더가 그것을 선언이 아니라 런타임 피드백으로 말한다), 스킬 카탈로그가 못 살아남고 생명주기·Lease 가 살아남은 것은 둘째 관문이다.

    ★**`move_relative` 가 세 기종 모두에서 닿는다는 사실은 합격 신호가 아니다** — 너무 저수준이라 어디에나 있는 것이고, 그것은 층을 잘못 골랐다는 신호일 수 있다. 이 문장을 문서에 남긴 이유는 *"전부에서 되니 좋은 계약 요소"* 라는 추론이 자연스럽고 틀리기 때문이다.

    ### '아직 안 함' 과 '여기가 아님' 을 갈랐다

    안 나르는 것 여섯(안전 · 지연 상한 · 환경 전제 · 배차 · 실행 정밀 · 시맨틱 결속)마다 **왜** 와 **그럼 어디서** 를 적었다. 안전이 그 경계에서 가장 날카롭다 — 비상정지를 이 면에 얹으면 §4.8 의 세션·시퀀스와 §10.6 의 버퍼링이 그 아래 깔린다. **정지가 재생 버퍼를 지나게 된다.**

    ### ★담보 표의 오른쪽 칸

    담보 열여덟 줄마다 **그것을 지키는 시험** 을 `모듈 · 클래스 · 시험 이름` 으로 적었다. **시험이 없는 담보는 담보가 아니라 희망이다.** 그리고 그 칸이 낡지 않도록 `DocumentClaimsTest` 가 둘을 본다 — ① 담보 표의 **모든 줄**이 시험을 대는가(증명 칸을 비우면 주장만 남는다), ② 문서가 댄 이름이 **코드에 실재하는가.**

    대조가 시험 소스를 읽으므로 `:gate:test` 의 입력에 `<모듈>/src/test` 가 들어왔다. 아무 시험을 고쳐도 게이트가 다시 도는 대가를 또 한 번 감수한다 — **안 도는 검사보다 늦게 도는 검사가 낫다.**

    마지막 줄이 이 면의 요지다: **같은 `robot_id` 뒤에 다른 기종을 놓아도 오간 요청이 바이트 동일하다**(`harness · A1Test`). 기종을 갈아도 와이어가 안 바뀌는 것이 '계약' 이라는 말의 값이다.

    ### 담보하지 않는 것도 적었다

    전달(유한 버퍼 — 담보하는 것은 전달이 아니라 **잃었다는 사실**) · 시각의 정확도 · 진행률의 뜻(`ProgressBasis` 가 근거를 같이 나르는 이유) · 그리고 **종착 래치는 우리 것이지 로봇의 것이 아니다**(`gate · VendorSurveyTest · 종착이 래치되지 않는 실물이 있다` — 계약은 집행하지 않고 어긋남을 **결함으로 보이게** 한다).

    ### 어댑터를 쓰기 전에 기종을 재는 절차

    표면 전수(`survey_scope` 포함) → 거리 문서 → 프로파일 → **미믹으로 소비자 시험**(어댑터 0줄) → 협상 판정. 다섯 단계가 이미 저장소에 다 있고 문서는 그 순서를 적은 것이다. 답하지 못하는 것 셋도 같이 적었다 — 벤더 문서가 불완전할 수 있고(Orbit 이 그랬다), 타이밍·현장 제약은 종이에 없고, 부재는 **벤더의 낱말로 다시 물어야** 한다.

    결함 주입 다섯을 겨냥한 단언이 전부 잡았다(없는 시험 이름 · 없는 클래스 · 틀린 모듈 · 빈 증명 칸 · 시험 쪽 개명). §15.120 에서 배운 대로 주입 전에 러너를 맨몸으로 돌려 *돈 시험 11 · 실패 0* 을 먼저 확인했다.

122. **ISA-95 대조표 — 그리고 `UNKNOWN` 을 칸에 그대로 남겼다.**

    *"상류 모델은 표준에서 왔다"* 는 주장이 문장으로만 있으면 값이 없다. `docs/isa95.md` 가 **필드 단위로** 대조한다. 구분이 실무에서 중요한 이유는 하나다 — **표준의 칸은 상대 시스템이 이미 채울 줄 알고, 우리가 지은 칸은 붙이는 쪽이 뭔가를 해야 한다.**

    ### 근거를 등급으로 적었다

    정본(IEC 62264 / ANSI·ISA-95)은 **유료라 절 번호로 못 짚는다.** 그래서 그런 칸은 `UNKNOWN` 이다 — `version` 이 표준의 어느 칸에 대응하는지, 표준의 상태 어휘가 우리의 두 축(`physical_state` × `upstream_ack`)을 가르는지 둘이 그렇다.

    ★**`UNKNOWN` 은 '표준에 없다' 가 아니다.** 정본을 못 읽었다는 뜻이다. Digit 에서 근거 등급이 낮은데 `NO` 라 적었다가 판정 넷이 한 번에 뒤집힌 적이 있고(§15.65), 같은 규칙을 여기에 적용한다. 대신 **공개 표면은 이름으로 짚는다** — OPC UA 10031-4 의 `ISA95JobOrderDataType` · `ISA95JobResponseDataType` · `ISA95MaterialDataType` · `ISA95EquipmentDataType` 넷을 코드 주석이 이미 짚고 있었다.

    ### 빈칸을 채우는 것과 발명하는 것은 다르다

    표준이 *"does not define any standardized entries for EquipmentRequirements"* 라고 **명시적으로 비워 둔** 자리가 있고, 그 빈칸이 정확히 ADR 36 의 층 ② 다. `source`·`destination` 은 그 자리를 채운 것이지 새 어휘를 지은 것이 아니다. **표준이 *"여기는 도메인이 정한다"* 라고 말한 자리를 채우는 것과, 표준에도 벤더에도 없는 어휘를 짓는 것은 다르다.**

    ### ★§4 의 논거가 한 줄에 매달려 있다

    근거 등급 `E0`~`E3` 은 표준에 없다. 그것이 ADR 36 에 안 걸리는 이유는 **발명 금지가 계약(`picasso/v1`)의 등재 기준이지 미들웨어 내부 모델의 것이 아니기** 때문인데, 그 구분은 **등급이 계약 면으로 안 나갈 때만** 말이 된다.

    그래서 시험이 그것을 지킨다 — `contracts/proto/picasso/v1/` 어디에도 근거 등급이 없어야 한다. 등급을 계약에 넣으면 로봇이 자기 완료의 신뢰도를 자기가 선언하게 되고, 그것은 **확인하려는 대상에게 확인을 맡기는 것**이다. 문서의 논거가 코드의 성질에 매달릴 때 그 성질을 시험으로 못 박아 두는 것이 §15.121 과 같은 규율이다.

    ### 뺀 것도 적었다

    `due_by` 와 별도 요청 id 는 처음부터 안 쓴 것이 아니라 **들고 있다가 뺀 것**이다(§15.117). 인원·물리 자산 요구는 소비자가 없어(ADR 9) 안 들였다.

    결함 주입 셋을 겨냥한 단언이 전부 잡았다(문서가 없는 타입을 댐 · 코드가 타입을 잃음 · 근거 등급이 계약 proto 로 샘).

123. **현장 적용과 설정 관리 — 그리고 ADR 37 이 안 세운 나가는 문을 세웠다.**

    로봇을 공장에 넣는 일은 **한 번 넣는 일과 넣은 뒤로 계속 바꾸는 일** 둘이고, 이 둘이 섞이면 처음 적용할 때 무엇을 준비해야 하는지 아무도 모르고 가동 중에 바꿔도 되는 것과 라인을 세워야 하는 것이 구별되지 않는다. `docs/commissioning.md` 가 그 둘을 가른다.

    ### 축은 이미 업계가 그어 두었다

    ISA-95 의 객체 모델이 **정의(마스터 데이터)** 와 **그것으로 도는 일(런타임)** 을 다른 것으로 두고, VDA5050 이 AMR 쪽에서 같은 자리에 둔 것이 **Factsheet** 다 — 기체가 능력을 한 번 선언하고 그 뒤의 주문이 그 선언을 참조한다. **우리 능력 프로파일이 정확히 그 자리의 물건이다.**

    우리 쪽에서 갈리는 기준은 *"바꾸면 판정이 바뀌는가"* 다. 마스터(계약 semver · 카탈로그 · 어댑터 빌드 · 프로파일 · 설비 배선 · 시간창 δ)는 바꾸면 협상과 능력 대조가 달라지고, 런타임(인스턴스 · 기체 등록과 퇴역 · 이름 등록 기록 · 개정 · 소비자 요구)은 목록만 바뀐다.

    ★**셋째 칸이 있다 — 런타임에 바뀌는데 우리가 안 갖는 것.** 지도와 세계 모델(로봇 안에 산다) · 로봇 상태(관측이지 설정이 아니다) · 플릿의 기체 목록 · 자격증명. **들면 같은 사실이 두 곳에 있게 된다.**

    ### 순서에서 가장 자주 빠지는 칸

    열 단계를 적었고, **3번이 그것이다 — 로봇 안에 사이트 이름을 등록하는 일.** 소프트웨어 설치가 아니라 현장에서 로봇을 끌고 다니는 일이라 배포 체크리스트에 안 들어간다. 안 하면 계약이 나르는 이름을 로봇이 몰라 작업이 `PARAMETER_INVALID` 로 거절된다.

    ### ★기체가 현장을 떠나는 길이 없었다

    ADR 37 이 **들어오는 문 둘을 세우고 나가는 문은 안 세웠다.** 그래서 원장은 *한 번 들어온 기체가 영원히 있는 곳* 이었다 — 팔린 기체도 폐기된 기체도 목록에 남는다. **현장에 처음 적용할 때는 안 보이고 두 번째 해부터 보이는 구멍이다.**

    `V15` 와 `retire`/`reinstate` 가 그것을 닫는다. 결정 넷:

    - **지우지 않는다.** 행은 남고 목록에서만 빠진다 — 태스크 관측·감사 로그·바인딩 이력이 그 기체에 매달려 있고, 지난달 그 라인에서 무엇이 돌았는지 물으면 답이 있어야 한다.
    - **불리언이 아니라 시각이다.** 이 표는 이미 `registered_at`·`registered_by` 로 들어온 쪽을 적으므로 나가는 쪽도 같은 어휘여야 두 사건이 같이 읽힌다. 불리언은 *"언제부터 없는가"* 에 못 답하고 그 질문은 이력을 볼 때 반드시 온다.
    - ★**조작 문에만 있다.** 어댑터가 *"플릿에서 안 보인다"* 고 해서 퇴역이 되면 **네트워크 단절이 퇴역이 된다.** 안 보이는 것은 관측이고 떠난 것은 판단이며, ADR 37 이 들어오는 문을 가른 것과 같은 이유로 나가는 문은 하나다. 그래서 발견은 퇴역한 기체를 **되살리지 않고 사유와 함께 거절한다** — 자동 복귀면 운영자의 판단을 현장 프로세스가 매번 덮는다.
    - ★**퇴역한 뒤에도 보고가 오면 그것이 목록에 뜬다**(`reportingAfterRetirement`). 원장에서 내렸다고 현장에서 사라지지 않으며, 그 어긋남은 숨길 것이 아니라 볼 것이다 — 사이트 이름의 `CONTRADICTED` 와 같은 자리다. 그래서 `RETIRED` 가 다른 상태를 덮되 `lastReportedAt` 은 안 지운다.

    ### 설정 표면 전부를 적고, 그 *전부* 를 시험이 지킨다

    §4 가 표면 열넷을 적고 *"전부"* 라고 말한다. **전부가 아니면 그 문장이 거짓이고, 문서에 없는 문으로 현장 설정을 바꿀 수 있다는 뜻이 된다.** `DocumentClaimsTest` 가 양방향으로 본다 — 문서가 없는 표면을 적었는가, 그리고 **바꾸는 문(`/operations`·`/ingest`·`/requirements`)이 빠짐없이 적혔는가.** 열람(`/diag`)까지 요구하지는 않는다: 진단 하나 더할 때마다 표가 커지면 아무도 안 읽는다.

    결함 주입 열을 겨냥한 시험이 전부 잡았다 — 퇴역 일곱(첫 사유 덮기 · 상태 안 덮기 · 현역 목록에 섞임 · 퇴역한 기체 재등록 · 퇴역 뒤 보고 숨김 · 복귀가 사유 안 지움 · 모르는 기체를 성공으로 답함)과 설정 표면 셋(문서에서 문 빼기 · 없는 표면 적기 · 코드에 문 더하고 안 적기).


124. **저장소가 담지 않기로 한 것을 시험이 지킨다 — 그리고 표지를 온전히 적을 수 없다는 것이 검사의 모양을 정했다.**

    이 저장소의 설계 판단 중에는 **저장소 밖의 사적 문서**를 읽고 내린 것이 있다. 규율은 처음부터 하나였다 — *판단은 그대로 하되 저장소에는 벤더 1차 자료로 독립 확인되는 근거만 적고, 확인이 안 되면 단정을 지우고 비워 둔다.* 2026-09-10 에 **242 커밋을 전수로** 훑어 그 규율이 **한 번 샌 것**을 찾았다: §15.91 의 첫 문장과 **같은 커밋의 메시지**가 사적 문서를 근거로 댔다. 조직·사업장·연도 계열은 전 히스토리에 0 건이었다 — 규율 자체는 서 있었고 샌 것은 한 자리다.

    **문장을 고친 것이 아니라 히스토리에서 걷어냈다.** §15.91 의 커밋(지금은 `d65567b`)부터 tip 까지 **37 커밋을 다시 썼고**, 옛 tip 대비 차이는 **한 파일 한 줄**이다(커밋 수 242 그대로, 줄바꿈 손상 없음). **옛 SHA 는 문서에 안 남긴다** — 걷어낸 뒤에는 없는 객체를 가리키는 인용이 된다. 출처만 빼고 기술 내용은 남겼다 — **넷이 무엇인지는 이 저장소가 스스로 말할 수 있다.**

    ★**금지하려는 낱말을 온전히 적을 수 없다는 것이 설계가 됐다.** `PrivateContextTest` 는 `:gate:test` 의 **선언된 입력 417 개**를 훑는다(§15.115 이 세운 문에 `Repo.declaredFiles` 를 하나 더했다 — 이름을 아는 파일만 훑으면 **새로 생긴 파일은 안 훑는다**). 그런데 표지를 온전한 낱말로 적으면 **이 파일 자신이 걸리고, 그 전에 금지하려는 문자열을 저장소에 들여놓는 것**이 된다. 그래서 반쪽으로 나눠 이어 붙인다.

    **정직**: 그래서 잡히는 것은 **사적 문서의 갈래를 가리키는 낱말 여덟**뿐이다. **조직과 사업장의 이름은 못 잡는다** — 같은 이유로 여기 적을 수 없고 그 목록은 저장소 밖에 있다. 이 검사는 사람의 규율을 대신하지 않고 **되풀이되는 실수 한 갈래**만 막는다.

    주입 둘 다 잡혔다 — 걷어낸 문장을 다른 문서에 되돌려 놓으니 **파일·줄·표지를 짚어** 빨개졌고, 문턱을 올리니 **훑은 개수 417** 이 드러났다. 뒤엣것이 요점이다: **빈 목록을 훑는 시험은 잡을 것이 없는 것과 안 본 것이 같은 색이다.**

125. **감사가 문서 셋을 훑었다 — 그리고 "참이지만 아무도 안 받치는 문장"이 무더기로 나왔다.**

    완료 조건의 첫 감사 묶음(`verification.md`·`limits.md`·`architecture.md`). **틀린 여섯**은 고쳐서 넷을 시험으로 받쳤다(같은 커밋). 그런데 남은 갈래가 하나 더 있었다 — **틀리지는 않았는데 그것을 지키는 것이 아무것도 없는 문장들.**

    ★가장 큰 덩이는 `architecture.md` 의 **의존 규칙 그림**이다. 아홉 줄 중 게이트가 집행하는 것은 **둘뿐**이다(검사 5번의 `contracts` 의존 0, 검사 7번의 여덟 모듈 기종 문자열). 나머지 일곱 — `profile-model`·`capability`·`uplink`·`adapter-core` 를 누가 공유하는가, `registry` 가 아무 데도 안 민다는 것 — 은 **전부 산문일 뿐이다.** 같은 성질이 `verification.md` 에도 있다(기본 스위트가 in-process 발행자를 쓴다는 것, 디스크립터가 protoc 산출이라 `:gate:test` 가 그 태스크에 매달려 있다는 것).

    **지어서 닫을 수 있다.** 모듈 의존 그래프는 `settings.gradle.kts` 와 각 `build.gradle.kts` 에서 읽어 그림과 댈 수 있고, 검사 7번이 그 모양을 이미 보여 준다. 지금 안 짓는 이유는 하나다 — **이 라운드의 일은 감사이지 새 검사를 짓는 것이 아니고**, 열어 적으면 그 사실이 대장에 남아 다음 사람이 고를 수 있다.

    ★**그래서 열림으로 적는다. 그것이 값을 치르는 방식이다** — 완료 조건 §4 가 강등에 값을 매겼고, 밖을 향한 문서가 드는 열림 수가 늘면 `limits.md` 를 고쳐야 하며 그 편집이 diff 에 보인다. **안 비는 대장으로 끝나는 것이 이 조건의 요점이다.**

126. **배차·라우팅이 비목표라는 것을 대장이 안 적고 있었다.**

    `architecture.md` §5 가 *"여기 없는 것"* 으로 **배차·라우팅·자원 중재·다중 로봇 경합**을 들면서 *"비목표"* 라고 적는데, `limits.md` 의 갈래 1(의도적 밖)에 그 행이 없었다. 가장 가까운 것이 §15.9(다중 사이트)인데 다른 것이다.

    ★**대장에 없으면 열림으로 댈 수 없고, 댈 수 없으면 그 문장은 완료 조건에서 삭제 대상이 된다.** 지우는 것이 맞는 답일 리 없다 — 그것은 이 저장소가 실제로 내린 범위 결정이다. 그래서 대장에 행을 연다. **감사가 문서를 고치는 만큼 대장도 고친다.**

127. **감사가 대장의 빈칸을 드러냈다 — 일지에 있는데 색인에 없는 한계들.**

    감사 둘째·셋째 묶음에서 문서들이 근거로 댄 §15 항목 중 **대장에 행이 없는 것이 여럿**이었다. `limits.md` 는 스스로 *"일지가 정본이고 이 문서는 색인이다"* 라 적는데, **색인이 정본을 다 싣지 않고 있었다.**

    ★그것이 왜 문제인가 — 완료 조건에서 **대장에 없으면 열림으로 댈 수 없고, 댈 수 없으면 그 문장은 삭제 대상이 된다.** 실제로 참인 한계를 적은 문장이 *근거를 못 대서* 지워질 뻔했다. 색인의 누락이 문서를 지우게 만드는 구조였다.

    §15.7(미믹이 계약을 정의해버릴 위험) · §15.22(`contract_digest` 가 디스크립터 셋의 SHA-256) · §15.38(HTTP 표면에 인증이 없다) 셋을 대장에 색인했다. **새 한계가 아니라 이미 일지에 있던 것이다.**

    ★**반대 방향도 나왔다.** 닫힌 항목(§15.111·§15.113)을 근거로 댄 문서가 있었다. 닫힌 것을 열림으로 대면 대장이 그것을 되살려야 하므로, 그 자리는 인용을 지우는 것이 맞다.

128. **자격증명을 어디에 두는지 안 정했다.**

    `commissioning.md` §5 가 *"자격증명을 어디 두는지 안 정했다(설계 §6.3). 접속 설정은 **주소만** 받는다"* 고 적는데 대장에 행이 없었다. 접속 설정이 주소만 받는 것은 §15.101 에서 내린 결정이고(비밀은 안 받는다), **그 결정의 나머지 절반 — 그러면 비밀은 어디 사나 — 이 안 정해진 채로 남아 있다.**

129. **사이트 이름 대조가 오타 하나는 못 잡는다.**

    레지스트리는 이름 대신 **개수만** 받는다(ADR 35 — 이름은 로봇 안에 산다). 그래서 *"셋을 등록했다"* 와 *"셋을 안다"* 가 맞으면 통과하고, 그중 하나가 오타여도 수가 같으면 안 걸린다. `CONTRADICTED` 가 잡는 것은 *못 한다면서 개수를 냈다* 는 모순이지 이름의 내용이 아니다.

    ★**닫으려면 이름을 받아야 하는데, 그것은 ADR 35 를 뒤집는 것이다.** 그래서 이 행은 값이 싸지 않다 — 적어 두는 것이 지금 할 수 있는 전부다.

130. **완료 조건을 만족한다 — 그리고 그 값이 얼마였는지도 함께 적는다.**

    자리 **47** 전부가 도장을 갖고, 밖을 향한 문서가 드는 열림은 **117** 이며, 셋 중 어느 것도 아닌 문장이 **0** 이다. 게이트 시험 242, 실패 0.

    **감사가 실제로 잡은 것: 문서가 코드와 어긋난 자리 스물 몇.** 무거운 순서로 셋만 —
    ① `architecture.md` 의 상태기계 표가 **태스크 상태 칸에 실패 분류를 섞어** 놓고 있었다. 그 표의 요지가 *"둘은 다른 층이고 하나가 다른 하나를 대신하지 않는다"* 인데 표 자신이 셋째 어휘를 들여놨다.
    ② `seams.md` 가 *"`mimic --registry` 로 프로파일 출처를 바꾼다"* 고 적었는데 **방향이 반대**였다 — 교체 지점 문서가 교체하는 방법을 틀리게 적고 있었다.
    ③ `README.md` 가 *"어댑터는 `contracts` 하나에만 의존한다"* 를 `adapter-core` 가 생긴 뒤에도 들고 있었다.

    ★**§4.2 가 실제로 물었다.** 셋째 것은 갈래 2 열림으로만 정당화되는 문장이었고, README 는 그런 열림을 못 든다. 그래서 **지어야 했다** — 각 모듈의 `build.gradle.kts` 에서 출하 의존을 읽어 `architecture.md` §4b 의 표와 대는 시험. 지어 보니 **그 문장은 애초에 거짓이었다.** 규칙이 없었으면 열림으로 남아 계속 거짓이었을 것이다.

    ★**왜 이것들이 살아 있었나 — 구조가 나왔다.** 도장 없는 문서는 해시 검사도 유령 id 검사도 `?: return@mapNotNull null` 로 **조용히 건너뛴다.** 그래서 `environment-preconditions.md` 가 대장에 없는 `§15.79` 를 근거로 대고도 초록이었다. 그리고 링크 검사가 `Files.list`(비재귀)라 **`docs/adr/` 를 아예 안 봤다** — 재귀로 바꾸자마자 죽은 링크를 정확히 하나 잡았다.

    ★**대장은 색인인데 정본을 다 싣지 않고 있었다.** §15.4·7·22·38·79 가 일지에 살아 있는 한계인데 행이 없었고, **대장에 없으면 열림으로 못 대고 못 대면 삭제 대상이 된다.** 참인 문장이 색인 누락 때문에 지워질 뻔했다. 행 열둘을 더했다(색인 다섯 + 신설 일곱).

    **정직하게 적는 값.** 이 조건의 유지비는 싸지 않다 — 문서를 고칠 때마다 도장을 다시 찍어야 하고, 열림 하나를 옮기면 `limits.md` 의 수가 따라와야 한다. 사용자가 진행 중에 *"작업을 위한 작업을 하고 있는 것 같다"* 고 물었고 **그것은 맞는 지적이었다.** 값이 있던 것은 **결함 스물 몇과 안티로트 시험 아홉**이고, 도장·해시·117 을 맞추는 사슬은 그 자체로는 저장소를 좋게 만들지 않는다. ★**다음에 이런 조건을 세울 때는 조건의 유지비가 조건이 잡는 결함보다 싼지를 먼저 본다.**

    **닫히지 않은 채로 끝난다.** `limits.md` 는 117 개의 열림 표기를 든 채 남고, 그것이 이 조건의 요점이다 — **안 비는 대장으로 끝날 수 있다.** C-3(실물 미검증)은 지어서 못 닫으므로 열린 채 선언한다.

131. **공개하자마자 CI 가 빨개졌다 — 한 번도 돌아 본 적 없는 검사가 거기 있었다.**

    원격을 만들고 push 하니 **검사 2번(`buf breaking`)이 실패했다.** 파괴가 아니라 인프라였다 — 컨테이너 안의 git 이 마운트된 `/workspace/.git` 을 *dubious ownership* 으로 거부한다. 러너에서 파일 소유자가 컨테이너 사용자와 다르기 때문이다.

    ★**원격이 없어서 CI 가 한 번도 안 돌았다.** 워크플로 파일은 처음부터 있었고 `--require` 로 *건너뛴 것이 초록으로 보이지 않게* 까지 해 뒀는데, **그 워크플로 자체가 실행된 적이 없었다.** §15.15·16·29·39 가 *"CI 에서만 도는 검사들"* 을 열림으로 적어 둔 그 자리이며, 이번에 그 열림이 **실제로 물었다.**

    ★**로컬에서는 재현되지 않는다.** Windows·Docker Desktop 에서는 마운트가 root 소유로 보여 git 이 안 멈춘다. 이 저장소가 *"CI 에서만 도는 검사는 환경의 성질"* 이라 적어 둔 것의 구체적인 얼굴이다.

    ★★**첫 판이 틀렸고 CI 가 그것을 잡았다.** 환경변수(`GIT_CONFIG_COUNT` 규약)로 `safe.directory` 를 주었는데 **아무 일도 안 했다** — git 은 그 설정을 *protected configuration*(시스템·전역)에서만 읽고, **명령줄과 환경변수로 준 것은 의도적으로 무시한다**(그러지 않으면 공격자가 주입할 수 있다). 같은 자리에 두 번 걸렸다.

    고친 방법은 **임시 파일을 만들어 `/etc/gitconfig`(시스템 설정)로 마운트**하는 것이다. `exec` 를 버리고 종료코드를 받아 넘기며 임시 파일을 지운다.

    ★**이것이 CI 가 사는 이유다.** 로컬에서 `bash -n` 도 `--version` 도 통과했고 겉보기 증상도 같았다. **틀린 고침과 맞은 고침이 로컬에서 구별되지 않았다** — 러너만이 그 둘을 가른다.

    ★**공개의 첫 수확이 이것이다.** 실물에 붙여 보지 않으면 안 드러나는 것이 있다는 것이 이 저장소의 상한(C-3)인데, **CI 도 같은 성질이었다.** 원격이라는 "실물" 에 붙이자 한 번도 안 돌던 검사가 바로 빨개졌다.

132. **그림 셋을 다시 그렸다 — 그리고 도장 도구가 근거를 조용히 지우는 것을 봤다.**

    사용자가 그림을 보고 셋을 지적했다: 배경이 희다 · 품질이 떨어진다 · 글이 번역투다. 첫째는 내 잘못이 아니라 **내가 보여준 방식의 잘못**이었다 — 검사용으로 띄운 PNG 가 라이트 변형이었고 다크 짝은 멀쩡히 있었다. 나머지 둘은 맞았다.

    ★**참조본과 나란히 놓고서야 차이가 보였다.** `yggdrasil-iiot` 의 `activation-ladder` 는 칸마다 *더한 것 · 막은 구멍 · 그것을 지키는 스크립트 이름* 이 들어 있다. 내 그림은 칸마다 **제목 하나와 `·` 로 이어 붙인 명사 목록** 뿐이었다 — arch-diagram 스킬이 실패 유형 표에 이름까지 적어 둔 바로 그것이다. 여백이 넓은 것이 세련된 것이 아니라 **댈 근거가 없었던 것**이다.

    **근거는 이미 문서에 있었고 그림이 그것을 안 들고 있었다.** `seams.md` 는 자리마다 *면 / 바꾸려면 / 안 고치는 것* 세 줄을 다 갖고 있다. 그림을 아홉 줄짜리로 다시 짜서 그 셋을 그대로 세우고, 오른쪽에 그 면이 사는 모듈을 붙였다. 그러자 **"안 고치는 것" 이 초록 한 줄로 아홉 칸을 관통**한다 — 코어를 안 뜯는다는 주장이 문장이 아니라 배치로 읽힌다.

    ★★**두 번째 그림은 사실관계가 틀려 있었다.** `ChangePlanService` 는 **의도 넷마다 단계 목록이 다른데**(`RETIRE_ADAPTER_VERSION` 은 예고가 아예 없고, `RETIRE_PROFILE_REVISION` 은 드레인도 없다) 그림은 `REMOVE_CAPABILITY` 하나만 그려 놓고 그것을 *"변경 계획"* 이라 불렀다. 조건 여섯을 먼저 세우고 그 아래 의도 넷 × 단계 다섯 표를 놓는 형태로 바꿨다. 그리고 dry run 의 핵심인 `Observed.NOT_OBSERVABLE` — **못 세면 충족은 언제나 거짓이다** — 가 빠져 있던 것을 넣었다.

    ★★★**`tools/stamp.py` 가 열림 인용을 조용히 지웠다.** 그림만 고치고 세 문서를 다시 찍었는데 `--open` 을 안 줬더니 기본값이 `[]` 라 **`열림: 없음` 으로 덮어썼다** — 실측 11 개가 한 번에 사라졌다. §15.130 이 세운 조건의 *강등의 값* 이 통째로 무력해지는 자리다.

    **기계는 잡았을 것이다.** 열림 표기 수와 `limits.md` 가 적은 수를 대조하는 시험이 117 ≠ 106 으로 빨개졌을 것이다. 그러나 그때는 **어느 문서의 어느 id 였는지가 이미 없다** — 잡히는 것과 되돌릴 수 있는 것은 다르다. 그래서 도구를 고쳐 **`--open` 을 안 주면 원래 도장의 열림을 그대로 옮기게** 했고, 지우려면 `--open` 을 빈 채로 주어야 한다.

    ★**규율.** 증거를 쓰는 도구의 기본값은 *비움* 이 아니라 *보존* 이다. 지우는 것은 명시적으로 시켜야 하는 일이다.

    **얼굴의 문장도 고쳤다.** README 그림이 *"실물이 없다. 통합 시험이 예상 소비자다"* 와 *"로봇이 없다"* 를 달고 있었다. 사실이지만 그 두 줄이 그림의 요지를 배반한다 — 이 그림이 하는 말은 *계약 하나가 이음매다* 이지 *우리는 실물이 없다* 가 아니다. §15.119 가 같은 자리에서 배운 것이고 이번이 두 번째다.

133. **거리 판정을 산문에서 칸으로 옮겼다 — 그리고 안 본 칸 일곱이 드러났다.**

    사용자가 짚은 것이 요점이었다: **이 매핑 단계에서 잘못 판단하면 그 아래 프로파일 검증이 통째로 무의미해진다.** 기계가 보는 것은 일관성이지 정확성이 아니고, 사슬의 뿌리인 거리 판정만 사람이 쥔다.

    ★**산문 `evidence` 는 빠진 것을 안 보여 준다.** *"파라미터 넷이 하나씩 대응한다"* 는 넷을 세어 보지 않고도 통과한다. `parameter_map` 이 계약 파라미터를 **전부** 요구하게 하자 **27 칸 중 7 칸이 조사된 적 없다**는 것이 드러났다 — 선택 파라미터 넷(`verify_grasp`·`grip_force`·`grasp_attempts`·`mode`)이다. 장치가 처음 돌면서 바로 빚을 찾았다.

    ★★**그래서 `NOT_SURVEYED` 가 생겼다.** 그 넷을 `UNMAPPED`(대응이 없다)로 적으면 **안 해 본 조사를 했다고 주장하는 것**이 된다. *부재를 단정하려면 1 차 근거가 있어야 한다*(§15.65 가 만든 `evidence_grade`)는 규율이 칸 단위로 내려온 것이다. 앞은 주장이고 뒤는 빚이며, 그 구분이 없으면 빚이 주장으로 위장된다.

    **`limitations` 는 Open-RMF 에서 가져왔다.** 통합 등급에 `Full Control`·`Traffic Light` 라는 **이름**을 준 자리인데, **우리는 기종이 아니라 칸마다 붙인다** — 한 기종 안에서 스킬마다 다르다는 것이 Spot 에서 드러났기 때문이다(명령 계층만 있으면 `move_relative` 는 돌고 `navigate_to` 는 안 돈다). `YES` 인 칸에도 붙는다: G1 `move_relative` 가 `NO_TERMINAL_SIGNAL` 이다.

    ★★★**`MACHINE_DRAFT` 는 AI 초안을 들이기 위한 문이 아니라 자물쇠다.** 기계가 1 차로 훑으면 이 단계의 괴로운 절반(전수 훑기)이 싸진다 — 그리고 그 값의 근거가 이 저장소 안에 있다. Spot 첫 판이 서비스 54 개 중 3 개만 읽고 쓰였다가 전수 조사에서 결론 셋이 뒤집혔다.

    그런데 **진짜 위험은 틀린 제안이 아니라 앵커링이다.** 백지에서 시작하면 사람은 *"이게 되나"* 를 묻고 초안을 받으면 *"이게 맞나"* 를 묻는데, 뒤엣것이 훨씬 잘 통과한다. 그래서 **초안은 `reachable` 을 못 채운다** — `MACHINE_DRAFT` 인 칸은 `UNKNOWN` 이어야 하고 스키마와 시험이 둘 다 막는다. `UNKNOWN` 은 `YES` 집합에 안 들어가므로 프로파일 선언과 묶이지 않는다. **사람의 성실함이 아니라 기계가 지킨다.**

    **환각 방어는 이미 있었는데 자리가 틀렸다.** 벤더 매니페스트 대조(이름 + 원본 sha256)가 어댑터 코드의 `@VendorSurface` 만 보고 있었고 **거리 문서의 인용은 밖이었다.** `adapter` 필드로 어느 매니페스트에 댈지 정하고 같은 대조를 받게 했다 — 없는 심볼을 짚으면 커밋 전에 빨개진다. 27 칸 중 20 칸을 실제 심볼로 짚었다.

    **결함 넷을 넣어 넷이 각자 잡히는 것을 봤다** — 대응표에서 파라미터 하나 빼기 · 필수 파라미터를 안 본 것으로 바꾸기 · 없는 벤더 심볼 짚기 · 초안에 판정 채우기. 넷 다 **겨냥한 시험이** 잡았고, 마지막 것은 스키마와 시험이 함께 잡았다.

    ★**남의 것 셋을 보고 왔다.** Open-RMF(기종 하나에 등급 하나) · openTCS(`canProcess` 가 이유를 단 참/거짓) · VDA 5050(`factsheet.agvActions[]` 로 AGV 가 선언). **셋 다 이 단계를 피하지 못했고, 셋 다 판단의 근거를 안 남긴다** — 등급 선택도 `canProcess` 구현도 factsheet 작성도 코드와 설정에 녹는다. 표준을 세운 VDA 5050 조차 *"AGV 의 능력이 액션 설명에 대응하면 그것을 쓴다"* 로 끝나며, **판단은 사라지지 않고 벤더 쪽으로 옮겨갈 뿐이다.** 근거를 문서로 남기는 것이 이 저장소가 지켜야 할 차별점이다.

    **못 하는 것은 그대로다.** 이 장치 어느 것도 **잘못된 판정 자체**는 못 잡는다. 잡는 것은 빈칸·모순·범위 누락이고 *"닿는다고 썼는데 실은 안 닿는다"* 는 실물에 붙는 날 드러난다(C-3). 사용자의 표현이 정확했다 — **레시피가 맛을 보장하지는 않지만, 순서가 있으면 빠뜨릴 자리가 줄어든다.**

134. **프로파일과 어댑터 사이가 끊겨 있었다 — 그리고 미믹의 초록이 그것을 가리고 있었다.**

    §15.133 으로 사슬을 훑다가 나왔다. 거리 판정 ↔ 프로파일은 묶여 있고(*"닿는다고 적은 것과 선언한 것이 같다"*) 프로파일 ↔ 계약 카탈로그도 묶여 있는데(`Check04CrossRef`), **프로파일 ↔ 어댑터만 대조가 없었다.**

    ★★**그 자리를 미믹이 가린다.** 미믹은 **프로파일이 곧 거동**이라 프로파일과 절대 안 어긋난다. 그래서 `AllModelsTest` 의 *"모든 기종이 공통 태스크를 완주한다"* 가 초록인 것은 **어댑터에 대해 아무 말도 하지 않는다.** 선언한 스킬을 어댑터가 안 들면 협상은 통과하고, 실물에서 `UNSUPPORTED_SKILL` 로 죽을 때까지 모든 검사가 초록이다. 사용자가 *"잘못 판단하면 프로파일 검증도 무의미해질 수 있다"* 고 한 것의 **기계적인 형태**가 이것이다.

    ★**대조가 없으면 모양도 안 모인다.** 어댑터 넷의 dispatch 가 제각각이었다 — Spot·Digit 은 `when(skillType)`, G1 은 `if (skillType != SKILL)`, Orbit 은 `if (skillType != NAVIGATE)`. 통일될 이유가 없었기 때문이다.

    **스킬 목록을 손으로 안 든다.** 어댑터 출하 소스의 문자열 상수를 뽑아 **계약 카탈로그와 교집합**을 낸다 — 파라미터 키도 같은 모양이라(`P_LOCATION = "location"`) 값만으로는 못 가르는데 카탈로그가 가려 준다. 어휘가 늘면 이 시험이 저절로 따라온다.

    결함 둘을 양방향으로 넣어 둘 다 잡혔다 — 어댑터가 스킬을 개명한 것과, 프로파일이 어댑터가 안 드는 스킬을 선언한 것. 뒤엣것은 **시험 둘이** 잡았다(§15.133 의 판정 대조까지 걸린다).

    **문자열 검사다**(ADR 23 · 게이트 5·7 번과 같은 가족). 주석에 스킬 이름을 적어도 통과한다 — 실수를 막는 장치이지 우회를 막는 장치가 아니다.

135. **VDA 5050 에서 물음만 가져오고 값은 안 가져왔다 — 그리고 소비자는 어댑터 안에 갇혀 있었다.**

    §15.133 의 §7 이 열림으로 세워 둔 `actionScopes` 축을 짓기로 하고, 먼저 관문 둘을 댔다. **ADR 36(발명 아님)은 통과한다** — VDA 5050 과 Spot Autowalk 가 둘 다 가진 것이다. **ADR 9(소비자)에서 걸렸다.**

    ★**값을 그대로 들이면 못 쓴다.** VDA 5050 의 `node`·`edge` 는 **주문이 그래프**라서 생긴 값이고 우리 주문은 태스크 열이라 앉을 자리가 없다. 계약 카탈로그에 넣어 봐야 읽는 곳이 없는데, 기존 카탈로그 옵션은 전부 실제 소비자가 있다 — `grasps_object`·`is_object_reference` 는 미믹의 `ObjectReferences` 가, `is_site_reference` 는 `SkillTypeSync` 가 읽는다. **소비자 없는 선언은 ADR 9 가 막는 바로 그것이다.**

    ★★**그래서 물음만 가져왔다.** *어디서 실행되는가* 를 이 저장소의 낱말로 답하면 **그 스킬이 어느 층 위에서 도는가** 이고, 어휘는 이미 `layer.vendor_layer` 에 있었다. 문서마다 하나이던 것을 **칸마다** 붙였다.

    ★★★**소비자는 지은 것이 아니라 찾은 것이다.** `SpotAdapter` 안에 `private enum class Layer { COMMAND, MISSION, ACQUISITION }` 가 이미 있고 태스크마다 층을 기억하며 **`poll`·`cancel`·`update`·종착 판정이 전부 거기서 갈린다** — 명령 계층은 **시계로** 종착을 적고 미션 계층은 벤더가 상태를 준다. 한 어댑터 안에 갇힌 사실이었고, 문서가 그것을 안 들고 있었다.

    **문서의 한 줄을 칸들이 떠받치게 했다.** `layer.vendor_layer` 는 이제 칸들의 **최댓값**이어야 한다(`NONE` < `COMMAND` < `MISSION`). G1 문서가 스스로 적어 둔 애매함 — *"이 값은 이제 **기종에 태스크 개념이 없다** 가 아니라 **우리가 쓰는 표면에 없다** 로 읽어야 한다"* — 이 그 관계로 닫힌다. **실측으로 넷 다 이미 일치했다**: Spot MISSION(COMMAND·MISSION·MISSION·COMMAND) · Digit MISSION · G1 NONE · Orbit MISSION. 관계가 데이터에 이미 참이었다는 것이 이 모양이 맞다는 증거다.

    결함 둘을 넣어 둘 다 잡혔다 — 칸의 층을 낮춰 문서 값과 어긋나게 한 것과, 못 닿는 스킬에 층을 단 것(뒤엣것은 시험 **둘이** 잡았다).

    ★**열림 하나를 지어서 닫았다.** §15.133 이 세운 *실행 범위* 가 대장에서 내려간다. 남은 것은 골격 템플릿 하나다.

136. **골격을 지었다 — 모듈로 만들지 않았고, 그래서 시험이 유일한 보증이다.**

    §15.133 이 세운 마지막 열림이다. 매번 남의 어댑터를 베꼈고, **베끼면 그 기종의 사정까지 온다** — Spot 의 리스 처리나 Digit 의 권한 게이트가 상관없는 기종에 남는다.

    ★**모듈로 안 만들었다.** `adapter-template` 을 진짜 모듈로 세우면 벤더도 프로파일도 없는 모듈이 빌드에 서고, 게이트 검사 여덟이 전부 그것을 대상으로 삼는다 — 매니페스트도 프로파일도 없으므로 검사마다 예외를 파야 하고, **예외가 여덟이면 그 검사들이 무엇을 막는지 아무도 모르게 된다.** 그래서 `tools/adapter-template/` 의 `.kt.txt` 로 뒀다.

    ★★**대가는 컴파일러를 잃는 것이다.** `RobotAdapter` 의 면이 늘어도 골격은 **조용히 낡는다** — 이 저장소가 반복해 물린 바로 그 모양이다. 그 자리를 `AdapterTemplateTest` 넷이 대신 본다: 반드시 채우는 자리를 빠짐없이 드는가 · 기본값 있는 것을 갈라 적는가 · README 가 세는 수가 면과 같은가 · **README 의 파일 일곱을 어댑터 넷이 전부 갖는가.**

    마지막 것이 요점이다 — **목록을 발명이 아니라 실측이게 한다.** 손으로 적은 파일 목록은 관례가 바뀌면 낡는데, 실제 모듈 넷에 대면 먼저 빨개진다.

    결함 다섯을 넣어 넷이 각자 물었다 — 면에 자리 더하기(시험 둘이 잡았다) · 골격에서 자리 지우기 · README 의 수 틀리기 · 파일 목록에서 한 줄 빼기 · 선택 목록에서 하나 빼기.

    **골격이 거리 문서를 가리킨다.** 순서 1~8 은 `docs/vocabulary-distance.md` 가 정하고 9 번부터가 골격이다. `parameter_map` 의 `CONVERTED`·`SYNTHESIZED` 줄이 `accept` 안의 코드이고, `adapter_must_own` 이 코드량 견적이고, `execution_scope` 가 `poll` 의 모양을 정한다 — `COMMAND` 면 시계로 적어야 한다. **재는 것이 먼저이고 채우는 것이 나중이라는 순서를 파일 배치가 말하게 했다.**

    ★**안 채워도 되는 일곱을 굳이 적었다.** 기본값은 *없다* 이지 *된다* 가 아니라서 **그대로 두는 것이 정직한 선택인 경우가 많은데**, 그 선택지가 안 보이면 다음 사람이 억지로 채운다. 특히 진행률은 지어낼 수 있는 유일한 값이라 기본이 *못 잰다* 다.

    ★**§15.133 의 열림 둘이 다 닫혔다.** 실행 범위(§15.135)와 골격(여기). 리서치에서 세운 열림을 둘 다 지어서 닫았다.

137. **§15.20 이 반만 맞았다 — 경고 넷 중 둘은 다른 플러그인의 것이었다.**

    `protobuf-gradle-plugin` 을 0.9.4 → 0.10.0 으로 올렸다. 먼저 **§15.20 이 적은 것을 그대로 재현했다** — `--warning-mode all` 로 경고 넷이 나오고 전부 *"This will fail with an error in Gradle 10"* 이다: legacy `Usage` 값 둘(`java-api-jars`·`java-runtime-jars`)과 다중 문자열 의존 표기 둘(`protoc`·`protoc-gen-grpc-java`).

    0.10.0 이 **다중 문자열 둘을 없앴다**(0.9.6 의 변경이다). 그런데 **legacy `Usage` 둘은 그대로 남았다.**

    ★★**§15.20 의 진단이 틀렸다.** *"legacy `Usage` 속성과 다중 문자열 의존 표기가 플러그인 내부에서 나오므로 우리가 못 고친다"* 고 한 문장이 **둘을 한 플러그인의 것으로 접었다.** 스택을 떠 보니 `Usage` 쪽은 `org.jetbrains.kotlin.gradle.plugin.AbstractKotlinPlugin$Companion.configureAttributes` 다 — **Kotlin Gradle 플러그인(2.0.21)이다.** 증상이 같고 같은 빌드에서 같이 나와서 한 원인으로 읽혔다.

    ★**먼저 댄 것은 계약 다이제스트다.** 헤더의 `contract_digest` 가 `descriptor.binpb` 의 SHA-256 이라, 플러그인이 디스크립터 바이트를 한 바이트라도 바꾸면 **전 소비자가 `CONTRACT_REVISION_MISMATCH` 경보를 받는다.** 올리기 전에 기준값을 떠 두고(27084 바이트 · `76dc026d…`) 올린 뒤 다시 떴다 — **같다.** 도구 판을 올릴 때 이 저장소가 가장 먼저 확인해야 하는 값이다.

    0.10.0 의 파괴적 변경(`generatedFilesBaseDir` 읽기 전용)은 안 맞는다 — 우리는 `descriptorSetOptions.path` 를 직접 준다. 0.9.5 가 바꾼 기본 출력 폴더도 안 맞는다(하드코딩한 생성 경로가 없다).

    **마감은 실재하되 임박하지 않았다(2026-09-11 확인).** Gradle 10 은 아직 안 나왔고 일정 공지도 없다 — 최신이 9.7.1(2026-08-19)이고 우리가 그것을 쓴다. §15.20 의 *"Gradle 10 이전에 해야 한다"* 가 대장에서 무기한 급한 줄로 읽히던 것을 여기서 끊는다.

    ★**남은 절반은 갈라서 세운다.** KGP 2.0.21 → 2.4.x 는 컴파일러 판이 바뀌는 일이라 protobuf 플러그인 한 줄과 값이 다르다. 한 PR 에 둘을 묶으면 무엇이 무엇을 깼는지 못 가린다.

138. **Kotlin 2.0.21 → 2.4.20 — 경고가 0 이 됐고, 조합이 이미 지원 밖이었다는 것도 알았다.**

    §15.137 이 갈라 세운 절반이다. 올리자 **legacy `Usage` 둘이 사라졌다** — 빌드 전체의 deprecation 이 0 건이다(`--warning-mode all` 전수). Kotlin 경고도 없고, 155 클래스 1,526 시험이 그대로 초록이다.

    ★**올리는 김에 더 나쁜 것을 봤다.** KGP 의 Gradle 호환표에 **2.0.21 이 아예 없다** — 표가 2.1.0 부터 시작하고 그 줄의 Gradle 최대가 8.10 이다. 우리는 Gradle 9.7.1 을 쓰고 있었으므로 **이미 지원 범위 밖에서 돌고 있었다.** 경고 둘은 그 사실의 겉면이었고, 그것만 보고 *"플러그인이 legacy 값을 쓴다"* 로 읽었던 것이다. 2.4.20 의 최대가 9.7.0 이라 9.7.1 은 여전히 한 칸 위지만, 판 하나 차이와 마이너 여섯 차이는 다르다.

    **여기서도 계약 디스크립터를 먼저 댔다**(§15.137 의 규율). Kotlin 은 protoc 을 안 건드리므로 안 변하는 것이 당연한데, *당연한데 안 대는 것* 이 §15.22 가 물린 자리다. 27084 바이트 · `76dc026d…` 로 같다.

    ★**언어 버전을 고정하지 않는다는 사실이 이때 드러난다.** `languageVersion` 을 어디에도 안 적었으므로 **플러그인 판을 올리면 언어 판도 함께 올라간다.** 이번에는 아무것도 안 깨졌지만, 그것은 운이 좋았다는 뜻이지 안전하다는 뜻이 아니다. 고정하지 않기로 **정한 적이 없고 그냥 안 적혀 있었던 것**이라 여기 적어 둔다.

139. **언어 판을 적었다 — 그리고 그것을 확인하는 주입이 처음에 안 돌았다.**

    §15.138 이 *"고정하지 않기로 정한 적이 없고 그냥 안 적혀 있었다"* 고 적어 둔 자리를 닫는다. 먼저 **실측했다** — 빌드에 탐침을 잠깐 넣어 `languageVersion=null`·`apiVersion=null`·`KotlinVersion.DEFAULT=KOTLIN_2_4` 를 떴다. 즉 2.0.21 → 2.4.20 에서 언어 판이 **2.0 → 2.4 로 움직인 것이 수치로 확인됐다.**

    ★**적기만 하면 이번에는 반대로 낡는다.** 플러그인을 올려도 이 줄이 그대로면 새 컴파일러로 옛 언어 판을 쓰게 되고 그것도 아무도 안 본다. 그래서 `KotlinLanguageVersionTest` 가 카탈로그의 `kotlin` 판과 `build.gradle.kts` 의 고정을 **묶는다.** 이 시험이 빨개지는 뜻은 *언어 판이 틀렸다* 가 아니라 ***플러그인을 올렸으니 언어 판을 어떻게 할지 정하라*** 다 — 막는 장치가 아니라 **사람이 봐야 하는 순간에 빨개지는 장치**이고, 도장의 해시가 문서에 대해 하는 일과 같다.

    ★★**결함 주입이 처음에 안 돌았고, 규율이 그것을 잡았다.** 카탈로그를 `2.5.0` 으로 올려 주입했는데 *실패 0* 이 나왔다. 이 저장소의 순서(① 시험이 돌았나 ② 주입이 들어갔나 …)대로 먼저 ①을 봤더니 — **2.5.0 은 존재하지 않아 플러그인 해소 단계에서 빌드가 죽었고, 시험이 아예 안 돌았다.** 내 스크립트는 그때 **낡은 XML** 을 읽어 *실패 0* 을 보고했다. `feedback-verify-the-check-runs` 가 적어 둔 것의 정확한 재현이다.

    방향을 바꿔 **고정을 낮추는 쪽**(`KOTLIN_2_4` → `KOTLIN_2_2`, 플러그인은 그대로)으로 다시 넣으니 잡혔다. 컴파일러는 자기보다 **새로운** 언어 판을 못 받으므로 주입은 이 방향으로만 성립한다 — 옛 판으로 낮추는 것은 되고 새 판으로 올리는 것은 안 된다.

    ★**판정은 종료 코드가 아니라 XML 의 시각으로 했다.** 두 번째 주입에서 결과 파일이 1 초 전 것임을 확인하고서야 *잡혔다* 고 적었다.

140. **저장소 밖에 있던 그림 아홉 중 셋을 들였다 — 들이기 전에 주장부터 댔다.**

    저장소 밖에 이 프로젝트의 그림 아홉이 **더 넓은 폭**으로 그려져 있었고 저장소에는 없었다. **복사가 아니다** — README 열이 837, `docs/*.md` 가 1012 라 그보다 넓은 캔버스를 그대로 넣으면 글자가 바닥(9.5px) 밑으로 내려간다. 그래서 **폭을 인자로 받는 생성기**로 다시 그렸다 — 넓은 판이 다시 필요해지면 같은 파일에서 뽑으면 되고, 손으로 두 벌 두면 어느 날 한쪽만 고쳐진다.

    ★**옮기기 전에 아홉의 주장을 코드에 댔다.** 오늘 `change-plan.svg` 가 사실관계가 틀린 채로 공개 문서에 걸려 있던 것을 봤기 때문이다. 1군 셋은 전부 참이었다 — `RevisionValidator` 가 `gate.GateRunner` 를 실제로 부르고(검사 8 + 음성), `BindingService` 가 `SUPERSEDED` 재활성화를 들고, 상태 열 열 개와 `Resolution` 셋이 계약과 일치한다. `startup` 은 `Main.kt:132` 의 문장과 **글자까지 같았다.**

    ★**한 장은 낡아 있었다.** `three-layers` 가 기체를 **셋만** 그린다 — Orbit 이 생기기 전 판이다. 주장(*"경계는 두 곳뿐이다"*) 자체는 참이고 `consumer.kind = CLIENT | UPSTREAM_SYSTEM` 도 맞지만, 그림이 세는 수가 낡았다. 2군으로 미뤘다. **그리고 그 주장은 오늘 README 에 넣은 `seam.svg` 와 같은 말이라** 둘 다 두면 얼굴에 같은 주장이 두 번 선다 — 자리를 정리하고 옮겨야 한다.

    ★★**다시 그리는 중에 내가 같은 결함을 두 번 냈다.** ① 부제가 *"노란 선은 상위가 부르는 명령"* 이라고 적혀 있는데 **그 선을 안 그렸다.** ② `cross` 배열에 라벨 넷을 정의해 놓고 **한 번도 안 그렸다** — 선이 무엇이었는지가 그림의 절반인데 없었다. 둘 다 렌더를 눈으로 보고서야 잡혔고, 검증기는 못 잡는 종류다(§15.119 가 적어 둔 그 자리다).

    ★**검증기가 잡은 것은 따로 있었다.** 라벨 칩 폭을 `label.length * 10.5` 로 어림했는데 한글과 라틴이 섞이면 한쪽이 반드시 틀린다. 칩이 옆 지대의 칸 위로 넘어갔고 *edge label over node* 가 둘 걸렸다. 거터를 넓히고 글자 종류별로 곱했다.

141. **2군 셋도 옮겼다 — 그리고 둘이 수를 잘못 세고 있었다.**

    §15.140 의 나머지다. 옮기기 전에 주장을 댄 것이 또 값을 했다.

    ★**`three-layers` 가 기체를 셋만 그렸다.** Orbit 이 생기기 전 판이다. 주장(*"경계는 두 곳뿐이다"*)도 `consumer.kind = CLIENT | UPSTREAM_SYSTEM` 도 참인데 **그림이 세는 수가 낡았다.** 넷으로 고치면서 Orbit 은 **로봇이 아니라 플릿 관리자**라고 적었다 — 다른 셋과 같은 칸에 두되 같은 것으로 읽히면 안 된다.

    ★★**`capability-projection` 이 「나머지 넷」이라 적었는데 다섯이다.** 세어 봤다 — 프로파일 최상위가 **열셋**, `Capability` 메시지가 **여덟**(`vendor`·`model`·`profile_revision`·`skills`·`optional_fields`·`protocol_limits`·`publish_interval`·`exclusive_control_required`)이므로 남는 것은 **다섯**이다. 빠진 것은 `derived_from` 이고, 그것은 스키마 첫 커밋부터 있었다 — 즉 그림이 **처음부터 하나를 빠뜨린 채** 수를 적었다. 이 저장소가 산문의 수를 기계로 세게 만든 이유가 그림에서도 같다.

    **자리를 정했다.** `three-layers` 는 `architecture.md` 의 새 §0 으로 갔고, README 히어로는 `seam.svg` 가 그대로 지킨다 — README 첫 문단이 `seam.svg` 의 주장을 그대로 풀어 쓰고 있어서 그림과 글이 붙어 있다. 두 그림은 **다른 질문에 답한다**: 하나는 *무엇이 붙는가*, 하나는 *왜 계약 하나로 붙는가*.

    ★**그러면서 `architecture.md` 안의 낱말 충돌을 하나 끊었다.** 새 §0 이 「경계 셋」을 말하고 §1 이 「층 넷」을 말한다 — 같은 낱말로 다른 것을 센다. 한 줄을 넣어 갈랐다: **§0 은 누가 무엇을 구현하는가(배치), §1 은 어휘가 어디서 갈리는가(ADR 36).**

    `startup` 은 `mimic/README.md` 로 갔다. 여덟 칸 전부에서 같은 곳으로 내려가는 화살표가 *"어느 칸에서 걸려도 결과는 하나"* 를 배치로 말한다.

    ★**검증기가 또 하나 잡았다.** *"하나라도 걸리면 ↓"* 라벨 위로 첫 칸의 화살표가 지나갔다. 라벨을 지우고 그 말을 아래 칸의 제목에 넣었다 — 화살표가 전부 붉고 아래 칸이 붉으면 그 말은 이미 그려져 있다.

142. **전체를 한 눈에 보는 자료가 없었다 — 그림 열한 장이 전부 단면이었다.**

    사용자가 *"전체 아키텍처를 한 눈에 볼 수 있는 자료가 들어가는 곳은 어디인가"* 를 물었고, 세어 보니 **없었다.** 그림 열한 장이 각자 한 주제씩 자른 단면이고 **모듈 열여섯이 서로 어떻게 붙어 있는지를 보여 주는 것이 하나도 없었다.** 가장 가까운 것이 `architecture.md` §4b 인데 그것은 표다.

    ★**손으로 안 그린다.** 각 `build.gradle.kts` 의 출하 의존을 읽어 칸과 간선을 뽑는다. 간선 서른여섯 중 **스무 개는 바닥 둘(`contracts`·`profile-model`)로 가는 것이라 안 그리고 수로 적었다** — 다 그리면 스파게티이고, 바닥이 바닥이라는 사실은 한 줄로 말하는 편이 강하다.

    ★★**그림이 자기가 그린 간선을 주석으로 싣는다.** 그리고 그 주석은 **표시 라벨이 아니라 실제 모듈 이름**으로 나온다 — 기종 어댑터 셋을 한 칸으로 묶은 것은 그림의 사정이고, 주석은 그 칸이 뜻하는 간선 셋을 각각 적는다. 그래서 `ComponentMapTest` 가 **묶음 규칙도 숨김 규칙도 한 벌 안 옮기고** *"빌드에 있는가"* 만 묻는다. §15.115 가 남긴 규율(규칙을 두 벌로 두면 한쪽만 는다)을 그림과 시험 사이에도 적용한 것이다.

    ★★★**첫 판에 구멍이 있었고 결함 주입이 그것을 찾았다.** 처음에는 *"그린 간선이 전부 실재하는가"* 만 봤다. 그래서 **빌드에 의존을 하나 더해도 초록이었다** — 실재하는데 안 그린 것을 아무도 안 봤다. XML 시각을 확인해 시험이 돌았다는 것부터 확인하고(1초 전) 구멍이라 판정했다. 주석이 실제 모듈 이름이라 수로 막을 수 있었다: **바닥으로 가지 않는 출하 간선은 전부 주석에 있어야 한다.**

    **자리는 둘이다.** README 의 「무엇이 있나」 절 머리(디렉터리 나무 위)와 `architecture.md` §4b(표 위). 같은 사실을 그림과 표로 두 번 보여 주는 것이고, 둘 다 같은 빌드 파일에서 나온다.

    ★★**첫 판을 통째로 버렸다 — 사용자 판정 「모두가 평등해 보인다」.** 의존 깊이로 줄을 세우고 같은 크기의 칸을 늘어놓았더니 `contracts` 와 `gate` 가 같아 보였다. **위계도 포함 관계도 없었다.**

    ★**그렇게 만든 것이 내 «스파게티를 피한다» 는 결정이었다.** 바닥 둘로 가는 간선 스무 개를 지우고 수로만 적었는데, **그 스무 개가 바로 위계였다** — `contracts` 가 열다섯 중 열셋에 붙어 있다는 사실이 그 선들이다. 읽기 쉽게 만든다고 읽을 것을 지웠다.

    **다시 그릴 때 바꾼 것 셋.** ① 어휘 둘을 가운데에 **가장 크게** 두고 *몇이 쓰는지* 를 칸 안에 적었다(13 · 7). ② `adapter-host` 가 기종 어댑터 넷과 `adapter-core` 를 **담아서** 그렸다 — 포함이 배치로 보인다. ③ `mimic` 과 `adapter-host` 를 **「같은 자리」로 묶었다.** 이 저장소의 가장 중요한 구조적 대칭인데 첫 판에는 흔적도 없었다.

    ★**검증기가 포함을 겹침으로 읽는다.** 담는 칸을 노드로 그리니 *node overlap* 이 둘 났다. 집 방언에서 **담는 것은 지대(zone)** 이므로 그 스타일로 바꾸자 사라졌다 — 검증기가 틀린 것이 아니라 내가 문법을 안 쓴 것이다.

    ★**받침 선을 그렸다가 지웠다.** 「위아래가 전부 여기 얹힌다」를 괄호 모양 선으로 그렸더니 **빈 상자로 읽혔다.** 캡션 두 줄(↑ · ↓)이 같은 말을 더 잘한다.

143. **사전 조건과 효과를 능력 선언에 붙였다 — 계약 0.9.0, 그리고 «추가 = major» 를 하루 만에 물렸다.**

    비직교 능력(이동 × 적재) 진단(`CAPABILITY_MODEL_ASSESSMENT.md`)의 판정 (나)에 따라 두 자리를 만들었다. **효과는 계약 카탈로그에** — `grasps_object` 옆에 `releases_object` 메시지 옵션 하나, `PickPlaceV1` 만 참. **사전 조건은 프로파일에** — `skills[].preconditions[{subject, requires}]`, v1 의 주어는 `HOLD` 하나이고 `HoldKind` 와 같은 어휘로 비교한다. `SkillDeclaration.preconditions = 8` 로 투영에 들어가며, 평가기 `PreconditionCheck` 는 `Negotiator` 와 같은 이유로 `capability` 에 둔다(미믹과 호스트가 같은 함수를 부른다). 관측 불가는 통과가 아니다. 거절 코드 `PRECONDITION_UNMET` 와 `KEY_PRECONDITION_SUBJECT` 를 예비했다(§15.98 의 자리).

    ★**버전 규칙을 틀리게 적었다가 음성 사례가 잡았다.** 첫 판은 «조건 추가 = major» 였다 — 필수 파라미터 추가와 같은 실패라는 논리였다. 그런데 프로파일의 `major` 는 계약 카탈로그의 major 에 묶여 있어(검사 4번) 프로파일이 혼자 올릴 수 없다. `humanoid-a` 에 조건을 더하는 순간 검사 6번이 «major 를 올려라» 를 요구하고, 올리면 검사 4번이 «카탈로그에 없는 major» 로 막는다. 이 저장소가 능력 철회를 통과시키는 길은 major 가 아니라 **축소 + §9.3 원장 조회**였다(스킬 제거가 그 길이다). 규칙을 `shrink()` 로 바꿨다 — 원장이 없으면 WARNING 과 건너뜀, 소비자가 남았으면 ERROR. 음성 사례 `06-precondition-added-with-consumers` 가 `active_consumers: 2` 로 «축소가 거부됐다» 를 기대한다. 제거는 확장이라 WARNING 으로만 남긴다.

    **실물 넷에는 조건을 하나도 적지 않았다.** 벤더 1차 자료로 «든 채로 이동 불가»가 확인된 기종이 없어 `UNKNOWN` 이고, `UNKNOWN` 은 선언하지 않는 것이다. G1 은 파지를 관측하지 못해 선언하면 영구 거절이다. 참고 프로파일 `humanoid-a` 만 `navigate_to` 에 `HOLD requires EMPTY` 를 든다 — `grip_force` 의 `min_value` 가 그 문서에만 있는 것과 같은 자리다. `ModelProfilesTest` 두 건이 이 둘을 고정한다.

    **적지 않은 것.** 적재 질량·무게중심·자세 조건. 네 어댑터 중 질량을 관측하는 것이 없어 ADR 9 에 걸린다. `PreconditionSubject` 를 열거로 둔 이유가 그것이고, 대장에 §15.143 로 올렸다.

144. **사전 조건이 거절이 됐다 — 남쪽 호출 전에, 엔진에 닿기 전에, 미믹과 호스트가 같은 말로.**

    §15.143 의 선언에 거절 경로를 붙였다. 호스트는 `HostedRobot.start` 에서 파라미터 검증 뒤·`adapter.accept` 전에 `PreconditionCheck` 를 부르고, 관측은 `RobotAdapter.hold()` 다. 미믹은 `TaskHost.start` 에서 `ParameterCheck` 옆에 같은 함수를 부른다. 거절 문장과 `KEY_PRECONDITION_SUBJECT` 참조는 `capability` 가 한 곳에서 만든다 — 각자 문장을 지으면 `HostParityTest` 가 잡는다. 코드는 `PRECONDITION_UNMET` 이고, 벤더가 `PRECONDITION_FAILED` 로 거절한 것(G1 의 `LOCOSTATE_NOT_AVAILABLE`·`ARM_ACTION_ERR_HOLDING`)도 같은 코드로 나간다 — §15.98 이 «계약에 그 자리가 없다»고 적어 두었던 정직 메모가 사전 조건에 한해 닫혔다. 다른 분류의 벤더 거절은 그대로 `INVALID_TRANSITION` 이다.

    ★**패리티 시나리오를 한 번 고쳤다.** 첫 판은 «도는 `pick_place` 뒤에 `navigate_to`» 를 양쪽에 보냈다. 미믹은 그것으로 `HOLDING` 이 되지만 **호스트는 배타 실행이라**(§4.9) 도는 동안의 새 요청은 사전 조건에 닿기도 전에 `INVALID_TRANSITION`(«이미 도는 태스크가 있다») 이다. 호스트 쪽은 «앞 태스크가 든 채로 `FAILED` 로 끝난 뒤» 여야 한다 — 실패는 파지를 바꾸지 않는다. 그래서 가짜 어댑터가 접수 뒤 첫 폴링에서 종착하게 했고, 양쪽이 다른 길로 같은 물리 상태에 이르러 같은 거절을 낸다. 이 차이 자체가 기록할 가치가 있다: 호스트에서 «든 채로 다음 요청»은 언제나 *앞 태스크가 끝난 뒤* 의 이야기다.

    **미믹의 손은 한 쌍이다.** 로봇 수준의 파지는 «어느 태스크든 `HOLDING` 이면 든 채» 로 정했다 — 도는 것도, 복구 실패로 든 채 끝난 것도. 미믹은 못 보는 기종이 아니므로 `NOT_OBSERVABLE` 을 내지 않는다. 관측 불가의 거절(설계안 §3.2)은 호스트 시험이 가짜 어댑터로 본다.

    실물 넷의 거동은 이번에도 바뀌지 않았다 — 조건을 든 프로파일은 참고용뿐이고, 벤더 `PRECONDITION_FAILED` 의 코드 변경은 G1 어댑터 시험이 보는 분류 매핑(§15.128 무렵)을 건드리지 않는다.

145. **미들웨어가 알고도 보내지 않는다 — 계획 시점 사슬 검사, 그리고 통보의 잔여 파지가 "빈손"을 말하게 됐다.**

    설계안의 마지막 조각이다. `Middleware.submit` 이 이 기체에서 **마지막으로 관측한 파지**(`RobotView.lastHold`, `WatchTask` 갱신에서 적는다)에서 출발해 카탈로그 효과(`HoldEffects` — `grasps_object`·`releases_object`)로 단위 사슬을 따라가며 로봇이 선언한 조건과 대조한다. 어긋나면 접수 요청조차 보내지 않는다 — 받아 놓고 돌리면 그 단위가 발신자에서 `PRECONDITION_UNMET` 으로 거절되기까지 앞 단위들이 물리적으로 움직인다. **권위는 발신자다**: 관측이 없거나 관측 불가면 미리 재단하지 않고, 능력을 못 물은 로봇도 같다. 이 검사가 `picasso → capability` 간선을 만들었다(구성도 재생성, 간선 하나).

    ★**시험이 규칙 하나를 가르쳐 줬다.** «적재를 잃은 실패는 세 축에 다 적힌다» 를 처음 쓸 때 셀 장치를 `perfect()` 로 두었더니 `blockedBy` 가 비었다. 미들웨어가 다음 단위에 가지 않았기 때문이다 — **하류는 잃었다는데 셀엔 증거가 있으면** 그 슬롯을 운영자 판단(`OPERATOR_HOLD`)으로 세운다(관측의 충돌, 결함이 아니다). 잃은 적재는 슬롯에 없어야 맞으므로 시험에서 증거를 뺐다. 그리고 둘째 축은 조건부다: `blockedBy` 는 기체가 `can_accept_new_task=false` 라 한 결함만 들므로, `PAYLOAD_LOST` 를 그렇게 선언하는 것이 프로파일의 몫이다(픽스처 `precondition.json` 이 그렇게 한다; `minimal` 은 아니다). 설계안 §4 가 이 둘을 빠뜨렸고 정정했다. 어댑터 골격 README 「적재를 잃었을 때」에 규약 셋을 적었다.

    **통보를 하나 고쳤다.** `notify()` 의 `residualHold` 는 든 단위가 없으면 기본값을 실어 «빈손» 이 «말하지 않았다» 로 접혔다. 이제 든 단위가 없으면 마지막으로 관측한 파지를 싣는다.

    결함 주입: 사슬 검사 제거 · 관측 기록 제거 · 효과 계산을 «그대로» 로 고정 · `residualHold` 보정 되돌림 — 넷이 각각 시험을 빨갛게 했다.

153. **자원 소유 대장을 문서로 올렸다 — 그리고 빈 칸이 셋 드러났다.**

    «무엇을 누가 승인하는가» 가 코드에는 있고 문서에는 없었다. `submit` 이 `chainViolation` 과 `liveHold` 로 배정을 막고, `AmrFleetPort.dispatch` 의 `null` 이 이송을 막고, `CellSignals.observe` 의 `null` 이 «말이 없다» 를 말하는데, **자원을 열거하고 그 관문을 적은 표가 없었다.** 표가 없으면 빠진 자원을 아무도 못 찾는다 — 빈 칸이 사고가 나는 자리인데 빈 칸을 볼 자리가 없다. `docs/orchestration.md` 가 그 표이고, 관문 칸의 기호는 `DocumentClaimsTest` 가 코드와 댄다. 이름을 바꾼 사람은 그 문서를 안 보므로.

    **표를 그리자마자 셋이 나왔다.** 셀 내 점유 축이 없어 재할당이 구조적으로 불가능한 것(§15.153), 걷는 기체의 통로에 소유자 자체가 없는 것(§15.154), 자리 결속의 기준 지도 버전을 아무도 안 보는 것(§15.155). 앞의 둘은 이 층이 이미 아는 사실이었는데 대장에 행이 없었다 — **문서로 올리는 일이 곧 그것을 찾는 일이었다.**

    그리고 **「없음」과 「구멍」을 갈랐다.** 소유자가 있고 관문만 없는 것은 만들면 닫히고, 소유자가 없는 것은 먼저 소유를 정해야 한다. 접으면 «만들면 되겠네» 로 읽혀 통로 문제가 개발 항목으로 오해된다. 같은 김에 README 가 손으로 적어 둔 미결 항목 수가 낡아 있는 것(37 대 40)을 찾아 세는 시험을 붙였다.

154. **셀을 벗어나면 바닥에 주인이 없다.**

    플릿은 자기 AMR 만 승인하므로, 걷는 기체가 셀 밖 통로를 쓸 때 이 층이 내는 명령은 **아무 관문도 통과하지 않는다.** 선택지는 둘뿐이다 — 소유자를 만들거나, 그 자원을 쓰지 않거나. 무승인 통행을 허용하는 셋째는 없으므로 소유자가 정해지기 전까지의 올바른 상태는 **그 구역으로 명령을 내지 않는 것**이다. 소유자 신설이 시스템 신설은 아니다 — 구간을 잘라 기존 소유자에게 주면 시스템이 하나도 안 늘고 소유자가 생긴다. 절차와 승격 조건은 아직 안 적었고 §15.154 로 등록한다.

156. **자리 이름이 어느 판에서 배운 것인지 묻기 시작했다 — 그리고 좌표는 끝내 안 들였다.**

    §15.155 가 연 구멍을 지도 판 축부터 닫는다. 계약 카탈로그의 `is_site_reference` 가 어느 칸이 자리 이름인지 말하고(`SiteReferences`), 결속 정본이 그 이름을 어느 판에서 배웠는지 말하며, 호스트가 **어댑터가 이름을 풀기 전에** 둘을 맞댄다. 뒤에 두면 이미 옛 좌표로 움직인 뒤에 판을 보게 된다.

    **좌표 칸을 두지 않았다.** 설계 문서는 결속 표에 «식별자 또는 좌표+프레임» 을 적었는데, 이 저장소에는 좌표·프레임 개념이 하나도 없고(계약의 `location` 은 `is_site_reference` 인 문자열이고 카탈로그가 *"좌표가 아니다"* 라고 적어 뒀다) 미믹에 기하가 없어 **좌표 결속을 틀리게 만들 방법이 없다.** 항상 통과하는 시험은 아무것도 보증하지 않는다. 판 이름은 다르다 — 맞대 볼 토큰이라 어긋나게 만들 수 있고, 그래서 시뮬레이션에서 진짜로 검증된다. ADR 34 와 충돌하지 않는다는 것도 함께 확인했다: §2 의 금지는 **어댑터가 보유하는 것**이고 §4 결론이 상위 사이트 레지스트리를 소유자로 명시 허용한다.

    **답을 셋으로 갈랐다.** 옛 판에서 배웠다(`PRECONDITION_UNMET` — 재등록을 기다린다) · 정본이 모르는 이름이다(`PARAMETER_INVALID` — 등록해야 한다) · 정본에 못 물어봤다(gRPC `UNAVAILABLE` — 요청의 잘못이 아니다). 운영자가 할 일이 셋 다 다르므로 접으면 무엇을 해야 할지 알 수 없다. **새 계약 코드는 만들지 않았다** — 늘리면 내부 판정 규칙이 계약으로 샌다.

    **정본을 안 붙인 배치는 안 막는다.** `ActiveMap.NotConfigured` 가 「검사할 정본이 없다」이고 `Unavailable` 이 「못 물어봤다」이며, 뒤엣것만 멈춘다. 둘을 접으면 정본 없는 현장이 통째로 서고 그러면 이 검사가 곧 꺼진다 — `SiteNames` 가 `Unsupported` 와 `Unavailable` 을 가른 것과 같은 규율이다.

    **검사 9번이 결속을 어댑터 경계 안에 가둔다.** 탐색어는 결속 파일이 선언한 최상위 이름에서 유도하므로 타입을 더하면 금지도 저절로 는다. 훑는 모듈에 `registry` 와 `profile-model` 을 넣었다 — 검사 7의 목록에 그 둘이 없어서, 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.

179. **원인을 번들에서 빼고 코퍼스로 옮겼다 — 되읽기와 근거로 좁히기가 그제야 갈린다.**

    읽는 쪽이 「1순위 원인 일치율」의 정답을 세 번 짜고 세 번 버렸다. 분류 이름을 정답으로 뒀더니 그 낱말이 질의에 이미 있어 **복창이 만점**이었고, 분류에서 기종을 되짚었더니 그것은 도메인 사실이 아니라 어댑터 매핑 공백이었으며, 미분류 사건으로 옮겼더니 그 번들의 `vendorDetail` 이 `X_FIXTURE_SIMULATED_HARDWARE_FAULT` 라 **원인이 옆 칸에 적혀 있었다.** 셋 다 같은 자리다 — 정답이 입력 안에 있으면 그 지표는 모델이 아니라 자기 입력을 잰다.

    ★★**그래서 결함 이름이 원인을 말하지 않게 했다.** 실물 기체와 플릿이 내는 것은 불투명한 코드다 — 코드 자체는 아무것도 말하지 않고 뜻은 벤더 문서에만 있다. 픽스처를 그 모양으로 맞췄다: `X_FIXTURE_E4412` 처럼 보고는 알 수 없는 코드를 내고, 뜻은 `docs/vendors/fixture-stop-codes.md` 에만 둔다. `X_FIXTURE_` 접두사는 그대로다 — 합성 표시는 규율이고 뗄 것이 아니다.

    **이것이 이 층과 설명 층의 경계를 값으로 만든다.** 미들웨어가 매핑 못 한 코드는 `UNCLASSIFIED` 로 나가고, 거기서부터가 설명 층의 일이다. 번들을 되읽으면 «미분류다» 밖에 못 말하고, 코퍼스의 벤더 문서를 인용해야 «관절 토크 한계 초과다» 가 나온다. 앞 판에서는 둘이 같은 답을 냈기 때문에 그 차이를 잴 수가 없었다.

    **정답은 번들 밖으로.** `handoff/narrator/ground-truth.jsonl` 이 사건마다 정답 낱말·사람이 읽을 사유·오답 후보를 든다. 번들에 넣으면 복창이 다시 만점이 되므로 인계 자리의 **다른 파일**이다. 읽는 쪽은 그것을 채점에서만 읽고 질의를 만드는 경로에는 안 들인다.

    ★**시험이 조건 하나를 기계로 든다.** 정답 낱말과 오답 후보가 그 사건의 번들 줄에 **한 낱말도 없어야** 한다. 사람이 훑어서 막을 수 없는 종류의 누수다 — 결함 이름을 고치면서 `error_hint` 에 «토크 한계를 확인하십시오» 라고 적었으면 그 자리에서 다시 샜을 것이고, 실제로 힌트는 «정지 코드를 벤더 문서에서 조회하십시오» 로 썼다. 그리고 주입한 코드마다 문서에 근거가 하나는 있는지도 함께 든다 — 없으면 그 사건은 «안 좁혀지는 것이 정상» 이 아니라 **재료가 없는 것**이고 둘은 다른 실패다.

    **안 좁혀지는 것이 정상인 사건을 하나 넣었다.** `X_FIXTURE_E9001` 은 한 코드에 원인이 둘이고 코드가 그 둘을 안 가른다 — 문서가 그렇게 적었고, 둘 중 하나를 지목하는 답은 근거가 없다. 전부 좁혀지면 «안 좁혀진다» 가 한 번도 정답이 되지 않아 그 답을 낼 줄 아는지를 잴 수가 없다. 과잉 단정을 잡는 자리다.

    **번호가 밀리는 것도 막았다.** 정답표가 `incident-N` 을 가리키는데 시나리오가 늘면 그 번호가 딴 사건을 가리킨다. 정답표가 정지 코드를 함께 들고 시험이 둘을 맞대므로, 밀리면 조용히 틀린 채로 채점되는 대신 빨개진다.

    한 벌이 사건 아홉이 됐고 그중 넷이 골든이다. 나머지는 그대로다 — 승인자·경로 둘·끊긴 선·매핑된 벤더 코드 하나는 각자 다른 것을 보여 주는 자리라 건드리지 않았다.

178. **의도와 경로와 관측 신뢰까지 실었다 — §15.177 의 급 2 는 닫힘.**

    §15.177 이 급 1 을 실으면서 *"그만큼 설명이 분류의 재진술에 머무는 자리가 남는다"* 를 대장에 남겼는데, 그 자리를 닫는다. 셋 다 이미 각자가 답해 둔 값이고 옮겨 싣는 것이다.

    **의도 — «무엇이 어긋났나» 와 «무엇을 하려 했나» 는 다른 질문이다.** 앞엣것만 있으면 설명이 어긋남의 재진술에 머문다: 어느 자리에 무엇을 놓으려 했는지를 모르면 «잘못 놓았다» 가 무슨 뜻인지 말할 수가 없다. **상류가 적은 것과 이 층이 편 것을 함께 든다** — 둘이 갈리는 자리가 곧 이 층의 번역이고, 한쪽만 실으면 읽는 쪽이 그 번역을 볼 수 없다. 능력의 최고 등급과 시간창 폭도 같이 넣었다. 요청에는 `Execution.capability` 라고만 적혀 있었는데, 그 객체에서 밖이 쓸 수 있는 것이 그 둘이다 — 근거 창이 **왜 그 범위였는지**가 그것으로 답해진다.

    **경로 — 없으면 책임 소재를 못 가른다.** 같은 «실패» 라도 `ROBOT` 은 기체와 어댑터의 일이고 `FLEET` 은 플릿의 일이며, 다음에 누구에게 물을지가 그것으로 갈린다. 한 칸짜리 값인데 그 한 칸이 다음 행동을 정한다.

    **관측 신뢰 — `progressObservable` 의 널이 요점이다.** 3값이고 널이 «아직 갱신을 못 봤다» 다. 거짓으로 접으면 «못 물어봤다» 가 «못 잰다» 가 되고, 진행률의 0.0 을 정체로 읽으면 진행률을 안 내는 기종이 언제나 멈춰 있는 것으로 보여 그 경보가 곧 무시된다 — 계약이 `ProgressBasis` 를 만든 이유가 그것인데 번들이 그 구분을 떨어뜨리고 있었다.

    ★**한 갈래뿐인 칸은 값이 있어도 아무것도 안 가른다.** §15.177 에서 배운 것을 이번에는 먼저 적용했다. 시나리오에 **플릿의 일**(출발지에 요청한 용기가 없어 인수되지 않는 운반)과 **선이 끊긴 채 난 사건**을 넣어 `route` 와 `linkBroken` 이 한 벌 안에서 갈리게 했다. 플릿 단위는 하달 자체가 없어 `progressObservable` 이 널로 나오므로 3값의 널도 실물에서 보인다. 남은 둘(`lateEvents`·`progressStalled`)은 한 벌에서 여전히 기본값이고, 그것은 대장에 적었다(§15.178).

    **맵을 키 순서로 정렬해 해시에 넣는다.** 의도가 맵 둘(`orderParameters`·`unitParameters`)을 들고 오는데, 순회 순서가 해시를 바꾸면 같은 사건이 두 값을 갖는다. 새 칸이 맵을 데려올 때마다 물릴 자리라 인코딩도 같은 규칙으로 맞췄다.

    `schemaVersion` 은 3 이다. **2 와 달리 이번은 칸이 는 것뿐**이라 모르는 칸을 무시하는 읽는 쪽은 그대로 돈다 — 2 는 `blockedBy` 의 모양이 바뀌어서 판을 봐야 했다.

    급 3(시간창 경계·시도 횟수·`physicalState`·`taskId`·`revision`)은 안 실었다. 그쪽이 급을 갈라 왔고 섞으면 무엇이 본질인지 안 보인다는 말이 맞다. 대장을 그 내용으로 바꿔 뒀다.

177. **번들이 자기 선언을 못 지키고 있었고, 읽을 것이 없으니 읽는 쪽이 되짚었다.**

    `IncidentBundle` 의 doc 이 *"«이 단위가» 와 «이 기체가» 와 «그때 무슨 일이 있었나» 를 한 화면에서 잇게"* 라고 적어 두고 **셋 중 «이 기체가» 를 안 싣고 있었다.** `Execution.robotId` 가 봉인 시점에 바로 있는데도.

    ★★**그것을 발견한 경로가 증거다.** 읽는 쪽이 평가 골든셋을 짜는데 기체에 대해 말할 재료가 없어 **`FailureClass` 에서 기종을 거꾸로 팠다.** 「`ROBOT_FELL` 이면 Spot」은 도메인 사실이 아니라 **어댑터 매핑 공백**이다 — Digit 도 휴머노이드라 넘어질 수 있는데 그 어댑터가 안 매핑했을 뿐이고(Spot 10종·Digit 5종·G1 3종은 벤더 API 노출량이다), 매핑 한 줄이 늘면 조용히 틀린 답이 된다. **기종 비인지를 거꾸로 돌린 것**이고, 원인은 읽는 쪽이 아니라 **읽을 것을 안 준 이쪽**이다.

    넷을 실었다. **기체** · **근거의 세기**(요구 등급·도달 등급·설비 대조 결과) · **분류의 근거**(결함 원문) · **걸음 위치**. 전부 이미 각자의 자리가 답해 둔 값이라 옮겨 싣는 것이고, 새 사실을 만들지 않는다는 번들의 성질은 그대로다.

    **가장 큰 것은 근거의 세기다.** 등급은 순서가 곧 세기인데(E0 자기 보고 · E1 플릿 · E2 독립 설비 · E3 업무 ack) 셋 다 없어서, 이 층은 *"이 판정은 로봇 자기 보고 하나에 기대고 있어 약하다"* 를 **말할 수 없었다.** 이 시스템에서 가장 1급인 진단 문장이 그것이다.

    ★**요청보다 한 칸 더 갔다.** 받는 쪽은 `blockedBy` 의 일곱 칸을 요청했는데, 그것은 **다음 단위를 막는** 결함이라 다음 단위가 없으면 빈 목록이다 — 실물 한 벌에서 사건 넷 중 둘이 그랬다. 그래서 «왜 그 분류가 됐나» 는 대부분의 사건에서 여전히 답이 없었을 것이다. 단위가 자기 결함 원문을 들게 해서(`ExecutionUnit.fault`) 번들이 `fault` 로 낸다. 요청의 글자는 `blockedBy` 였지만 목적은 *"분류를 재진술하지 않는 것"* 이었고, 글자만 채우면 목적이 안 선다.

    **칸을 만들어도 시나리오가 안 채우면 빈 칸이다.** 실물 한 벌의 결함 유형 자리에 정준 분류 이름이 그대로 들어 있어 벤더 원문이 통째로 비어 있었다. 픽스처에 벤더 이름공간(`X_`) 모드를 더하고 주입을 그것으로 바꿨다. 안 좁혀지는 사건(`UNCLASSIFIED`)과 **둘째 걸음에서 깨진 사건**도 한 벌에 넣었다 — 전부 «1 / N» 이면 걸음 위치가 값으로는 있어도 아무것도 가려 주지 않는다.

    ★★**공용 픽스처에 모드를 더했다가 `WithholdingTest` 둘이 빨개졌다.** 추첨이 *해당하는 모드마다 한 번씩* 뽑으므로 모드가 하나 늘면 같은 시드가 다른 순서를 낸다. `FailureDraw` 가 그 자리를 *"골든이 통째로 흔들리는 자리"* 라고 적어 뒀는데 그것을 읽고도 물렸다. 새 모드는 `vendor-fault.json` 으로 갈라 두 기체만 쓴다.

    **해시는 새 칸을 전부 든다.** 봉인 시점에 확정되고 같은 시드면 같은 값이며, 그중 하나가 다른 사건은 다른 사건이다 — 특히 기체가 그렇다. 빠지는 것은 여전히 실 시계와 나중의 판정 둘뿐이다. `schemaVersion` 은 2 다: `blockedBy` 가 칸이 는 것이 아니라 **모양이 바뀌었으므로** 읽는 쪽이 판을 봐야 한다.

    급 2(의도·경로·관측 신뢰)와 급 3 은 안 실었다. 요청이 급을 갈라 왔고, 섞으면 무엇이 본질인지 안 보인다는 것이 그쪽 말이며 맞다. 대장에 적어 둔다(§15.177). **급 2 는 §15.178 에서 닫혔다(닫힘).**

176. **입을 열었더니, 떠난 소비자 하나가 세계를 멈추고 있었다.**

    표면을 정하고 규약을 적어도 부르는 쪽의 권한은 여전히 0 이었다 — 소켓을 여는 손이 없었다. 그래서 구동기를 오래 도는 것으로 만들고 루프백에 `POST /approvals` 를 붙였다. `picasso` 는 그대로 라이브러리다. 여는 코드가 시험 소스에 사는 것이 그 성질을 지키는 방법이고, 파일 내보내기가 같은 자리에 있는 이유와 같다(§15.176).

    **제안을 넷 세운다.** 승인되는 것 하나와 거절되는 것 셋(가려짐·범위 밖·제안 없음). 거절을 종류로 가른 이유가 «다음 행동이 다르다» 인데, 하나만 세우면 그 차이를 값으로 볼 수가 없다. 그리고 첫 승인이 설 때까지 가상 시계를 세워 둔다 — 성공하는 승인은 **지금 든 것의 이름**을 관측에서 가져오므로 기체가 먼저 내려놓으면 그 출처가 사라진다. 거절 셋은 값 채우기 앞에서 판정되므로 시계와 무관하다.

    ★★**그리고 오래 돌리자마자 미믹이 죽었다.** 첫 승인 뒤 시계를 돌리는 순간 `CANCELLED: call already cancelled` 가 터졌다. 취소된 `WatchTask` 스트림에 밀면 gRPC 가 던지는데, 그 예외가 `settleAll` 을 통째로 끊어 **다른 기체의 시간까지 멈춘다.** 한 소비자가 떠났다는 사실이 세계를 세우는 것이고, 그 정지는 로그도 예외도 없이 «아무 일도 안 일어난다» 로 보인다 — `MimicServer.advance` 가 전진·반영·밀어내기 셋을 한 함수로 묶은 이유와 정확히 같은 자리다.

    시험에서는 안 보였다. **기다리던 것이 오면 시험은 곧 끝나므로 그 다음 전진이 없었다.** 오래 도는 것이 없으면 이 부류의 결함은 영원히 안 보인다. 떠난 관측자를 밀기 전에 지우도록 고쳤고, 기체 둘로 «떠난 쪽 때문에 남은 쪽의 갱신이 끊기는가» 를 든다.

    **선언 목록의 첫 구현체도 여기서 나왔다.** ADR 43 이 «배치의 결정» 이라 한 자리를 파일로 채웠고, 못 읽으면 **던진다** — 빈 목록으로 접으면 오타 난 파일이 «아무것도 선언 안 했다» 와 같은 모양이 되고, 그 배치는 자동 승인이 전부 거절되는 것을 정상으로 읽는다.

175. **승인이 조치를 조용히 떨어뜨릴 수 있는 자리 둘 — 하나는 막았고 하나는 못 만든다.**

    `commit` 이 `submit` 을 부르는데, 그 주문이 **이미 다른 기체에서 돌고 있으면** 접수가 멱등으로 접히고 조치 열은 안 나간다. 앞 판은 그것을 성공으로 냈다 — 승인했는데 아무 일도 안 일어난 것이 초록으로 보이는 모양이고, 이 저장소가 반복해 물린 조용한 통과 그 자체다. `REMEDY_NOT_APPLIED` 로 거절하고 기체 둘로 시험이 든다.

    **둘째 자리는 막지 않았다.** `revise` 는 조치 열 인자를 아예 안 받으므로, 개정 거절에서 난 제안을 승인하면 조치가 소리 없이 사라진다. 방벽을 하나 썼다가 **지웠다** — v1 에서 그 제안이 설 수 없기 때문이다. 파지를 바꾸는 스킬이 `pick_place` 하나뿐이라 그것이 막히면 풀 수단이 없고, 그래서 개정 거절은 `Found` 를 못 낸다. 만들 수 없는 상황을 막는 검사는 결함을 주입해도 아무 시험도 빨개지지 않는다 — `HoldEffects.compare` 에서 기대 쪽 검사를 안 든 것과 같은 규율이다. 대장(§15.175)에 적는 것이 그 자리를 들고 있는 방법이다.

174. **둘째 걸음이 들 것의 이름은 아무도 모른다.**

    값 채우기가 걸음마다 효과로 파지를 굴린다. 첫 걸음이 놓으면 그 다음 파지는 빈손이고, 빈손에는 이름이 없으므로 대상 참조를 요구하는 둘째 걸음은 거절된다. 굴리지 않으면 **지금 든 것의 이름이 둘째 걸음에까지 실려 나간다** — 그 값은 관측이 아니라 낡은 관측의 재사용이다. v1 의 조치 열은 전부 한 걸음이라 아직 안 물리지만, 물리는 날 그것이 조용히 틀리는 대신 거절이 되게 해 뒀다.

173. **«든 채다» 와 «무엇을 들었다» 는 다른 사실인데, 미믹으로는 그 차이를 못 만든다.**

    자동 승인이 대상의 이름을 관측에서 가져오므로, 기체가 «든 채» 라고만 답하고 이름을 안 주면 거절해야 한다. 그런데 이 층을 통해서는 그 상황이 안 나온다 — 하달된 단위가 대상 참조 파라미터를 싣고 미믹이 그것을 그대로 되울리기 때문이다. 실물 기체는 그렇게 답할 수 있다.

    그래서 **판정을 순수 함수로 떼어 냈다**(`RemedyValues`). 미들웨어도 포트도 시계도 안 보고 관측을 인자로 받으므로, 실물에서 못 만드는 관측을 시험이 직접 세운다. §15.170 의 `DEPTH_LIMIT` 과 같은 모양의 한계이고, 같은 방식으로 대장에 남긴다.

172. **자동 승인에 실을 값이 없었다 — 그리고 «어느 스킬» 은 범위가 아니었다.**

    받는 쪽이 자동 승인을 세우다 두 번 막혔다. 표면이 없고(프로세스 밖에서 부를 길이 없다), 부를 값이 없다. 뒤엣것이 깊었다. 실측하면 `profile/` 의 모든 스킬이 필수 파라미터를 받고, 조치의 걸음이 될 수 있는 것은 파지를 바꾸는 스킬뿐이라 전 카탈로그에서 `pick_place` 하나이며, 그것이 `object_id` 와 `destination` 을 받는다. 값은 사람이 준다는 것이 규율이므로 자동 승인은 **구조적으로 성립하지 않는 상태**였다.

    받는 쪽은 갈래 셋을 주면서 «파라미터를 받는 조치는 자동 승인 대상이 아니다» 와 «선언이 값을 함께 든다» 를 **대안으로** 놓았다. 대안이 아니다. 앞엣것의 기준(조치가 완전히 기술되는가)이 맞고, 뒤엣것이 **그 기준을 충족시키는 방법**이다. 선언된 효과만으로는 `pick_place` 가 어디에 놓는지를 말하지 못하지만, 효과와 선언된 값을 함께 두면 말한다.

    ★**결정의 이유는 그쪽이 든 것과 다르다.** 그쪽은 «값도 결국 사람이 준 것» 이라고 했다. 맞지만 약하다. 진짜 이유는 이것이다 — **`skillTypes` 는 어느 스킬인지를 묶을 뿐 무엇으로인지를 안 묶는다.** `pick_place` 자격 하나가 실제로 주는 권한은 «내려놓아도 된다» 가 아니라 «무엇이든 어디에든 놓아도 된다» 이고, 선언이 적은 범위와 실제 권한의 그 차이가 곧 이 자격이 존재하는 이유다. 프로세스 안에서는 안 드러났다. 부르는 쪽이 시험뿐이었기 때문이다.

    **값의 출처를 계약이 이미 가르고 있었다.** `is_object_reference` 가 붙은 칸은 **관측**에서(지금 든 것의 이름), 나머지 필수 칸은 **선언**에서. 지어내는 갈래는 없고, 어느 쪽도 못 채우면 거절이며 사람에게 남는다. 이 결합은 추정이 아니다 — 어느 칸이 대상의 이름인지는 계약이 말하고, 든 것의 이름은 기체가 말한다. 미믹이 같은 사실을 반대 방향으로 쓰고 있었고(파라미터에서 파지로), 그 읽는 자리를 `capability` 로 옮겨 한 곳으로 만들었다.

    ★★**그래서 문이 둘이 됐다.** 사람의 문은 값을 들고, 밖의 문에는 값을 실을 칸이 **없다.** 주문도 없다 — 제안 표가 주문을 함께 들게 해서, 승인은 «무엇을 하라» 가 아니라 «그것을 하라» 가 됐다. 앞 판은 주문을 인자로 받았고, 그 말은 부르는 쪽이 주문을 지어낼 수 있었다는 뜻이다. 승인 표면이 아니라 일반 접수 표면이었던 것이고, 밖에서 부를 길이 생기고 나서야 보였다. **부르는 쪽의 권한이 하나로 묶이는 것은 규율이 아니라 표면의 모양 때문이어야 한다** — 규율은 지켜지는 동안만 유효하고, 칸이 없는 것은 언제나 유효하다.

    **거절을 값으로 만들었다.** 사유 산문만 내면 읽는 쪽이 한국어를 문자열로 맞춰야 하고, 그 시험은 «거절됐다» 만 보므로 아무 거절에나 초록이 된다. 가르는 기준은 다음 행동이 다른가 하나뿐이다. 그리고 **가림을 맨 앞에 뒀다** — 뒤로 물리면 선언 없는 에이전트가 «자격 없음» 을 받고, 사람이 먼저 진단해야 한다는 지시가 답에서 사라진다.

    **관문 거절이 제안을 소모하던 것도 고쳤다.** 자리 경쟁으로 못 들어간 것은 자격의 문제가 아닌데 앞 판은 그 자리에서 제안을 지웠다. 조건이 풀려도 누를 것이 없다.

    지나가며 둘을 더 봤다. `revise` 가 조치 열을 떨어뜨리는 자리(§15.175)와, 실물에서 못 만드는 관측(§15.173) 및 둘째 걸음의 대상(§15.174).

171. **넘기려고 남의 저장소에 파일을 썼다 — 그 한 번이 경계의 증명을 지운다.**

    한 벌을 인계하면서 받는 쪽 저장소의 `tests/fixtures/` 아래에 파일을 직접 넣었다. 받는 쪽이 그것을 발견해 알려 왔고, 맞는 지적이다. **두 계층이 다른 저장소에 있고 서로를 쓰지 않는다는 것이 이 설계의 산출물 중 하나**인데, 편의로 한 번 넘어가면 그 증명이 사라진다.

    **더 나쁜 것은 그 판단의 경로다.** 「직접 넣으라는 거냐」는 말을 «내가 넣어도 된다» 로 읽었다. 그것은 편의에 대한 불만이었지 경계 규칙을 지우는 허락이 아니었고, **문서로 적힌 제약은 편의를 이유로 물러서지 않는다** — 물러설 수 있으면 애초에 제약이 아니다. 받는 쪽 문서가 이 유혹에 이름을 붙여 뒀다: *"여기서 바로 보내면 편한데"*.

    **자리를 만들었다.** `handoff/<받는 쪽>/` 에 두고 경로만 알린다. 넘기는 쪽은 자기 저장소만 건드리고, 무엇을 들일지는 받는 쪽이 정한다 — 다른 층이 이미 그렇게 하고 있었고 그 모양이 맞다.

    **인계본은 스냅샷이라 낡는다.** 인코더가 칸을 늘리거나 줄여도 커밋된 한 벌은 그대로 남고, 그러면 받는 쪽은 **없는 계약에 맞춘 수신기**를 든다. 그래서 커밋된 한 벌을 지금의 인코더로 다시 재서 **칸의 집합**만 대조한다 — `runId` 와 `wallClockAt` 은 구동마다 다른 것이 설계이므로 값은 안 본다. 값을 고정하면 그 시험이 설계와 싸운다.

    **주입 하나가 안 들어갔다.** 두 벌의 구동 식별자를 같게 만들려고 꼬리 숫자만 바꿨는데 앞의 시각이 달라 여전히 다른 값이었다. GREEN 을 시험 탓으로 읽을 뻔했다 — 규칙 ②(주입이 실제로 반영됐는가)가 먼저다.

170. **한 벌을 실물로 내놨더니, 규약이 아니라 도달 가능성이 걸렸다.**

    읽는 쪽이 인코더 소스를 읽어 수신기를 맞추고 있었다 — 성실히 읽어도 봉투 한 겹을 놓치면 아무도 모른다. 그래서 디스크에 한 벌을 놓고, **같은 시나리오를 두 번** 돌렸다. 두 벌의 해시가 같고 `runId` 만 다른 것이 그 값이 존재하는 이유 자체다.

    **기체를 넷으로 갈라야 했다.** 한 기체는 태스크를 하나씩만 드는데(§15.98) 처음엔 한 기체에 다 몰아 앞 실행이 뒤를 막았다. 시나리오가 «가능한 일들» 이 아니라 «동시에 가능한 일들» 이어야 했다.

    **`DEPTH_LIMIT` 은 못 만들었고, 그게 이번의 소득이다.** 탐색은 그 값을 낼 줄 아는데 이 층을 통해서는 도달하지 못한다 — 파지 값이 넷인데 효과가 내는 것은 빈손과 든 채 둘뿐이라, 너비 우선이 상한(세 걸음)에 닿기 전에 볼 것이 없어진다. **선언된 값이 실물에서 한 번도 안 나오는 자리**이고, 읽는 쪽이 그 갈래를 다뤄도 밟히지 않는다. §15.170 으로 걸었고 `AGENT-06` G3.2 에도 적었다.

    **잔여 파지 `UNSPECIFIED` 도 시나리오로는 안 나왔다.** 하달된 단위는 기체가 파지를 답하고, 하달 전에 실패한 것(출발 결품)은 사건을 열지 않는다. 다만 **기본값 출력이 서는지는 실물에서 확인됐다** — `objectRef` 와 `reason` 이 빈 문자열로 남아 있고, 표준 printer 였으면 그 키들이 통째로 빠진다. 단언을 그쪽으로 옮겼다.

    **결함을 넣는 시점이 한 번 틀렸다.** 이 층이 단위를 `RUNNING` 으로 적는 시점은 하달한 순간이고 기체가 실제로 도는 것은 그 다음 tick 이다. 그 창에 결함을 넣으면 기체가 받지 않는다 — 기체 쪽 상태를 봐야 했다. **두 시계가 다른 자리가 여기에도 있었다.**

169. **G2 를 대 봤더니 내 매핑이 후했고, 뒷절 둘이 안 걸려 있었다.**

    `AGENT-06` G2 의 여덟 경우를 picasso 쪽 시험에 대고 맞춰 봤다. **먼저 걸린 것은 코드가 아니라 내 매핑이었다** — 2.6 의 판정 기준이 «거부 + 에스컬레이션» 인데 내가 댄 시험은 반복이 지표로 나오는 것까지만 증명한다. 세어 보면 초록인데 그 초록이 다른 명제의 초록이었다. **대는 시험을 고르는 일도 주장이다.**

    **뒷절 둘이 비어 있었다.** 2.1 은 «거부. **사람에게 남는다**» 이고 2.8 은 «거부. **사람 승인으로 복귀**» 인데, 나는 앞절(거부)만 걸어 뒀다. 거부가 제안을 소모하면 자격이 없다는 이유로 사람까지 누를 것을 잃고, 만료가 라인을 세우면 아무도 만료를 짧게 걸지 않는다 — **자격은 좁히기만 하고 없애지 않는다** 가 뒷절의 내용이다. 둘을 시험으로 걸었고 주입이 문다.

    **2.4 는 안 지었고, 안 지은 데 이유가 있다.** 자동 승인한 조치가 실패하면 자격이 강등돼야 하는데, 관문은 선언을 **읽기만** 하고 선언의 주인은 밖이다(ADR 43). 강등을 이 층이 쓰면 소유하지 않은 값을 고치는 것이고, 억제를 이 층에 따로 두면 **자격의 원천이 둘**이 된다. 어느 쪽이 드는가는 결정이 필요한 자리라 짓지 않고 §15.169 로 걸었다.

168. **자격 관문을 지었고, 주입 아홉 중 둘이 시험 쪽 결함을 꺼냈다.**

    §15.165 가 적은 셋이 한 PR 로 들어왔다 — 승인 API 의 신원 인자, 선언 목록 포트, 승인자 종류별 이의율. ADR 43 이 이미 판정을 고정해 뒀으므로 지은 것은 그 판정이다.

    **사람에게는 선언을 요구하지 않는다.** 선언 목록이 있는 이유는 «사람 대신» 누르는 것을 허락하는 것이고, 사람까지 대조하면 그 목록이 운영자 명부가 된다 — 명부가 한 명 빠진 날 라인이 선다. 반대로 **선언 안 된 에이전트는 전부 거절**이고(deny by default), 그래도 라인이 안 서는 이유는 사람이 누를 수 있기 때문이다. 바닥 소유 대장이 반대 방향이었던 것과 대조된다 — 거기서는 «대장 없음» 을 막으면 라인이 서고, 여기서는 안 선다. **같은 규율이 자원마다 다른 기본값을 낸다.**

    **빈 집합을 «전부» 로 읽지 않는다.** 선언을 반쯤 적은 배치가 무제한 자격을 얻으면 그것이 가장 나쁜 실패다. 넓히려면 적어야 한다.

    **반복 카운터는 한 통, 이의율은 두 통.** ADR 43 §4 의 결정을 코드가 든다. 둘 다 시험이 고정하므로, 나중에 «일관성» 을 이유로 한쪽을 다른 쪽에 맞추려 하면 빨개진다.

    **승인자가 해시에 든다.** 다른 쪽이 승인한 같은 모양의 사건은 **다른 사건**이다 — 누가 눌렀는지가 그 사건에 대해 할 말을 바꾼다. 해시의 필드 목록이 «무엇이 결정적인가» 의 답이므로 늘리는 데는 이유가 필요했고, 이것이 그 이유다.

    **주입 아홉 중 둘이 GREEN 이었고 둘 다 시험이 틀린 것이었다.** 하나는 빈 집합 시험이 기체 범위와 조치 유형을 **한 번에** 비워 앞 관문이 뒤 관문을 가렸다 — 축마다 떼서 다시 지었다. 다른 하나는 «승인 없는 사건은 어느 통에도 안 들어간다» 를 **사건이 하나도 없는 세계**에서 물어 공짜로 통과했다 — 결함을 강제해 사건을 만든 뒤 다시 물었다. **전제가 빈 단언은 주입이 없으면 영원히 초록이다.**

    그리고 시험 전제가 한 번 더 틀렸다. 가려 둔 제안을 세우는 헬퍼가 `proposal()` 이 널이 아니라고 단정했는데, **가린 제안은 조회로 안 보이는 것이 설계다.** 코드가 맞았고 헬퍼가 틀렸다.

    신원이 위조 가능한 것은 §15.3 이 이미 의도적 제외로 적어 둔 자리다 — 새 한계를 만들지 않고 그것을 댄다.

167. **내보내기를 지었더니 규약의 한 줄이 규약만으로는 안 되는 것이었다.**

    §6 이 «계약 메시지는 protobuf JSON 규약» 이라 적었고 그것은 저장소의 기존 적재 표면과 같은 규약이다. 그런데 그 규약의 **기본 설정은 기본값 필드를 빼는 것**이다. 그대로 쓰면 `HoldState` 가 `HOLD_KIND_UNSPECIFIED` 일 때 통째로 `{}` 가 되어 **「빈손」과 「말하지 않았다」가 접힌다** — §15.145 가 막은 바로 그 일이 직렬화 한 겹 아래에서 되살아난다. 기본값도 적도록 바꿨다. **같은 규약을 쓰면서 설정 하나가 반대 방향이면, 문서의 「같은 규약」은 지켜진 채로 규율이 깨진다.**

    **구동 식별자 하나만 결정적이지 않다.** `digest()` 도 `incident-N` 도 같은 시드면 같은 값이고 그것이 재현의 근거인데, 읽는 쪽이 멱등 열쇠를 그 값들로 들면 같은 시드로 다시 돌린 두 번째 구동의 사건이 전부 «이미 본 것» 으로 접힌다. **그 고장에는 아무 신호가 없다** — 로그도 예외도 없이 처리 결과만 비고, 그것은 «아직 안 붙었다» 와 구별되지 않는다. 그래서 이 값 하나가 실 시계에서 나온다. 결정성이 규율인 저장소에서 **의도적으로 비결정적인 값**이라, 나중에 누가 «이것도 결정적으로» 라고 고칠 자리다. 시험이 그것을 든다.

    **인코딩과 전송을 갈랐다.** `LedgerExport` 는 문자열만 만들고 파일도 소켓도 자기 시계도 모른다. 그래서 순수 함수이고 **인프라 없이 시험이 문다** — 그것이 §6 에서 C 를 고른 이유의 절반이었고, 지어 보니 실제로 그렇다. 열 시험 중 아홉이 하네스 없이 돈다.

    **JSON 인지 눈으로 보지 않는다.** 줄마다 계약의 JSON 파서로 되읽어 구조로 단언한다. 그래야 따옴표 하나가 새는 것과 키가 조용히 빠지는 것을 같은 시험이 문다 — 사람이 적은 진단 사유가 그 줄로 나가므로 이것은 가정이 아니라 실제 경로다.

    **가림의 방벽이 적재면에도 있다.** `outcome` 이 `WITHHELD` 인 줄에는 `steps` 키가 **없다**. 값이 비는 것이 아니라 키가 없는 것이 답이고, 여기서 편의로 제안 표의 걸음을 꺼내 실으면 조회 한 번으로 가림이 풀린다. 코드의 방벽(§15.164)과 같은 성질을 한 겹 밖에서 든다.

166. **두 대장을 누가 담는가 — 「아직 호스트할 것이 없다」가 답을 정했다.**

    §6 이 «어느 쪽이 담을지는 배치 결정» 이라 적고 자리를 비워 뒀는데, 읽는 쪽이 별도 프로세스라 **규약은 있는데 실어 나를 주체가 없는** 상태가 됐다. 셋을 놓고 봤다 — 파일 내보내기(C) · 얇은 호스트 모듈(B) · `registry` 가 담기(A).

    **실측이 답을 좁혔다.** `src/main` 어디에서도 `Middleware` 를 세우지 않는다. 세우는 것은 시험 스물둘뿐이고, 포트 여섯 중 실물 구현체를 든 것은 `ClientRobotPort` 하나이며 나머지 다섯은 `None` 이다. **B 를 지금 세우면 다섯 개의 `None` 을 든 프로세스가 된다** — 호스트가 아니라 하네스의 다른 이름이다. 그러면 이미 그 일을 하는 것을 쓰는 것이 맞다.

    **A 를 미룬 이유는 폭발 반경이다.** `registry` 는 설정 평면이고 그 정지는 «새 어댑터 판을 못 올린다» 다. 거기에 실행 계층의 적재를 얹으면 정지가 «사건을 못 읽는다» 로 바뀐다. ADR 43 §3-1 이 `picasso → registry` 를 막은 논증의 거울상인데, **조회가 멈추는 것과 판정이 멈추는 것은 다르므로 같은 논증이 그대로 서지는 않는다.** 그래서 금지가 아니라 승격으로 뒀고, 조건 셋을 미리 적었다.

    **B 는 중간 계단이 아니다.** 진짜 배치가 생기면 그 호스트는 포트 여섯을 다 들어야 하고 그것은 배치 프로젝트다. 두 대장만 내는 프로세스는 존재 이유가 하나뿐이라 그날 폐기된다. 사다리는 C → A 이고 B 는 거기 없다.

    **규약에서 제일 무거운 줄은 가림이다.** `outcome` 이 `WITHHELD` 인 줄에는 `steps` 키가 없다 — 코드의 방벽(§15.164)과 같은 성질을 적재면에서도 든다. 내보내는 쪽이 편의로 제안 표에서 걸음을 꺼내 실으면 **조회 한 번으로 가림이 풀린다.** 구현 PR 이 이 줄을 주입으로 확인한다.

    **「없음」을 키 누락으로 적지 않는다.** 빼면 «값이 없다» 와 «이 판이 아직 그 필드를 안 낸다» 가 같은 모양이 된다. 이 저장소가 `null` 과 침묵을 가르는 것과 같은 규율이고, 빈 목록도 `[]` 로 적는다.

    **그리고 §15.166 이 일지 없이 대장에만 있었다.** PR 5 때 §15.160 이 그랬던 것과 같은 실수를 되풀이했다 — 대장의 id 가 일지 번호를 가리키는데 그 번호의 항목이 없었다. **이것을 잡는 시험은 여전히 없다.** 이 항목이 그 자리다. 그리고 파일 내보내기는 **사본이지 주인이 아니어서** §15.166 을 닫지 않는다 — 닫히는 것은 승격 때다.

165. **자격의 자리를 물었더니 포트였다 — 그리고 대장에 자원 하나가 빠져 있었다.**

    설계 문서가 자동 승인 선언 목록의 자리를 한쪽은 설정 파일로, 다른 쪽은 `registry` 로 적었다. 둘 다 아직 없어 어느 쪽도 선례가 아니었고, 실재하는 선례는 생성자 인자 하나였다 — `withholdEvery` 에서 시작해 이번 배선에서 결속 정본·비용 함수·재할당 정책·바닥 소유 대장·작업 구역까지 다섯이 같은 모양으로 들어왔다. **답은 「둘 중 하나」가 아니라 「둘 다 구현체다」였다.** 설정 파일이든 `registry` 든 이 층의 코드는 같으므로, 그 선택은 미뤄진 결정이 아니라 **배치의 결정**이다. ADR 43 이 그것이다.

    **§B5 의 근거는 살아남았다** — 프로파일은 기종의 사실이고 자동 승인 허용은 현장 정책이라 한 문서에 두면 기종 교체가 정책 변경을 동반한다. 실측도 같은 방향이다: 이 모듈은 `profile/` 을 직접 읽지 않는다.

    **포트 대장이 자격 조회 포트에 「반복 카운터」를 적은 것은 틀렸다.** 반복 횟수는 이 층이 이미 센다(`approvals`). 밖에 물으면 같은 수의 원천이 둘이 되고, 둘이 갈릴 때 반복 한도가 어느 값으로 걸리는지 판정할 수 없다. 포트가 답하는 것은 선언(유형·범위·만료)뿐이다.

    **반복 카운터는 승인자별로 가르지 않고 이의율은 가른다 — 반대인 것이 맞다.** 반복 카운터가 재는 것은 자원의 상태(같은 조치가 몇 번 반복됐나)이고 근본 원인은 누가 눌렀는지 모른다. 가르면 사람 다섯 번과 에이전트 다섯 번이 열이 아니라 다섯과 다섯이 되어 한도에 안 걸린다 — **지표가 자기 집계 방식에 진다.** 이의율이 재는 것은 판단 주체의 성능이고 에이전트 승인이 바로 그 시험 대상이라, 같은 통에 담으면 사람 승인이 희석한다. **가를지는 재는 대상이 자원인지 주체인지로 갈린다.**

    **그리고 자원 소유 대장에 자원 하나가 빠져 있었다.** §15.162 에서 «빈 칸이 없다» 라고 적었는데 그것은 **표에 올린 일곱 자원에 대해서만** 참이었다. 조치의 자동 승인 권한도 자원이고 소유자는 현장이며, 지금 `approveRemedy` 는 호출자 신원을 받지 않아 누구의 승인인지 남지 않는다 — 무승인 경로가 아니라 **무기명 승인 경로**다. 대장의 규율이 «표에 없는 자원으로 나가는 명령은 아무 관문도 통과하지 않은 명령» 이었으니 이것이 그 경우다. 행을 올리고 §15.165 로 걸었다.

    **조회면은 규약만 적었다.** 이 층은 라이브러리라 진입점도 영속화도 없고, 그것은 결핍이 아니라 경계다 — 프로세스를 들면 그 프로세스의 가용성이 실행 보증의 가용성이 된다. 이 층이 보증할 수 있는 것은 순서와 식별자뿐이며, 커서는 **마지막으로 읽은 식별자**이고 불투명한 값이다. `incident-N` 을 뜯어 번호를 꺼내는 코드는 대조 시험이 없는 형식에 묶인다. 적재의 무한 증가와 영속화 부재는 §15.166 으로 걸었다.

    **설계 §14 의 ADR 표가 39 에서 멈춰 있었다.** 40·41·42 가 `docs/adr/` 에는 있고 표에는 없었다 — 색인이 «번호는 §14 의 행 번호» 라 적고 있는데도. 대는 시험이 없는 표는 낡는다(README 의 한계 수가 그랬던 것과 같다). 네 행을 채웠다.

164. **탐색이 답한 것을 대장으로 냈다 — 「못 찾았다」를 계산한 자리에서 버리고 있었다.**

    `RemedySearch` 의 답은 셋인데(`Found` · `None(NO_CAPABILITY)` · `None(DEPTH_LIMIT)`) 제안 표에 남는 것은 `Found` 뿐이었고 나머지는 계산된 자리에서 사라졌다. 가림까지 세면 밖에서 갈라야 할 것이 셋인데 밖으로 나가는 값은 «제안이 없다» 하나였다 — 「이 기체로는 안 된다」와 「더 찾아보면 있을 수 있다」와 「사람이 먼저 진단하라」가 같은 침묵이 됐다. **새 사실을 만들지 않았다.** 이미 계산된 답을 그때의 두 시각과 함께 옮겨 실었을 뿐이고, ADR 40 의 파생값과 같은 모양이다.

    **거절은 사건 번들이 될 수 없다.** 탐색은 접수 관문 안에서 돌고, 관문이 막으면 실행이 안 생긴다 — 실행 식별자도 근거 창도 막는 결함도 없다. 그런 번들을 열면 빈 창이 «창은 완전한데 아무 일도 없었다» 로 읽히고(§4.3 이 금지한 바로 그것), 검토율의 분모가 거절 수만큼 늘어 §7.2 의 지표가 희석된다. 그래서 번들에 칸을 더하지 않고 대장을 따로 뒀다. 열쇠도 (기체, 주문)이다 — 그것이 탐색의 입력이기 때문이다.

    **가린 것의 걸음은 대장에 안 싣는다.** 조회면이 가린 내용을 내면 조회 한 번으로 가림이 풀리고 의도적 비자동화가 그 자리에서 무의미해진다. `Withheld` 가 값을 안 갖는 것은 누락이 아니라 성질이다.

    **적는 자리는 `record` 하나다.** 관문은 순수 술어라 후보 셋에 물어봐도 아무것도 안 쌓이고(§15.161), 쌓는 것은 실제로 그 기체에 내려 본 쪽이다. 그래서 이 대장은 «무엇을 물어봤나» 가 아니라 «무엇을 시도했고 무엇을 답받았나» 다. 대가는 미배정으로 끝난 순위 목록의 탐색 결과가 안 남는다는 것이고(§15.164), 넓히는 것은 L1 이 실제로 붙을 때다.

    **미믹에서 「없다」를 만들 수 있게 픽스처를 하나 더 뒀다.** 기존 픽스처는 `pick_place` 에 전제가 없어 든 채로도 늘 대안이 나온다 — 그것만 있으면 「없다」의 경로가 시험에서 한 번도 안 밟히고, 그러면 이 변경이 담보하는 것의 절반이 검증되지 않는다. 새 픽스처는 `pick_place` 도 빈손을 요구해서 든 채로는 딛을 스킬이 없다.

163. **작업 공간을 구역 이름으로 다뤘다 — 반경으로 지었다면 항상 통과했을 것이다.**

    두 기체의 작업 반경이 겹치면 그것도 셀 전용 자원의 경쟁이다. 그런데 이 저장소에는 좌표도 반경도 없고 미믹에 기하가 없어 **반경 겹침을 틀리게 만들 방법이 없다** — 그렇게 지으면 항상 통과하는 시험이 된다. 공간을 구역 이름으로 다루면 맞대 볼 토큰이 되어 시뮬레이션에서 진짜로 검증된다. 자리 결속의 좌표 칸(§15.156)과 바닥 소유자(§15.162)를 같은 이유로 이름으로 다뤘다. **세 번째다.**

    **`destination` 의 두 뜻이 여기서는 둘 다 센다.** 슬롯 점유는 «아는 것을 놓는 자리» 만 셌는데(§15.159), 공간은 놓을 자리든 갈 자리든 기체가 차지한다. 같은 필드를 자원마다 다르게 세는 것이고, 자원의 성질이 다르기 때문이다. 점검 순회의 `navigate_to` 가 그 경계에 있다 — 자재를 안 나르지만 구역은 차지한다.

    **같은 기체는 안 본다.** 한 기체가 두 자리에 동시에 있을 수 없고 그 배타는 발신자가 든다. 여기서 또 막으면 한 기체가 같은 구역에서 이어 일하는 것이 불가능해진다.

    **모름을 값으로 두지 않았다.** 구역 밖 자리를 «어느 구역도 아니다» 라는 하나의 값으로 묶으면 **구역 밖 자리들이 서로 하나의 구역이 되어** 대장에 없는 두 자리가 서로를 막는다. 세는 쪽과 대는 쪽이 같은 함수(`zoneOf`)를 쓰게 해서, 한쪽만 «모름» 을 다르게 다루는 날이 오지 않게 했다.

    남은 것은 성김이다(§15.163) — 같은 구역 안에서 실제로 겹치는지는 안 본다. 잘게 나누는 것은 현장의 수단이고, 기하로 가르는 것은 실물이 붙을 때다.

162. **안 만든 것에도 자리를 줬다 — 소유자 없는 바닥으로는 명령이 안 나간다.**

    걷는 기체가 셀을 나가면 그 바닥의 소유자가 없다. 플릿은 자기 AMR 만 승인하므로, 이 층이 내는 명령이 **아무 관문도 통과하지 않고** 물리 세계로 나갔다. 선택지는 둘뿐이다 — 소유자를 만들거나, 그 자원을 쓰지 않거나. 무승인 통행을 허용하는 셋째는 없으므로 `unownedFloor` 가 접수에서 막는다.

    **기하가 아니라 이름으로 다룬다.** 이 저장소에는 좌표도 반경도 없다. 바닥을 구역 이름으로 다루면 맞대 볼 토큰이 되어 시뮬레이션에서 진짜로 검증된다 — 좌표였다면 미믹에 기하가 없어 항상 통과하는 시험이 됐을 것이다. 자리 결속의 좌표 칸을 안 들인 것과 같은 판단이다(§15.156).

    **「주인이 없다」와 「대장이 없다」를 갈랐다.** 뜻이 반대다. 접으면 대장을 안 붙인 현장이 통째로 서고, 그러면 이 관문이 곧 꺼진다. 대장에 없는 자리도 «선언 안 됨» 이다 — 모르는 것을 무소유로 단정하지 않는다.

    **한 단위만 무소유여도 주문 전체를 막는다.** 앞 단위가 나가고 뒤에서 막히면 기체가 도중에 선다.

    통로 소유자를 정하는 절차(D 물리 분리 → C 구간 배정 → B 조정층 → A 벤더 흡수)와 승격 트리거(겹치는 구간 셋 초과 또는 바닥 공유 벤더 셋 이상)를 문서로 확정했다. **조건을 먼저 적어 두면 나중에 즉흥적으로 결정되지 않는다.** 구간 예약 포트는 규약만 적고 코드로 두지 않았다 — 소비자가 없으면 짓지 않는다(ADR 9). ADR 42 가 그 결정이다.

161. **재할당에 진동 방지를 붙였다 — 문턱만으로는 왕복을 못 막는다.**

    더 싼 기체가 보일 때마다 옮기면 비용이 1 흔들릴 때마다 두 기체 사이를 왕복하고, 그동안 아무 일도 진행되지 않는다. **라인을 멈추지 않는 것이 최적성보다 우선한다.** 막는 장치가 셋이다 — 도는 단위, 최소 유지 시간, 이득의 문턱.

    **문턱 하나로는 부족하다.** 일이 A 를 떠나면 A 의 부담이 그만큼 줄어 **반대 방향의 이득이 곧바로 생긴다** — 문턱을 넘어 옮긴 직후에는 되돌아오는 쪽도 문턱을 넘는다. 최소 유지 시간이 그 자리를 막고, 시험이 두 장치를 따로 고정한다(옮긴 직후 · 시간이 지난 뒤).

    **동점에서는 안 옮긴다.** `<` 가 아니라 `<=` 다 — 동점에서 움직이는 것이 진동의 시작이다.

    **옮길 일 자신을 양쪽에서 뺀다.** 안 빼면 지금 든 쪽이 그 일 하나만큼 늘 불리해 보여, 이득이 없어도 옮기는 쪽으로 기운다. 결함 주입이 그 자리를 잡았다.

    **도는 단위는 안 옮긴다.** 물리적으로 움직이는 중인 일을 옮기면 한 물건에 소유자가 둘이 된다. 트리거가 사건 번들이 생기는 자리(실패 판정·깨진 전제·잔여 파지)라 실제로는 그때 도는 단위가 없다.

    **언제 물을지는 이 층이 정하지 않는다**(ADR 41). 여기가 답하는 것은 «옮겨도 되는가» 뿐이고, 부르는 것은 배정 정책의 몫이다. 부담을 실행 수로만 재는 것은 한계로 남겼다(§15.161) — 단위 열 개짜리 실행과 하나짜리 실행이 같은 무게로 센다.

    시험을 쓰다가 하나를 잘못 짚었다. **관문은 모르는 기체를 막지 않는다** — *"권위는 발신자다. 여기서 막는 것은 알고도 보내는 일뿐"* 이라 능력을 못 물은 기체의 조건은 지어내지 않는다. 그 성질을 시험으로 고정했다.

160. **배정 관문을 순수 술어로 떼고 제안 포트를 붙였다 — 관문이 부작용을 갖고 있었다.**

    설계안 §7 이 관문의 규칙으로 *"상태를 바꾸지 않고 후보를 고르지 않는다"* 를 적었는데, **그때 관문은 이미 상태를 바꾸고 있었다.** 사슬 검사가 거절을 만들면서 제안을 기록하고(`proposals[key]`), 제안 수를 올리고(`proposalsMade += 1`), 가림 차례를 계산해 가려 뒀다. 후보 셋에 물어보면 제안이 셋 쌓이고 가림 차례가 세 칸 돌아간다 — **묻는 것이 곧 결정이었다.**

    계산과 기록을 갈랐다. `admits` 는 순수하고(탐색 자체는 부작용이 없으므로 안에 남는다), `record` 가 제안을 남기며 **채택을 시도한 쪽만** 그것을 부른다. 물어본 것과 내려다 막힌 것은 다른 일이다.

    **채택 시점에 관문을 다시 묻는다.** 순위가 만들어진 뒤 그 기체의 상태가 바뀌었을 수 있고, 그 창은 통신 지연이 아니라 읽는 자와 쓰는 자가 다르기 때문에 생긴다(같은 프로세스에서 스레드만 나눠도 남는다). 낡은 제안은 여기서 걸린다.

    **전부 떨어지면 미배정으로 남긴다.** 재계산을 요청하면 이 층이 배정기와 관문 사이의 중재자가 되고, 그 자리에 재시도 고리가 생긴다. 대신 후보마다의 사유를 함께 낸다 — 없으면 배정기가 무엇을 고쳐야 하는지 모른 채 같은 목록을 다시 보낸다.

    **비용의 항은 이 층이 아는 것뿐이다.** 진행 중 실행 수와 파지 여부 둘이다. 이동 거리나 잔여 전력은 여기 없다 — 이 층은 좌표도 기체 내부도 모르고(기종 비인지), 지어낸 항을 넣으면 가중치가 아무 의미 없는 수를 곱한다. 같은 비용이면 이름으로 가른다: **재생이 결정적이어야 한다.**

    ADR 41 로 남겼다 — *"배정 정책은 밖에 둘 수 있으나 채택 결정은 소유자가 tick 안에서 내린다."* 나중에 반드시 다시 물어볼 결정이다.

159. **셀 자리의 점유를 축으로 올렸다 — 그리고 그것이 계약의 주어가 아님을 확인했다.**

    상태 축이 파지 하나뿐이라 이 층은 «저 자리가 지금 쓰이는가» 를 몰랐다. 두 주문이 같은 슬롯을 목적지로 삼아도 둘 다 접수됐고, 출발 자리가 비어 있어도 하달한 뒤 로봇이 빈 자리에서 집으려다 실패했다. `occupancyViolation` 이 **자리 경쟁**과 **출발 결품** 둘을 하달 전에 본다.

    **점유는 계약의 사전 조건이 될 수 없다.** 설계안은 이 축이 대안 탐색을 깊게 할 것으로 적었는데, 탐색이 도는 것은 **계약이 선언한 능력 전이**다. 계약의 `PreconditionSubject` 는 *"조건이 보는 로봇 상태"* 이고 값이 늘려면 *"관측하는 어댑터"* 가 있어야 한다고 계약 자신이 적어 뒀다. 슬롯 점유를 보는 것은 어댑터가 아니라 셀 설비(E2)다. 그래서 점유는 **이 층의 관문**이고, 탐색의 상태 공간은 넷 그대로다. 재할당의 전제가 이 축이라는 설계안의 판단은 그대로 맞다 — 배정 관문이 읽을 축이기 때문이다.

    **`destination` 이 두 뜻을 지고 있었다.** «놓을 자리» 와 «갈 자리» 다. 점검 순회의 `navigate_to` 도 목적지를 드는데 그 자리를 채우지는 않는다. 목적지만 보고 점유로 세니 **같은 설비를 두 번 살피는 주문이 서로를 막았고**(기존 시험 둘이 그 자리에서 빨개졌다), 점유를 «아는 것을 놓는 자리» 로 좁혀 갈랐다. 가르는 표시는 `expectedIdentity` 다.

    **플릿 위임은 이 관문이 안 본다.** 처음에는 모든 단위의 출발 자리를 봤는데 용기 인수의 불일치 보고 시험이 빨개졌다 — 플릿이 인수 시점에 대조해 불일치를 보고하는 것이 계약의 약속이고, 여기서 막으면 그 경로가 영영 안 밟힌다. `Route.ROBOT` 으로 좁혔다.

    **결품 판정은 «말이 없으면 판정하지 않는다».** 「비었다」와 「신호 없음」을 접으면 관측 경로가 죽은 셀이 통째로 선다. 다른 자리 제시도 같은 규율이다 — 셀이 그 질문에 답하지 않으면 빈 목록이 아니라 널이 흐른다. 빈 목록으로 접으면 못 물어본 것이 재고 부족으로 읽힌다.

    **점유에 유효 기간을 두지 않았다**(§15.159 로 등록). 실행의 생애로 대신한다 — 시각으로 만료를 두면 아직 도는 실행의 목적지를 남에게 내줄 수 있기 때문이다. 대가는 오래 끄는 실행이 그만큼 자리를 잠그는 것이고, 그것을 가르려면 «이 자리는 곧 쓰인다» 와 «잡아만 뒀다» 를 구별할 진척도 관측이 있어야 한다.

    **등가 변이가 또 하나 있었다.** 자리를 놓는 조건이 실행의 종착과 단위의 완료 둘이었는데, 시험이 «성공으로 끝난 실행» 만 보고 있어 **실행 필터를 빼도 아무것도 빨개지지 않았다.** 실패로 종착하는 경우(계획 단계에서 `NO_SOURCE` 로 죽는 주문)를 시험에 더하니 그때부터 물었다. 필터 둘 다 뜻이 있고, 없던 것은 시험 쪽이었다.

158. **파지 값에 방벽을 세웠다 — 값이 늘면 컴파일이 깨진다.**

    미믹의 `holdOf` 는 태스크 상태에 대해 `else` 없이 분기하며 *"상태가 늘면 여기서 컴파일이 깨져야 한다"* 고 적어 두었는데, **파지 값에는 같은 방벽이 없었다.** 파지를 보는 자리가 전부 `if` 비교라 값이 늘면 조용히 빠진다 — `compare` 의 마지막 `else -> null`, 대안 탐색의 시작 상태 검사, 시험이 손으로 적은 `listOf(NOT_OBSERVABLE, UNSPECIFIED)` 가 그것이다.

    부류를 정하는 자리를 하나 뒀다(`HoldKinds.kt` 의 `isConcreteObservation`, `else` 없는 `when`). `compare` 의 어긋남 모양도 `else` 를 버렸다 — 구체 관측이 셋이 되는 날 `HoldMismatch` 둘로는 모자라고, 그 사실이 거기서 컴파일 오류로 선다. 대안 탐색과 시험은 이름을 다시 적지 않고 분류에서 파생한다.

    **실측으로 확인했다.** 계약에 `HOLD_KIND_PARTIAL = 4` 를 임시로 넣으니 두 자리가 *"'when' expression must be exhaustive"* 로 깨졌다. 같은 값을 **이 변경 이전 코드**에 넣으면 빌드가 초록이고, `compare(HOLDING, PARTIAL)` 이 «어긋남 없음» 으로 나간다 — 이 PR 이 닫는 조용한 통과가 그것이다.

    **첫 시도는 초록이었다.** 주입을 넣고 `:capability:compileKotlin` 을 돌렸는데 오류가 없었다. 생성된 `HoldKind.java` 에는 새 값이 있었지만 `:contracts:compileJava` 가 UP-TO-DATE 로 넘어가 **컴파일러가 보는 클래스에는 주입이 도달하지 않았다.** 이 저장소의 추적 순서(① 시험이 돌았나 ② 주입이 들어갔나)가 그대로 적용되는 자리이고, `--rerun-tasks` 로 다시 돌려 확인했다.

    **관문 절반이 등가 변이였다.** 처음에는 `compare` 가 기대와 관측을 둘 다 막았는데, 기대 쪽을 빼도 아래 `when` 이 같은 답을 내어 **아무 시험도 빨개지지 않았다.** 아무도 구별할 수 없는 검사는 들고 있지 않는다 — 빼고, 그 자리를 `when` 의 비구체 가지가 맡는다.

    `PreconditionCheck.holdViolation` 은 그대로 뒀다. 거기 손으로 적힌 두 값은 **사정 문구를 고르는 데 쓰이고 `else` 가 위반을 내므로**, 값이 늘어도 조용히 통과하지 않는다. 막을 것이 없는 자리에 방벽을 세우면 방벽이 흔해지고 흔해지면 안 본다.

157. **축을 둘로 늘렸다 — 지도가 그대로여도 개체가 바뀌면 같은 이름이 다른 자세다.**

    §15.155 를 닫는다. 지도 판만 보던 검사가 **기체 교체를 통과시키고 있었다** — 세계 모델을 복원해도 티칭의 기준이 달라지는데, 같은 지도에서 배운 결속이라 판이 일치했다. `SiteBinding` 에 캘리브레이션 판을 싣고 같은 규칙으로 판정한다.

    **거절을 축으로 갈랐다**(`SITE_BINDING_STALE_MAP` · `SITE_BINDING_STALE_CALIBRATION`). 접으면 운영자가 지도를 다시 찍을지 개체를 다시 티칭할지 고를 수 없다 — 계약의 자리는 둘 다 `PRECONDITION_UNMET` 이지만 사람이 할 일이 다르므로 이 층의 어휘는 갈라야 한다.

    **둘 다 어긋나면 지도를 먼저 낸다.** 지도를 다시 찍으면 티칭도 따라 하게 되므로 상류부터 고치게 하는 것이 순서다. 다만 사정에는 **어긋난 축을 전부 적는다** — 캘리브레이션이 어긋난 사실을 감추면 지도만 고치고 같은 거절을 한 번 더 받는다.

    **축 하나만 붙은 정본을 정상으로 둔다.** 캘리브레이션 판을 내놓는 정본과 지도 판을 내놓는 정본이 같으리라는 보장이 없어서, `activeCalibration()` 의 기본값이 `NotConfigured` 다. 순서 때문이 아니라 배치의 사실이다.

    `ActiveMap` 을 `ActiveRevision` 으로 고쳤다 — 축 둘이 같은 세 갈래를 쓰는데 이름이 지도만 가리키고 있었다. 검사 9번의 탐색어는 선언 파일에서 유도하므로 개명이 저절로 따라왔다.

155. ~~**자리 이름이 어느 지도에서 배운 것인지 아무도 안 본다.**~~ **(닫힘 — §15.157)** 아래는 그때의 기록이다.

    ADR 35 가 결속을 기체 세계 모델에 맡긴 뒤로 어댑터는 이름을 그대로 넘기고 기체가 푼다. 그런데 **그 이름이 언제 어느 지도에서 등록된 것인지는 어디에도 없다** — `mapId`·`mapVersion` 류의 낱말이 저장소 전체에 0 건이다. 지도가 갱신되거나 개체가 교체되면 같은 이름이 다른 자리를 가리키는데 상위가 그것을 모른 채 명령을 낸다. VDA 5050 이 좌표마다 기준 지도를 달게 한 것이 정확히 이 경로를 막기 위해서다. 판정 로직만이라 시뮬레이션에서 완전히 검증되는 종류이고, §15.155 로 등록한다.

152. **일부러 답을 가렸고, 같은 우회가 반복되는 것을 시스템이 스스로 고발하게 했다.**

    설계안 §7.2 넷째와 §7.3. 남은 장치 둘이고, 이로써 §7 에서 코드가 할 수 있는 것은 다 했다.

    **의도적 비자동화.** 몇 번에 한 번 제안을 가리고 사람이 먼저 진단하게 둔다. 가리는 것은 비용이다 — 그 사건에서 운영자가 더 오래 걸린다. 비용임을 알고 지불하는 결정이어야 유지되므로 기본값을 두지 않고 배치가 명시하게 했다. **0 을 고르는 것도 결정이다.** 그리고 «가렸다» 와 «대안이 없다» 를 다른 답으로 낸다 — 접으면 의도적 비자동화가 능력 부재와 구별되지 않아 운영자가 시스템을 고장으로 읽는다. 상한 초과와 능력 부재를 가른 것(§15.149)과 같은 자리다.

    순서가 뒤집히지 않게 했다. 제안을 보고 나서 적으면 그것은 진단이 아니라 동의다. 그래서 가려 둔 건은 진단을 적어야 풀리고, 이미 적어 둔 건은 다시 가리지 않는다 — 같은 값을 두 번 요구하면 학습이 아니라 절차다.

    **반복 승인은 결함 신호다.** 임시 대안이 매끄럽게 작동할수록 근본 원인을 고칠 압력이 사라진다. 그리퍼를 교체해야 하는데 우회 경로가 매번 잘 돌아가면 아무도 교체하지 않는다. 같은 걸음 열이 같은 기체에서 거듭 승인되면 그 사실을 올린다. 한 번은 조치이고 두 번부터가 양식이라 문턱을 둘에 뒀다 — 한 번에 고발하면 지표가 곧 소음이 되고, 소음이 된 지표는 아무도 안 본다.

151. **자동 진단을 사람이 감사하기 시작했다 — 그리고 이의율 0 이 거짓말이 되지 않게 타입으로 막았다.**

    설계안 §7.2 의 첫째·둘째·셋째. §7 은 기능이 아니라 정책이고, 없으면 앞의 기능이 시간이 지나며 해로워진다 — 같은 분류가 오백 번 나왔는데 그중 오십 번이 오판이어도 아무도 모른다. PR 1 이 자리만 냈던 «사후 확인» 을 **판정(동의·이의)** 으로 바꾸고, 그 위에 지표를 얹었다.

    **이의율만 내면 안 된다.** 아무도 안 읽으면 이의가 0 이고, 그것은 완벽해진 것이 아니라 아무도 안 읽는 것이다. 그래서 읽은 것이 없을 때 이의율은 `null` 이다 — 0 을 돌려주면 그 자리에서 «완벽하다» 로 읽힌다. 검토율과 함께 보라는 규율을 주석이 아니라 타입이 든다. 결함 주입에서 이 한 줄을 0.0 으로 바꾸는 것만으로 지표 전체가 거짓이 됐다.

    분모도 마찬가지다. 이의율의 분모는 전체가 아니라 **읽은 것**이다. 전체로 나누면 안 읽을수록 이의율이 낮아져, 읽지 않는 것이 좋은 성적으로 보인다.

    **판단 경로를 함께 싣는다.** 분류만 내면 아무도 배우지 않는다. 번들이 «무엇을 기대했고 무엇을 봤는가» 를 든다 — 어긋나지 않았을 때도 기대를 남긴다. 왜 아무 판정도 안 났는지까지 따라갈 수 있어야 한다. 근거를 내는 이유는 신뢰가 아니라 학습이다.

    남은 장치 둘(의도적 비자동화, 반복 승인 지표)과 코드가 셀 수 없는 계량 하나는 아래에 적는다.

150. **제안이 승인을 거쳐야만 돈다 — 그리고 제안이 붙을 자리는 하나뿐이었다.**

    설계안 §6.4. 대안은 제안이고, 승인 없이 실행되는 경로는 만들지 않는다. 승인은 사람의 책임 있는 행위이므로 **조치가 요구하는 값도 사람이 준다** — 탐색기는 어느 스킬을 딛을지까지만 계산한다. 어디에 놓을 것인가를 지어내면 승인은 무엇을 승인하는지 모르는 채 누르는 단추가 된다. 승인은 서 있는 제안이 있을 때만 받고, 받은 제안은 지운다.

    **제안이 붙는 자리는 계획 시점 거절이다.** 설계안은 운영자 판단 상태에 붙는다고 적었는데, 그 상태에 이르는 길(발신자의 `PRECONDITION_UNMET`)에는 이 층의 **관측이 없다.** 계약의 스냅샷은 파지를 싣지 않고 `WatchTaskResponse` 만 싣는다 — 도는 단위가 없으면 파지를 볼 길이 없고, 거절의 참조는 주어만 주지 관측값을 주지 않는다. 관측과 선언이 둘 다 있는 자리는 계획 시점 사슬 검사 하나다. 그래서 제안은 `Submission.Rejected` 에 실리고, 승인은 그 주문을 조치 열과 함께 다시 접수한다.

    이것이 «관측 없이 판정하지 않는다» 의 네 번째 적용이다(§15.144 접수, §15.148 어긋남, §15.149 탐색, 그리고 여기). 발신자 거절 경로에 제안을 붙이려면 관측을 실을 자리가 계약에 있어야 하고, 그것은 소비자가 생긴 뒤의 일이다(ADR 9).

    조치 열을 앞에 세우는 길은 `approveRemedy` 하나뿐이다 — `submit` 의 앞자리 인자는 private 이라 밖에서 채울 수 없다. 담보를 시험 하나가 아니라 **구조**가 든다.

149. **대안을 계산하기 시작했다 — 그리고 «못 찾았다» 와 «없다» 를 갈랐다.**

    설계안 §6. 사전 조건과 효과가 선언에 들어온 뒤로 «이 조건을 참으로 만들려면 무엇을 해야 하나» 는 상태 공간의 문제가 됐다. 능력 하나가 전이 하나이고, 조건이 적용 가능성이고, 효과가 결과 상태다. 계획 시점 사슬 검사의 역방향이며, 적용 가능성은 그 검사를 그대로 부른다 — 두 곳이 다른 답을 내면 계획과 제안이 갈린다.

    **호출자 없이 병합한다.** 규칙을 단위 시험으로 고정한 뒤 경로를 붙인다(§9). 순서를 바꾸면 탐색의 오류와 경로의 오류가 섞인다.

    **상한 초과와 능력 부재를 다른 사유로 답한다.** 첫 판은 둘을 한 사유로 접었고, 상한을 0 으로 준 시험이 그것을 «능력 없음» 으로 받았다. 운영자에게 이 둘은 다른 말이다 — 하나는 «범위를 넓히면 있을 수 있다» 이고 하나는 «이 기체로는 안 된다» 다. 못 채운 조건도 함께 싣는다. 빈 목록으로 접으면 둘이 또 같아진다.

    **못 보는 것은 조치로 못 덮는다.** 관측이 `NOT_OBSERVABLE` 인 상태에서 탐색하면, 선언된 효과가 «이제 빈손이다» 라고 답해 관측 불가를 한 걸음으로 지워 버린다. 첫 판이 그렇게 답했고 시험이 잡았다. 효과는 파지를 바꾸지 관측 가능성을 바꾸지 못한다 — §5.2 와 같은 자리다.

    깊이 상한은 성능이 아니라 책임의 문제다. 셋을 넘는 조치 열은 승인 시점에 검토할 수 없고, 검토되지 않는 제안은 제안이 아니라 자동 실행이다. 계약을 안 올리는 이유와 함께 ADR 40 에 적었다.

148. **효과가 관측을 만나 «모른다» 를 줄였다 — 기대는 종료 시점이 아니라 중단 시점의 것이다.**

    설계안 §5. 결함 통지는 «스킬이 실패했다» 까지만 말하고 적재가 어디 있는지는 말하지 않는다. 카탈로그의 효과가 있으면 그 자리를 채울 수 있다.

    **첫 판은 기대를 종료 시점의 효과로 잡았다가 물렸다.** `after(pick_place)` 는 빈손이므로, 쥐고 실패했는데 빈손인 경우가 «정상» 으로 읽혔다 — 설계안 §1.1 이 든 예와 §10.1 둘째 시나리오가 겨냥한 바로 그 경우다. 표가 규범이라고 단정한 것이 근거 없는 판단이었다: 문서 안에서 §5 를 말하는 자리가 셋인데 둘이 중단 시점을 가리킨다. 기대는 **어디까지 갔는지**가 정한다 — 놓기까지 마쳤으면 효과대로, 쥐었다가 놓지 못한 채 끝났으면 든 채, 쥔 적이 없으면 기대할 것이 없다(`expectedAtEnd`). 그래서 단위마다 «한 번이라도 쥔 것을 봤는가» 를 든다.

    이 기준에서 표의 두 줄이 모두 `pick_place` 하나로 도달한다. 쥐었다가 빈손으로 끝나면 적재 유실이고, 놓았다는데 들고 있으면 미완료 파지다. 첫 판이 «첫째 줄은 카탈로그에 대상이 없다» 며 한계 대장에 올린 항목은 해석이 틀려서 생긴 것이라 지운다 — 남겨 두면 없는 한계를 문서가 주장한다.

    **성공으로 끝났는데 어긋난 경우에도 사건을 연다.** 하류는 끝났다는데 손에 남아 있다. 사건을 안 열면 그 사실이 어디에도 안 실린다. 미믹은 성공에 언제나 빈손으로 두므로 그리퍼가 걸린 기체를 시험 쪽 포트로 세웠다.

    **관측이 없으면 판정하지 않는다.** `NOT_OBSERVABLE` 과 침묵은 둘 다 판정 대상이 아니다. 여기서 뚫리면 선언을 근거로 현실을 단정하는 것이 되고, 관측 경로가 죽었을 때 고장난 기체가 멀쩡해 보인다 — 이 저장소가 파지·진행률·결함에서 반복해 거절한 접기다.

147. **사건 하나를 한 자리에 묶었다 — 그리고 설비 신호가 그동안 버려지고 있었다.**

    `INCIDENT_AND_REMEDY_PROPOSAL.md` §4 의 첫 조각. 실패의 사실은 이미 각자의 자리에서 답하고 있었다 — 어느 단위가 왜 멈췄나, 무엇이 실행을 막나, 로봇이 무엇을 들고 있나. 없던 것은 **한 시점의 한 사건으로 묶인 형태**다. `IncidentBundle` 이 그 열 가지 사실에 그때의 근거 창을 붙인다. 새 판단은 하나도 안 한다 — 실패 분류도 잔여 파지도 조건 위반의 주어도 전부 옮겨 싣는 것이고, 다시 계산하면 두 곳의 판정이 어긋난다.

    **설계안 §12 가 물은 여덟 중 둘이 문서의 가정과 달랐다.** 근거 창을 새로 만들어야 하는 줄 알았는데 로봇 이벤트는 이미 `Execution.eventTrail` 이 들고 있었다 — 버려지던 것은 **설비 신호**뿐이었다. `cell.observe()` 를 세 자리에서 부르고 판정에 쓴 뒤 그대로 버려, 사건을 읽을 때 "그때 설비는 무엇을 봤나" 가 영영 없었다. `observeCell()` 이 그 조회를 자취에 남긴다. 그리고 운영자 판단 상태가 계약 밖(미들웨어 모델)이라, 제안 경로가 계약 마이너를 올릴 필요가 없다는 것도 여기서 확인됐다.

    **번들은 전이 순간이 아니라 라운드 끝에 봉한다.** 단위가 닫히는 그 자리에서는 «무엇이 다음 단위를 막는가» 가 아직 안 정해져 있다 — `blockingFaults` 는 다음 단위를 재려 할 때 돈다. 그 자리에서 묶으면 차단 축이 늘 비어, 번들이 «단위의 문제인가 기체의 문제인가» 를 가르지 못한다. 그래서 닫는 여덟 자리는 표시만 하고 `sealIncidents()` 가 봉한다.

    해시는 실 시계와 사후 확인만 뺀다. 설계안은 식별자도 빼라고 했는데 이 층의 식별자는 전부 세는 수라(`exec-N`·`incident-N`) 같은 시드면 같은 값이고, 넣으면 사건의 순서까지 고정된다. `ObservedEvent` 에 `local` 을 더해 계약 이벤트와 이 층의 관측을 갈랐다 — 섞어 번호를 보면 «커서가 안 나아갔다» 로 읽힌다(`EventStreamTest` 가 그렇게 읽고 있었다).

146. **코드 리뷰가 잡은 열 — 낡은 관측이 권위가 되어 있었다.**

    §15.143~145 를 여덟 관점으로 리뷰해 후보 41, 검증 28, 확정 10. 결함 주입 16건이 전부 «잡았다» 였는데도 남은 것들이다 — 주입은 시험이 *묻는* 질문만 확인한다. 가장 무거운 둘이 같은 모양이었다. 미믹의 `currentHold()` 는 «어느 태스크든 HOLDING 이면 든 채» 로 로그 전체를 훑어, 복구에 실패해 든 채로 끝난 옛 태스크가 뒤에 놓고 끝난 태스크를 영원히 가렸다(지울 RPC 도 없었다). 미들웨어의 `RobotView.lastHold` 는 자기 실행의 `WatchTask` 갱신으로만 쓰이고 다시 볼 길이 없어, 사람이 그리퍼를 비운 뒤에도 낡은 HOLDING 이 제출 거절의 권위가 됐다 — KDoc 은 «권위는 발신자» 라 적어 놓고. 미믹은 **가장 최근에 기록된 쥐는 태스크의 파지**(`handsHold`, `record()` 에서 갱신)로, 미들웨어는 **지금 도는 단위의 관측**만으로 바꿨고 `lastHold` 는 지웠다. 종착한 단위의 파지는 정보이지 판정이 아니다.

    나머지: `revise()` 가 사슬 검사를 건너뛰었다 — 이 개정이 실제로 교체·추가할 단위에만 건다. 발신자의 `PRECONDITION_UNMET` 을 FAILED 로 접고 다음 단위로 넘어갔다 — 이제 그 단위는 운영자 판단(`OPERATOR_HOLD`)에 서고 사유·주어가 단위에 남는다. 평가기가 모르는 주어를 위반으로 접어 소비자 쪽 마이너 호환을 깼다 — `Unknown.REFUSE|DEFER` 로 갈라 발신자는 fail-closed, 소비자는 유보. 호스트가 배타 실행 검사를 사전 조건보다 먼저 두어 «이미 도는 태스크» 와 «무엇이 걸렸나» 가 미믹과 갈렸다 — 순서를 바꿨고 패리티 시험이 그 경우를 든다. 벤더 `PRECONDITION_FAILED` 가 주어 참조 없는 `PRECONDITION_UNMET` 으로 나가면서 계약 주석과 모순됐다 — 원천이 둘임을 주석에 적었다. `CancelReport.residualHold` 가 빈손을 «말하지 않았다» 로 접었다 — `JobResponse` 와 같은 규칙으로. `WireTest` 가 계약을 올리며 «상대가 더 새로워도 차단하지 않는다» 사례를 잃었다 — 우리 버전에서 파생한다. `adapter-host` 시험이 픽스처를 읽으면서 Gradle 입력 선언이 없었다. `contract.md` 의 담보 행이 «관측 불가는 통과가 아니다» 를 증명하지 않는 시험만 인용했다. 그리고 카탈로그 효과를 미믹과 미들웨어가 따로 읽던 것을 한 곳(`HoldEffects`)으로.
