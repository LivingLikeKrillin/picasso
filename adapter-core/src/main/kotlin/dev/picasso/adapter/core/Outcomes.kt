package dev.picasso.adapter.core

import dev.picasso.contracts.v1.Fault

/**
 * 어댑터가 계약 쪽으로 내는 답의 어휘.
 *
 * ## 왜 모듈이 생겼나
 *
 * ADR 33이 어댑터를 기종마다 한 모듈로 두면서 대가를 적어 두었다 —
 * *"두 번째 어댑터가 생길 때 중복이 보이면 공통을 뽑아야 하는데, 그 공통
 * 모듈은 다시 기종을 몰라야 하고 그때 검사 7번의 목록에 더해야 한다.
 * 지금은 그 모듈이 없으므로 안 더한다."*
 *
 * 두 번째 어댑터(Spot)에서 중복이 실제로 보였다. 이 모듈이 그 답이며 게이트
 * 7번의 대상 목록에 함께 들어갔다 — **기종을 모르는 것이 이 모듈의 조건**이고,
 * 검사가 그것을 지킨다.
 *
 * ## 여기 있는 것과 없는 것
 *
 * 계약 쪽 어휘만 있다. 벤더 개념은 하나도 없다 — [Refusal.VENDOR_SURFACE_ABSENT]가
 * G1의 `sport` 서비스와 Spot의 미션 계층을 **같은 이름으로** 덮는 것이 그 증거다.
 * 둘을 각자의 이름으로 두면 이 모듈이 기종을 알게 된다.
 */
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
 * **[VENDOR_SURFACE_ABSENT]가 여기 있는 것이 이 열거의 요점이다.** 어댑터가
 * 붙긴 했는데 필요한 표면이 안 떠 있는 상태 — G1을 시뮬레이터에 붙였을 때의
 * `sport` 서비스, Spot에서 미션 서비스가 없을 때의 `navigate_to` — 에서
 * 태스크를 받아 놓고 아무것도 안 하는 것이 가장 나쁜 결과이며, 그 상태는
 * 초록으로 보인다.
 */
enum class Refusal {

    /**
     * 이 스킬이 필요로 하는 벤더 표면이 없다.
     *
     * 두 실물에서 각각 다른 모습으로 나타난다 — G1은 시뮬레이터가 고수준
     * `sport` 서비스를 아예 안 답하고, Spot은 명령 계층만 있고 미션 계층이
     * 없으면 `navigate_to`를 못 든다(`move_relative`는 든다). **표면이
     * 스킬마다 다르다는 것이 Spot에서 처음 드러났다.**
     */
    VENDOR_SURFACE_ABSENT,

    /** 신원이 설정되지 않았다. 로봇이 말해 주지 않는 기종에서는 설정뿐이다. */
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

    /**
     * 제어 권한을 잃었다(§4.9).
     *
     * **Spot에서 처음으로 기계적 근거가 생겼다** — `LeaseUseResult.Status`에
     * `STATUS_REVOKED`·`STATUS_OLDER`·`STATUS_WRONG_EPOCH`가 있다. G1은 lease
     * id가 있어도 인증이 없어 같은 것을 못 채운다.
     */
    CONTROL_AUTHORITY_LOST,

    /** 이미 도는 태스크가 있다. 실물은 예외 없이 배타적 제어 모델이다(§4.9). */
    ALREADY_RUNNING,

    /** 조작할 태스크가 없다. */
    NO_TASK,
}

/**
 * 결함을 봤는가, 아니면 못 봤는가.
 *
 * **빈 목록과 "모른다"를 나눈다.** 관측 경로가 죽었을 때 결함 없음을 답하면
 * 고장난 기체가 정상으로 보이고 그 위에 선 판단이 전부 틀린다 — 원장의
 * `Observed`/`NotObservable`이 같은 이유로 갈라져 있다.
 */
sealed interface FaultObservation {
    data class Observed(val faults: List<Fault>) : FaultObservation
    data class NotObservable(val reason: String) : FaultObservation
}

/**
 * 기체의 신원.
 *
 * **기종마다 이것을 아는 정도가 다르다.** G1은 신원을 묻는 질의가 아예 없어
 * 설정이 유일한 출처이고, Spot은 `RobotIdService.GetRobotId`가
 * `serial_number`·`species`·`software_release`를 준다. 지금은 둘 다 설정으로
 * 받되, Spot 쪽은 붙이는 날 **대조가 가능하다**는 것이 다르다.
 */
data class AdapterIdentity(val vendor: String, val model: String, val robotId: String) {

    /** 비어 있는 좌표로는 등록도 보고도 의미가 없다. */
    val complete: Boolean
        get() = vendor.isNotBlank() && model.isNotBlank() && robotId.isNotBlank()
}
