# harness — 인터페이스 계약 적합성 통합 테스트 하네스 (Contract Test Harness)

인터페이스 계약 계층(제4계층)의 완료 기준과 아키텍처 불변식을 기계적으로 입증하기 위한 종단 간 통합 테스트 프레임워크입니다. `mimic` 에뮬레이터 또는 `adapter-host` 프로세스를 기동하고 `client` SDK로 호출하여, 계약이 약속한 상태 전이와 복구 메커니즘이 실제 환경에서 정확히 수행되는지 검증합니다.

---

## 1. 아키텍처 특성: 에뮬레이터와 실물 경로의 유일한 동시 인지 모듈

시스템 설계상 `mimic`과 `adapter-host`는 상호 독립적이며 서로를 전혀 인지하지 않습니다 (§3.2). 그러나 *"클라이언트는 엔드포인트 URL만 변경하여 에뮬레이터와 실물 어댑터를 완전히 동일하게 제어할 수 있어야 한다"*는 계약 투명성을 검증하기 위해, 본 모듈은 두 경로를 나란히 배치하고 동등한 거동을 산출하는지 동치성을 검증합니다 (`HostParityTest`).

---

## 2. 테스트 스위트 구성

- `Harness` · `ContractSuite`: 에뮬레이터를 기동하고 제어 채널을 통해 가상 시계 및 결함을 조작하는 테스트 기반 런처.
- **완료 기준별 적합성 테스트**:
  - `A1Test`: 동일한 클라이언트 코드로 상이한 능력의 2개 기종 제어 검증
  - `ReconstructionTest`: 중간 구독자의 과거 이벤트 스트림 스냅샷 재구성 검증
  - `TransportFaultTest`: 패킷 결손, 중복 전달, 역전 발생 시 복구 검증
  - `SilenceTest`: 통신 침묵(Silence) 3대 원인별 타임아웃 감지 검증
  - `RetryTest`, `CancelRecoveryTest`: 멱등 재시도 및 취소 복구 절차 검증
  - `TerminalViolationTest`, `ControlAuthorityTest`: 종착 상태 위반 및 권한 제어 검증
  - `HandshakeTest`, `DegradationTest`, `RevisionSwitchTest`: 버전 핸드셰이크, 기능 강등, 개정판 전환 검증
  - `CanaryTest`, `LedgerObservationTest`: 카나리 배포 및 원장 관측 검증
- **도메인 시나리오 통합 테스트**: `SequencingCellTest` (시나리오 ② 계약 계층), `InspectionPatrolTest` (시나리오 ③)
- **종단 간(End-to-End) 통합 테스트**: `LedgerIngestEndToEndTest` (에뮬레이터 → 원장), `HostIngestEndToEndTest` (어댑터 → 원장), `OrbitDiscoveryEndToEndTest` (발견 → 원장), `MqttBrokerTest` (실제 브로커 연동)

---

## 3. 테스트 실행 환경 및 알려진 한계

- **컨테이너 환경 의존성 (§15.39)**: 카나리 테스트는 실제 레지스트리 컨테이너를 구동하고, 브로커 테스트는 실제 MQTT 브로커 컨테이너(Docker) 환경을 요구합니다. 검증 신뢰성을 위해 컨테이너 부재 시 임의로 성공 처리하지 않습니다.
- **실물 기체 연동 한계**: 본 하네스의 검증 성공은 소프트웨어 계약 계층까지의 정합성을 보증하며, 물리 하드웨어 실물에 대한 연동 검증(C-3)은 분리되어 있습니다 (상세 검증 등급: [`docs/verification.md`](../docs/verification.md)).

> 마지막 대조: 2026-09-15 · sha256:bcb414ff4888 · 열림: C-3, §15.39
