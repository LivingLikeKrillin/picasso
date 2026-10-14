package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **단위마다의 기체 묶음**(ADR 51 «대가», 단계 0 최소 자리). 실행의 단위는 각자 기체 묶음을 들고, 기본은 실행의 기체다.
 *
 * 오늘의 계획은 단위를 다른 기체에 묶지 않으므로 기본값만으로는 «실행의 기체를 읽는 자리» 와 «단위의 묶음을 읽는 자리» 가 같은
 * 값을 낸다 — 그 시험은 아무것도 가르지 못한다. 그래서 둘째 단위를 내부 자리([ExecutionUnit.robotBinding])로 다른 기체에
 * 묶은 계획을 세우고, 명령·관측·관문·스냅숏이 어느 기체로 가는지를 기체 쪽 기록에서 센다. 한 자리라도 실행의 기체를 읽으면
 * 그 호출이 엉뚱한 기체에 찍힌다.
 */
class UnitRobotBindingTest {

    // ── 기본값

    @Test
    fun `단위의 기체 묶음은 기본이 실행의 기체이고 재할당하면 함께 옮겨 간다`() {
        World(bound = null).use { w ->
            val exec = w.submit()
            assertTrue(exec.units.all { it.robotBinding == null }, "오늘의 계획이 단위를 기체에 묶었다")
            assertEquals(listOf(A, A), exec.units.map { exec.robotOf(it) })

            // 앞 기체에 다른 일이 있어야 옮길 이득이 선다.
            w.mw.submit(JobOrder("OTHER", Split.WORK_MASTER, 1), A)
            assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(C)))
            assertEquals(listOf(C, C), exec.units.map { exec.robotOf(it) }, "묶이지 않은 단위가 실행의 기체를 따라가지 않았다")

