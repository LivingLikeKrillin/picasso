# ISA-95 대조 — 무엇이 표준의 것이고 무엇이 우리가 지은 것인가

상류 API 의 모양은 우리가 고른 것이 아니라 표준에서 왔다. 그 말이 *"표준을 참고했다"* 로만 있으면 값이 없다 —
**필드 단위로 대조해야** 어느 칸이 표준의 것이고 어느 칸이 우리 것인지 읽는 사람이 안다.

그 구분이 실무에서 중요한 이유는 하나다. **표준의 칸은 상대 시스템이 이미 채울 줄 안다.** 우리가 지은 칸은
상대가 모르므로, 붙이는 쪽이 그 칸을 위해 무언가를 해야 한다. 이 문서는 그 목록이다.

---

## 0. 무엇을 근거로 삼았고, 무엇은 못 봤나

| 문서 | 공개 여부 | 이 저장소가 쓴 방식 |
|---|---|---|
| IEC 62264 / ANSI·ISA-95 정본 (Part 1 모델과 용어 · Part 2 객체와 속성 · Part 4 MOM 통합의 객체와 속성) | **유료** | **절 번호로 못 짚는다.** 아래 표에서 그런 칸은 `UNKNOWN` 이다 |
| OPC UA 10031-4 — ISA-95 Job Control | 공개 | **타입 이름이 여기서 왔다.** `ISA95JobOrderDataType` · `ISA95JobResponseDataType` · `ISA95MaterialDataType` · `ISA95EquipmentDataType` 를 코드 주석이 그대로 짚는다 |
| VDA5050 Factsheet | 공개 | 우리 능력 프로파일과 **같은 자리**의 물건이다(기체가 자기 능력을 선언한다). 다만 **도메인이 다르다**(AMR·빌딩) — 참고이지 근거가 아니다 |

★**`UNKNOWN` 은 '표준에 없다' 가 아니다.** 정본을 못 읽었다는 뜻이다. 이 저장소는 근거 등급이 낮을 때
`NO` 라고 적었다가 판정 넷이 한 번에 뒤집힌 적이 있고(Digit, §15.65), **같은 규칙을 여기에도 적용한다.**

---

## 1. 상류가 내는 것 — `JobOrder`

| 우리 필드 | 표준의 대응 | 근거 | 비고 |
|---|---|---|---|
| `jobOrderId` | `ISA95JobOrderDataType.JobOrderID` | 공개 타입 | 그대로다 |
| `workMasterId` | `ISA95JobOrderDataType.WorkMasterID` | 공개 타입 | 자리는 표준의 것인데 **값은 우리가 못 짓는다** — 논리적 능력의 이름은 실제 상류를 만나야 안다(ADR 36 층 ②) |
| `version` | `JobOrderParameters` 의 한 줄로 싣는다 | `UNKNOWN` | 표준에 *생산 순서 버전* 이라는 이름의 칸이 따로 있는지 확인 못 했다. **이 값이 곧 원자 태스크의 `revision` 이다**(§1.6) |
| `parameters` | `ISA95JobOrderDataType.JobOrderParameters` | 공개 타입 | 키는 능력별이고 표준이 정하지 않는다 |
| `materialRequirements` | `ISA95MaterialDataType` (`MaterialDefinitionID` · `Quantity`) | 공개 타입 | **부품은 타입으로 온다.** 인스턴스 id 는 이력 키이지 조작 파라미터가 아니다(§15.80) |
| `equipmentRequirements` | `ISA95EquipmentDataType` (`ID` · `EquipmentUse` · `Properties`) | 공개 타입 | 구조는 표준, **값은 표준이 열어 두었다** — §3 |
| `requiredEvidence` | 없다 | — | **우리가 더했다** — §4 |

---

## 2. 우리가 상류에 내는 것 — `JobResponse`

