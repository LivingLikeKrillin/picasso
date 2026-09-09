# picasso 미들웨어 중앙 — 정준 모델과 공통 실행 구조

설계 문서 · 2026-09-09 · **결정됨** — [결정 필요] 셋은 2026-09-09 에 정했다(§1.3 그대로 · §1.4 그대로 · 모듈 `picasso`)

이 문서는 [본 설계](2026-09-05-picasso-design.md)의 범위를 넓힌다. 근거는 저장소 밖에서 작성된 미들웨어 설계 보고서 셋이며, 여기 적힌 것은 그 보고서의 구조를 picasso 의 모듈과 어휘로 옮긴 것이다. **보고서가 이름 붙인 것은 그 이름으로 부른다.** picasso 쪽 결정이 필요한 곳은 `[결정 필요]` 로 표시했다.

---

## 0. 무엇이 바뀌는가 — 범위

**만드는 것은 로봇 계약이 아니라 미들웨어다.** 상류(MES·WMS/WCS·SCADA·ERP)와 하류(로봇·설비 어댑터) 양쪽의 연동 계약과 **공통 처리 구조**. 핵심 산출물은 **정준 모델** — 태스크·상태·**실패 분류**·능력 표현. picasso 는 지금까지 그중 하류 절반(로봇 계약 ④, 로봇 쪽 MiMic, 어댑터 셋, 게이트, 운영 변경)을 지었고, 가운데와 위쪽이 없다.

| 본 설계 §1.3 이 비목표로 둔 것 | 이 문서 |
|---|---|
| "미션 계층 — 배차·라우팅·자원 중재·다중 로봇 경합" | **배차·라우팅·자원 중재는 여전히 밖.** 그러나 **논리적 능력의 조합과 실행 상태 관리는 안**이다. 둘은 떼어낼 수 없다 — 조합을 하면서 상태를 안 갖는 것은 불가능하고, PoC 미들웨어는 그것을 안에 둔다. 나중에 미션 서비스가 따로 서더라도 **스키마는 공유**되므로 스키마가 먼저다 |
| "B-1 무선 단절 대응의 집행" | **접수 여부 불명(`IN_DOUBT`)의 해소 경로는 안**이다. 재기동 후 저장소 복원은 여전히 밖 |
| "상위 시스템 어댑터(ACL) 구현"(§9.6 으로 옮김) | **상류 인터페이스는 통합 테스트 수준**으로 만든다. MES·WMS 는 우리 API 를 부르는 **예상 소비자**이지 흉내낼 내부가 있는 시스템이 아니다 — 통합 시험이 그 역할을 맡아 JobOrder 를 넣고 JobResponse 를 받는다. 별도의 MES·WMS Mock 을 만들지 않고, 실제 상위 시스템 어댑터도 만들지 않는다 |

**검증의 주는 시나리오 ①(용기 공급)·②(부품 시퀀싱)이고**, ③(점검 순회)은 능력군 추가의 회귀 검증이다. → ADR 38.

ADR 36 의 정정: 결정 5·6 은 유지한다(실행 계층은 상류 비노출, 계약에 넣는 것은 어느 한쪽이 이미 가진 것). 그러나 *"우리는 층 ④만 갖는다"* 는 대가 3 의 서술은 틀렸다 — **층 ②③의 스키마와 PoC 엔진은 우리 것**이다. 논리적 능력의 이름은 보고서가 *프로젝트 정의*로 표시한 것을 그대로 쓴다.

---

## 1. 정준 모델

### 1.1 실행 상태 — 두 축

보고서 10.3 을 그대로 받는다. 축이 둘이고 독립이다.

```
execution.physical_state ∈ { REQUESTED, ACCEPTED, RUNNING, PARTIAL, IN_DOUBT, OPERATOR_HOLD,
                             PHYSICALLY_DONE, UNVERIFIED, FAILED, CANCELING, ABORTED }
execution.upstream_ack   ∈ { NOT_SENT, SENT_UNACKED, ACKED }
```

이것은 **논리적 능력(실행) 하나의 상태**이며, 계약의 `TaskState` 는 그 아래 **원자적 태스크 하나의 상태**다. 둘은 다른 층이고 하나가 다른 하나를 대신하지 않는다.

