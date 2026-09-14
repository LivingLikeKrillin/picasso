# picasso 미들웨어 코어 설계 사양 — 정준 도메인 모델 및 공통 실행 아키텍처

- **문서 상태**: 확정 (2026-09-09 결정 및 2026-09-10 구현 완료)
- **참조 ADR**: [ADR 36](../../adr/0036-work-first-assignment-vs-execution.md) (Work-First 분리), [ADR 38](../../adr/0038-mission-layer-schema-is-ours.md) (미션 계층 내재화), [ADR 39](../../adr/0039-adapter-host.md) (공용 호스트)

본 문서는 상위 생산 실행 시스템(MES/WMS)과 하위 이종 로봇 및 자동화 설비 간의 작업을 오케스트레이션하는 **미들웨어 코어 엔진의 정준 모델(Canonical Model) 및 실행 아키텍처**를 규정합니다.

---

## 0. 아키텍처 범위 및 경계 정의

본 프로젝트의 개발 대상은 단순한 로봇 통신 계약을 넘어, 상위 비즈니스 계층과 하위 물리 계층을 연결하는 **지능형 미들웨어 코어 엔진**입니다. 핵심 산출물은 태스크, 상태, 실패 분류, 작업 능력을 표준화한 **정준 도메인 모델**입니다.

| 아키텍처 영역 | 스코프 내/외 구분 | 아키텍처 결정 근거 |
|---|---|---|
| **글로벌 배차 및 라우팅** | **비목표 (Out-of-Scope)** | 공장 전역 단위의 최적화 배차 및 라우팅은 외부 플릿 관제 시스템에 위임 |
| **논리적 능력 조합 및 상태 관리** | **핵심 범위 (In-Scope)** | 단일 워크셀 내 작업 분해 및 상태 보증은 필수 핵심 기능이며 코어 모듈(`picasso`)에 내재화 (ADR 38) |
| **통신 단절 복구 (IN_DOUBT)** | **핵심 범위 (In-Scope)** | 접수 불확실 상태 해소 파이프라인 수립 (재기동 후 영구 저장소 완전 복원은 차기 과제) |
| **상위 시스템 연동 (ACL)** | **통합 테스트 수준 (Test Double)** | 실제 엔터프라이즈 어댑터는 배제하고 통합 테스트가 예상 소비자(JobOrder 발행) 역할을 전담 |

---

## 1. 정준 도메인 모델 (Canonical Domain Model)

### 1.1 2축 실행 상태기계 (Execution State Model)

상위 미들웨어의 실행 상태는 물리적 전이 상태와 상류 통보 상태가 완전히 독립된 2축으로 분리 관리됩니다:

```
execution.physical_state ∈ { REQUESTED, ACCEPTED, RUNNING, PARTIAL, IN_DOUBT, OPERATOR_HOLD,
                             PHYSICALLY_DONE, UNVERIFIED, FAILED, CANCELING, ABORTED }
execution.upstream_ack   ∈ { NOT_SENT, SENT_UNACKED, ACKED }
```

- **계층 분리**: `physical_state`는 고수준 복합 일감의 전체 상태이며, 계약 계층의 `TaskState`는 하부 원자적 실행 단위의 상태입니다.
- **`upstream_ack` 분리**: 작업 완료 판정과 상류 시스템으로의 통보 확인을 분리하여, 네트워크 장애에 따른 통보 재시도와 물리 명령 재시도가 상호 간섭하지 않도록 아웃박스 패턴을 적용합니다.

### 1.2 4단계 근거 신뢰 등급 (Evidence Hierarchy E0~E3)

로봇 자체의 완료 보고와 물리 현장 설비 신호를 엄격히 분리 평가합니다:

| 등급 | 신뢰 원천 | 시스템 내 판정 근거 및 연동 위치 |
|---|---|---|
| **E0** | **로봇 자체 완료 보고** | 어댑터 직결 경로를 통한 원자적 태스크 정상 종착 수신 |
| **E1** | **플릿 관제 시스템 확인** | 상위 플릿 매니저(Orbit 등)의 미션 완료 승인 신호 |
| **E2** | **독립 물리 설비 센서 신호** | PLC, 재석 센서, 게이트 RFID 리더 등 독립 검증 장치의 유효 시간창 내 감지 신호 |
| **E3** | **작업자 수동 스캔/승인** | 현장 작업자의 최종 육안 검수 및 엔터프라이즈 확정 승인 (현재 스펙 선언 단계) |

요청된 작업의 요구 근거 등급을 충족하지 못하고 로봇 신호만 인입된 경우 최종 상태는 `PHYSICALLY_DONE`이 아닌 `UNVERIFIED`로 명시적 분기됩니다.

### 1.3 15종 정준 실패 분류 체계 (`FailureClass`)

벤더 고유의 수백 가지 에러 코드를 상위 비즈니스 판단이 가능한 15개 정준 실패 유형으로 정규화합니다:

