package dev.picasso.adapter.g1

import dev.picasso.contracts.v1.Fault

/** 태스크를 받았는가. */
sealed interface Acceptance {
    data class Accepted(val taskId: String) : Acceptance
    data class Refused(val reason: Refusal, val detail: String) : Acceptance
}

/** 조작을 받았는가. 취소·일시정지가 공유한다. */
sealed interface Applied {
    data object Ok : Applied
    data class Refused(val reason: Refusal, val detail: String) : Applied
}

/**
 * 거절 사유.
 *
 * **[NO_SPORT_SERVICE]가 여기 있는 것이 이 열거의 요점이다.** 시뮬레이터를
 * 상대로 띄운 어댑터가 태스크를 받아 놓고 아무것도 안 하는 것이 가장 나쁜
 * 결과이며, 그 상태는 초록으로 보인다.
 */
enum class Refusal {

    /** 고수준 서비스가 없다 — 시뮬레이터이거나 `ai_sport`가 안 떠 있다. */
    NO_SPORT_SERVICE,

    /** 신원이 설정되지 않았다. 로봇이 말해 주지 않으므로 설정뿐이다. */
    IDENTITY_UNSET,

    /** 이 어댑터가 그 스킬을 안 든다. */
    UNSUPPORTED_SKILL,

    /** 계약이 필수로 둔 파라미터가 없다. */
    PARAMETER_MISSING,

    /** 벤더에게 그 수단이 없다 — 일시정지가 그 경우다. */
    NO_VENDOR_PRIMITIVE,

    /** 이미 종착한 태스크다(§4.4의 래치). */
    TERMINAL_LATCHED,

    /** 남쪽 호출이 실패했다. */
    LINK_ERROR,

    /** 이미 도는 태스크가 있다. 배타적 제어이므로 둘을 동시에 들지 않는다(§4.9). */
    ALREADY_RUNNING,

    /** 조작할 태스크가 없다. */
    NO_TASK,
}

/**
 * 결함을 봤는가, 아니면 못 봤는가.
 *
 * **빈 목록과 "모른다"를 나눈다.** 저수준 상태를 한 번도 못 받았을 때 결함
 * 없음을 답하면 과열된 기체가 정상으로 보이고, 그 위에 선 판단이 전부
 * 틀린다 — 원장의 `Observed`/`NotObservable`이 같은 이유로 갈라져 있다.
 */
sealed interface FaultObservation {
    data class Observed(val faults: List<Fault>) : FaultObservation
    data class NotObservable(val reason: String) : FaultObservation
}
