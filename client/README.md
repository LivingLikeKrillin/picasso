# `client` — 얇은 소비자

계약을 두드려 완료 기준을 증명하는 쪽. `harness` 가 이것으로 미믹과 어댑터 호스트를 돌리고, `picasso` 의
`ClientRobotPort` 가 이것을 감싼다.

---

## 이 모듈의 규칙 하나

★**기종을 모른다.** 게이트 7번이 이 모듈의 소스에 벤더·모델 문자열이 들어오면 실패시킨다 — *"능력 집합이 다른
두 로봇을 같은 클라이언트 코드로"*(완료 기준 1)가 문장이 아니라 **CI 실패 조건**인 자리다.

요구 집합도 코드가 아니라 **설정 파일**이다(`profile/requirements/`, §5.4). 코드에 적으면 그것이 곧 기종 지식이 된다.

---

## 무엇이 여기 있나

- `PicassoClient` — 계약 RPC 의 얇은 감싸개. 능력은 `capability_epoch` 단위로 캐시한다.
- `TaskFollower` / `EventConsumer` — `WatchTask` 스트림과 발행 구독의 소비자 쪽 규칙(재정렬 창, 멱등).
- `cli/Main.kt` — `client --target … --robot … --requirements …`.

---

## 정직하게 적어 둘 것

- **`ProfileDocument` 가 클래스패스에 들어온다**(§3.2 의 대가). 요구 집합 파일을 읽어야 해서인데, 얇은 소비자가
  그것을 읽기 시작하면 게이트 7번이 막으려는 그것이 된다 — `ClientBoundaryTest` 가 그 선을 지킨다.

> 마지막 대조: 2026-09-11 · sha256:a9eae880e048 · 열림: 없음