| 실패 분류 코드 | 기술적 정의 및 발생 원인 | 대표적 벤더 매핑 |
|---|---|---|
| `PERCEPTION_FAILED` | 시각 센서가 대상을 식별/포착하지 못함 | Spot `GRASP_FAILED_TO_RAYCAST_INTO_MAP`, Digit 객체 미탐지 |
| `GRASP_PLANNING_FAILED` | 파지 궤적 및 기구학적 해를 생성하지 못함 | Spot `GRASP_PLANNING_NO_SOLUTION` |
| `GRASP_FAILED` | 파지 시도 중 물리적 집기 실패 | Spot `GRASP_FAILED`, Digit `action-pick` 실패 |
| `PLACE_FAILED` | 목표 지점에 안착/하역 실패 | Spot `PLACE_FAILED`, Digit `action-place` 실패 |
| `PAYLOAD_LOST` | 이송 도중 파지물 이탈/낙하 | 하드웨어 센서 압력 급감 감지 |
| `ROUTE_BLOCKED` | 이동 경로 상의 동적 장애물에 의한 통행 차단 | Spot `STUCK`, 픽스처 `NAVIGATION_BLOCKED` |
| `NO_ROUTE` | 지도 상에 목표 좌표까지 유효 경로 생성 불가 | Spot `NO_ROUTE` |
| `LOCALIZATION_LOST` | 로봇 SLAM 자기 위치 추정 상실 | Spot `NO_LOCALIZATION` |
| `CONTROL_AUTHORITY_LOST` | 기체 제어권 상실 또는 임대권 만료 | Spot `LEASE_ERROR`, Digit 권한 선점 박탈 |
| `COMMAND_TIMED_OUT` | 명령 실행 유효 시간 초과 | Spot `COMMAND_TIMED_OUT` |
| `COMMAND_OVERRIDDEN` | 후속 명령에 의한 현재 태스크 강제 덮어쓰기 | Spot `COMMAND_OVERRIDDEN` |
| `HARDWARE_FAULT` | 기체 모터, 관절, 통신 버스 등 물리 하드웨어 결함 | Spot `BehaviorFault`, G1 모터 과열 |
| `ROBOT_FELL` | 기체 전도(Fall-down) 감지 | Spot `CAUSE_FALL`, G1 `bad_orientation` |
| `PRECONDITION_FAILED` | 작업 실행 전제 조건 미충족 (초기화 미완료 등) | G1 `NOT_INIT`, Spot `NOT_POWERED_ON` |
| `UNCLASSIFIED` | 벤더가 범용 미분류 에러만을 반환 | Digit `action-status-changed.info` 단독 인입 |

---

## 2. 공통 실행 구조 (`picasso` 모듈)

미들웨어 코어 엔진은 전용 모듈 `picasso`에 구축되어 있으며, 게이트 검사 7번(기종 분기 금지)을 엄격히 준수합니다:
- **접수 파이프라인**: ISA-95 `JobOrder` 수신, 요청 ID 멱등성 평가, 요구 근거 등급 바인딩
- **실행 분해 엔진**: 상위 논리적 능력을 하위 원자적 태스크 시퀀스로 분해 및 스케줄링
- **다중 소스 근거 결합기**: 로봇 완료 피드백과 PLC 센서 피드백을 유효 시간창(δ) 내에서 결합 평가
- **불확실 상태(`IN_DOUBT`) 해소기**: 통신 단절 시 멱등 재전송 → 설비 관측값 확인 → 운영자 개입 요청 3단계 시퀀스 집행
- **정밀 취소 보고 조립기**: 중단점, 완료된 원자 단위 목록, 잔여 파지 상태(`holding`)를 포함하는 정밀 취소 보고서 생성

---

## 3. 상류 인터페이스 규격 (ISA-95 Job Control 매핑)

상류 MES/WMS와의 접점은 ISA-95 표준 규격을 준용합니다:
- `JobOrder`: 고유 주문 식별자, 목표 사이트, 자재 속성, 요구 근거 등급(`requiredEvidence`)
- `JobResponse`: 실행 결과 상태, 도달 근거 등급(`reachedEvidence`), 15종 실패 분류, 잔여 물리 상태
- `EquipmentUse` 4대 어휘 체계: `destination`(적재 슬롯), `source`(자재 제시 위치), `source.container`(용기 태그), `inspection_target`(점검 대상 좌표 및 항목)

---

## 4. 검증 매트릭스 및 실증 현황

- **시나리오 ① (용기 공급)**: `DeliverContainerTest` — AMR 플릿 위임 및 자재 인계 센서 결합 실증
- **시나리오 ② (부품 시퀀싱)**: `SequencingRackTest` — 4개 슬롯 순차 적재, 런타임 버전 갱신, 중간 취소 실증
- **시나리오 ③ (점검 순회)**: `InspectAssetTest` — 코어 엔진 수정 없는 신규 능력군 확장 실증
- 물리적 실물 연동 검증 현황은 [`docs/verification.md`](../../verification.md)의 종합 평가 매트릭스를 정본으로 유지합니다.

> 마지막 대조: 2026-09-15 · sha256:53d4f2fe3f6e · 열림: 시나리오 §8, §15.125, §15.126, ADR 32 · 시나리오 5, C-3, §15.127