| 실행 상태 | 계약(④)에서 오는 것 | 없는 것 |
|---|---|---|
| `REQUESTED → ACCEPTED` | `StartTask` 의 핸들 | — |
| `RUNNING` | 원자 태스크 `RUNNING` | — |
| `PARTIAL` | 원자 태스크 일부가 종착(슬롯마다 태스크 하나) | 실행이 그것을 **모아** 부분 완료 목록으로 드는 자리 |
| `IN_DOUBT` | 연결 스트림 `CONNECTION_BROKEN` + 같은 `(task_id, revision)` 재전송이 같은 핸들 | 해소 경로의 **순서**(13.2)를 집행하는 자리 |
| `OPERATOR_HOLD` | — | 조회 수단이 없을 때의 상태 |
| `PHYSICALLY_DONE` | 원자 태스크 `SUCCEEDED` ∧ **요구 근거 등급 충족** | 근거 등급 |
| `UNVERIFIED` | 원자 태스크 `SUCCEEDED` ∧ 근거 미충족 | 근거 등급 |
| `FAILED` | 원자 태스크 `FAILED`·`NEEDS_INTERVENTION`(→ 운영자) | 정준 실패 분류 |
| `CANCELING → ABORTED` | `CANCELLING → CANCELLED / CANCELLED_RECOVERY_FAILED` + `hold` | 중단점(완료 단위 목록)·정리 동작 |

`upstream_ack` 는 계약과 무관하다 — 결과 통보의 축이고, **명령 재시도와 통보 재시도를 가르는 이유**다(13.3). 트랜잭셔널 아웃박스, at-least-once, 상류 멱등.

### 1.2 논리적 능력 — 계약 요소 다섯 (보고서 11장)

능력 하나는 입력 스키마만으로 정의되지 않는다.

| 요소 | 내용 | picasso 지금 |
|---|---|---|
| 11.1 입력 스키마 | 용기 ID, 목적지, 허용 오차, 사이클타임 목표 | ISA-95 JobOrder 의 모양으로 `scenarios.md` 에 예시만 |
| 11.2 완료 조건(사후조건) | 자연어 + 검증 가능한 술어 (`container.location == destination ∧ evidence.level ≥ requested`) | 없음 |
| 11.3 완료 근거 등급 | 능력이 **제공 가능한 최고 등급**을 선언, 상류가 **요구 등급**을 지정, 미달이면 `UNVERIFIED` | 없음. ADR 37 의 문(직결/플릿)이 E0/E1 을 가르나 어디에도 안 실린다 |
| 11.4 취소 제약 | 취소 가능 지점, 정리 동작 유무, kill 지원 | 계약: `cancel_support` 3값. kill 은 ADR 32 로 밖. 취소 가능 지점·정리 동작 없음 |
| 11.5 부분 효과 모델 | 단위별 완료 보고, 파지 중 물체 보고 | `hold` 있음(§15.85). 단위별 완료는 태스크 단위로 |

**시나리오의 논리적 능력 셋** — 보고서의 이름 그대로, *프로젝트 정의*:

| 논리적 능력 | 시나리오 | 조합(예) | 근거 |
|---|---|---|---|
| `DeliverContainer` | ① 용기 공급 | AMR Fleet(D 수준 위임) + 인계 설비 신호 | E1(플릿 완료) + **E2**(PLC 재석·태그) |
| `PrepareSequencedRack` | ② 부품 시퀀싱 | 슬롯마다 `pick_place` + 셀 검증 장치 | E0(로봇) + **E2**(슬롯 점유·품번) |
| `InspectAsset` | ③ 점검 순회 | 지점마다 `navigate_to` + `inspect` | E0 또는 E1(플릿) |

### 1.3 근거 등급 E0~E3 (보고서 11.3)

| 등급 | 원천 | picasso 에서 나오는 자리 |
|---|---|---|
| E0 로봇 자체 보고 | 로봇 상태 | 어댑터가 **로봇에 직결**(ADR 37 선언) → 계약 종착 |
| E1 플릿 확인 | 플릿의 완료 | 어댑터가 **플릿에** 붙음(ADR 37 발견) |
| E2 독립 설비 확인 | PLC 신호(재석·게이트 리더·계량) | **PLC/WCS Mimic** — 로봇 보고와 **시간창 안에서 결합**(12장) |
| E3 업무 확인 | 상류 스캔 | 상류(예상 소비자 = 통합 시험)의 ack |

규칙: 능력은 최고 등급을 선언하고, 요청은 요구 등급을 지정하고, 미달이면 `UNVERIFIED`(도달 등급 표기). *"확인 수단이 없으면 제공 불가"* 는 E2 이상을 요구하는 업무에만. **결정(2026-09-09)** — 도달 등급은 **실행 층**이 든다. 계약(④)에는 어댑터의 문 종류(직결/플릿)만 오른다.