| 우리 필드 | 표준의 대응 | 근거 | 비고 |
|---|---|---|---|
| `jobResponseId` · `jobOrderId` · `version` | `ISA95JobResponseDataType` 의 대응 칸 | 공개 타입 | 되돌아가는 키다 |
| `physicalState` | 상태 어휘가 있다 | `UNKNOWN` | **우리는 축이 둘이다**(`physical_state` × `upstream_ack`). 표준이 그 둘을 가르는지 절 단위로 확인 못 했다 |
| `completedUnits` · `unverifiedUnits` · `incompleteUnits` · `inDoubtUnits` | 없다 | — | **우리가 더했다** — §4 |
| `reachedEvidence` · `residualHold` · `operatorRequired` | 없다 | — | **우리가 더했다** — §4 |

---

## 3. 표준이 열어 둔 자리 — 우리가 채운 것

표준이 **명시적으로 비워 둔** 칸이 있고, 그 빈칸이 정확히 ADR 36 의 층 ② 다.

- **`EquipmentUse` 의 `source` · `destination`** — 표준은
  *"does not define any standardized entries for EquipmentRequirements"* 라고 적는다.
  그래서 이 두 낱말은 **우리가 지은 말이고, 문서와 코드 주석이 그렇게 표시한다.**
- **`Properties` 의 `material` · `container` 키** — 같은 자리, 같은 이유.

★**빈칸을 채우는 것은 발명이 아니다.** 표준이 *"여기는 도메인이 정한다"* 라고 말한 자리를 채우는 것과,
표준에도 벤더에도 없는 어휘를 새로 짓는 것은 다르다. 전자는 규격이 시킨 일이고 후자가 ADR 36 이 막는 것이다.

---

## 4. 표준에 없고 우리가 더한 것 — 그리고 왜 ADR 36 에 안 걸리나

근거 등급(`E0`~`E3`) · 단위별 결과 목록 넷 · 잔여 파지 상태 · 운영자 개입 표시 — 표준에 대응이 없다.

**ADR 36 의 발명 금지는 계약(`picasso/v1`)의 등재 기준이지 미들웨어 내부 모델의 것이 아니다.**
그 구분이 말이 되려면 조건이 하나 붙는다 — **이 값들이 계약 면으로 안 나가야 한다.**

실제로 안 나간다. `contracts/proto/picasso/v1/` 어디에도 근거 등급이 없다. 로봇은 *"내가 E2 다"* 라고 말하지
않으며, 등급은 **미들웨어가 로봇의 보고와 설비 신호를 결합해 매기는 것**이다. 등급을 계약에 넣었다면
로봇이 자기 완료의 신뢰도를 자기가 선언하게 되고, 그것은 **확인하려는 대상에게 확인을 맡기는 것**이다.

---

## 5. 표준의 것인데 우리가 안 쓰는 것

| 안 쓰는 것 | 왜 |
|---|---|
| `due_by`(납기) | 납기는 배차의 입력이고 배차는 이 시스템 밖이다(§1.3). 쓰려면 정책을 발명해야 한다 |
| 별도 요청 id | 재전송 구분은 `(jobOrderId, version)` 이 이미 한다. **두 키로 같은 질문에 답하면 어긋나는 날이 온다** |
| 인원(Personnel) · 물리 자산(Physical Asset) 요구 | 소비자가 없다(ADR 9). 로봇 하나에 사람을 배정하는 모델이 이 PoC 에 없다 |
| 일정·배차 구조 전반 | ADR 36 층 ③ 의 일이고 이 저장소는 그 층을 최소로만 갖는다 |

둘은 처음부터 안 쓴 것이 아니라 **들고 있다가 뺀 것**이다(§15.117). 상류의 모양을 흉내내면서 아무도 안 읽는
값을 셋 들고 있었고, 계약에는 ADR 9 로 엄격히 막아 온 것을 **우리 모델 안에서는 하고 있었다.**
`materialRequirements` 만 소비자가 생겨 살아남았다 — 선언한 수량과 배정된 단위 수가 타입마다 같은지 보고,
어긋나면 **접수 자체를 거절한다.**
