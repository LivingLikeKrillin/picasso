package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * 제안과 승인의 장부가 **소모를 기록하고 판정에 쓰는 순서**(ADR 46).
 *
 * 공개 경로로는 소모 뒤 같은 (기체, 주문)에 새 제안이 서지 않는다 — 소모된 주문의 재접수는 개정으로 가고,
 * 개정의 거절은 제안을 세우지 않는다(ADR 46). 그래서 그 순서는 장부를 직접 불러 댄다.
 * 기체는 능력 조회만 답하고 아무것도 받지 않는다.
 */
class RemedyDeskTest {

    /** 능력 조회만 답하는 기체. 판정을 값 검사까지 가게 하고, 아무것도 내리지 않는다. */
    private class Declares(vararg skills: String) : RobotPort {
        private val capability = Capability.newBuilder()
            .apply { skills.forEach { addSkills(SkillDeclaration.newBuilder().setSkillType(it)) } }
            .build()

        override fun capabilities(robotId: String): Capability = capability
        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
            error("장부 시험은 기체에 아무것도 내리지 않는다")
        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = error("내리지 않는다")
        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = error("내리지 않는다")
        override fun snapshot(robotId: String): RobotSnapshot? = null
        override fun replay(robotId: String, from: Long): Replay? = null
    }

    private fun desk(withholdEvery: Int = 0) =
        RemedyDesk(Declares(SKILL), Entitlements.None, withholdEvery, { T0 }, { WALL }, { null })

    /** 사람의 문처럼 값을 들고 판정한다 — 자격을 안 거치고 `Go` 까지 간다. */
    private fun RemedyDesk.press(approver: Approver) =
        judge(ROBOT, JOB, approver, given = listOf(mapOf("destination" to "DROP-01")), saw = null)

    /** 관문이 든 채라고 거절하고 조치 열을 낸 자리 — 제안 하나가 선다. */
    private fun RemedyDesk.propose(version: Int = 1) = record(
        ROBOT,
        JobOrder(jobOrderId = JOB, workMasterId = InspectAsset.WORK_MASTER, version = version),
        Middleware.Submission.Rejected("든 채다", remedy = Remedy.Found(listOf(STEP))),
    )

    @Test
    fun `소모 뒤 같은 열쇠에 새 제안이 서면 새 제안이 이긴다`() {
        // ★기록은 «지금 서 있는 것이 없을 때» 만 읽힌다. 먼저 읽으면 새 제안이 영영 승인되지 않는다.
        val d = desk()
        d.propose()
        d.settle(assertIs<RemedyDesk.Judgment.Go>(d.press(FIRST)), "exec-1")
        assertEquals(ApprovalRefusal.CONSUMED, assertIs<RemedyDesk.Judgment.No>(d.press(SECOND)).refusal)

        d.propose(version = 2)
        assertIs<RemedyDesk.Judgment.Go>(d.press(SECOND), "새 제안이 섰는데 소모 기록이 이겼다")
    }

    @Test
    fun `소모 뒤 새 제안이 가려지면 가림이 먼저다`() {
        // 둘에 한 번 가린다 — 첫 제안은 보이고 둘째 제안이 가려진다.
        val d = desk(withholdEvery = 2)
        d.propose()
        d.settle(assertIs<RemedyDesk.Judgment.Go>(d.press(FIRST)), "exec-1")

        d.propose(version = 2)
        assertEquals(ApprovalRefusal.WITHHELD, assertIs<RemedyDesk.Judgment.No>(d.press(SECOND)).refusal)
    }

    @Test
    fun `다시 소모되면 마지막 소모가 남는다`() {
        // ★부르는 쪽이 묻는 것은 «지금 왜 없는가» 다. 처음 것을 남기면 엉뚱한 승인자와 실행을 받는다.
        val d = desk()
        d.propose()
        d.settle(assertIs<RemedyDesk.Judgment.Go>(d.press(FIRST)), "exec-1")
        d.propose(version = 2)
        d.settle(assertIs<RemedyDesk.Judgment.Go>(d.press(SECOND)), "exec-2")

        val consumed = assertNotNull(assertIs<RemedyDesk.Judgment.No>(d.press(FIRST)).consumed)
        assertEquals(SECOND, consumed.approver)
        assertEquals("exec-2", consumed.executionId)
        assertEquals(T0, consumed.at)
        assertEquals(WALL, consumed.wallClockAt)
        assertEquals(listOf(ApprovedStep(SKILL, mapOf("destination" to "DROP-01"))), consumed.steps)
    }

    @Test
    fun `판정도 소모 거절만 소모 기록을 든다`() {
        // ★사람의 문은 `Judgment.No` 를 바로 산문으로 옮긴다 — 그 길에서는 이 검사가 유일한 방벽이다.
        assertFailsWith<IllegalArgumentException> { RemedyDesk.Judgment.No(ApprovalRefusal.CONSUMED, "사유") }
        assertFailsWith<IllegalArgumentException> {
            RemedyDesk.Judgment.No(ApprovalRefusal.NO_PROPOSAL, "사유", ConsumedApproval(FIRST, T0, WALL, "exec-1", emptyList()))
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val JOB = "PATROL-1"
        const val SKILL = "pick_place"
        val T0: Instant = Instant.parse("2026-09-06T00:00:01Z")
        val WALL: Instant = Instant.parse("2026-10-01T07:12:44.120Z")
        val FIRST = Approver("op-1", ApproverKind.PERSON)
        val SECOND = Approver("op-2", ApproverKind.PERSON)
        val STEP = RemedyStep(SKILL, emptyList(), HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING)
    }
}
