# 컴포넌트 교체 지점(Seams) 명세서 — 실물 전환 및 확장 가이드

본 문서는 `picasso` 미들웨어 아키텍처에서 모의 대역(Mock/Mimic)을 실제 공장 설비, 상위 시스템, 실물 로봇 기체 및 통신 인프라로 전환하기 위한 **9대 핵심 교체 지점(Seams)**의 인터페이스 규격과 수정 범위를 정의합니다.

구간별 현행 검증 수준은 [`verification.md`](verification.md)를 참조하십시오.

---

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="diagrams/seams.dark.svg">
  <img alt="9대 교체 지점 아키텍처 다이어그램. 최상단 미들웨어 코어 계층과 하단 9개 교체 지점의 인터페이스, 실물 전환 시 작업 대상, 불변 유지 대상 및 대상 모듈이 정의되어 있습니다." src="diagrams/seams.svg">
</picture>

## 교체 가능성의 아키텍처적 보증

- **포트와 대역의 모듈 레벨 분리**:
  - `CellSignals`, `AmrFleetPort`, `RobotPort` 등의 인터페이스(포트)는 `picasso/src/main`에 정의되어 있으며, 이를 구현하는 테스트 더블(`CellMimic`, `AmrFleetMimic` 등)은 `picasso/src/test`에 완전히 격리되어 있습니다.
  - 코어 엔진은 모의 대역의 존재를 전혀 참조하지 않으며, 테스트 대역을 삭제하더라도 본체 코드는 정상 컴파일됩니다.
- **교체 지점 간의 완전한 독립성**:
  - 9개 교체 지점은 상호 결합되어 있지 않으므로, 특정 지점(예: PLC 설비 신호)을 실물로 교체할 때 다른 지점(로봇 어댑터, 상위 MES 연계 등)의 코드를 수정할 필요가 없습니다.

---

## 9대 교체 지점 상세 명세

### 1. 상위 시스템 연계 (MES·WMS·SCADA → picasso)

상위 시스템과의 연계 지점은 별도 인터페이스가 아닌 `Middleware` 클래스의 **공개 API**로 제공됩니다:
- 주요 메서드: `submit(JobOrder, robotId)`, `pump()`, `pending()`, `ack(jobResponseId)`, `resolve(...)`, `cancel(...)`, `release(...)`

- **실물 전환 작업**:
  - 상위 통신 프로토콜(OPC UA, REST, Kafka, MQTT 등)을 수신하는 **인바운드 ACL(Anti-Corruption Layer)**을 구현하여 본 공개 API를 호출합니다.
  - 상류 데이터 포맷과 미들웨어의 `JobOrder` / `JobResponse` 간 상호 변환은 ACL 계층이 전담합니다.
- **불변 유지 대상**: `picasso` 내부 코어 전체 (논리적 능력, 오케스트레이션 엔진, 3대 포트).
- **포트 인터페이스를 별도 정의하지 않은 이유**: 현시점에서 상류 소비자는 통합 테스트뿐이며, 실제 상위 시스템 요구사항 없이 추상 인터페이스를 사전 발명하는 것은 ADR 9(소비자 존재 원칙)에 위배되기 때문입니다.

### 2. 현장 설비 센서 신호 (PLC/WCS)

**인터페이스**: `CellSignals.observe(location): SlotSignal?` (null 반환 시 '신호 없음'으로 처리)

- **실물 전환 작업**:
  - 현장 PLC의 OPC UA 노드 또는 무전압 I/O 접점 상태를 폴링하여 `SlotSignal(identity, observedAt, latched)` 객체로 변환하는 구현체를 제공합니다.
  - 설비 신호 폴링 주기 및 하드웨어 래치 정책은 현장 환경에 맞춰 구현하며, 시간창 δ는 논리적 능력 파라미터로 설정합니다.
- **불변 유지 대상**: 근거 결합 엔진 규칙 전체 (시간창 판정, 센서 재확인, `UNVERIFIED`, `VERIFICATION_MISMATCH` 처리 로직).

### 3. AMR 플릿 관리 시스템

**인터페이스**: `AmrFleetPort{executionLookup, dispatch(TransportOrder), status(handle), cancel(handle)}`
(기본 Null 구현체: `None`)

