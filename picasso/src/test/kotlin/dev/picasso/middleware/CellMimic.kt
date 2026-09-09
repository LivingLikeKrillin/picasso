package dev.picasso.middleware

/**
 * PLC/WCS Mimic — 셀 검증 장치·인계 설비의 신호를 **명세에 따라** 모사한다(보고서 4.3·12장).
 *
 * 하류 더블이다. 로봇의 mimic 이 프로파일대로 행동하듯 이것은 시험이 프로그램한
 * 대로 답한다 — 어느 자리에 무엇이 있다고 말할지를 시험이 정하고, 미들웨어는
 * 그것을 하류 보고와 결합한다. 시간창 δ 와 래치 비트는 다음 단계에서 붙는다.
 *
 * [live] 를 주면 프로그램된 값이 없는 자리는 그 세계를 읽는다 — 시나리오 ①에서
 * 플릿 더블이 실제로 내려놓은 용기를 인계 설비가 **보는** 것을 흉내낸다. 침묵시킨
 * 자리는 세계가 어떻든 말이 없다.
 *
 * **"Mock" 구간이다.** 실제 PLC 태그·OPC UA 노드는 여기 없다(보고서 8장 "검증 근거").
 */
class CellMimic(private val live: (String) -> String? = { null }) : CellSignals {

    private val programmed = mutableMapOf<String, SlotSignal>()
    private val silenced = mutableSetOf<String>()

    /** 그 자리에 그것(부품 타입·용기 태그)이 있다고 답하게 한다. */
    fun program(location: String, identity: String?) {
        silenced -= location
        programmed[location] = SlotSignal(occupied = true, identity = identity)
    }

    /** 그 자리는 비었다고 답하게 한다 — 신호 없음과 다르다. */
    fun empty(location: String) {
        silenced -= location
        programmed[location] = SlotSignal(occupied = false, identity = null)
    }

    /** 그 자리에 대해서는 말이 없게 한다. 세계에 무엇이 있든. */
    fun silence(location: String) {
        programmed.remove(location)
        silenced += location
    }

    override fun observe(location: String): SlotSignal? {
        if (location in silenced) return null
        programmed[location]?.let { return it }
        return live(location)?.let { SlotSignal(occupied = true, identity = it) }
    }
}
