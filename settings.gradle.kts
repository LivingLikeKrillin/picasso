rootProject.name = "picasso"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include("contracts", "profile-model", "gate", "mimic", "client", "harness", "registry")

// 어댑터는 기종마다 모듈 하나다(ADR 33). 게이트 7번의 대상 셋
// (client·mimic·harness) 밖에 두는 것이 요점이며, 기종을 아는 코드가 갈
// 곳이 여기라서 나머지가 기종을 모를 수 있다.
include(
    "adapter-core",
    "adapter-unitree-g1",
    "adapter-boston-dynamics-spot",
    "adapter-agility-digit",
)