- **실물 전환 작업**:
  - 상용 AMR 플릿 관리자(Fleet Manager) API를 호출하는 어댑터 클래스를 구현하여 본 인터페이스를 충족시킵니다.
  - `TransportState`의 시맨틱 계약을 엄격히 준수해야 합니다. `DELIVERED` 상태는 *[목적지 도착 ∧ 하역 완료 ∧ 인계 승인 ∧ 기체 미보유]* 조건을 모두 만족해야 하며, 단순 "도착" 신호를 성급히 매핑해서는 안 됩니다.
- **불변 유지 대상**: E1→E2 근거 결합 모델, 작업 인계 대기, 작업 취소 정리 규칙.

### 4. 로봇 인터페이스 계약 — 소비자 영역

**인터페이스**: `RobotPort{capabilities, start, watch, cancel, snapshot, replay, executionLookup}` (실물 배선은 ClientRobotPort(gRPC))

- **실물 전환 작업**:
  - 기본적으로 gRPC 기반 `ClientRobotPort`가 제공되므로 추가 수정이 불필요합니다.
  - 테스트 시 결함 주입을 위해 `LossyRobotPort` 또는 `ProgressPort`와 같은 테스트 더블을 교체 연결할 수 있습니다.
- **불변 유지 대상**: `picasso` 엔진 및 상위 소비 로직 전체.

### 5. 로봇 인터페이스 계약 — 발신자 영역 (호스팅 런타임)

계약 인터페이스 배후의 런타임 구현체를 교체하는 지점입니다. `MimicServer`(프로파일 기반 에뮬레이터)와 `AdapterHost`(실물 어댑터 호스팅 서버)가 상호 대체 가능합니다. 소비자는 통신 엔드포인트 URL만 변경합니다.

**함께 교체되는 것**: 자리 이름의 결속 정본(`SiteBindingSource`). 기본값은 「안 붙임」이며, 현장에서는 활성 지도 판을 아는 정본을 물려 옛 판에서 배운 자리로 가는 명령을 차단합니다(§15.156). 별도 교체 지점으로 세지 않는 이유는 이 지점과 같은 경계에서 같은 시점에 바뀌기 때문입니다.

**인터페이스**: `RobotAdapter` — 호스트 서버가 로봇 어댑터를 구동하기 위한 표준 인터페이스 (ADR 39)

- **실물 전환 작업**:
  - 배포 환경에 따라 실행 프로세스를 `mimic` 또는 `adapter-host`로 선택 실행합니다. (예: `OrbitLauncher`)
- **불변 유지 대상**: 상위 클라이언트 코드 일체 (`HostParityTest`를 통해 두 실행체의 거동 동등성 보증).

### 6. 어댑터 사우스바운드 — 벤더 API 연동

**인터페이스**: `SpotLink` · `DigitLink` · `G1Link` · `OrbitLink` (각 기종별 모듈 내 격리 정의, ADR 33)

- **실물 전환 작업**:
  - 벤더사 공식 SDK 또는 통신 라이브러리를 연동하여 해당 기종의 Link 인터페이스를 구현합니다. (현재 Orbit의 경우 HTTP REST 기반 `OrbitHttpLink` 실구현 포함)
  - 벤더 SDK는 저장소에 직접 커밋하지 않는 원칙을 준수합니다.
- **불변 유지 대상**: 상위 계약, 어댑터 호스트, 미들웨어 코어 및 타 기종 어댑터. 구현체 내의 `@VendorSurface` 선언은 매니페스트 대조 검증을 받습니다.

### 7. 메시지 발행 인프라 (MQTT)

**인터페이스**: `Publisher.publish(Publication)`

- **발행 대상**: `NONE`(미발행), `RecordingPublisher`(테스트용), `MqttPublisher`(Paho 기반 실물 MQTT 브로커 연동).
- **데코레이터 파이프라인**: 전송 결함을 주입하는 `TransportFaults`와 원장 적재를 분기하는 `IngestBridge`가 동일한 인터페이스를 래핑하여 동작합니다.
- **실물 전환 작업**: 현장 MQTT 브로커 연결 정보(호스트, 포트, 인증 정보)를 주입하여 `MqttPublisher`를 활성화합니다.
- **불변 유지 대상**: 토픽 명명 체계, 헤더 시퀀스 규격, 재생 링 버퍼 및 세션 관리 로직.