### 1.4 정준 실패 분류

정준 모델이 **실패 분류**를 포함한다. 로봇은 실패 원인을 아는데 상위가 받을 수 없는 것이 메워야 할 갭이다. 분류는 **벤더 무관**하고, 벤더 코드에서 여기로 옮기는 것은 **어댑터**(기종을 아는 유일한 자리, ADR 33)이며, 상류·실행 층은 이 분류로만 분기한다. 벤더 원문(코드·메시지)은 **진단 자리에 동반**할 수 있다 — 로그·사후 분석용이고 분기의 입력이 아니다.

**분류표 — 결정(2026-09-09), 아래 열다섯 그대로.** 측정된 벤더 어휘(Spot 조작 18·항법 14·미션 8, Digit 상태 4, G1 에러 11·종료 7)에서 **여럿이 실제로 내는 뜻**을 뽑았다.

| 분류 | 뜻 | 내는 벤더(측정) |
|---|---|---|
| `PERCEPTION_FAILED` | 대상을 못 찾음·못 봄 | Spot `GRASP_FAILED_TO_RAYCAST_INTO_MAP`·`PLACE_FAILED_TO_RAYCAST_INTO_MAP`, Digit 세계 모델에 대상 없음 |
| `GRASP_PLANNING_FAILED` | 잡을 방법이 없음 | Spot `GRASP_PLANNING_NO_SOLUTION` |
| `GRASP_FAILED` | 잡으려다 실패 | Spot `GRASP_FAILED`, Digit `action-pick` failure |
| `PLACE_FAILED` | 놓으려다 실패 | Spot `PLACE_FAILED`, Digit `action-place` failure |
| `PAYLOAD_LOST` | 들고 있던 것을 놓침 | (코어 기존) |
| `ROUTE_BLOCKED` | 갈 길이 막힘 | Spot `STUCK`·`AREA_CALLBACK_ERROR`, 픽스처 `NAVIGATION_BLOCKED` |
| `NO_ROUTE` | 갈 길이 없음 | Spot `NO_ROUTE` |
| `LOCALIZATION_LOST` | 위치 상실 | Spot `LOST`·`NO_LOCALIZATION`·`NOT_LOCALIZED_TO_ROUTE` (코어 기존) |
| `CONTROL_AUTHORITY_LOST` | 제어권 상실 | Spot `LEASE_ERROR`, Digit privilege, (코어 기존) |
| `COMMAND_TIMED_OUT` | 명령 만료 | Spot `COMMAND_TIMED_OUT` |
| `COMMAND_OVERRIDDEN` | 다른 명령이 덮음 | Spot `COMMAND_OVERRIDDEN` |
| `HARDWARE_FAULT` | 기체 결함 | Spot `BehaviorFault CAUSE_HARDWARE`·`ROBOT_IMPAIRED`, G1 과열·`HandState_.error` |
| `ROBOT_FELL` | 넘어짐 | Spot `CAUSE_FALL`, G1 `bad_orientation` |
| `PRECONDITION_FAILED` | 시작 조건 미충족(FSM·초기화·권한) | G1 `INVALID_FSM_ID`·`NOT_INIT`·`LOCOSTATE_NOT_AVAILABLE`, Spot `STATUS_DOCKED`·`NOT_POWERED_ON` |
| `UNCLASSIFIED` | 벤더가 분류 불가 정보만 줌 | Digit `action-status-changed.info` 만 있을 때 |

원칙: 한 벤더만 내는 뜻도 **상류가 판단에 쓰는 뜻이면** 분류에 둔다(파지 계획 실패는 재시도해도 소용없고 대상을 바꿔야 한다 — 상류의 결정이 다르다). 벤더가 못 가르면 더 거친 분류로 옮긴다(Digit 의 `failure` → 액션 종류로 `GRASP_FAILED`/`PLACE_FAILED`/`ROUTE_BLOCKED`, 그 밖은 `UNCLASSIFIED`). **어댑터 매핑표는 `@VendorSurface` 로 인용하고 매니페스트 시험이 대조한다.**

### 1.5 취소 응답 (보고서 14)

취소는 원상복구가 아니라 **중단점과 잔여 물리 상태의 보고**다.

```
{ executionId, taskVersion, cancelLevel: cancel,
  accepted, motionStopped,
  stopPoint: { completedUnits, inProgressUnit },
  residual: { holding: {objectRef}, robotPose? },
  cleanup: { action, result },
  finalState: ABORTED }
```

