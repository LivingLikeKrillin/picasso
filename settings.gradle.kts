rootProject.name = "picasso"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include("contracts", "profile-model", "gate", "mimic", "client", "harness", "registry")

// 프로파일 → Capability 투영. mimic 과 어댑터 호스트가 공유한다(ADR 29 의 이유 그대로).
include("profile-projection")

// 발신자의 위쪽 결선 — 브로커 발행과 레지스트리 적재. mimic 과 어댑터 호스트가 공유한다.
include("uplink")

// 어댑터의 북쪽 — RobotAdapter 하나를 계약의 gRPC 서비스 뒤에 세운다. 기종을 모른다.
include("adapter-host")

// 미들웨어의 가운데 — 정준 모델과 공통 실행 구조(ADR 38). 이름이 picasso 인 것은
// 이것이 곧 이 저장소가 만드는 것이기 때문이다. 나머지는 그 부품이다.
include("picasso")

// 어댑터는 기종마다 모듈 하나다(ADR 33). 게이트 7번의 대상 셋
// (client·mimic·harness) 밖에 두는 것이 요점이며, 기종을 아는 코드가 갈
// 곳이 여기라서 나머지가 기종을 모를 수 있다.
include(
    "adapter-core",
    "adapter-unitree-g1",
    "adapter-boston-dynamics-spot",
    "adapter-agility-digit",
)
