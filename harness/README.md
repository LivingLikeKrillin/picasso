# `harness` — 계약 스위트의 주인

**계약 층의 완료 기준을 증명하는 자리.** `mimic` 을 띄우고 `client` 로 두드려, 계약이 약속한 것이 실제로 그렇게
되는지 본다. 여기가 초록인 것이 이 저장소의 주장이다.

---

## 이 모듈의 규칙 하나

★**둘을 동시에 아는 유일한 모듈이다.** `mimic` 도 `adapter-host` 도 서로를 모르는 것이 §3.2 인데, *"소비자는
엔드포인트만 바꿔 미믹과 실물을 오간다"* 를 확인하려면 둘을 나란히 세워야 한다. 그 자리가 여기다
(`HostParityTest`).

---

## 무엇이 여기 있나

- `Harness` · `ContractSuite` — 미믹을 띄우고 제어 채널로 조종하는 뼈대. 시험이 `main` 소스를 쓴다.
- 완료 기준별 시험 — `A1Test`(같은 코드로 두 기종) · `ReconstructionTest`(중간 구독자) ·
  `TransportFaultTest`(결손·중복·역전) · `SilenceTest`(침묵의 세 원인) · `RetryTest` · `CancelRecoveryTest` ·
  `TerminalViolationTest` · `ControlAuthorityTest` · `HandshakeTest` · `DegradationTest` · `RevisionSwitchTest` ·
  `CanaryTest` · `LedgerObservationTest` …
- **시나리오** — `SequencingCellTest`(②의 계약 층) · `InspectionPatrolTest`(③).
- **끝에서 끝까지** — `LedgerIngestEndToEndTest`(미믹→원장) · `HostIngestEndToEndTest`(어댑터→원장) ·
  `OrbitDiscoveryEndToEndTest`(발견→원장) · `MqttBrokerTest`(진짜 브로커).

---

## 정직하게 적어 둘 것

- **Docker 가 필요하다**(§15.39). 카나리 시험이 진짜 레지스트리를 쓰고 브로커 시험이 컨테이너를 띄운다.
  건너뛰기로 만들지 않은 것이 의도다 — **건너뛴 카나리는 초록으로 보인다.**
- **여기가 초록인 것은 계약 층까지다.** 실물 로봇에 대고 돌린 적이 없다(C-3) — 구간별 등급은
  [`docs/verification.md`](../docs/verification.md).

> 마지막 대조: 2026-09-11 · sha256:2cec020607e7 · 열림: C-3, §15.39, §15.125
