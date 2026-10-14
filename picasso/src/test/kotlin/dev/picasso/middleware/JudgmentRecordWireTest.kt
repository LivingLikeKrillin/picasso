package dev.picasso.middleware

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 판단 기록의 인코딩([JudgmentRecordWire]). 담는 쪽이 저장소에 적었다가 재기동 뒤 읽어 오는 모양이다.
 *
 * 읽기는 **전부 아니면 거부**다. 일부만 읽어 다시 지으면 못 읽은 사실이 «없었다» 가 된다 — 그래서 모르는 판·모르는 칸·빠진 칸·
 * 판정이 아닌 상태를 하나씩 넣어 거부를 본다.
 */
class JudgmentRecordWireTest {

    @Test
    fun `판단 기록은 적었다 읽으면 같다`() {
        val text = JudgmentRecordWire.encode(FULL)
        assertEquals(FULL, JudgmentRecordWire.decode(text))
        assertTrue("\"schemaVersion\":\"${JudgmentRecordWire.SCHEMA_VERSION}\"" in text, text)

        // 빈 칸이 많은 기록도 같다. 널 칸은 키를 빼지 않고 null 로 적는다.
        val bare = JudgmentRecord("J-2", 1, emptyList(), null, listOf(UnitRecord("u", 0, "", null, null, null, null)))
        val bareText = JudgmentRecordWire.encode(bare)
        assertEquals(bare, JudgmentRecordWire.decode(bareText))
        assertTrue("\"approval\":null" in bareText && "\"requestedAt\":null" in bareText, bareText)
    }

    @Test
    fun `모르는 판이나 판이 없는 기록은 읽지 않고 거부한다`() {
        val text = JudgmentRecordWire.encode(FULL)
        listOf(
            text.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"2\""),
            text.replace("\"schemaVersion\":\"1\",", ""),
            text.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":1"),
        ).forEach { wrong ->
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue("모르는 판단 기록 판" in e.message!!, e.message)
        }
    }

    @Test
    fun `모르는 칸이나 빠진 칸이 있으면 거부한다`() {
        val text = JudgmentRecordWire.encode(FULL)
        mapOf(
            text.replace("\"taskId\":", "\"lease\":7,\"taskId\":") to "모르는 칸",
            text.replace("\"result\":\"ref://m1\"", "\"result\":\"ref://m1\",\"epoch\":3") to "모르는 칸",
            text.replace(",\"decision\":null", "") to "칸이 없다",
            text.replace("\"acknowledgedFaults\":[\"LOW_BATTERY[]\"],", "") to "칸이 없다",
        ).forEach { (wrong, why) ->
            assertTrue(wrong != text, "시험의 전제: 바꿀 자리를 못 찾았다")
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue(why in e.message!!, e.message)
        }
    }

    @Test
    fun `판정 칸에 정착하지 않은 상태나 모르는 값이 오면 거부한다`() {
        val text = JudgmentRecordWire.encode(FULL)
        listOf(
            text.replace("\"state\":\"DONE\"", "\"state\":\"RUNNING\""),
            text.replace("\"state\":\"DONE\"", "\"state\":\"FINISHED\""),
            text.replace("\"decision\":\"REWORK\"", "\"decision\":\"SKIP\""),
            text.replace("\"attempt\":2", "\"attempt\":2.5"),
            text.replace("\"at\":\"2026-10-14T00:00:30Z\"", "\"at\":\"어제\""),
        ).forEach { wrong ->
            assertTrue(wrong != text, "시험의 전제: 바꿀 자리를 못 찾았다")
            assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
        }
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-14T00:00:00Z")
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        /** 칸마다 값이 든 기록. 사람이 적은 글(메모)에 따옴표와 줄바꿈을 넣는다. */
        val FULL = JudgmentRecord(
            jobOrderId = "J-1",
            orderVersion = 3,
            acknowledgedFaults = listOf("LOW_BATTERY[]"),
            approval = RecordedApproval(
                robotId = "hum-02",
                approver = OPERATOR,
                at = T0,
                wallClockAt = T0.plusMillis(7),
                steps = listOf(ApprovedStep("pick_place", mapOf("object" to "A", "destination" to "S01"), "remedy-1-pick_place")),
            ),
            units = listOf(
                UnitRecord(
                    unitId = "s1",
                    attempt = 0,
                    taskId = "J-1#s1",
                    requestedAt = T0,
                    verifyWindow = null,
                    verdict = UnitVerdict(UnitState.DONE, Evidence.E2, Verification.MATCHED, T0.plusSeconds(3), null, "operator \"confirmed\"\n두 줄", "ref://m1"),
                    decision = null,
                ),
                UnitRecord(
                    unitId = "s2",
                    attempt = 2,
                    taskId = "J-1#s2@r2",
                    requestedAt = T0.plusSeconds(20),
                    verifyWindow = VerifyWindow(T0.plusSeconds(25), T0.plusSeconds(40)),
                    verdict = null,
                    decision = RecordedDecision(OperatorDecision.REWORK, OPERATOR, T0.plusSeconds(30), 1),
                ),
            ),
        )
    }
}
