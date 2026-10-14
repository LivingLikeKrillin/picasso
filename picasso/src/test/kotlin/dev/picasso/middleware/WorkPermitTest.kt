package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **작업 허가 — 셀 안전 상태를 비춘 값으로 새 발행을 막는다**(ADR 52, ADR 51 결정 6).
 *
 * 이것은 안전 기능이 아니다(ADR 32). 기체를 세우는 것은 안전 계통이고, 여기서 보는 것은 이 층이 «막혀 있거나 모르는 셀에 새
 * 일을 내지 않는가» 뿐이다. 그래서 시험은 전부 «무엇이 나갔고 무엇이 안 나갔나» 를 기체 쪽(미믹의 태스크 표와 `StartTask`
 * 기록)에서 센다 — 실행의 상태만 보면 안 나간 명령과 나갔다가 거절된 명령이 같아 보인다.
 *
 * 기체는 4족 픽스처 `quadruped-b` 둘, 업무는 점검 순회(로봇 단위만, 지점마다 이동 뒤 점검)다. 허가 원천은 [SitePermits] 이고
 * 기본으로 **읽을 때마다 관측 시각을 지금으로 찍는다** — 살아 있는 원천이다. 유효 기간 시험만 시각을 묶는다.
 */
class WorkPermitTest {

    // ── 허가 원천의 더블

    /** 원천이 낼 줄 하나. [session] 이 널이면 원천의 지금 세션으로 찍는다. */
    private data class Line(
        val robots: Set<String>?,
        val permit: WorkPermit,
        val subject: String? = null,
        val basis: String? = null,
        val session: String? = null,
    )

    private class SitePermits(private val now: () -> Instant) : WorkPermitPort {
        var session = "s-1"
        var governed: Set<String> = setOf(A, B)
        var failing = false
        var frozenAt: Instant? = null
        var validFor: Duration = Duration.ofSeconds(30)
        private var lines: List<Line> = emptyList()

        fun only(vararg l: Line) {
            lines = l.toList()
        }

        override fun read(): List<PermitReading> {
            if (failing) throw IllegalStateException("허가 원천에 못 닿았다")
            val at = frozenAt ?: now()
            return listOf(
                PermitReading(
                    source = SOURCE,
                    session = session,
                    governed = governed,
                    scopes = lines.map {
                        PermitScope(
                            robots = it.robots, permit = it.permit, subject = it.subject, resumeBasis = it.basis,
                            observedAt = at, validFor = validFor, session = it.session ?: session,
                        )
                    },
                ),
            )
        }
    }

    private fun permitted(vararg robots: String) = Line(robots.toSet(), WorkPermit.PERMITTED)
    private fun inhibited(vararg robots: String) = Line(robots.toSet(), WorkPermit.INHIBITED, DOOR, RESET)

