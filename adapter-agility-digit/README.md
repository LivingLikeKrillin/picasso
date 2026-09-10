# `adapter-agility-digit` — Digit

기종을 아는 자리(ADR 33). 남쪽은 WebSocket JSON 이다.

## 이 기종이 계약에 얼마나 닿나

정본은 [`profile/distance/agility-digit.json`](../profile/distance/agility-digit.json) ·
[`profile/vendors/digit.json`](../profile/vendors/digit.json).

요점만: **대상을 이름으로 지시하는 유일한 기종이다.** `ObjectSelector{name, april_tag_id, has_attributes, …}`
가 질의 가능한 세계 모델의 선택자이고, `action-pick`·`action-place` 가 둘 다 그것을 받는다. **ADR 35(사이트
이름은 로봇 안에 산다)의 가장 강한 증거**다.

★이 기종의 측정은 한 번 통째로 뒤집혔다 — 제3자 래퍼를 근거로 판정 넷이 틀렸었다. **근거 등급이 낮으면 `NO`
가 아니라 `UNKNOWN`**(§15.65)이 거기서 나왔다.

## 이 어댑터가 가진 것

- **진행률을 낸다** — 실행 트리의 잎 중 `success` 를 센다. **분모가 서는 이유는 조건·분기가 0 건**이라서다.
- **갱신을 든다** — `remove-action` → 다시 보낸다. 다만 지운 뒤에도 `running` 이면 **새 액션을 안 얹는다**
  (매뉴얼이 예고한 컨테이너 거동 — 얹으면 둘이 겹쳐 돈다).
- **파지를 추론한다** — 벤더가 파지를 발행하지 않아 실행 트리에서 읽는다. 트리에 `action-pick` 이 없으면
  **빈손이 아니라 모른다**.

## 못 하는 것

- **일시정지가 없다** — SDK 메시지 전수에 없다. `remove-action` 은 지우기이지 재개가 아니다.
- **`verify_grasp` 를 선언하지 않는다** — 그래서 미들웨어가 그 선택 파라미터를 이 기종에는 안 보낸다(§15.116).

## 남쪽에 전송이 없다

`DigitLink` 는 인터페이스다. 인용만 매니페스트와 대조된다. 실물 확인은 열려 있다(C-3).

> 마지막 대조: 2026-09-11 · sha256:b972ba2b5c14 · 열림: C-3
