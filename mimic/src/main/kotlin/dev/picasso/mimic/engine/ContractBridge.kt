package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.Resolution as ProtoResolution
import dev.picasso.contracts.v1.SkillState as ProtoSkillState
import dev.picasso.contracts.v1.TaskState as ProtoTaskState

/**
 * 엔진의 값 어휘를 계약의 enum으로 옮긴다.
 *
 * **엔진이 자체 enum을 두는 이유:**
 *
 * - 계약 enum은 proto3라 `*_UNSPECIFIED`와 `UNRECOGNIZED`를 **강제로** 갖는다.
 *   엔진에는 그 둘에 대해 정의된 거동이 없고, `when`에 넣으면 §4.4 표에 없는
 *   두 행이 영구히 생긴다. `UNRECOGNIZED`는 `getNumber()`가 예외를 던지는
 *   특수 값이라 도메인 로직에 둘 것이 못 된다.
 * - `isTerminal` 같은 도메인 술어를 생성 코드에 붙일 수 없다. 확장 함수로
 *   빼면 `when`에 `else`가 필요해지고 §12.1의 망라성이 무너진다.
 * - 엔진은 **값 어휘의 소비자**이지 wire 형식의 소비자가 아니다.
 *
 * **두 벌을 두면서 다리를 안 걸면 Chunk 1 결정 1과 모순이다** — 두 벌로 쓰면
 * 이 프로젝트가 막으려는 드리프트를 우리가 낸다. 아래는 전부 총함수이고
 * `else` 가지가 없다. 엔진 쪽이 늘면 컴파일이 깨지고, 계약 쪽이 늘면
 * `ContractBridgeTest`의 전수 대조가 잡는다.
 */
fun TaskState.toProto(): ProtoTaskState = when (this) {
    TaskState.ACCEPTED -> ProtoTaskState.TASK_STATE_ACCEPTED
    TaskState.RUNNING -> ProtoTaskState.TASK_STATE_RUNNING
    TaskState.PAUSED -> ProtoTaskState.TASK_STATE_PAUSED
    TaskState.CANCELLING -> ProtoTaskState.TASK_STATE_CANCELLING
    TaskState.RETRIABLE -> ProtoTaskState.TASK_STATE_RETRIABLE
    TaskState.NEEDS_INTERVENTION -> ProtoTaskState.TASK_STATE_NEEDS_INTERVENTION
    TaskState.SUCCEEDED -> ProtoTaskState.TASK_STATE_SUCCEEDED
    TaskState.FAILED -> ProtoTaskState.TASK_STATE_FAILED
    TaskState.CANCELLED -> ProtoTaskState.TASK_STATE_CANCELLED
    TaskState.CANCELLED_RECOVERY_FAILED -> ProtoTaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED
}

fun SkillState.toProto(): ProtoSkillState = when (this) {
    SkillState.READY -> ProtoSkillState.SKILL_STATE_READY
    SkillState.RUNNING -> ProtoSkillState.SKILL_STATE_RUNNING
    SkillState.SUSPENDED -> ProtoSkillState.SKILL_STATE_SUSPENDED
    SkillState.HALTED -> ProtoSkillState.SKILL_STATE_HALTED
}

fun ProtoResolution.toEngine(): Resolution = when (this) {
    ProtoResolution.RESOLUTION_SELF_RETRIABLE -> Resolution.SELF_RETRIABLE
    ProtoResolution.RESOLUTION_NEEDS_INTERVENTION -> Resolution.NEEDS_INTERVENTION
    ProtoResolution.RESOLUTION_TERMINAL -> Resolution.TERMINAL
    // 프로파일이 스키마를 통과했다면 여기 오지 않는다. 오면 스키마와 계약이
    // 어긋난 것이고 그것은 게이트 3·4번이 잡아야 할 일이다.
    ProtoResolution.RESOLUTION_UNSPECIFIED,
    ProtoResolution.UNRECOGNIZED,
    -> error("프로파일이 선언하지 않은 Resolution이다: $this")
}

fun Resolution.toProto(): ProtoResolution = when (this) {
    Resolution.SELF_RETRIABLE -> ProtoResolution.RESOLUTION_SELF_RETRIABLE
    Resolution.NEEDS_INTERVENTION -> ProtoResolution.RESOLUTION_NEEDS_INTERVENTION
    Resolution.TERMINAL -> ProtoResolution.RESOLUTION_TERMINAL
}
