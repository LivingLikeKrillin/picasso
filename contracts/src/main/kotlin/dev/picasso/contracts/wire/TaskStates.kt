package dev.picasso.contracts.wire

import dev.picasso.contracts.v1.TaskState

/**
 * 계약이 선언하는 **종착 집합**(§4.5).
 *
 * ## 왜 계약이 이것을 말해야 하는가
 *
 * §9.3의 드레인 판정이 `NOT terminal` 카운트다. 그것을 세는 것은 `registry`인데,
 * 종착이 무엇인지 아는 것은 여태 `mimic`의 엔진 enum뿐이었다. `registry`가
 * 그 목록을 **두 번째로 적으면** `mimic`이 종착 상태를 하나 더하는 날
 * `registry`는 그것을 비종착으로 세고, **드레인이 영원히 0이 안 되어 축소가
 * 영원히 막힌다.** 막히는 쪽이라 사고는 안 나지만 기능이 죽고, 죽은 것이
 * 안 보인다 — 축소는 원래도 자주 막히기 때문이다.
 *
 * proto enum에는 술어를 붙일 수 없으므로(값 옵션은 디스크립터를 읽는 쪽마다
 * 해석기가 필요하다) 계약 모듈의 **코드**가 그 자리를 맡는다. `contracts`는
 * 프로젝트 내 의존이 0이므로 누구나 이것을 쓸 수 있다.
 *
 * ## 두 벌인 것을 시험이 붙든다
 *
 * `mimic`은 자기 엔진 enum을 계속 갖는다 — 상태 기계의 전이표가 거기 있고,
 * 계약 enum에 전이를 붙이면 생성 코드에 도메인을 얹게 된다. 그래서 두 벌이
 * 남고, **`mimic` 쪽의 망라 시험이 둘을 붙들어 맨다**(그쪽이 두 enum을 다
 * 안다). 새 상태가 생기면 그 시험이 먼저 깨진다.
 */
object TaskStates {

    /**
     * §4.5의 종착 다섯.
     *
     * **`CANCELLING`은 종착이 아니다.** 취소 요청을 받고 되돌리는 중이며
     * 로봇은 아직 물건을 들고 있다 — 그것을 종착으로 세면 §9.3의 드레인이
     * *"아직 움직이는 로봇"*을 비었다고 판정한다.
     *
     * **`RETRIABLE`·`NEEDS_INTERVENTION`도 종착이 아니다.** 둘 다 `RetryTask`를
     * 기다리는 상태이고(task.proto), 재시도가 오면 `attempt`가 올라 다시 돈다.
     * 종착으로 세면 사람이 개입하러 오는 사이에 능력이 제거된다.
     */
    val TERMINAL: Set<TaskState> = setOf(
        TaskState.TASK_STATE_SUCCEEDED,
        TaskState.TASK_STATE_FAILED,
        TaskState.TASK_STATE_CANCELLED,
        TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
    )

    /**
     * **`UNSPECIFIED`는 종착이 아니다.** 모르는 값을 종착으로 접으면 낯선
     * 발신자의 태스크가 드레인에서 사라지고, 그 위에서 축소가 열린다.
     * 모르면 "아직 돌고 있다"가 안전한 쪽이다.
     */
    fun isTerminal(state: TaskState): Boolean = state in TERMINAL
}

/** [TaskStates.isTerminal]의 확장 형태. 읽는 자리에서 술어처럼 보인다. */
val TaskState.isTerminal: Boolean get() = TaskStates.isTerminal(this)