    /** 기체에 나간 `StartTask`·`CancelTask` 를 센다 — 응답이 오든 말든, 요청이 나간 것만. */
    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        val starts = mutableListOf<Triple<String, String, Int>>()
        val cancels = mutableListOf<String>()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += Triple(robotId, taskId, revision)
            return delegate.start(robotId, taskId, revision, skillType, parameters)
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
            cancels += handle.taskId
            return delegate.cancel(robotId, handle)
        }
    }

    private class World(port: (RobotPort) -> RobotPort = { it }, attach: Boolean = true) : AutoCloseable {
        val harness = Harness(mapOf(A to QUAD, B to QUAD))
        val permits = SitePermits { harness.clock.now() }
        val wire = Wire(port(ClientRobotPort(harness.client())))
        val mw = Middleware(wire, now = { harness.clock.now() }, permits = if (attach) permits else null)

        fun tasks(robot: String = A) =
            harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(robot).build()).tasksList

        fun task(taskId: String, robot: String = A) = tasks(robot).firstOrNull { it.taskId == taskId }

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(A).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        fun pumps(n: Int) = repeat(n) {
            mw.pump()
            harness.advance(Duration.ofSeconds(1))
        }

        fun drive(rounds: Int = 120, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    private fun order(id: String = "PATROL-7", targets: List<String> = listOf("PUMP-01", "PANEL-3"), version: Int = 1) = JobOrder(
        jobOrderId = id,
        workMasterId = InspectAsset.WORK_MASTER,
        version = version,
        requiredEvidence = Evidence.E0,
        parameters = mapOf("shift" to "night"),
        equipmentRequirements = targets.map {
            EquipmentRequirement(it, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "$it-BAY", EquipmentUse.PROP_ITEM to "thermal"))
        },
    )

    private fun travel(target: String = "PUMP-01") = "$target${InspectAsset.TRAVEL_SUFFIX}"
    private fun taskOf(unitId: String, id: String = "PATROL-7") = "$id#$unitId"

    private fun World.accept(order: JobOrder = order(), robot: String = A) =
        assertIs<Middleware.Submission.Accepted>(mw.submit(order, robot)).execution

    private fun Middleware.Execution.trailOf(kind: String) = eventTrail.filter { it.kind == kind }

    // ── 붙이지 않으면 오늘 그대로, 붙이면 PERMITTED 만 낸다

    @Test
    fun `허가 원천을 안 붙이면 오늘처럼 첫 pump 에 낸다`() {
        World(attach = false).use { w ->
            w.permits.only(inhibited(A)) // 붙이지 않았으니 이 말은 아무도 안 읽는다
            val exec = w.accept()
            w.mw.pump()

            assertEquals(listOf(taskOf(travel())), w.tasks().map { it.taskId })
            assertNull(exec.permitBlock)
            assertTrue(exec.trailOf(ISSUE_WITHHELD).isEmpty())
        }
    }

    @Test
    fun `PERMITTED 이면 낸다`() {
        World().use { w ->
            w.permits.only(permitted(A))
            val exec = w.accept()
            w.mw.pump()

            assertEquals(listOf(taskOf(travel())), w.tasks().map { it.taskId })
            assertNull(exec.permitBlock)
        }
    }

    @Test
    fun `INHIBITED 이면 내지 않고 단위는 실패가 아니라 기다리며 막은 기체와 주체와 풀릴 근거가 보인다`() {
        World().use { w ->
            w.permits.only(inhibited(A))
            val exec = w.accept()
            w.pumps(4)

            assertEquals(emptyList(), w.wire.starts, "막혔는데 StartTask 가 나갔다")
            assertEquals(0, w.tasks().size)
            // 실패가 아니다 — 기다린다. 운영자 판단도 아니다(사람이 감수할 값이 아니다).
            assertEquals(UnitState.PENDING, exec.units.first().state)
            assertEquals(PhysicalState.ACCEPTED, exec.physicalState)
            assertEquals(emptyList(), w.mw.incidents(), "기다림이 사건으로 섰다")

            val block = assertNotNull(exec.permitBlock, "막음이 안 보인다")
            assertEquals(travel(), block.unitId)
            assertEquals(WithheldIssue.START, block.withheld)
            assertEquals(listOf(A), block.robotIds)
            assertEquals(listOf(DOOR), block.subjects, "막은 주체가 안 실렸다")
            assertEquals(listOf(RESET), block.resumeBasis, "풀릴 근거가 안 실렸다")
            val reason = block.robots.single().reasons.single()
            assertEquals(SOURCE, reason.source)
            assertEquals(PermitCause.REPORTED, reason.cause)
            // 원천이 관측 시각을 매번 새로 찍어도 같은 막음은 자취에 한 번이다.
            assertEquals(1, exec.trailOf(ISSUE_WITHHELD).size, exec.eventTrail.toString())
        }
    }

    @Test
    fun `INHIBITED 가 PERMITTED 로 돌아오면 그때 내고 막음이 걷힌다`() {
        World().use { w ->
            w.permits.only(inhibited(A))
            val exec = w.accept()
            w.pumps(2)
            assertEquals(0, w.tasks().size)

            w.permits.only(permitted(A))
            w.mw.pump()

            assertEquals(listOf(taskOf(travel())), w.tasks().map { it.taskId })
            assertNull(exec.permitBlock, "허가가 돌아왔는데 막음이 남았다")
            val resumed = exec.trailOf(ISSUE_RESUMED).single()
            assertTrue(SOURCE in resumed.detail && "PERMITTED" in resumed.detail, "풀린 근거가 자취에 없다: ${resumed.detail}")
        }
    }

    // ── 모르면 막는다

    @Test
    fun `원천이 UNKNOWN 이라고 하면 막는다`() {
        World().use { w ->
            w.permits.only(Line(setOf(A), WorkPermit.UNKNOWN, subject = "cell-mode unreadable"))
            val exec = w.accept()
            w.pumps(2)

            assertEquals(emptyList(), w.wire.starts)
            val robot = assertNotNull(exec.permitBlock).robots.single()
            assertEquals(WorkPermit.UNKNOWN, robot.permit)
            assertEquals(PermitCause.REPORTED, robot.reasons.single().cause)
        }
    }

    @Test
    fun `관측이 유효 기간을 넘기면 UNKNOWN 으로 막는다`() {
        World().use { w ->
            w.permits.frozenAt = w.harness.clock.now()
            w.permits.validFor = Duration.ofSeconds(30)
            w.permits.only(permitted(A))
            val exec = w.accept()
            w.harness.advance(Duration.ofSeconds(31))
            w.mw.pump()

            assertEquals(emptyList(), w.wire.starts, "낡은 PERMITTED 로 냈다")
            val robot = assertNotNull(exec.permitBlock).robots.single()
            assertEquals(WorkPermit.UNKNOWN, robot.permit)
            assertEquals(PermitCause.EXPIRED, robot.reasons.single().cause)

            w.permits.frozenAt = null // 원천이 다시 관측한다
            w.mw.pump()
            assertEquals(1, w.tasks().size)
        }
    }

    @Test
    fun `원천의 세션이 바뀌면 앞 세션의 관측은 UNKNOWN 으로 막는다`() {
        World().use { w ->
            w.permits.session = "s-2"
            w.permits.only(Line(setOf(A), WorkPermit.PERMITTED, session = "s-1"))
            val exec = w.accept()
            w.mw.pump()

            assertEquals(emptyList(), w.wire.starts, "앞 세션의 PERMITTED 로 냈다")
            assertEquals(PermitCause.SESSION_CHANGED, assertNotNull(exec.permitBlock).robots.single().reasons.single().cause)
        }
    }

    @Test
    fun `포트가 던지면 UNKNOWN 으로 막는다`() {
        World().use { w ->
            w.permits.only(permitted(A))
            w.permits.failing = true
            val exec = w.accept()
            w.pumps(2)

            assertEquals(emptyList(), w.wire.starts, "허가를 못 읽고 냈다")
            val robot = assertNotNull(exec.permitBlock).robots.single()
            assertEquals(WorkPermit.UNKNOWN, robot.permit)
            assertEquals(PermitCause.PORT_FAILED, robot.reasons.single().cause)
        }
    }

    // ── 범위는 기체 집합이다

    @Test
    fun `A 를 막아도 B 에는 낸다`() {
        World().use { w ->
            w.permits.only(inhibited(A), permitted(B))
            val onA = w.accept(order("PATROL-A", listOf("PUMP-01")), A)
            val onB = w.accept(order("PATROL-B", listOf("PANEL-3")), B)
            w.mw.pump()

            assertEquals(0, w.tasks(A).size)
            assertEquals(listOf("PATROL-B#${travel("PANEL-3")}"), w.tasks(B).map { it.taskId }, "A 의 막음이 B 로 샜다")
            assertEquals(listOf(A), assertNotNull(onA.permitBlock).robotIds)
            assertNull(onB.permitBlock)
        }
    }

    @Test
    fun `어느 기체인지 원천이 모르면 원천이 맡은 기체 전부를 막는다`() {
        World().use { w ->
            // 원천은 A 만 막는다고 마지막으로 확인했고 B 는 허가였다. 지금은 막는데 어느 기체인지 모른다.
            w.permits.only(Line(null, WorkPermit.INHIBITED, "light-curtain-2"), permitted(A, B))
            val onB = w.accept(order("PATROL-B", listOf("PANEL-3")), B)
            w.mw.pump()

            assertEquals(0, w.tasks(B).size, "마지막으로 확인한 범위만 막았다 — 넓어진 범위가 샜다")
            val robot = assertNotNull(onB.permitBlock).robots.single()
            assertEquals(WorkPermit.UNKNOWN, robot.permit)
            assertEquals(PermitCause.SCOPE_UNKNOWN, robot.reasons.single().cause)
            assertEquals(listOf("light-curtain-2"), onB.permitBlock!!.subjects)
        }
    }

    @Test
    fun `어느 원천도 덮지 않는 기체는 막는다`() {
        World().use { w ->
            w.permits.governed = setOf(A)
            w.permits.only(permitted(A))
            val onB = w.accept(order("PATROL-B", listOf("PANEL-3")), B)
            w.mw.pump()

            assertEquals(0, w.tasks(B).size, "말이 없는 것을 허가로 읽었다")
            assertEquals(PermitCause.NOT_COVERED, assertNotNull(onB.permitBlock).robots.single().reasons.single().cause)
        }
    }

    // ── 같은 관문 — 개정의 갱신과 미확정 조회

    @Test
    fun `개정의 갱신도 같은 관문을 지나 막히고 허가가 돌아오면 그때 나간다`() {
        World().use { w ->
            w.permits.only(permitted(A))
            val exec = w.accept()
            w.drive { w.task(taskOf(travel()))?.taskState == "RUNNING" }

            w.permits.only(inhibited(A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2), A))
            w.mw.pump()
            w.mw.pump()

            assertTrue(w.wire.starts.none { it.third == 2 }, "막혔는데 개정의 갱신이 나갔다: ${w.wire.starts}")
            assertEquals(1, w.task(taskOf(travel()))!!.revision)
            val block = assertNotNull(exec.permitBlock)
            assertEquals(WithheldIssue.REVISION_UPDATE, block.withheld)
            assertEquals(travel(), block.unitId)

            w.permits.only(permitted(A))
            w.mw.pump()
            assertTrue(Triple(A, taskOf(travel()), 2) in w.wire.starts, "허가가 돌아왔는데 미룬 갱신이 안 나갔다: ${w.wire.starts}")
            assertEquals(2, w.task(taskOf(travel()))!!.revision)
            assertNull(exec.permitBlock)
        }
    }

    @Test
    fun `막힌 갱신 뒤 허가 아래 판이 또 오르면 마지막 판 하나만 보낸다`() {
        // 판 2 의 갱신은 막혀 미뤄졌다. 허가가 돌아온 뒤 판 3 이 오면 그 갱신이 판 2 까지 싣는다 — 다음 pump 가 미룬 갱신을
        // 또 보내면 같은 판이 두 번 나간다.
        World().use { w ->
            w.permits.only(permitted(A))
            val exec = w.accept()
            w.drive { w.task(taskOf(travel()))?.taskState == "RUNNING" }

            w.permits.only(inhibited(A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2), A))
            w.permits.only(permitted(A))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 3), A))
            w.mw.pump()
            w.mw.pump()

            val updates = w.wire.starts.filter { it.second == taskOf(travel()) }.map { it.third }
            assertEquals(listOf(1, 3), updates, "판 2 가 나갔거나 판 3 이 두 번 나갔다")
            assertEquals(3, w.task(taskOf(travel()))!!.revision)
            assertNull(exec.permitBlock)
        }
    }

    @Test
    fun `미확정 조회도 허가 없이 다시 보내지 않는다`() {
        // 조회는 같은 참조의 StartTask 재전송이다 — 원 요청이 안 닿았으면 새 접수가 된다.
        World(port = { LossyRobotPort(it, dropStartResponses = 1) }).use { w ->
            w.permits.only(permitted(A))
            val exec = w.accept()
            w.mw.pump()
            assertEquals(1, w.wire.starts.size)
            assertEquals(UnitState.IN_DOUBT, exec.units.first().state)

            w.permits.only(inhibited(A))
            w.pumps(3)
            assertEquals(1, w.wire.starts.size, "막혔는데 조회가 나갔다")
            assertEquals(0, exec.units.first().lookups, "막힌 조회를 조회 횟수로 셌다")
            assertEquals(WithheldIssue.IN_DOUBT_LOOKUP, assertNotNull(exec.permitBlock).withheld)

            w.permits.only(permitted(A))
            w.mw.pump()
            assertEquals(2, w.wire.starts.size)
            assertEquals(UnitState.RUNNING, exec.units.first().state)
            assertNull(exec.permitBlock)
        }
    }

    // ── 취소는 언제나 나간다

    @Test
    fun `INHIBITED 여도 도는 단위의 취소는 기체에 나가고 실행은 중단으로 끝난다`() {
        World().use { w ->
            w.permits.only(permitted(A))
            val exec = w.accept()
            w.drive { w.task(taskOf("PUMP-01"))?.taskState == "RUNNING" }

            w.permits.only(inhibited(A))
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(listOf(taskOf("PUMP-01")), w.wire.cancels, "막음이 취소를 막았다")
            w.drive { exec.physicalState.isSettled && exec.active == null }

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals("CANCELLED", w.task(taskOf("PUMP-01"))!!.taskState)
            assertEquals(2, w.tasks().size, "취소 뒤에 새 단위가 나갔다")
        }
    }

    @Test
    fun `내기 전에 막힌 실행의 취소는 허가를 기다리지 않고 끝난다`() {
        World().use { w ->
            w.permits.only(inhibited(A))
            val exec = w.accept()
            w.pumps(2)

            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(4, assertNotNull(w.mw.lastCancel(exec.executionId)).notStartedUnits.size)
            assertEquals(emptyList(), w.wire.starts)
            assertNull(exec.permitBlock, "끝난 실행에 막음이 남았다")
        }
    }

    // ── 운영자 해제는 허가 막음을 못 푼다

    @Test
    fun `운영자 해제는 허가 막음을 못 푼다`() {
        World().use { w ->
            w.permits.only(inhibited(A))
            val exec = w.accept()
            w.pumps(2)

            assertFalse(w.mw.release(exec.executionId), "해제가 허가 막음을 받아들였다")
            w.pumps(2)
            assertNotNull(exec.permitBlock)
            assertEquals(emptyList(), w.wire.starts)
        }
    }

    @Test
    fun `결함 막음과 허가 막음이 함께 서면 해제는 결함만 감수하고 허가가 돌아와야 낸다`() {
        World().use { w ->
            w.permits.only(permitted(A))
            val exec = w.accept()
            w.drive { w.task(taskOf("PUMP-01"))?.taskState == "RUNNING" }
            assertEquals("NEEDS_INTERVENTION", w.forceFault("LOCALIZATION_LOST", taskOf("PUMP-01")))
            w.permits.only(inhibited(A))
            w.drive { exec.blockedBy.isNotEmpty() }

            // 둘 다 보인다 — 칸이 다르다.
            assertNotNull(exec.permitBlock)
            assertEquals(2, w.tasks().size)

            assertTrue(w.mw.release(exec.executionId))
            w.pumps(3)
            assertTrue(exec.blockedBy.isEmpty())
            assertNotNull(exec.permitBlock, "해제가 허가 막음까지 걷었다")
            assertEquals(2, w.tasks().size, "해제 뒤 허가 없이 다음 단위가 나갔다")

            w.permits.only(permitted(A))
            w.mw.pump()
            assertEquals(3, w.tasks().size)
            assertNull(exec.permitBlock)
        }
    }

    // ── 두 침묵

    @Test
    fun `결함을 못 물어 통과하는 침묵과 허가를 모르는 침묵은 다르다`() {
        // 결함 관문은 못 물으면 통과시킨다(관측 실패로 현장을 세우지 않는다). 허가 관문은 모르면 막는다.
        // 둘을 같은 규칙으로 접으면 어느 한쪽이 뒤집힌다.
        World(port = { FaultBlindRobotPort(it) }).use { w ->
            w.permits.only(Line(setOf(A), WorkPermit.UNKNOWN))
            val exec = w.accept()
            w.pumps(2)

            val first = exec.units.first()
            assertTrue(first.note!!.contains("not observable"), "결함 관문이 침묵을 통과로 적지 않았다: ${first.note}")
            assertEquals(1, Regex("not observable").findAll(first.note!!).count(), "막힌 동안 같은 메모가 쌓였다: ${first.note}")
            assertEquals(emptyList(), w.wire.starts, "허가를 모르는데 냈다")
            assertTrue(exec.blockedBy.isEmpty())

            w.permits.only(permitted(A))
            w.mw.pump()
            assertEquals(1, w.tasks().size, "결함을 못 물어도 허가가 있으면 낸다")
        }
    }

    private companion object {
        const val A = "quad-01"
        const val B = "quad-02"
        const val SOURCE = "cell-1-safety"
        const val DOOR = "door-3 open"
        const val RESET = "door-3 closed and reset"
        const val ISSUE_WITHHELD = Middleware.ISSUE_WITHHELD
        const val ISSUE_RESUMED = Middleware.ISSUE_RESUMED
        val QUAD: Path = Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()
    }
}
