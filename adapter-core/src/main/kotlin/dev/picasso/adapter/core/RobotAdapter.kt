package dev.picasso.adapter.core

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import java.time.Instant

/**
 * 어댑터의 **북쪽 모양** — 계약 서버(어댑터 호스트)가 기종을 모른 채 부르는 것.
 *
 * 세 어댑터(Spot·Digit·G1)가 같은 메서드 열을 각자 갖고 있었다. 호스트가 생기면서 그 열이 인터페이스가
 * 됐다 — 호스트는 이것만 알고, 기종은 구현 뒤에 있다(ADR 33). **판단은 여기 없다.** 어댑터는 로봇이 말한
 * 것을 계약의 낱말로 옮길 뿐이고, 무엇을 할지는 계약 소비자(미들웨어)가 정한다.
 *
 * **태스크는 한 번에 하나다.** 실물은 예외 없이 배타적 제어 모델이고(§4.9), 세 어댑터가 모두 그렇게 산다.
 * 동시 태스크가 필요하면 기체마다 어댑터 인스턴스를 둔다(§15.77).
 *
 * 기본 구현이 있는 것들은 **벤더에 그 프리미티브가 없는 것이 흔해서**다 — 재개·재시도는 셋 중 아무도 안 들고,
 * 결과 참조는 Spot 의 취득만 낸다. 기본값은 "없다" 이지 "된다" 가 아니다.
 */
interface RobotAdapter {

    /** 지금 든 태스크의 상태. 아무것도 안 들었으면 `UNSPECIFIED`. */
    val state: TaskState

    fun accept(skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance

    /** 상태를 갱신하고 돌려준다. 호스트가 펌프마다 부른다. */
    fun poll(now: Instant): TaskState

    fun pause(): Applied

    fun resume(): Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "이 어댑터에는 재개가 없다")

    fun cancel(): Applied

    fun retry(): Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "이 어댑터에는 재시도가 없다")

    /** 잔여 물리 상태(§4.4). */
    fun hold(): HoldObservation

    /** 기체 수준 결함(§4.6). */
    fun faults(): FaultObservation

    /** 종착이 실패인 태스크의 `Fault`(정준 분류 + 벤더 원문). 실패가 아니거나 못 옮기면 `null`. */
    fun failure(): Fault? = null

    /** 종착이 성공인 태스크의 결과 참조 — 계약의 `partial_result`. 낼 것이 없으면 `null`. */
    fun result(): String? = null

    /** ADR 35 의 확인 질의. */
    fun knownSiteNames(): SiteNames
}