### 8. 운영 원장 적재 (Ingest)

**인터페이스 넷**: `HandshakeReporter` · `TaskObservations` · `LivenessObservations` · `RobotDiscovery`
(적재 실패 시 `FailedObservations` 파일 폴백 지원)

- **실물 전환 작업**:
  - REST API 기반의 표준 HTTP 적재 클라이언트(`Http*`)가 기구현되어 있으므로, 레지스트리 서버 엔드포인트 및 인증 토큰을 구성합니다.
  - MQTT 브로커 구독 기반 비동기 적재 연동 시에도 `ObservationService` 입력 계약(`MessageHeader`)이 기확립되어 있어 즉시 통합 가능합니다.
- **불변 유지 대상**: 관측 데이터 수집 및 상태 분류 파이프라인.

### 9. 기종 프로파일 제공자 (Profile Source)

**인터페이스**: `ProfileSource.load(path)` (로컬 파일 시스템 로드), `RegistrySource.binding(robotId)` (운영 레지스트리 원격 풀링)

- **실물 전환 작업**:
  - 독립 실행 시 로컬 파일 소스를 사용하고, 중앙 운영 환경에서는 레지스트리 풀링 소스를 바인딩합니다.
- **불변 유지 대상**: 프로파일 파싱 모델 및 검증 엔진. 레지스트리가 클라이언트에 푸시하지 않고 클라이언트가 풀링함으로써 단방향 결합도를 유지합니다 (§3.2).

---

## 색인 — 자리와 인터페이스

| 자리 | 인터페이스 | 어디 |
|---|---|---|
| 설비 | `CellSignals` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
| 플릿 | `AmrFleetPort` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
| 로봇(소비자) | `RobotPort` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
| 벤더 — Spot | `SpotLink` | `adapter-boston-dynamics-spot/src/main/kotlin/dev/picasso/adapter/spot/SpotLink.kt` |
| 벤더 — Digit | `DigitLink` | `adapter-agility-digit/src/main/kotlin/dev/picasso/adapter/digit/DigitLink.kt` |
| 벤더 — G1 | `G1Link` | `adapter-unitree-g1/src/main/kotlin/dev/picasso/adapter/g1/G1Link.kt` |
| 벤더 — Orbit | `OrbitLink` | `adapter-boston-dynamics-orbit/src/main/kotlin/dev/picasso/adapter/orbit/OrbitLink.kt` |
| 어댑터 북쪽 | `RobotAdapter` | `adapter-core/src/main/kotlin/dev/picasso/adapter/core/RobotAdapter.kt` |
| 발행 | `Publisher` | `uplink/src/main/kotlin/dev/picasso/uplink/Publisher.kt` |
| 적재 — 핸드셰이크 | `HandshakeReporter` | `uplink/src/main/kotlin/dev/picasso/uplink/report/HandshakeReporter.kt` |
| 적재 — 태스크 | `TaskObservations` | `uplink/src/main/kotlin/dev/picasso/uplink/report/IngestBridge.kt` |
| 적재 — 생존 | `LivenessObservations` | `uplink/src/main/kotlin/dev/picasso/uplink/report/HttpLiveness.kt` |
| 적재 — 발견 | `RobotDiscovery` | `uplink/src/main/kotlin/dev/picasso/uplink/report/RobotDiscovery.kt` |
| 프로파일 — 파일 | `ProfileSource` | `mimic/src/main/kotlin/dev/picasso/mimic/profile/ProfileSource.kt` |
| 프로파일 — 원장 | `RegistrySource` | `mimic/src/main/kotlin/dev/picasso/mimic/RegistrySource.kt` |

> **검증 보증:** 본 색인 테이블의 인터페이스명 및 파일 경로는 `DocumentClaimsTest`를 통해 실제 소스 코드와 상시 대조 검증됩니다.

> 마지막 대조: 2026-09-17 · sha256:be6b2f2f4e60 · 열림: §15.34, C-3, §15.5, §15.156
