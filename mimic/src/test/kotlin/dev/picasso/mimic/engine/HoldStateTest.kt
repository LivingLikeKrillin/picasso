package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.profile.projection.CapabilityProjection
import dev.picasso.profile.ProfileDocument
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 설계 §4.4의 잔여 물리 상태 — 미믹이 `HoldState`를 어떻게 채우는가.
 *
 * 여기서 붙드는 것은 값 자체보다 **불변식**이다: `CANCELLED`(복구까지
 * 마쳤다)와 `HOLDING`이 함께 올 수 없다. 그것이 깨지면 §4.4가
 * `CANCELLED_RECOVERY_FAILED`를 따로 둔 이유가 죽는다.
 */
class HoldStateTest {

    private val listener = EngineListener.NONE

    private fun host(doc: ProfileDocument, clock: VirtualClock) = TaskHost(
        CapabilityProjection.of(doc), doc, clock,
        listener, FaultRegistry(clock), Seeded(0),
    )

    private fun pickPlaceParams(): List<ParameterValue> = listOf(
        TaskMachineFixtures.param("object_id", "SEQ-IN-02.BIN-A"),
        TaskMachineFixtures.param("destination", "RACK-204.S01"),
    )

    private fun TaskRuntime.lastHold(): HoldState = log.last!!.hold

    /** `pick_place`를 접수하고 `RUNNING`까지 민다. */
    private fun runningPickPlace(tasks: TaskHost, taskId: String = "t1"): TaskRuntime {
        val outcome = tasks.start(taskId, 1, "pick_place", pickPlaceParams())
        check(outcome is StartOutcome.Accepted) { "접수 실패: $outcome" }
        tasks.tick()
        val task = tasks.find(taskId)!!
        check(task.machine.state == TaskState.RUNNING) { "RUNNING이 아니다: ${task.machine.state}" }
        return task
    }

    // ── 누가 드는가는 계약이 정한다

    @Test
    fun `대상의 이름을 받는 스킬은 도는 동안 든 채다 — 그 이름을 싣는다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val task = runningPickPlace(host(TaskMachineFixtures.document(), clock))

        val hold = task.lastHold()
        assertEquals(HoldKind.HOLD_KIND_HOLDING, hold.kind)
        assertEquals("SEQ-IN-02.BIN-A", hold.objectRef, "대상의 이름 공간이어야 한다(§15.78)")
    }

    @Test
    fun `대상이 없는 스킬은 빈손이다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(TaskMachineFixtures.document(), clock)
        tasks.start("n1", 1, "navigate_to", listOf(TaskMachineFixtures.param("location", "dock-3")))
        tasks.tick()

