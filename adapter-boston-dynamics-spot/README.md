# adapter-boston-dynamics-spot — Boston Dynamics Spot 어댑터

Boston Dynamics Spot 기체 전용 하드웨어 어댑터 모듈입니다 (ADR 33). 북쪽 인터페이스는 표준 `RobotAdapter`를 구현하고, 남쪽 포트는 Spot SDK의 gRPC 통신 표면을 추상화합니다.

---

## 1. 인터페이스 계약 적합성 및 도메인 분석

- 계약 적합성 정본: [`profile/distance/spot-arm.json`](../profile/distance/spot-arm.json)
- 벤더 API 전수 조사: [`profile/vendors/spot.json`](../profile/vendors/spot.json)

**스킬별 실행 계층의 분기 특성**: 스킬 유형에 따라 연동되는 벤더 제어 계층이 상이합니다.
- `navigate_to`: 미션 계층 (`LoadMission` → `PlayMission`)
- `move_relative`: 저수준 명령 계층 (`se2Velocity`)
- `inspect`: 데이터 취득 계층 (`AcquireData`)

이러한 하부 제어 계층의 차이는 상위 인터페이스 계약 소비자에게 투명하게 은닉되어야 합니다 (ADR 36 결정 5).

---

## 2. 어댑터 주요 구현 기능

- **로봇 내부 그래프 기반 사이트 명칭 매핑**: `DownloadGraph`의 웨이포인트 주석(Annotations)에서 사이트 이름을 질의하여 좌표를 획득하며, 어댑터 내부에 명칭 테이블을 하드코딩하지 않습니다 (ADR 35).
- **매니퓰레이터 암 장착 상태 결함 가시화**: `manipulator_state`가 부재하면 `ARM_ABSENT`, 상태 판독 실패 시 `HARDWARE_UNKNOWN` 결함으로 진단합니다.
- **미션 계층 런타임 갱신 지원**: `StopMission` → `LoadMission` → `PlayMission` 3단계 시퀀스를 통해 주행 미션의 동적 갱신을 수행합니다.
- **데이터 취득 결과 식별자 반환**: `DataIdentifier`를 계약의 `partial_result`로 상류에 전달합니다.

---

## 3. 미지원 기능 및 기술적 한계

- **정량 진행률 측정 불가 (§15.108)**: 저수준 명령 피드백은 진행 중/완료 2단계 상태만 제공하며, 데이터 취득(`GetStatusResponse.Status`) 피드백은 11개 국면(Phase)으로 응답하므로 연속적인 수치 진행률을 산출할 수 없습니다.
- **취득 계층 갱신 미지원**: `CancelAcquisition` 호출이 벤더에 의해 거절될 수 있어 결정론적 중단이 보장되지 않습니다.
- **`pick_place` 부분 지원 (PARTIAL)**: 하역은 시퀀스 합성을 통해 가능하나, 집기 동작의 타깃 지정이 3차원 점/픽셀 좌표를 요구하여 완전 자동화에 제약이 있습니다.

---

## 4. 모듈 경계 및 벤더 심볼 검증

저장소 내에 벤더 바이너리 SDK를 포함하지 않는 원칙에 따라, 남쪽 포트(`SpotLink`)는 인터페이스로만 선언되어 있습니다. 벤더 API 심볼 인용의 정합성은 `SpotVendorSurfaceTest`를 통해 `vendor-manifest.txt`와 전수 대조 검증되며, 물리 기체 연동 검증은 미결(C-3)로 관리됩니다.

> 마지막 대조: 2026-09-15 · sha256:1ab5cb17b057 · 열림: C-3
