package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.wire.ContractIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 승인 표면의 **규약**(ADR 44, `docs/orchestration.md` §7).
 *
 * `LedgerExport` 와 같은 성질이다 — 문자열만 다루므로 인프라 없이 시험이 문다. 여기서 고정하는 것은
 * 읽는 쪽이 맞출 칸이며, 이것이 없으면 밖은 인코더 소스를 읽어 추측으로 수신기를 만든다.
 */
class ApprovalWireTest {

    private fun parse(json: String): Map<String, com.google.protobuf.Value> =
        Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build().fieldsMap

    // ── 요청

    @Test
    fun `요청은 가리킬 제안과 누가 누르는지만 싣는다`() {
        // ★★**값도 주문도 걸음도 실을 칸이 없다.** 그것이 이 표면의 요점이고, 칸이 없으므로 부르는
        //   쪽의 권한은 규율이 아니라 모양 때문에 «승인 시도 한 번» 을 못 넘는다.
        val attempt = ApprovalWire.decode(
            """{"approverId":"narrator-1","approverKind":"AGENT","robotId":"hum-02",""" +
                """"jobOrderId":"PATROL-1","sawSkillTypes":["pick_place"]}""",
        )
        assertEquals(Approver("narrator-1", ApproverKind.AGENT), attempt.approver)
        assertEquals("hum-02", attempt.robotId)
        assertEquals("PATROL-1", attempt.jobOrderId)
        assertEquals(listOf("pick_place"), attempt.sawSkillTypes)
    }

    @Test
    fun `자격 주장을 실어도 읽히지 않는다`() {
        // ★부르는 쪽이 «나는 자격이 있다» 고 적어도 판정에 닿는 길이 없다. 남는 칸은 신원뿐이고
        //   그것은 위조 가능한 조사 단서다(§15.3).
        val attempt = ApprovalWire.decode(
            """{"approverId":"narrator-1","approverKind":"AGENT","robotId":"hum-02","jobOrderId":"PATROL-1",""" +
                """"sawSkillTypes":["pick_place"],"entitled":true,"skillTypes":["아무거나"],"parameters":{"destination":"내가-정한-자리"}}""",
        )
        assertEquals(
            ApprovalAttempt(Approver("narrator-1", ApproverKind.AGENT), "hum-02", "PATROL-1", listOf("pick_place")),
            attempt,
            "규약 밖의 칸이 요청에 실려 들어왔다",
        )
    }

    @Test
    fun `못 읽는 요청은 거절이 아니라 오류다`() {
        // ★둘을 같은 모양으로 내면 읽는 쪽이 자기 오타와 «자격 없음» 을 구별하지 못한다.
        assertFailsWith<IllegalArgumentException> { ApprovalWire.decode("{") }
        assertFailsWith<IllegalArgumentException> {
            ApprovalWire.decode("""{"approverId":"a","approverKind":"AGENT","robotId":"r","jobOrderId":"j"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            ApprovalWire.decode(
                """{"approverId":"a","approverKind":"로봇","robotId":"r","jobOrderId":"j","sawSkillTypes":[]}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ApprovalWire.decode(
                """{"approverId":"a","approverKind":"AGENT","robotId":"r","jobOrderId":"j","sawSkillTypes":"pick_place"}""",
            )
        }
    }

    // ── 답

    @Test
    fun `승인의 답이 실제로 나간 값을 싣는다`() {
        val json = ApprovalWire.encode(
            ApprovalOutcome.Approved(
                "exec-3",
                listOf(ApprovedStep("pick_place", mapOf("object_id" to "COVER-7", "destination" to "RACK-204.S01"))),
            ),
        )
        val fields = parse(json)
        assertEquals("APPROVED", fields.getValue("outcome").stringValue)
        assertEquals("exec-3", fields.getValue("executionId").stringValue)
        assertEquals(ApprovalWire.SCHEMA_VERSION, fields.getValue("schemaVersion").stringValue)
        assertEquals(ContractIdentity.semver, fields.getValue("contractSemver").stringValue)

        val step = fields.getValue("steps").listValue.getValues(0).structValue.fieldsMap
        assertEquals("pick_place", step.getValue("skillType").stringValue)
        val parameters = step.getValue("parameters").structValue.fieldsMap
        assertEquals("COVER-7", parameters.getValue("object_id").stringValue)
        assertEquals("RACK-204.S01", parameters.getValue("destination").stringValue)
    }

    @Test
    fun `거절의 답이 종류를 값으로 싣는다`() {
        // ★★사유 산문만 내면 읽는 쪽이 한국어를 문자열로 맞춰야 하고, 그 시험은 «거절됐다» 만 보므로
        //   아무 거절에나 초록이 된다 — 자격이 없어서인지 제안이 없어서인지 갈리지 않는다.
        val fields = parse(
            ApprovalWire.encode(ApprovalOutcome.Refused(ApprovalRefusal.NOT_DECLARED, "자동 승인 자격이 선언돼 있지 않다: narrator-1")),
        )
        assertEquals("REFUSED", fields.getValue("outcome").stringValue)
        assertEquals("NOT_DECLARED", fields.getValue("refusal").stringValue)
        assertTrue("narrator-1" in fields.getValue("reason").stringValue)
    }

    @Test
    fun `사람이 적은 글이 답을 깨지 않는다`() {
        // 진단 사유가 그대로 답에 실린다. 따옴표 하나만 새어도 그 답이 통째로 못 읽히고, 읽는 쪽에는
        // «부르면 깨진다» 로만 보인다.
        val nasty = "따옴표 \" 와 역슬래시 \\ 와 줄바꿈 \n 이 든 사유"
        val fields = parse(ApprovalWire.encode(ApprovalOutcome.Refused(ApprovalRefusal.WITHHELD, nasty)))
        assertEquals(nasty, fields.getValue("reason").stringValue)
    }

    @Test
    fun `모든 거절 종류가 답에 실릴 수 있다`() {
        // 종류가 늘었는데 답이 그것을 못 실으면 읽는 쪽이 모르는 값을 만나는 대신 **아무 값도** 못 받는다.
        ApprovalRefusal.entries.forEach { refusal ->
            val fields = parse(ApprovalWire.encode(ApprovalOutcome.Refused(refusal, "사유")))
            assertEquals(refusal.name, fields.getValue("refusal").stringValue)
        }
    }
}
