package dev.picasso.harness

import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 시나리오 ③ — 설비 점검 순회([`docs/scenarios.md`](../../../../../../../docs/scenarios.md) §5)를
 * 계약 표면에서 돌린다.
 *
 * 보전 시스템이 점검 지점 셋과 항목을 냈고, 층 ③은 지점마다 **`navigate_to` 하나 +
 * `inspect` 하나**로 나눴다. 갈 곳은 장소의 이름(`is_site_reference`), 살필 것은
 * 대상의 이름(`is_object_reference`)이다 — 공간 둘(§15.78).
 *
 * | 지점 | `location` | `target` |
 * |---|---|---|
 * | 1 | `PUMP-ROOM` | `PUMP-01` |
 * | 2 | `SWITCHGEAR` | `PANEL-3` |
 * | 3 | `COMPRESSOR-BAY` | `COMP-2` |
 *
 * 기체는 4족 픽스처 `quadruped-b` — `inspect`는 일시정지·취소가 둘 다 `YES`이고
 * (Spot의 `MissionService`가 `PauseMission`·`StopMission`을 갖는 것과 같은 모양),
 * `navigate_to`는 취소 `NO`·일시정지 `UNKNOWN`이라 순회 중 개입은 점검 구간에서만
 * 시험한다.
 *
 * 실물은 경로가 둘이다(① `MissionService` 직결, ③ Orbit). 하네스는 경로 하나만
 * 돌리지만 §15.77이 요구하는 것은 **두 경로가 계약에서 같은 모양**이라는 것이고,
 * 여기 있는 것이 그 모양이다. Orbit 경로에는 취소가 없으므로(`vendors/orbit.md`)
 * 그쪽 어댑터는 `CANCEL_UNSUPPORTED`로 답해야 하며, 그것은 완료 기준 7이 따로 본다.
 */
class InspectionPatrolTest {

    private data class Point(val n: Int, val location: String, val target: String) {
        val navigateId get() = "PATROL-7#$n.goto"
        val inspectId get() = "PATROL-7#$n.inspect"
        val navigateParams get() = listOf(param("location", location))
        val inspectParams get() = listOf(param("target", target))
    }

    private val points = listOf(
        Point(1, "PUMP-ROOM", "PUMP-01"),
        Point(2, "SWITCHGEAR", "PANEL-3"),
        Point(3, "COMPRESSOR-BAY", "COMP-2"),
    )

    private val profile = Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()

    private fun harness() = Harness(mapOf(ROBOT to profile))

