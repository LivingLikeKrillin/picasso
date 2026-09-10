# `contracts` — 계약

**이 저장소의 중심.** 상류 소비자와 로봇 사이의 명령·질의(gRPC)와 발행(MQTT)의 모양, 그리고 그 구조를 채우는
규칙(§5.5 의 헤더 열, 계약 신원).

정본은 `proto/picasso/v1/` 의 다섯 파일이고 **주석이 문서다** — 왜 그 필드가 있는지, 왜 그 값 집합인지가 필드
옆에 있다. 이 문서는 그 앞에 서는 표지판이다.

---

## 이 모듈의 규칙 하나

★**프로젝트 내 의존이 0 이다.** 게이트 5번이 강제한다. 계약이 무언가에 의존하는 순간 그 무언가가 계약의 일부가
되고, 소비자가 그것까지 받아야 한다.

그리고 **값이나 제약을 담지 않는다.** *"최대 적재 5kg"* 은 계약이 아니라 프로파일이다 — 계약에 넣으면 기종이
늘 때마다 계약이 바뀐다.

---

## 무엇이 여기 있나

| 파일 | 무엇 |
|---|---|
| `common.proto` | 헤더, 거절 코드, `Support` 3값, 참조 |
| `task.proto` | 태스크의 생명주기 — 상태 열하나, `WatchTaskResponse`, `HoldState`, `ProgressBasis` |
| `skill.proto` | 능력의 투영 — `Capability`, 파라미터 선언, 협상 |
| `skill_catalog.proto` | 스킬 넷의 파라미터(`pick_place`·`navigate_to`·`inspect`·`move_relative`) |
| `event.proto` · `fault.proto` | 발행 셋과 결함 — 정준 실패 분류 열다섯 |
| `src/main/kotlin/…/wire/` | 헤더 열을 **채우는 규칙**. 발신자마다 두 벌로 쓰면 그것이 드리프트다 |

계약 개정판은 `build.gradle.kts` 의 `contractSemver` 가 정본이고, **자란 이력이 그 위 주석에 있다.**

---

## 자랄 때의 규율

**발신자와 소비자가 함께 있을 때만 자란다**(ADR 9). 0.4.0 의 `HoldState`, 0.6.0 의 `FailureClass`, 0.8.0 의
`ProgressBasis` 가 전부 그 순서로 들어왔다 — 필드를 먼저 만들고 쓸 사람을 기다린 적이 없다.

**추가는 minor, 파괴는 major.** 게이트 2번(`buf breaking`)이 그것을 집행한다.

---

## 정직하게 적어 둘 것

- **`contract_digest` 가 `buf` 모듈 다이제스트가 아니라 디스크립터 셋의 SHA-256 이다**(§15.22). 목적은 같고
  계산이 다르다 — `mimic` 이 Docker 없이 기동해야 하기 때문이다.
- **`update_index`·`sequence` 가 0 부터**라 헤더에서 미설정과 구별되지 않는다(§15.25). `schema_id` 가 방향을
  말하므로 실질 문제는 없다.
- **`GetCapabilitiesResponse` 에만 `Rejection` 자리가 없다**(§15.24) — 그 RPC 만 신원 불일치가 gRPC 상태로 나간다.