        assertEquals(HoldKind.HOLD_KIND_EMPTY, tasks.find("n1")!!.lastHold().kind)
    }

    @Test
    fun `대상을 참조만 하는 스킬은 빈손이다 — inspect`() {
        // `inspect(target)` 의 target 은 대상의 이름(is_object_reference)이지만
        // 점검은 쥐지 않는다(grasps_object 없음). 앞 판은 이 둘을 접어 점검 중인
        // 로봇을 든 채로 보고했다 — 시나리오 ③이 잡은 결함이다(§15.87).
        val quadruped = TaskMachineFixtures.document(
            java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "profile", "profiles", "quadruped-b.json").normalize(),
            ).replace("\r\n", "\n"),
        )
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(quadruped, clock)
        tasks.start("i1", 1, "inspect", listOf(TaskMachineFixtures.param("target", "PUMP-01")))
        tasks.tick()

        val task = tasks.find("i1")!!
        assertEquals(TaskState.RUNNING, task.machine.state)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, task.lastHold().kind, "점검 중인 로봇이 대상을 든 채로 보고된다")
    }

    @Test
    fun `접수 시점에는 아직 빈손이다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(TaskMachineFixtures.document(), clock)
        tasks.start("t1", 1, "pick_place", pickPlaceParams())

        // tick 전 — ACCEPTED
        val task = tasks.find("t1")!!
        assertEquals(TaskState.ACCEPTED, task.machine.state)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, task.lastHold().kind)
    }

    @Test
    fun `완주하면 놓았다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(TaskMachineFixtures.document(), clock)
        val task = runningPickPlace(tasks)

        repeat(3) { clock.advance(Duration.ofSeconds(30)) } // 소요시간을 시험이 알면 지터가 붙는 날 빨개진다
        tasks.tick()

        assertEquals(TaskState.SUCCEEDED, task.machine.state, "전제가 무너졌다 — 완주가 안 됐다")
        assertEquals(HoldKind.HOLD_KIND_EMPTY, task.lastHold().kind)
    }

    // ── 실패는 파지를 바꾸지 않는다, 놓친 것만 빼고

    @Test
    fun `스킬 실패는 든 채다 — 실패가 물건을 내려놓지는 않는다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(TaskMachineFixtures.document(), clock)
        val task = runningPickPlace(tasks)

        tasks.forceFault("SKILL_EXECUTION_FAILED", "t1")

        assertEquals(TaskState.RETRIABLE, task.machine.state)
        assertEquals(HoldKind.HOLD_KIND_HOLDING, task.lastHold().kind)
    }

    @Test
    fun `PAYLOAD_LOST 는 빈손이다 — 놓쳤으니까`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(TaskMachineFixtures.document(), clock)
        val task = runningPickPlace(tasks)

        tasks.forceFault("PAYLOAD_LOST", "t1")

        assertEquals(TaskState.NEEDS_INTERVENTION, task.machine.state)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, task.lastHold().kind)
    }

    // ── 취소와 복구 — 불변식이 사는 자리

    /**
     * 픽스처의 `pick_place`는 `cancel_support: NO`라 여기서는 취소 가능한
     * 사본을 쓴다. **바꾸는 것은 그 한 값뿐**이고 하나뿐인지 확인한다 —
     * 둘 이상이면 무엇을 바꿨는지 모르는 채 시험하는 것이다.
     */
    private fun cancellableDocument(): ProfileDocument {
        val raw = TaskMachineFixtures.fixtureRaw
        val needle = "\"cancel_support\": \"NO\""
        check(raw.split(needle).size == 2) { "픽스처에 cancel_support NO 가 하나가 아니다" }
        return TaskMachineFixtures.document(raw.replace(needle, "\"cancel_support\": \"YES\""))
    }

    private fun cancel(tasks: TaskHost, task: TaskRuntime) {
        val transition = task.machine.apply(TaskCommand.CANCEL)
        check(transition is TaskTransition.Moved) { "취소를 못 걸었다: $transition" }
        tasks.record(task)
    }

    @Test
    fun `취소 중에는 아직 든 채다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(cancellableDocument(), clock)
        val task = runningPickPlace(tasks)

        cancel(tasks, task)

        assertEquals(TaskState.CANCELLING, task.machine.state)
        assertEquals(HoldKind.HOLD_KIND_HOLDING, task.lastHold().kind)
    }

    @Test
    fun `복구까지 마치면 빈손이다 — CANCELLED 와 HOLDING 은 함께 오지 않는다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(cancellableDocument(), clock)
        val task = runningPickPlace(tasks)

        cancel(tasks, task)
        tasks.tick()

        assertEquals(TaskState.CANCELLED, task.machine.state)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, task.lastHold().kind)
    }

    @Test
    fun `복구에 실패하면 든 채다 — 그것이 CANCELLED_RECOVERY_FAILED 의 뜻이다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(cancellableDocument(), clock)
        val task = runningPickPlace(tasks)

        cancel(tasks, task)
        tasks.forceFault("LOCALIZATION_LOST", "t1")

        assertEquals(TaskState.CANCELLED_RECOVERY_FAILED, task.machine.state)
        val hold = task.lastHold()
        assertEquals(HoldKind.HOLD_KIND_HOLDING, hold.kind)
        assertEquals("SEQ-IN-02.BIN-A", hold.objectRef, "무엇을 든 채 멈췄는지가 실려야 한다")
    }

    @Test
    fun `복구 실패가 언제나 든 채는 아니다 — 놓쳐서 실패했으면 빈손이다`() {
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(cancellableDocument(), clock)
        val task = runningPickPlace(tasks)

        cancel(tasks, task)
        tasks.forceFault("PAYLOAD_LOST", "t1")

        assertEquals(TaskState.CANCELLED_RECOVERY_FAILED, task.machine.state)
        assertEquals(HoldKind.HOLD_KIND_EMPTY, task.lastHold().kind)
    }

    @Test
    fun `로그 어디에도 CANCELLED 와 HOLDING 이 함께 있지 않다`() {
        // 위 시험들이 마지막 줄만 본다. 불변식은 **모든 줄**에 걸린다.
        val clock = VirtualClock(Instant.EPOCH)
        val tasks = host(cancellableDocument(), clock)
        val task = runningPickPlace(tasks)
        cancel(tasks, task)
        tasks.tick()

        val offenders = task.log.from(0).filter {
            it.state == TaskState.CANCELLED && it.hold.kind == HoldKind.HOLD_KIND_HOLDING
        }
        assertEquals(emptyList(), offenders)
        assertTrue(task.log.from(0).any { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }, "든 적이 없다면 이 시험은 공허하다")
        assertNotEquals(0, task.log.size)
    }
}
