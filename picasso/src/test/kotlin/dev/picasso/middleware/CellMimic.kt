package dev.picasso.middleware

/**
 * PLC/WCS Mimic — 셀 검증 장치·인계 설비의 신호를 **명세에 따라** 모사한다(보고서 4.3·12장).
 *
 * 하류 더블이다. 로봇의 mimic 이 프로파일대로 행동하듯 이것은 시험이 프로그램한
 * 대로 답한다 — 어느 자리에 무엇이 있다고 말할지를 시험이 정하고, 미들웨어는
 * 그것을 로봇 보고와 결합한다. 시간창 δ 와 래치 비트는 다음 단계에서 붙는다.
 *
 * **"Mock" 구간이다.** 실제 PLC 태그·OPC UA 노드는 여기 없다(보고서 8장 "검증 근거").
 */
class CellMimic : CellSignals {

    private val slots = mutableMapOf<String, SlotSignal>()

    /** 그 자리에 그 자재가 있다고 답하게 한다. */
    fun program(location: String, material: String?) {
        slots[location] = SlotSignal(occupied = true, material = material)
    }

    /** 그 자리는 비었다고 답하게 한다 — 신호 없음과 다르다. */
    fun empty(location: String) {
        slots[location] = SlotSignal(occupied = false, material = null)
    }

    /** 그 자리에 대해서는 말이 없게 한다. */
    fun silence(location: String) {
        slots.remove(location)
    }

    override fun observe(location: String): SlotSignal? = slots[location]
}