    private fun Harness.tasks() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    ).tasksList

    private fun Harness.faults() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    ).faultsList

    private fun Harness.internal(taskId: String) = tasks().single { it.taskId == taskId }

    private fun Harness.forceFault(errorType: String, taskId: String): String = oracle.forceFault(
        ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
    ).taskState

    private fun Harness.accepted(taskId: String, skill: String, parameters: List<ParameterValue>): TaskHandle {
        val response = client().start(ROBOT, taskId, 1, skill, parameters)
        assertTrue(response.hasHandle(), "$taskId 접수가 거절됐다: ${response.rejection}")
        return response.handle
    }

    private fun Harness.running(taskId: String, skill: String, parameters: List<ParameterValue>): Pair<TaskHandle, TaskFollower> {
        val handle = accepted(taskId, skill, parameters)
        val follower = client().follow(ROBOT, handle)
        advance(Duration.ofSeconds(1))
        assertEquals("RUNNING", internal(taskId).taskState, "$taskId 가 RUNNING 까지 못 갔다")
        return handle to follower
    }

    /** 종착까지 넉넉히 민다. 소요시간을 시험이 알면 지터가 붙는 날 빨개진다. */
    private fun Harness.push() = repeat(3) { advance(Duration.ofSeconds(30)) }

    private fun Harness.complete(taskId: String, skill: String, parameters: List<ParameterValue>): List<WatchTaskResponse> {
        val (_, follower) = running(taskId, skill, parameters)
        push()
        val updates = follower.awaitTerminal()
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, updates.last().state, "$taskId 가 완주하지 못했다")
        return updates
    }

    /** 지점 하나 — 가서 살핀다. 점검 스트림을 돌려준다. */
    private fun Harness.visit(point: Point): List<WatchTaskResponse> {
        complete(point.navigateId, "navigate_to", point.navigateParams)
        return complete(point.inspectId, "inspect", point.inspectParams)
    }

    // ── 순회

    @Test
    fun `지점 셋은 태스크 여섯이고, 점검은 대상을 참조할 뿐 쥐지 않는다`() {
        harness().use { harness ->
            for (point in points) {
                val inspection = harness.visit(point)
                // `target` 은 대상의 이름이지만 점검은 쥐지 않는다 — 참조와 쥠은 다르다(§15.87).
                assertTrue(
                    inspection.all { it.hold.kind == HoldKind.HOLD_KIND_EMPTY },
                    "${point.inspectId} 가 든 채로 보고됐다: ${inspection.map { it.hold.kind }}",
                )
            }
            assertEquals(
                points.flatMap { listOf(it.navigateId, it.inspectId) }.toSet(),
                harness.tasks().map { it.taskId }.toSet(),
                "지점 셋 = 태스크 여섯",
            )
        }
    }

    @Test
    fun `점검 결과를 실을 자리가 없다 — 이 시험은 통과하는 것이 좋은 상태가 아니다`() {
        // 시나리오 ③의 결과는 *항목별 수행 상태와 측정값 또는 증거 자료 참조*다.
        // 계약이 위로 올릴 수 있는 것은 상태·결함·능력 변경뿐이고 구조화된 관측을
        // 실을 자리가 `partial_result` 문자열 하나다(§15.76). 아무도 채우지 않는다.
        // 채우는 쪽이 생기면 여기가 빨개진다 — 그때 이 시험을 지우고 진짜 단언으로
        // 바꾼다. `LongRunningTaskTest`가 같은 것을 `pick_place`에 대해 고정한다.
        harness().use { harness ->
            val inspection = harness.visit(points.first())
            assertTrue(
                inspection.all { it.partialResult.isEmpty() },
                "점검 결과를 채우는 쪽이 생겼다 — 이 시험을 지우고 결과 단언을 세울 때다",
            )
        }
    }

    // ── 순회 중 개입

    @Test
    fun `점검을 멈췄다 이어 간다 — 그 사이에도 든 것은 없다`() {
        // Spot 의 PauseMission → PlayMission 이 계약에서 갖는 모양.
        harness().use { harness ->
            val point = points.first()
            harness.complete(point.navigateId, "navigate_to", point.navigateParams)
            val (handle, follower) = harness.running(point.inspectId, "inspect", point.inspectParams)

            assertEquals(TaskState.TASK_STATE_PAUSED, harness.client().pause(ROBOT, handle).state)
            harness.advance(Duration.ofSeconds(30))
            assertEquals("PAUSED", harness.internal(point.inspectId).taskState, "멈췄는데 시간이 태스크를 끝냈다")

            assertEquals(TaskState.TASK_STATE_RUNNING, harness.client().resume(ROBOT, handle).state)
            harness.push()

            val updates = follower.awaitTerminal()
            assertEquals(TaskState.TASK_STATE_SUCCEEDED, updates.last().state)
            assertTrue(updates.any { it.state == TaskState.TASK_STATE_PAUSED }, "스트림에 PAUSED 가 안 보인다")
            assertTrue(updates.all { it.hold.kind == HoldKind.HOLD_KIND_EMPTY })
        }
    }

    @Test
    fun `점검 중 위치를 잃으면 사람이 와야 하고, 결함은 인터록이 아니다`() {
        // 기체 수준 LOCALIZATION_LOST(can_accept_new_task=false). 점검은 NEEDS_INTERVENTION
        // 으로 서고, 계약은 **다음 태스크를 막지 않는다** — 판단은 밖이다(§4.6).
        // 점검 결과나 결함을 안전 인터록으로 쓰지 않는 것이 시나리오 ③의 경계다(ADR 32).
        harness().use { harness ->
            val (first, second) = points
            harness.complete(first.navigateId, "navigate_to", first.navigateParams)
            val (handle, follower) = harness.running(first.inspectId, "inspect", first.inspectParams)

            assertEquals("NEEDS_INTERVENTION", harness.forceFault("LOCALIZATION_LOST", first.inspectId))
            val fault = harness.faults().single { it.errorType == "LOCALIZATION_LOST" }
            assertEquals(false, fault.canAcceptNewTask, "프로파일이 선언한 그 불리언이어야 한다")

            // 결함이 "새 태스크 못 받는다" 고 말해도 계약은 접수를 막지 않는다 — 판단은 층 ③ 의 것.
            val next = harness.client().start(ROBOT, second.navigateId, 1, "navigate_to", second.navigateParams)
            assertTrue(next.hasHandle(), "계약이 정책을 가졌다 — 결함을 인터록으로 썼다: ${next.rejection}")

            // 사람이 조치한 뒤 재시도 — attempt 가 오르고 점검이 끝난다.
            assertEquals(TaskState.TASK_STATE_RUNNING, harness.client().retry(ROBOT, handle).state)
            harness.push()
            val updates = follower.awaitTerminal()
            assertEquals(TaskState.TASK_STATE_SUCCEEDED, updates.last().state)
            assertEquals(1, updates.last().attempt, "재시도가 attempt 를 올려야 한다")
        }
    }

    @Test
    fun `점검을 취소하면 빈손으로 끝난다 — 쥔 것이 없으니 복구할 것도 없다`() {
        harness().use { harness ->
            val point = points.first()
            harness.complete(point.navigateId, "navigate_to", point.navigateParams)
            val (handle, follower) = harness.running(point.inspectId, "inspect", point.inspectParams)

            assertEquals(TaskState.TASK_STATE_CANCELLING, harness.client().cancel(ROBOT, handle).state)
            harness.advance(Duration.ofSeconds(1))

            val last = follower.awaitTerminal().last()
            assertEquals(TaskState.TASK_STATE_CANCELLED, last.state)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, last.hold.kind)
        }
    }

    private companion object {
        const val ROBOT = "quad-01"

        fun param(key: String, value: String): ParameterValue =
            ParameterValue.newBuilder().setKey(key).setStringValue(value).build()
    }
}
