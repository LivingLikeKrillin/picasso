# `adapter-boston-dynamics-spot` — Spot

기종을 아는 자리(ADR 33). 북쪽은 `RobotAdapter`, 남쪽은 Spot SDK 의 gRPC 표면이다.

## 이 기종이 계약에 얼마나 닿나

정본은 [`profile/distance/spot-arm.json`](../profile/distance/spot-arm.json) 이고, 벤더 조사는
[`profile/vendors/spot.json`](../profile/vendors/spot.json) 이다. **여기 숫자를 옮겨 적지 않는다** — 두 곳에
같은 판정을 두면 어긋난다.

요점만: **층이 넷이고 스킬마다 붙는 층이 다르다.** `navigate_to` 는 미션 층(`LoadMission`→`PlayMission`),
`move_relative` 는 명령 층(`se2Velocity`), `inspect` 는 취득 층(`AcquireData`). 그 차이가 계약 쪽에서
보이지 않아야 한다는 것이 ADR 36 결정 5 다.

## 이 어댑터가 가진 것

- **이름을 로봇의 표에 묻는다** — `DownloadGraph` 의 웨이포인트 주석에서 사이트 이름을 찾는다. 어댑터가 표를
  들지 않는다(ADR 35). ★앞 판은 사람 이름을 웨이포인트 id 자리에 그대로 넘기고 있었고 그것이 A-1 위반이었다.
- **팔 유무를 결함으로 드러낸다** — `manipulator_state` 가 비면 `ARM_ABSENT`, 못 읽으면 `HARDWARE_UNKNOWN`.
  막지는 않는다.
- **갱신을 든다** — `StopMission` → `LoadMission` → `PlayMission`. 셋을 다 들어야 갱신이다.
- **취득의 결과 참조를 낸다** — `DataIdentifier` 가 `partial_result` 로 올라간다.

## 못 하는 것

- **진행률을 못 낸다.** 명령 피드백은 `IN_PROGRESS`/`COMPLETE` 둘이고 취득은 국면 열하나다 — **국면은 분수가
  아니다**(§15.108).
- **취득 층의 갱신을 안 든다.** `CancelAcquisition` 이 거절할 수 있어 **멈춤이 보장되지 않는다**.
- **`pick_place` 는 반만 열린다**(PARTIAL) — 놓기는 합성으로 되지만 집기의 대상 지시가 3D 점·픽셀이다.

## 남쪽에 전송이 없다

`SpotLink` 는 인터페이스이고 구현은 시험의 가짜뿐이다 — 벤더 원문을 저장소에 안 들이는 규칙이 막는다.
검사받는 것은 **인용**이다(`SpotVendorSurfaceTest` ↔ `vendor-manifest.txt`). 실물 확인은 열려 있다(C-3).

> 마지막 대조: 2026-09-11 · sha256:14045689a166 · 열림: C-3
