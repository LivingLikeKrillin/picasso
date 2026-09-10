package dev.picasso.adapter.core

/**
 * 태스크가 **얼마나 갔는가** — 어댑터가 계약의 `WatchTaskResponse.progress`(0.0..1.0)로 내는 답.
 *
 * ## 둘인 이유
 *
 * [HoldObservation]·[FaultObservation]과 같은 이유다. 못 재는 기체에게 `0.0` 을 답하게 하면 *아직 아무것도 안
 * 됐다* 와 *잴 방법이 없다* 가 같은 값이 되고, 진행이 멈춘 것을 보고 개입하는 소비자가 **못 재는 기체를 영원히
 * 정체된 것으로** 읽는다. 그래서 어댑터 쪽에서는 둘을 가른다.
 *
 * ## 계약 면에서는 아직 안 갈린다
 *
 * **계약의 `progress` 는 맨 `double` 이고 *못 잰다* 를 실을 자리가 없다.** 호스트가 [NotObservable] 을 `0.0` 으로
 * 접으므로 그 구분은 어댑터 안에서 끝난다. 자리를 만들려면 계약을 고쳐야 하는데, 그 구분을 **읽고 다르게 행동하는
 * 소비자가 아직 없다** — 진행 정체로 개입을 판단하는 소비자가 생기는 날이 그날이다(§15.79 가 발신자 없는 거절 코드를
 * 안 만든 것과 같은 규율의 반대쪽).
 *
 * ## 무엇을 세는지를 함께 낸다
 *
 * [Fraction.basis] 는 사람이 읽는 근거다 — *"행동 3/7"*. 분수 하나만 내면 그 숫자가 무엇을 센 것인지 아무도 모르고,
 * 기종마다 세는 단위가 다르다는 사실이 숨는다.
 */
sealed interface ProgressObservation {

    /**
     * 셀 수 있는 근거가 있다.
     *
     * @param fraction 0.0..1.0. 범위 밖이면 호스트가 자른다.
     * @param basis 무엇을 셌는가. 진단과 사람이 읽는다.
     */
    data class Fraction(val fraction: Double, val basis: String) : ProgressObservation

    /** 못 잰다. **0 이 아니다.** */
    data class NotObservable(val reason: String) : ProgressObservation
}