picasso: `residual.holding` = `hold`(있음). `stopPoint` = 종착·미종착 원자 태스크 목록(실행 층이 모은다). `cleanup` = `CANCELLED`(정리됨) / `CANCELLED_RECOVERY_FAILED`(못함) 의 뜻을 그대로. `kill` 은 ADR 32 로 계약 밖 — 능력 계약이 *"kill 미지원"* 을 선언한다(감추지 않는다). 이미 배치한 부품을 되돌리는 것은 취소가 아니라 **별도의 재작업 능력**이다.

### 1.6 정체성과 버전 (보고서 15)

요청 ID(상류) — 실행 ID(미들웨어) — 하류 작업 ID(계약 `task_id`) — **작업 버전**. picasso 의 `(task_id, revision)` 이 하류 두 칸이다. 결과 이벤트는 `(실행 ID, 버전)` 으로 맞추고 **불일치 이벤트는 폐기가 아니라 지연 이벤트로 보존**한다. 새 버전은 확정된 단위 이후에만(완료 슬롯은 상속 — 계약의 래치가 이미 그렇게 한다, `SequencingCellTest`).

---

## 2. 공통 실행 구조 (보고서 19장 3번)

새 모듈 **`picasso`** — 이름이 저장소와 같은 것은 이것이 곧 이 저장소가 만드는 것이기 때문이다. 의존: `contracts`, `client`(계약 소비자). **`registry` 와 분리** — registry 는 개정판·원장·변경 계획이고 이것은 실행이다. 게이트 7번(기종 분기 금지)의 대상에 **넣는다**.

| 구성 | 하는 일 |
|---|---|
| 접수 | JobOrder(ISA-95 모양) → 실행 생성, 요청 ID 멱등, 요구 근거 등급 보관 |
| 능력 선택 | 논리적 능력 → 조합 계획(어느 하류에 어떤 원자 태스크 열을) |
| 실행 상태기계 | 1.1 의 두 축. 원자 태스크 전이(`WatchTask`·이벤트)를 모아 실행 상태로 |
| 근거 결합 | 로봇 보고 + PLC/WCS Mimic 신호의 시간창 결합 → `PHYSICALLY_DONE` / `UNVERIFIED` |
| IN_DOUBT 해소 | 13.2 의 순서 — 같은 요청 재전송(계약의 멱등) → 물리 관측(E2) → `OPERATOR_HOLD` |
| 취소 | 1.5 의 응답을 조립 |
| 결과 통보 | JobResponse 조립 + 아웃박스 + `upstream_ack` |
| 지연 이벤트 | 옛 버전의 종착 보존 |

**조합은 미션 계층의 일이고 그것은 이 모듈 안이다.** 배차·라우팅은 여기 없다 — 어느 로봇에 줄지는 시나리오에서 하나뿐이거나 상류가 지정한다.

---

## 3. 상류 인터페이스 — 통합 테스트 수준

ISA-95 Job Control 의 `JobOrder`/`JobResponse` 모양(`scenarios.md` §4.1 의 예)으로 **미들웨어가 API 를 낸다.** MES·WMS 는 그 API 의 **예상 소비자**다 — 우리가 그들의 내부를 흉내낼 이유가 없으므로 Mock 이 아니라 **통합 시험이 소비자 역할을 한다**: 요청을 넣고, 결과 통보를 받고, ack 하고, ack 유실을 일으킨다. 하류의 mimic 과는 위치가 반대다 — mimic 은 우리가 **부르는** 쪽을 대신하고, 여기서는 우리를 **부르는** 쪽이 시험이다. 실제 상위 시스템 어댑터는 만들지 않는다. `EquipmentUse` 값(`destination`·`source`)은 우리가 지은 말이라 표시한다(표준이 열어 둠).

---

## 4. 하류 구성 — 시험 더블과 실물 대용

하류는 우리가 **부르는** 쪽이라 더블이 필요하다. 로봇은 mimic 이 대신하고, 같은 이유로 AMR 플릿과 인계 설비도 더블이 있어야 시나리오가 끝까지 돈다.

