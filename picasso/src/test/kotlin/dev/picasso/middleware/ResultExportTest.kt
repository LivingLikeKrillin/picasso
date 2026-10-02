package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 결과 통보의 바깥 형식(ADR 48, `docs/orchestration.md` §8).
 *
 * 읽는 쪽은 프로세스 밖에서 이 줄만 본다. 그래서 칸이 빠지는 길과, 다시 뜬 인스턴스의 통보가 이번 것으로 읽히는
 * 길을 막는 것이 이 시험의 일이다.
 */
class ResultExportTest {

    @Test
    fun `한 줄이 제 판과 인스턴스와 실행을 든다`() {
        // ★실행 식별자는 인스턴스 안의 셈이다. 인스턴스가 빠지면 다시 뜬 뒤의 `exec-1` 이 앞 구동의 `exec-1` 과 같은 모양이 된다.
        val line = parse(ResultExport.line(SAMPLE, INSTANCE))
        // ★판은 글자로 댄다 — 상수끼리 대면 판을 안 올려도 초록이다.
        assertEquals("1", line.getValue("schemaVersion").stringValue)
        assertEquals(INSTANCE, line.getValue("instanceId").stringValue)
        assertEquals("exec-7", line.getValue("executionId").stringValue)
        assertEquals("resp-3", line.getValue("jobResponseId").stringValue)
        assertEquals("PATROL-1", line.getValue("jobOrderId").stringValue)
        assertEquals(2.0, line.getValue("version").numberValue)
        assertEquals(listOf("remedy-1-pick_place"), strings(line, "completedUnits"))
        assertEquals("PHYSICALLY_DONE", line.getValue("physicalState").stringValue)
    }

    @Test
    fun `빈 목록과 빈 표도 키를 뺀 채 나가지 않는다`() {
        // ★키를 빼면 «완료된 단위가 없다» 와 «이 판이 그 칸을 안 낸다» 가 같은 모양이 된다 — 읽는 쪽은 앞을 UNKNOWN 으로 읽는다.
        val line = parse(ResultExport.line(SAMPLE, INSTANCE))
        listOf("unverifiedUnits", "inDoubtUnits", "blockedBy").forEach { key ->
            assertTrue(key in line, "$key 키가 없다")
            assertEquals(emptyList(), strings(line, key), key)
        }
        assertTrue(line.getValue("results").structValue.fieldsMap.isEmpty())
        // 파지는 기본값도 적는다 — 빈손이 「말하지 않았다」로 접히지 않게(§15.145).
        assertEquals("HOLD_KIND_EMPTY", line.getValue("residualHold").structValue.fieldsMap.getValue("kind").stringValue)
    }

    @Test
    fun `상류 확인 상태는 싣지 않는다`() {
        // 나르는 쪽의 상태다. 실으면 같은 통보가 확인 전후로 두 모양이 되어 «같은 줄이 다시 왔다» 를 못 가른다.
        val before = ResultExport.line(SAMPLE, INSTANCE)
        val after = ResultExport.line(SAMPLE.copy(ack = UpstreamAck.ACKED), INSTANCE)
        assertEquals(before, after)
        assertFalse("ack" in parse(before))
    }

    @Test
    fun `여러 통보는 낸 순서대로 한 줄씩 나간다`() {
        val body = ResultExport.jobResponses(listOf(SAMPLE, SAMPLE.copy(jobResponseId = "resp-4")), INSTANCE)
        val lines = body.lines().filter { it.isNotEmpty() }
        assertEquals(listOf("resp-3", "resp-4"), lines.map { parse(it).getValue("jobResponseId").stringValue })
        assertTrue(body.endsWith("\n"), "마지막 줄에 줄바꿈이 없다")
    }

    private companion object {
        const val INSTANCE = "mw-test-instance"

        val SAMPLE = JobResponse(
            jobResponseId = "resp-3",
            jobOrderId = "PATROL-1",
            executionId = "exec-7",
            version = 2,
            physicalState = PhysicalState.PHYSICALLY_DONE,
            requiredEvidence = Evidence.E0,
            reachedEvidence = Evidence.E0,
            completedUnits = listOf("remedy-1-pick_place"),
            unverifiedUnits = emptyList(),
            incompleteUnits = emptyMap(),
            operatorRequired = false,
            residualHold = HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build(),
        )

        fun parse(json: String): Map<String, Value> =
            Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build().fieldsMap

        fun strings(fields: Map<String, Value>, key: String): List<String> =
            fields.getValue(key).listValue.valuesList.map { it.stringValue }
    }
}
