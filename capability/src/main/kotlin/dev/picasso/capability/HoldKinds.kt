package dev.picasso.capability

import dev.picasso.contracts.v1.HoldKind

/**
 * 파지 값의 **부류를 정하는 한 자리**.
 *
 * ## 왜 따로 두나
 *
 * 이 저장소는 파지 값을 보는 자리를 전부 `if` 비교로 두고 있었다 — `compare` 의 마지막 `else`,
 * 대안 탐색의 시작 상태 검사, 시험의 손으로 적은 열거. **값이 늘면 그 자리들이 조용히 빠진다.**
 * 미믹의 `holdOf` 는 태스크 상태에 대해 `else` 없이 분기하며 *"상태가 늘면 여기서 컴파일이 깨져야
 * 한다"* 고 적어 두었는데, 파지 값에는 같은 방벽이 없었다(§15.158).
 *
 * ## `else` 를 쓰지 않는다
 *
 * 값이 늘면 **여기서 컴파일이 깨진다.** 그 자리가 «새 값이 어느 부류인가» 를 사람이 정해야 하는 곳이고,
 * 정하고 나면 이것을 읽는 자리들이 따라온다. `else` 로 접으면 새 값이 «구체 관측이 아니다» 로 조용히
 * 분류되고, 그 분류가 맞는지 아무도 묻지 않는다.
 */
val HoldKind.isConcreteObservation: Boolean
    get() = when (this) {
        // 무엇을 들고 있는지(또는 안 들고 있는지)를 **말한 것**이다.
        HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING -> true

        // 볼 수 없었거나(관측 경로가 죽었다) 발신자가 말하지 않았다. **빈손이 아니라 침묵이다.**
        HoldKind.HOLD_KIND_NOT_OBSERVABLE, HoldKind.HOLD_KIND_UNSPECIFIED -> false

        // 이 판이 모르는 값. 상대가 더 새로울 때 여기 온다 — 모르는 것을 관측으로 세지 않는다.
        HoldKind.UNRECOGNIZED -> false
    }