| 하류 | 무엇 | 수준 | picasso |
|---|---|---|---|
| 로봇(휴머노이드·4족) | 계약(④) 뒤의 `mimic` — 프로파일 주도 | C(원자 스킬) | 있음 |
| 로봇 실물 어댑터 | Spot·Digit·G1 | — | 있음(북쪽 없음) |
| **AMR Fleet Mock** | 용기 운반을 **D 수준으로 위임**받는 하류. **프로젝트용 계약**이며 특정 벤더 API 의 재현이 아니다 | D | **없음 — 만든다** |
| **PLC/WCS Mimic** | 재석·태그·슬롯 점유·품번 신호. 폴링 모델, 짧은 신호는 래치 비트, 시간창 δ | E2 원천 | **없음 — 만든다** |
| RB-Y1 실행기 | B 수준 SDK 위의 작업 실행기(PickPart 합성). 공식 시뮬레이터가 실기와 같은 gRPC | B→C | 없음 — **후속 트랙** |

**`scenarios.md` §1 의 AMR 경계 규칙을 고친다.** *"picasso 는 AMR 을 어댑터로 감싸지 않는다"* 는 **벤더 AMR API·VDA 5050 을 구현하지 않는다**로 좁힌다. 미들웨어는 시나리오 ①에서 AMR Fleet Mock 에 운반을 D 수준으로 **위임하고 결과를 받는다** — 그것이 보고서의 배치다. 나머지 둘(들어오는 문은 환경 전제와 설비 신호 / 휴머노이드 파라미터에 AMR 식별자 없음)은 유지.

---

## 5. 검증

- **시나리오 ①·② 통합 시험**이 주다. 시험(= 예상 소비자 MES/WMS 의 역할) → `middleware` → mimic(로봇) / AMR Fleet Mock / PLC/WCS Mimic → JobResponse 를 시험이 받는다. 보고서 5·6장의 상황표 전부.
- 보고서 17장의 열 개를 이 층에서 다시 단언한다 — 특히 3(IN_DOUBT), 4(부분 완료 후 취소), 6(지연 이벤트), 7·8(로봇 보고와 설비 신호의 불일치 → `UNVERIFIED`·운영자), 10(능력군 추가 = ③).
- 기존 하네스(계약 수준)는 그대로 둔다. `SequencingCellTest`·`InspectionPatrolTest` 는 ④의 시험이고, 이 문서의 시험은 그 위 층이다.
- 실제 API 에 연결한 구간과 Mock 구간을 표로 구분한다(보고서 8장 "검증 근거").

---

## 6. 순서 (보고서 19장의 순서를 따른다)

1. ~~정준 모델 스키마 확정~~ **됐다(2026-09-09)** — 도달 등급은 실행 층 · 분류표 그대로 · 모듈 `picasso`. ADR 38 결정됨.
2. **`middleware` 최소 + 시나리오 ②** — 접수·조합(슬롯마다 `pick_place`)·실행 상태기계·JobResponse. mimic 위에서. 셀 검증은 PLC/WCS Mimic 으로 E2.
3. ~~PLC/WCS Mimic + 근거 결합~~ **됐다(2026-09-10, §15.90)** — 시간창 δ · `VERIFYING` 재확인 · 래치 · 12.3 의 셋(무응답 행은 6 에서).
4. **AMR Fleet Mock + 시나리오 ①** — D 수준 위임, 인계 신호 결합, 응답 유실 후 재전송(통보 재시도 ≠ 명령 재시도).
5. ~~정준 실패 분류 → 어댑터 매핑~~ **됐다(2026-09-10, §15.91, 계약 0.6.0)** — `FailureClass` 가 계약을 타고, Spot·Digit·G1·미믹이 옮기며, 원문은 `vendor_detail` 에 동반. 발신자 없는 값 둘(`PERCEPTION_FAILED`·`GRASP_PLANNING_FAILED`)은 §15.91 정직 항목.
6. ~~IN_DOUBT 해소·지연 이벤트·`OPERATOR_HOLD`~~ **됐다(2026-09-10, §15.92)** — 13.2 의 순서를 `resolveDoubt` 가 집행하고(포트가 조회 능력을 선언, 없으면 운영자·자동 재실행 없음), 옛 버전의 종착은 `lateEvents` 에 보존하고 새 버전의 기대에 대고 다시 본다. 17장 3·6·9.
7. **③ 회귀** — `InspectAsset` 추가로 공통 엔진 무변경 확인.

RB-Y1 은 이 순서 뒤의 별도 트랙이다.

---

## 7. 이 문서에 없는 것

- 배차·라우팅·자원 중재·다중 로봇 경합. 여전히 밖이다.
- kill(안전 정지). ADR 32.
- 실제 상위 시스템 어댑터, 그리고 MES·WMS 의 Mock. 상류는 예상 소비자이고 통합 시험이 그 역할까지다.
- 사업장·일정·조직. 근거는 표준과 벤더 1차 자료뿐이다.
