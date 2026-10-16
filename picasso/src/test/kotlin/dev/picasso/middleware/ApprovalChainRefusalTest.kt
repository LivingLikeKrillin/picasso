package dev.picasso.middleware

import dev.picasso.capability.HoldEffects
import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **승인 시점의 사슬 거절이 서 있는 제안을 바꾸지 않는다** — `commit` 의 «조건이 풀리면 같은 제안이 그대로 선다».
 *
 * 승인이 서면 조치 열을 계획 앞에 붙여 다시 접수하고, 그 접수가 관문의 사슬 검사를 다시 지난다. 그 사이 파지가 바뀌었으면 조치 열의
 * 첫 걸음이 걸리고 탐색이 그 걸음을 위한 조치 열을 다시 찾는다. 찾은 것을 제안으로 세우면 사람이 본 적 없는 조치 열이 승인을 기다리고,
 * 본 제안은 사라진다.
 *
 * **계약 카탈로그로는 이 길이 안 열린다.** 파지를 바꾸는 스킬이 `pick_place` 하나이고 끝나면 빈손이라, 어느 걸음에서 막혀도 탐색이
 * «든 채» 로 가는 조치를 못 찾는다. 그래서 시험 동안만 쥐기만 하는 스킬의 효과를 [HoldEffects] 의 표에 더한다([withGripOnly]).
 * 기체는 대본대로 답하는 시험 더블이다.
 */
class ApprovalChainRefusalTest {

    @Test
    fun `승인 시점의 사슬 거절은 서 있는 제안을 바꾸지 않고 조건이 풀리면 같은 제안으로 승인된다`() = withGripOnly {
        val robot = Robot()
        val mw = Middleware(robot, capabilities = listOf(OneSkill()), now = { T0 })

        // 든 채로 도는 실행 하나 — 사슬 검사의 출발점이다.
        val running = assertIs<Middleware.Submission.Accepted>(mw.submit(order("MOVE-1", "navigate_to"), ROBOT)).execution
        repeat(2) { mw.pump() }
        assertEquals(HoldKind.HOLD_KIND_HOLDING, running.units.single().hold.kind, "이 시험이 보려는 출발점이 아니다")

        // 빈손을 요구하는 주문이 막히고 «놓는다» 가 제안으로 선다.
        assertIs<Middleware.Submission.Rejected>(mw.submit(order(JOB, "inspect"), ROBOT))
        assertEquals(listOf("pick_place"), mw.proposal(ROBOT, JOB)?.steps?.map { it.skillType })

        // 승인 전에 기체가 빈손이 됐다. 조치 열의 첫 걸음(든 채를 요구한다)이 사슬 검사에 걸린다.
        robot.hold = HoldKind.HOLD_KIND_EMPTY
        mw.pump()
        val refused = assertIs<Middleware.Submission.Rejected>(mw.approveRemedy(ROBOT, JOB, listOf(emptyMap()), OPERATOR))
        val searched = assertIs<RemedyOutcome.Found>(mw.remedySearches().last().outcome, "승인 시점의 거절이 조치 열을 찾지 않았다 — 이 시험이 보려는 길이 아니다")
        assertEquals(listOf("grip"), searched.steps.map { it.skillType })

        assertEquals(
            listOf("pick_place"),
            mw.proposal(ROBOT, JOB)?.steps?.map { it.skillType },
            "승인 시점의 거절이 서 있는 제안을 바꿨다",
        )
        assertNull(refused.remedy, "세우지 않은 조치 열을 거절에 실었다")

        // 조건이 풀리면 같은 제안이 승인된다.
        robot.hold = HoldKind.HOLD_KIND_HOLDING
        mw.pump()
        val accepted = assertIs<Middleware.Submission.Accepted>(mw.approveRemedy(ROBOT, JOB, listOf(emptyMap()), OPERATOR))
        assertEquals(listOf("remedy-1-pick_place", "u"), accepted.execution.units.map { it.unitId })
        assertEquals(listOf(RepeatedRemedy(ROBOT, listOf("pick_place"), 1)), mw.repeatedRemedies(atLeast = 1))
        assertTrue(mw.proposal(ROBOT, JOB) == null, "소모된 제안이 서 있다")
    }

