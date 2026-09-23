# ISA-95 표준 매핑 명세서 — 상위 제조 데이터 모델 정합성 분석

본 문서는 `picasso` 미들웨어의 상위 시스템 연계 인터페이스(`JobOrder`, `JobResponse`)가 글로벌 제조 운영 통합 표준인 **ANSI/ISA-95 (IEC 62264) 및 OPC UA Part 14 (Job Control)** 표준과 어떻게 매핑되는지 필드 단위로 정밀 대조한 엔지니어링 명세서입니다.

상위 시스템(MES·WMS) 연동 시 표준 필드는 기존 상위 시스템의 데이터 모델을 그대로 수용할 수 있으며, 미들웨어 자체 확장 필드는 연동 어댑터(ACL)를 통해 구성합니다.

---

## 0. 표준 참조 기준 및 근거 자료

| 표준 규격 | 공개 여부 | 본 프로젝트 참조 및 적용 방식 |
|---|---|---|
| **IEC 62264 / ANSI·ISA-95 정본** (Part 1~4: 모델, 객체 속성, MOM 통합) | 유료 표준 | 정본 라이선스 미보유 항목은 조항 번호 인용을 배제하고 `UNKNOWN` 으로 표기하여 사실 왜곡 방지 |
| **OPC UA 10031-4 — ISA-95 Job Control** | 공개 규격 | 상위 데이터 모델 타입명의 1차 근거: `ISA95JobOrderDataType`, `ISA95JobResponseDataType`, `ISA95MaterialDataType`, `ISA95EquipmentDataType` |
| **VDA5050 Factsheet** | 공개 규격 | 기체 능력 선언 모델의 구조적 레퍼런스 (도메인은 상이하므로 직접 참조가 아닌 구조적 개념 참조) |

> **판정 원칙 (UNKNOWN의 의미):** 본 명세에서 `UNKNOWN` 표기는 "표준에 해당 기능이 부재함"을 의미하지 않으며, "공식 정본을 통한 1차 검증이 수행되지 않았음"을 나타내는 엄격한 3값 논리 표기입니다.

---

## 1. 상위 일감 발주 모델 대조 — `JobOrder`

| 미들웨어 필드 | ISA-95 표준 대응 항목 | 근거 수준 | 상세 분석 및 엔지니어링 비고 |
|---|---|---|---|
| `jobOrderId` | `ISA95JobOrderDataType.JobOrderID` | 공개 규격 | 1:1 직접 매핑 |
| `workMasterId` | `ISA95JobOrderDataType.WorkMasterID` | 공개 규격 | 논리적 능력 식별자 (실제 명칭은 현장 상위 시스템 기준 수용, ADR 36) |
| `version` | `JobOrderParameters` 내부 파라미터 매핑 | `UNKNOWN` | 시퀀스 리비전 관리 (미들웨어 내부 원자 태스크의 `revision`과 동기화, §1.6) |
| `parameters` | `ISA95JobOrderDataType.JobOrderParameters` | 공개 규격 | 스킬별 런타임 동적 파라미터 키-값 세트 |
| `materialRequirements` | `ISA95MaterialDataType` (`MaterialDefinitionID`, `Quantity`) | 공개 규격 | 자재/부품 타입 식별자 및 소요 수량 매핑 (인스턴스 ID는 이력 추적용으로 분리, §15.80) |
| `equipmentRequirements` | `ISA95EquipmentDataType` (`ID`, `EquipmentUse`, `Properties`) | 공개 규격 | 대상 설비 및 로봇 자원 조건 매핑 |
| `requiredEvidence` | (표준 대응 없음) | — | 미들웨어 자체 확장 필드 (§4 참조) |

---

## 2. 작업 실행 응답 모델 대조 — `JobResponse`