            w.mw.pump()
            assertEquals(listOf(Call("start", C, "$ORDER#u1")), w.robots.calls.filter { it.task.startsWith(ORDER) && it.op == "start" })
        }
    }

    // ── 명령과 관측

    @Test
    fun `묶인 단위의 출발과 관측과 취소는 그 단위의 기체로 간다`() {
        World().use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            val mine = w.robots.calls.filter { it.task.startsWith(ORDER) }
            assertEquals(
                listOf(Call("start", A, "$ORDER#u1"), Call("watch", A, "$ORDER#u1"), Call("start", B, "$ORDER#u2"), Call("watch", B, "$ORDER#u2"), Call("cancel", B, "$ORDER#u2")),
                mine,
            )
        }
    }

    @Test
    fun `개정의 갱신은 도는 묶인 단위의 기체로 간다`() {
        World().use { w ->
            w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2), A))

            assertEquals(Call("start", B, "$ORDER#u2", revision = 2), w.robots.calls.last { it.op == "start" })
        }
    }

    @Test
    fun `미확정 단위의 조회는 묶인 단위의 기체로 다시 묻는다`() {
        World().use { w ->
            w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.robots.lose(B, "$ORDER#u2")
            w.mw.pump()
            val exec = w.mw.executions().single()
            assertEquals(UnitState.IN_DOUBT, exec.units[1].state, "시험의 전제: 첫 요청의 답을 잃었다")

            w.mw.pump()
            assertEquals(listOf(B, B), w.robots.calls.filter { it.task == "$ORDER#u2" && it.op == "start" }.map { it.robot })
            assertEquals(UnitState.RUNNING, exec.units[1].state)
        }
    }

    @Test
    fun `미확정 중에 건 취소는 다시 붙은 뒤 묶인 단위의 기체로 간다`() {
        World().use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.robots.lose(B, "$ORDER#u2")
            w.mw.pump()
            // 핸들이 없어 취소가 아직 못 나간다. 조회가 핸들을 다시 잡는 순간 나간다.
            assertTrue(w.mw.cancel(exec.executionId))
            assertTrue(w.robots.calls.none { it.op == "cancel" }, "시험의 전제: 미확정 중에는 보낼 핸들이 없다")
            w.mw.pump()

            assertEquals(listOf(Call("cancel", B, "$ORDER#u2")), w.robots.calls.filter { it.op == "cancel" })
        }
    }

    @Test
    fun `선택 파라미터는 묶인 단위의 기체가 선언한 것만 싣는다`() {
        World(optional = mapOf(OPTIONAL to "true")).use { w ->
            w.robots.declare(A, SKILL, OPTIONAL)
            w.robots.declare(B, SKILL)
            val exec = w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()

            assertEquals(mapOf(OPTIONAL to "true"), w.robots.parameters.getValue("$ORDER#u1"))
            assertEquals(emptyMap(), w.robots.parameters.getValue("$ORDER#u2"), "선언하지 않은 기체에 선택 파라미터를 실었다")
            assertTrue(exec.eventTrail.any { it.kind == "OPTIONAL_NOT_SENT" && OPTIONAL in it.detail })
        }
    }

    // ── 관문

    @Test
    fun `발행 관문은 묶인 단위의 기체 허가를 묻는다`() {
        World(permits = PermitsBy(mapOf(A to WorkPermit.PERMITTED, B to WorkPermit.INHIBITED))).use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()

            assertEquals(listOf(A), w.robots.calls.filter { it.op == "start" }.map { it.robot }, "막힌 기체로 새 명령이 나갔다")
            val block = assertNotNull(exec.permitBlock)
            assertEquals("u2", block.unitId)
            assertEquals(listOf(B), block.robotIds)
        }
    }

    @Test
    fun `결함 관문은 묶인 단위의 기체를 동기화해 그 결함을 읽는다`() {
        World().use { w ->
            w.robots.fault(B, "ARM_FAULT")
            val exec = w.submit()
            w.mw.pump()
            assertEquals(listOf(A), w.robots.calls.filter { it.op == "start" }.map { it.robot }, "시험의 전제: 첫 단위의 기체는 막히지 않는다")
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()

            assertEquals(listOf("ARM_FAULT"), exec.blockedBy.map { it.errorType }, "묶인 기체의 결함이 다음 단위를 막지 않았다")
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertTrue(w.robots.calls.none { it.op == "start" && it.robot == B })
            assertNotNull(w.mw.view(B), "묶인 기체를 동기화하지 않았다")
        }
    }

    @Test
    fun `기체 연결이 끊기면 묶인 단위의 기체 연결로 미확정을 판정하고 결과 통보에 싣는다`() {
        World().use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()
            w.robots.connection[B] = ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN
            w.mw.pump()

            assertTrue(exec.linkBroken, "묶인 기체의 단절을 못 봤다")
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
            assertEquals(ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN.name, w.mw.responses().last().connection)
        }
    }

    @Test
    fun `작업 구역은 묶인 단위의 기체로 센다`() {
        World(workspace = { it.substringBefore('/') }).use { w ->
            w.submit()

            // 둘째 단위는 B 에 묶여 구역 Z2 를 쓴다. 같은 기체의 일은 그 구역에 막히지 않고 다른 기체의 일은 막힌다.
            assertIs<Admission.Passed>(w.mw.admits(single("Z2/x"), B, w.single.plan(single("Z2/x"))))
            val refused = assertIs<Admission.Refused>(w.mw.admits(single("Z2/x"), A, w.single.plan(single("Z2/x"))))
            assertTrue("Z2 에서 $B" in refused.rejection.reason, refused.rejection.reason)

            // 새 계획의 묶인 단위도 그 기체로 센다. A 가 받는 같은 모양의 주문은 둘째 단위가 B 에 묶여 B 의 구역 Z2 와 겹치지 않는다.
            assertIs<Admission.Passed>(w.mw.admits(order(), A, w.split.plan(order())), "묶인 새 단위를 주문을 받는 기체로 셌다")
        }
    }

    @Test
    fun `사슬 검사는 묶인 기체에서 지금 도는 단위의 파지로 본다`() {
        World().use { w ->
            w.robots.declare(B, SKILL, requiresEmpty = true)
            w.robots.declare(A, SKILL, requiresEmpty = true)
            w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()
            w.robots.hold(B, "$ORDER#u2", HoldKind.HOLD_KIND_HOLDING)
            w.mw.pump()

            // B 는 든 채로 돈다. 빈손을 요구하는 일은 B 로 못 가고 A 로는 간다.
            assertTrue(w.mw.admits(single("Q/1"), B, w.single.plan(single("Q/1"))) is Admission.Refused, "묶인 기체의 파지를 못 봤다")
            assertIs<Admission.Passed>(w.mw.admits(single("Q/1"), A, w.single.plan(single("Q/1"))))
        }
    }

    @Test
    fun `사슬 검사는 묶인 단위를 그 기체에서 지금 도는 파지로 본다`() {
        World().use { w ->
            w.robots.declare(B, SKILL, requiresEmpty = true)
            // B 에서 다른 실행이 든 채로 돈다. 새 주문은 A 가 받지만 둘째 단위는 B 에 묶인다.
            w.mw.submit(single("Q/1"), B)
            w.mw.pump()
            w.robots.hold(B, "SINGLE#s1", HoldKind.HOLD_KIND_HOLDING)
            w.mw.pump()

            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(), A), "묶인 단위를 그 기체의 파지로 대지 않았다")
            assertTrue("u2" in refused.reason, refused.reason)
        }
    }

    @Test
    fun `배정 부담은 묶인 단위의 기체에도 센다`() {
        World().use { w ->
            w.submit()
            // B 는 우리 실행의 둘째 단위를 맡았다. 비용이 같으면 이름 순이라 B 가 먼저다 — 부담을 세야 C 로 간다.
            val chosen = assertIs<Middleware.Submission.Accepted>(w.mw.assign(single("Q/1"), listOf(B, C)))
            assertEquals(C, chosen.execution.robotId)
        }
    }

    @Test
    fun `묶인 단위의 사건은 그 단위의 기체를 싣는다`() {
        World().use { w ->
            w.submit()
            w.mw.pump()
            w.robots.finish(A, "$ORDER#u1")
            w.mw.pump()
            w.robots.revision(B, 7)
            w.robots.finish(B, "$ORDER#u2", TaskState.TASK_STATE_FAILED)
            w.mw.pump()

            val incident = w.mw.incidents().single { it.unitId == "u2" }
            assertEquals(B to 7, incident.robotId to incident.profileRevision, "사건이 실행의 기체를 실었다")
        }
    }

    // ── 다시 짓기

    @Test
    fun `다시 짓기는 단위마다 그 기체의 스냅숏을 읽고 하나라도 못 읽으면 미룬다`() {
        World().use { w ->
            w.robots.task(A, "$ORDER#u1", TaskState.TASK_STATE_SUCCEEDED)
            w.robots.unreadable += B
            val outcome = w.mw.resume(order(), A, ActiveMission(w.split, null))

            val rejected = assertIs<Middleware.Submission.Rejected>(outcome, "묶인 기체의 스냅숏 없이 지었다: $outcome")
            assertTrue(rejected.reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE), rejected.reason)
            assertTrue("robot=$B" in rejected.reason, rejected.reason)
            assertEquals(emptyList(), w.mw.executions())
            assertEquals(setOf(A, B), w.robots.snapshotsRead, "단위의 기체마다 스냅숏을 읽지 않았다")
        }
    }

    @Test
    fun `다시 짓기는 묶인 단위의 재작업 횟수를 그 기체의 스냅숏에서 되찾는다`() {
        World().use { w ->
            w.robots.task(A, "$ORDER#u1", TaskState.TASK_STATE_SUCCEEDED)
            w.robots.task(B, "$ORDER#u2@r1", TaskState.TASK_STATE_RUNNING)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.resume(order(), A, ActiveMission(w.split, null))).execution

            assertEquals(listOf(0, 1), exec.units.map { it.attempt })
            w.mw.pump()
            w.mw.pump()
            assertEquals(
                listOf(Call("start", A, "$ORDER#u1"), Call("start", B, "$ORDER#u2@r1")),
                w.robots.calls.filter { it.op == "start" },
            )
            assertEquals(0, w.robots.created, "기존 태스크에 붙지 않았다")
        }
    }

    // ── 더블

    private data class Call(val op: String, val robot: String, val task: String, val revision: Int = 1)

    /** 기체 여럿. 호출을 (종류, 기체, 태스크)로 적고, 기체마다 태스크·결함·연결·선언을 든다. */
    private class Robots(private val now: () -> Instant) : RobotPort {
        val calls = mutableListOf<Call>()
        val parameters = mutableMapOf<String, Map<String, String>>()
        val connection = mutableMapOf<String, ConnectionState>()
        val unreadable = mutableSetOf<String>()
        val snapshotsRead = linkedSetOf<String>()
        var created = 0
        private val tasks = mutableMapOf<String, LinkedHashMap<String, TaskState>>()
        private val holds = mutableMapOf<String, HoldKind>()
        private val faults = mutableMapOf<String, List<Fault>>()
        private val declared = mutableMapOf<String, Capability.Builder>()
        private val lost = mutableSetOf<Pair<String, String>>()

        fun task(robot: String, taskId: String, state: TaskState) {
            tasks.getOrPut(robot) { linkedMapOf() }[taskId] = state
        }

        fun finish(robot: String, taskId: String, state: TaskState = TaskState.TASK_STATE_SUCCEEDED) = task(robot, taskId, state)
        fun hold(robot: String, taskId: String, kind: HoldKind) {
            holds["$robot/$taskId"] = kind
        }

        fun lose(robot: String, taskId: String) {
            lost += robot to taskId
        }

        fun fault(robot: String, type: String) {
            faults[robot] = listOf(Fault.newBuilder().setErrorType(type).setCanAcceptNewTask(false).build())
        }

        fun declare(robot: String, skill: String, vararg params: String, requiresEmpty: Boolean = false) {
            val decl = SkillDeclaration.newBuilder().setSkillType(skill)
            params.forEach { decl.addParameters(ParameterDeclaration.newBuilder().setKey(it).setOptional(true)) }
            if (requiresEmpty) {
                decl.addPreconditions(
                    Precondition.newBuilder().setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD).setRequires(HoldKind.HOLD_KIND_EMPTY),
                )
            }
            declared.getOrPut(robot) { Capability.newBuilder() }.addSkills(decl)
        }

        fun revision(robot: String, revision: Int) {
            declared.getOrPut(robot) { Capability.newBuilder() }.setProfileRevision(revision)
        }

        override fun capabilities(robotId: String): Capability? = declared[robotId]?.build()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            calls += Call("start", robotId, taskId, revision)
            if (lost.remove(robotId to taskId)) {
                task(robotId, taskId, TaskState.TASK_STATE_RUNNING)
                throw IllegalStateException("답을 잃었다")
            }
            this.parameters[taskId] = parameters
            val book = tasks.getOrPut(robotId) { linkedMapOf() }
            if (taskId !in book) {
                book[taskId] = TaskState.TASK_STATE_RUNNING
                created += 1
            }
            return StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()
        }

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> {
            calls += Call("watch", robotId, handle.taskId, handle.revision)
            val state = tasks[robotId]?.get(handle.taskId) ?: return emptyList()
            return listOf(
                WatchTaskResponse.newBuilder()
                    .setHeader(MessageHeader.newBuilder().setStateAsOf(now().toString()))
                    .setState(state)
                    .setRevision(handle.revision)
                    .setHold(HoldState.newBuilder().setKind(holds["$robotId/${handle.taskId}"] ?: HoldKind.HOLD_KIND_EMPTY))
                    .build(),
            )
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
            calls += Call("cancel", robotId, handle.taskId, handle.revision)
            return CancelTaskResponse.getDefaultInstance()
        }

        override fun snapshot(robotId: String): RobotSnapshot? {
            snapshotsRead += robotId
            if (robotId in unreadable) return null
            return RobotSnapshot(
                0,
                faults[robotId].orEmpty(),
                connection[robotId] ?: ConnectionState.CONNECTION_STATE_ONLINE,
                tasks[robotId].orEmpty().toMap(),
            )
        }

        override fun replay(robotId: String, from: Long): Replay = Replay.Events(emptyList())
    }

    /** 기체마다 허가 하나. 원천은 하나이고 늘 지금 관측한다. */
    private class PermitsBy(private val each: Map<String, WorkPermit>) : WorkPermitPort {
        override fun read(): List<PermitReading> = listOf(
            PermitReading(
                source = "cell-door",
                session = "s-1",
                governed = each.keys,
                scopes = each.map { (robot, permit) ->
                    PermitScope(setOf(robot), permit, "door", null, T0, Duration.ofDays(1), "s-1")
                },
            ),
        )
    }

    /** 단위 둘. 둘째는 [bound] 에 묶는다 — 묶음을 대는 내부 자리다(널이면 기본값). */
    private class Split(
        private val bound: String?,
        override val preferredOptionals: Map<String, String> = emptyMap(),
    ) : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2

        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf(
            unit("u1", "Z1/a"),
            unit("u2", "Z2/a").also { it.robotBinding = bound },
        )

        companion object {
            const val WORK_MASTER = "Split"
        }
    }

    /** 단위 하나 — 목적지를 파라미터로 받는다. */
    private class Single : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2
        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf(unit("s1", order.parameters.getValue("to")))

        companion object {
            const val WORK_MASTER = "Single"
        }
    }

    private class World(
        bound: String? = B,
        optional: Map<String, String> = emptyMap(),
        permits: WorkPermitPort? = null,
        workspace: (String) -> String? = { null },
    ) : AutoCloseable {
        var t: Instant = T0
        val robots = Robots { t }
        val split = Split(bound, optional)
        val single = Single()
        val mw = Middleware(
            robots,
            capabilities = listOf(split, single),
            now = { t },
            reassignPolicy = ReassignPolicy(margin = 0, minHold = Duration.ZERO),
            workspace = object : Workspace {
                override fun zoneOf(location: String): String? = workspace(location)
            },
            permits = permits,
        )

        fun submit(): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), A)).execution

        override fun close() = Unit
    }

    private companion object {
        const val A = "amr-a"
        const val B = "amr-b"
        const val C = "amr-c"
        const val ORDER = "SPLIT-1"
        const val SKILL = "navigate_to"
        const val OPTIONAL = "verify_grasp"
        val T0: Instant = Instant.parse("2026-10-14T00:00:00Z")

        fun unit(id: String, to: String) = ExecutionUnit(
            unitId = id,
            route = Route.ROBOT,
            skillType = SKILL,
            parameters = emptyMap(),
            expectedIdentity = null,
            source = null,
            destination = to,
        )

        fun order(version: Int = 1) = JobOrder(ORDER, Split.WORK_MASTER, version)
        fun single(to: String) = JobOrder("SINGLE", Single.WORK_MASTER, 1, parameters = mapOf("to" to to))
    }
}
