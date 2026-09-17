package dev.picasso.middleware

import java.time.Duration
import java.time.Instant

/**
 * PLC/WCS Mimic — 셀 검증 장치·인계 설비의 신호를 **명세에 따라** 모사한다(보고서 4.3·12장).
 *
 * 하류 더블이다. 로봇의 mimic 이 프로파일대로 행동하듯 이것은 시험이 프로그램한
 * 대로 답한다 — 어느 자리에 무엇이 있다고 말할지를 시험이 정하고, 미들웨어는
 * 그것을 하류 보고와 시간창 안에서 결합한다.
 *
 * ## 신호의 세 모양 (보고서 12.2)
 *
 * - [program] — 현재값. 시각을 안 주는 PLC 처럼 **읽는 순간이 `t_p`** 다. [programAt] 은 시각을 준다.
 * - [pulse] — 짧게 켜졌다 꺼지는 센서. 그 시간 안에 읽지 않으면 **놓친다**. 이것이 폴링 주기가
 *   신호 지속 시간보다 길 때 생기는 일이며, 보고서가 PLC 쪽 **래치 비트**를 요구하라고 한 이유다.
 * - [latch] — 그 자리에 래치 비트를 둔다. 펄스가 지나가도 마지막 펄스가 **시각과 함께** 남는다.
 *
 * [live] 를 주면 프로그램된 값이 없는 자리는 그 세계를 읽는다 — 시나리오 ①에서
 * 플릿 더블이 실제로 내려놓은 용기를 인계 설비가 **보는** 것을 흉내낸다. 침묵시킨
 * 자리는 세계가 어떻든 말이 없다.
 *
 * **"Mock" 구간이다.** 실제 PLC 태그·OPC UA 노드는 여기 없다(보고서 8장 "검증 근거").
 */
class CellMimic(
    private val now: () -> Instant = { Instant.now() },
    private val live: (String) -> Pair<String, Instant?>? = { null },
) : CellSignals {

    private class Pulse(val identity: String?, val at: Instant, val lasting: Duration)

    private val programmed = mutableMapOf<String, SlotSignal>()
    private val pulses = mutableMapOf<String, Pulse>()
    private val latched = mutableSetOf<String>()
    private val silenced = mutableSetOf<String>()

    /**
     * 이 자재를 든 자리들. **침묵한 자리는 안 센다** — 신호가 없는 자리를 «없다» 에 넣으면 그것이
     * 곧 없다는 답이 된다.
     */
    override fun holding(material: String): List<String> = programmed
        .filterKeys { it !in silenced }
        .filterValues { it.occupied && it.identity == material }
        .keys.sorted()

    /** 그 자리에 그것(부품 타입·용기 태그)이 있다고 답하게 한다. 시각은 안 준다 — 읽는 순간이다. */
    fun program(location: String, identity: String?) {
        silenced -= location
        pulses.remove(location)
        programmed[location] = SlotSignal(occupied = true, identity = identity, observedAt = null)
    }

    /** 그 자리에 그것이 **그 시각부터** 있다고 답하게 한다. */
    fun programAt(location: String, identity: String?, at: Instant) {
        silenced -= location
        pulses.remove(location)
        programmed[location] = SlotSignal(occupied = true, identity = identity, observedAt = at)
    }

    /** 그 자리는 비었다고 답하게 한다 — 신호 없음과 다르다. */
    fun empty(location: String) {
        silenced -= location
        pulses.remove(location)
        programmed[location] = SlotSignal(occupied = false, identity = null)
    }

    /** 짧게 켜졌다 꺼지는 신호. [lasting] 안에 읽지 않으면 놓친다 — 래치가 없는 한. */
    fun pulse(location: String, identity: String?, at: Instant = now(), lasting: Duration) {
        silenced -= location
        programmed.remove(location)
        pulses[location] = Pulse(identity, at, lasting)
    }

    /** 그 자리에 PLC 쪽 래치 비트를 둔다 — 지나간 펄스가 시각과 함께 남는다. */
    fun latch(location: String) {
        latched += location
    }

    /** 그 자리에 대해서는 말이 없게 한다. 세계에 무엇이 있든. */
    fun silence(location: String) {
        programmed.remove(location)
        pulses.remove(location)
        silenced += location
    }

    override fun observe(location: String): SlotSignal? {
        if (location in silenced) return null
        programmed[location]?.let { return it }
        pulses[location]?.let { pulse ->
            val t = now()
            val on = !t.isBefore(pulse.at) && !t.isAfter(pulse.at.plus(pulse.lasting))
            return if (on || location in latched) SlotSignal(occupied = true, identity = pulse.identity, observedAt = pulse.at) else null
        }
        return live(location)?.let { (identity, at) -> SlotSignal(occupied = true, identity = identity, observedAt = at) }
    }
}