| 미들웨어 필드 | ISA-95 표준 대응 항목 | 근거 수준 | 상세 분석 및 엔지니어링 비고 |
|---|---|---|---|
| `jobResponseId`, `jobOrderId`, `version` | `ISA95JobResponseDataType` 대응 필드 | 공개 규격 | 작업 결과 상관관계(Correlation) 추적 키 |
| `physicalState` | 표준 실행 상태 어휘 | `UNKNOWN` | 물리적 실행 상태와 상류 통보 상태(`upstream_ack`)의 이원화 관리 모델 적용 |
| `completedUnits`, `unverifiedUnits`, `incompleteUnits`, `inDoubtUnits` | (표준 대응 없음) | — | 미들웨어 자체 확장: 원자적 작업 단위별 상태 분류 세트 (§4) |
| `reachedEvidence`, `residualHold`, `operatorRequired` | (표준 대응 없음) | — | 미들웨어 자체 확장: 최종 달성 근거 등급, 잔여 파지 상태, 운영자 개입 요구 (§4) |

---

## 3. 표준 오픈 사양의 구체화 (ADR 36 계층 ②)

ISA-95 표준에서 도메인별 특화를 위해 개방해 둔 확장 필드를 다음과 같이 시스템화했습니다:

- **`EquipmentUse`의 `source` · `destination` 속성**:
  - 표준은 *"EquipmentRequirements에 대해 표준화된 항목을 사전에 정의하지 않는다"*고 명시하고 있습니다.
  - 본 시스템은 자재 입고원(`source`)과 적치처(`destination`)를 지정하는 표준 어휘로 이를 구체화했습니다.
- **`Properties`의 `material`, `container` 키**:
  - 자재 취급 도메인을 위해 동일한 확장 메커니즘을 적용했습니다.

---

## 4. 미들웨어 자체 확장 필드 설계 및 아키텍처 격리

근거 등급(`E0`~`E3`), 작업 단위별 분할 결과 집합, 잔여 파지 상태(`residualHold`), 운영자 개입 요구(`operatorRequired`)는 표준에 없는 미들웨어 자체 확장 모델입니다.

- **격리 보증 (ADR 36 준수)**:
  - 본 확장 필드들은 상위 시스템과의 통신 및 미들웨어 내부 오케스트레이션을 위한 모델이며, **`contracts/proto/picasso/v1/` 계약 인터페이스로 누출되지 않습니다.**
  - 로봇 기체는 결코 *"내가 E2 등급을 달성했다"*고 스스로 선언하지 않으며, 근거 등급은 미들웨어가 로봇의 완료 보고와 현장 설비 센서 신호를 교차 검증하여 결합 산출합니다.
  - 검증 대상에게 검증 신뢰도 판정을 위임하는 구조적 결함을 방지하기 위해, 계약 인터페이스에는 근거 등급이 일절 포함되지 않습니다.

---

## 5. 미적용 표준 사양 및 배제 사유

| 표준 항목 | 배제 사유 |
|---|---|
| **납기 기한 (`due_by`)** | 실시간 배차 및 공정 스케줄링 알고리즘의 입력값으로, 본 미들웨어의 책임 범위 외(Out-of-scope)에 해당합니다. |
| **별도 요청 트랜잭션 ID** | 재전송 멱등성은 `(jobOrderId, version)` 튜플을 통해 보장되므로, 이중 트랜잭션 키로 인한 불일치를 방지하기 위해 배제했습니다. |
| **작업자(Personnel) 및 물리 자산 요구조건** | 단일 로봇 태스크 수행 시 다중 인력 배정 시나리오는 본 PoC의 범위를 벗어나므로 제외했습니다 (ADR 9). |
| **전사 스케줄링 및 자원 라우팅** | ADR 36 계층 ③의 경계에 따라 상위 MES/APS 시스템의 고유 영역으로 유지합니다. |

> 마지막 대조: 2026-09-15 · sha256:11ab4738a00a · 열림: 시나리오 §8, §15.126, §15.127
