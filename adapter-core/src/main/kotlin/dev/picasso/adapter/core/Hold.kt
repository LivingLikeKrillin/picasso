package dev.picasso.adapter.core

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState

/**
 * 로봇이 **무엇을 들고 있는가** — 어댑터가 계약의 `HoldState`(task.proto)로 내는 답.
 *
 * ## 셋인 이유
 *
 * [FaultObservation]이 `Observed`/`NotObservable`을 가르는 것과 같은 이유다.
 * 관측 경로가 없거나 죽었을 때 "빈손"을 답하면 물건을 든 채 멈춘 기체가
 * 정리된 것으로 보이고, 그 위에서 소비자가 다음 태스크를 준다. §4.4의
 * `CANCELLED`/`CANCELLED_RECOVERY_FAILED` 구분이 바로 그 자리에서 죽는다.
 *
 * ## 기종을 모른다
 *
 * 여기에는 "왜 못 보는가"의 종류가 없다 — G1의 압력 센서도 Spot의 읽기 실패도
 * [NotObservable]의 `reason` 문자열 하나다. 종류를 열거하면 이 모듈이 기종을
 * 알게 되고, 게이트 7번이 그것을 막는다(ADR 33).
 */
sealed interface HoldObservation {

    /**
     * 들고 있다.
     *
     * @param objectRef 그것이 이 태스크의 대상이면 그 **이름**(계약의
     *   `is_object_reference` 파라미터 값). 벤더 id가 아니다(§15.78). 들고는
     *   있는데 무엇인지 모르면 `null` — 그 기체가 마지막에 받은 태스크의
     *   대상이라고 **짐작해 넣지 않는다.**
     */
    data class Holding(val objectRef: String?) : HoldObservation

    data object Empty : HoldObservation

    /** 볼 수 없다. 빈손이 아니다. */
    data class NotObservable(val reason: String) : HoldObservation

    fun toProto(): HoldState = when (this) {
        is Holding -> HoldState.newBuilder()
            .setKind(HoldKind.HOLD_KIND_HOLDING)
            .setObjectRef(objectRef ?: "")
            .build()

        Empty -> HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build()

        is NotObservable -> HoldState.newBuilder()
            .setKind(HoldKind.HOLD_KIND_NOT_OBSERVABLE)
            .setReason(reason)
            .build()
    }
}
