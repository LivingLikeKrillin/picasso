package dev.picasso.adapter.core

import dev.picasso.contracts.v1.HoldKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 어댑터의 답이 계약의 `HoldState`로 **정보를 잃지 않고** 건너가는가.
 *
 * 셋 다 본다 — 하나라도 빠지면 그 갈래는 컴파일은 되는데 건너가지 않는다.
 */
class HoldObservationTest {

    @Test
    fun `들고 있음은 대상의 이름을 싣는다`() {
        val proto = HoldObservation.Holding("SEQ-IN-02.BIN-A").toProto()
        assertEquals(HoldKind.HOLD_KIND_HOLDING, proto.kind)
        assertEquals("SEQ-IN-02.BIN-A", proto.objectRef)
        assertEquals("", proto.reason)
    }

    @Test
    fun `무엇인지 모르면 이름을 비운다 — 짐작해 넣지 않는다`() {
        val proto = HoldObservation.Holding(null).toProto()
        assertEquals(HoldKind.HOLD_KIND_HOLDING, proto.kind)
        assertEquals("", proto.objectRef)
    }

    @Test
    fun `빈손`() {
        val proto = HoldObservation.Empty.toProto()
        assertEquals(HoldKind.HOLD_KIND_EMPTY, proto.kind)
        assertEquals("", proto.objectRef)
    }

    @Test
    fun `볼 수 없음은 빈손이 아니고 이유를 싣는다`() {
        val proto = HoldObservation.NotObservable("벤더가 파지 판정을 주지 않는다").toProto()
        assertEquals(HoldKind.HOLD_KIND_NOT_OBSERVABLE, proto.kind)
        assertEquals("벤더가 파지 판정을 주지 않는다", proto.reason)
        assertEquals("", proto.objectRef)
    }
}