    /** 단위 하나. 스킬은 작업 지시의 `skill` 파라미터다. 자리를 쓰지 않아 점유·구역 관문에 안 걸린다. */
    private class OneSkill : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E0
        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf(
            ExecutionUnit(
                unitId = "u",
                route = Route.ROBOT,
                skillType = order.parameters.getValue("skill"),
                parameters = emptyMap(),
                expectedIdentity = null,
                source = null,
                destination = null,
            ),
        )
    }

    /**
     * 대본대로 답하는 기체. 받은 태스크는 전부 도는 중이고, 관측하는 파지는 [hold] 하나다.
     *
     * 선언: `inspect` 는 빈손, `pick_place` 는 든 채를 요구한다. `grip` 과 `navigate_to` 는 조건이 없다.
     */
    private class Robot : RobotPort {
        var hold: HoldKind = HoldKind.HOLD_KIND_HOLDING

        override fun capabilities(robotId: String): Capability = Capability.newBuilder()
            .addSkills(skill("navigate_to"))
            .addSkills(skill("inspect", requires = HoldKind.HOLD_KIND_EMPTY))
            .addSkills(skill("pick_place", requires = HoldKind.HOLD_KIND_HOLDING))
            .addSkills(skill("grip"))
            .build()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
            StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = listOf(
            WatchTaskResponse.newBuilder()
                .setHeader(MessageHeader.newBuilder().setStateAsOf(T0.toString()))
                .setState(TaskState.TASK_STATE_RUNNING)
                .setRevision(handle.revision)
                .setHold(HoldState.newBuilder().setKind(hold))
                .build(),
        )

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance()
        override fun snapshot(robotId: String): RobotSnapshot? = null
        override fun replay(robotId: String, from: Long): Replay? = null

        private fun skill(type: String, requires: HoldKind? = null) = SkillDeclaration.newBuilder().setSkillType(type).apply {
            requires?.let {
                addPreconditions(Precondition.newBuilder().setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD).setRequires(it))
            }
        }
    }

    private companion object {
        const val ROBOT = "hum-01"
        const val JOB = "CHECK-1"
        const val WORK_MASTER = "OneSkill"
        val T0: Instant = Instant.parse("2026-10-16T00:00:00Z")
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        fun order(jobOrderId: String, skill: String) = JobOrder(jobOrderId, WORK_MASTER, 1, parameters = mapOf("skill" to skill))

        /**
         * 시험 동안만 **쥐기만 하는** 스킬 `grip` 의 효과(쥔다·안 놓는다)를 [HoldEffects] 의 표에 더하고, 끝나면 뺀다.
         *
         * 표는 [HoldEffects] 가 계약 카탈로그에서 한 번 읽어 든 것이다. 운영 코드에 효과를 갈아 끼울 자리를 두지 않으려고 시험이 그 표를
         * 직접 고친다 — 같은 JVM 의 다른 시험이 이 줄을 보지 않도록 반드시 뺀다.
         */
        fun withGripOnly(block: () -> Unit) {
            HoldEffects.grasps("grip") // 표를 읽힌다
            val delegate = HoldEffects::class.java.getDeclaredField("byName\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val table = (delegate.get(null) as Lazy<*>).value as MutableMap<String, Pair<Boolean, Boolean>>
            check("grip" !in table) { "계약 카탈로그에 grip 이 생겼다 — 이 시험의 전제가 바뀌었다" }
            table["grip"] = true to false
            try {
                check(HoldEffects.after("grip", HoldState.getDefaultInstance()).kind == HoldKind.HOLD_KIND_HOLDING) { "효과를 더하지 못했다" }
                block()
            } finally {
                table.remove("grip")
            }
        }
    }
}
