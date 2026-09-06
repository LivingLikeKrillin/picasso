package dev.picasso.harness

import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.DumpInternalStateResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **완료 기준 2 (A-2)** — 진행 중 신규 구독 → 스냅샷 + `event`로 재구성한
 * 상태가 **내부 상태와 일치**.
 *
 * §12.2가 `DumpInternalState`를 이 기준의 오라클로 둔 이유가 요점이다 —
 * `GetSnapshot`과 비교하면 **계약 표면의 투영을 투영과 비교**하는 순환이 되어,
 * 투영이 틀려도 양쪽이 똑같이 틀린다.
 *
 * 공허해지는 길이 다섯이다.
 *
 * 1. 내부 상태가 비어 있으면 무엇이든 일치한다 → 자명하지 않음을 먼저 단언.
 * 2. 모든 이벤트가 끝난 뒤 스냅샷을 뜨면 이어 붙일 것이 없다 → **진행 중**에 뜬다.
 * 3. 스냅샷을 스냅샷과 비교하면 순환이다 → 오라클로 비교.
 * 4. 스냅샷만으로도, 이벤트만으로도 도달하면 나머지 하나가 장식이다 → 둘 다 못 미침을 단언.
 * 5. **리듀서가 시험 안에 있다** → `else` 없이 쓰고 모르는 종류에 `fail()`.
 */
class ReconstructionTest {

    private val harness = Harness(
        mapOf(ROBOT to Path.of("..", "profile", "profiles", "humanoid-a.json").normalize()),
    )

    @AfterTest fun close() = harness.close()

    private val instance get() = harness.registry.byId(ROBOT)!!.instance

    private fun location(value: String) =
        ParameterValue.newBuilder().setKey("location").setStringValue(value).build()

    private fun start(taskId: String, target: String) {
        instance.tasks.start(taskId, 1, "navigate_to", listOf(location(target)))
    }

    private fun snapshot(): GetSnapshotResponse = harness.events.getSnapshot(
        GetSnapshotRequest.newBuilder()
            .setHeader(RequestHeaders.build("picasso.v1.GetSnapshotRequest", ROBOT, CLIENT))
            .build(),
    )

    private fun replay(from: Long): List<Event> = harness.events
        .withDeadlineAfter(10, TimeUnit.SECONDS)
        .replayEvents(
            ReplayEventsRequest.newBuilder()
                .setHeader(RequestHeaders.build("picasso.v1.ReplayEventsRequest", ROBOT, CLIENT))
                .setFromSequence(from).build(),
        ).asSequence().map {
            if (it.hasRejection()) fail("재생이 거절됐다: ${it.rejection.detail}")
            it.event
        }.toList()

    private fun dump(): DumpInternalStateResponse = harness.oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    /**
     * 시나리오. **세 태스크가 스냅샷을 기준으로 서로 다른 자리에 놓인다** —
     * 그래야 스냅샷만으로도 이벤트만으로도 도달하지 못한다.
     *
     * | 태스크 | 스냅샷 이전 | 스냅샷 이후 |
     * |---|---|---|
     * | `before` | 접수·시작·완주 | 없음 → **이벤트만으로는 못 본다** |
     * | `across` | 접수·시작 | 완주 → 둘 다 필요하다 |
     * | `after` | 없음 | 접수·시작 → **스냅샷만으로는 못 본다** |
     */
    private fun scenario(): Long {
        start("before", "dock-1")
        harness.advance(Duration.ofSeconds(30)) // 접수 → 진행
        harness.advance(Duration.ofSeconds(30)) // 완주(navigate_to는 20초)

        start("across", "dock-2")
        harness.advance(Duration.ofSeconds(5)) // 접수 → 진행. 아직 안 끝났다

        val at = snapshotAndRemember()

        harness.advance(Duration.ofSeconds(30)) // across 완주
        start("after", "dock-3")
        harness.advance(Duration.ofSeconds(5)) // after 진행
        return at
    }

    // ── 전제

    @Test
    fun `내부 상태가 자명하지 않다`() {
        // 비어 있으면 무엇이든 일치한다.
        scenario()
        val tasks = dump().tasksList
        assertEquals(3, tasks.size, "${tasks.map { it.taskId }}")
        assertTrue(tasks.map { it.taskState }.toSet().size >= 2, "상태가 전부 같다")
        assertTrue(tasks.any { it.taskState == "SUCCEEDED" } && tasks.any { it.taskState == "RUNNING" })
    }

    @Test
    fun `스냅샷 시점에 진행 중인 태스크가 있다`() {
        // 모든 이벤트가 끝난 뒤 스냅샷을 뜨면 이어 붙일 것이 없다.
        start("across", "dock-2")
        harness.advance(Duration.ofSeconds(5))
        val snapshot = snapshot()
        assertTrue(
            snapshot.tasksList.any { it.state.name == "TASK_STATE_RUNNING" },
            "진행 중인 태스크가 없다 — 중간 구독이 아니다",
        )
    }

    // ── 본체

    @Test
    fun `진행 중에 붙은 소비자가 스냅샷과 이벤트로 내부 상태에 도달한다`() {
        val at = scenario()
        val reconstructed = reduce(snapshotBefore = at, events = replay(at))
        assertEquals(oracle(), reconstructed)
    }

    @Test
    fun `이벤트 없이 스냅샷만으로는 도달하지 못한다`() {
        val at = scenario()
        val snapshotOnly = reduce(snapshotBefore = at, events = emptyList())
        assertNotEquals(oracle(), snapshotOnly, "이벤트가 장식이다")
        // 무엇이 다른지 못박는다 — 우연히 다른 것이 아니어야 한다.
        assertTrue("after" !in snapshotOnly.keys, "스냅샷이 이후 태스크를 이미 안다")
        assertEquals("RUNNING", snapshotOnly.getValue("across").taskState)
    }

    @Test
    fun `스냅샷 없이 이벤트만으로는 도달하지 못한다`() {
        val at = scenario()
        val eventsOnly = reduce(snapshotBefore = null, events = replay(at))
        assertNotEquals(oracle(), eventsOnly, "스냅샷이 장식이다")
        assertTrue("before" !in eventsOnly.keys, "스냅샷 이전에 끝난 태스크가 이벤트에 있다")
    }

    @Test
    fun `재생이 스냅샷 경계를 정확히 지킨다`() {
        // **리듀서는 한 이벤트를 다시 적용해도 같은 답을 낸다**(덮어쓰기라
        // 멱등이다). 그래서 스냅샷의 sequence가 하나 어긋나도 끝값 비교는
        // 그대로 통과한다(실측: 주입한 결함이 새어 나갔다). 경계를 직접 본다.
        val at = scenario()
        val replayed = replay(at)

        assertTrue(replayed.isNotEmpty(), "스냅샷 뒤에 아무 일도 없었다 — 시험이 공허하다")
        assertEquals(
            at, replayed.first().header.sequence,
            "재생이 스냅샷 이전 이벤트를 포함한다 — 이미 반영된 것을 다시 접는다",
        )
        assertTrue(
            replayed.all { it.header.sequence >= at },
            "재생에 경계 이전 번호가 섞였다: ${replayed.map { it.header.sequence }}",
        )
        // **연속이라고 단언하지 않는다.** `sequence`는 기체 단위 하나이고
        // `state`·`event`·`connection` 세 스트림이 그것을 함께 쓴다
        // (§5.5의 발행 열이 셋을 다 덮는다). 주기 상태 발행이 붙은 뒤로
        // `ReplayEvents`가 돌려주는 번호에는 **상태 메시지가 쓴 자리가
        // 비어 있다** — 그것은 결손이 아니다.
        //
        // 이 사실이 완료 기준 3의 소비자 설계를 정한다: **이벤트 스트림만
        // 보고 구멍을 결손이라고 판정하면 안 된다.** 판정은 발행 축 전체
        // 위에서 해야 한다.
        assertEquals(
            replayed.map { it.header.sequence }.sorted(),
            replayed.map { it.header.sequence },
            "재생이 번호 순이 아니다",
        )
        assertEquals(
            replayed.map { it.header.sequence }.distinct(),
            replayed.map { it.header.sequence },
            "재생에 같은 번호가 두 번 있다",
        )
    }

    @Test
    fun `스냅샷이 말하는 번호가 엔진이 아는 다음 번호와 같다`() {
        // **경계 단언만으로는 부족하다.** sequence가 하나 어긋나도 재생은
        // 그 번호부터 성실히 돌려주고, 리듀서는 한 이벤트 재적용에 멱등이라
        // 끝값 비교도 통과한다(실측: 주입한 결함이 둘 다 새어 나갔다).
        //
        // 계약 표면이 말하는 것과 **오라클이 아는 것**을 직접 댄다.
        start("across", "dock-2")
        harness.advance(Duration.ofSeconds(5))

        assertEquals(dump().nextSequence, snapshot().sequence)

        harness.advance(Duration.ofSeconds(30))
        assertEquals(dump().nextSequence, snapshot().sequence, "시간이 흐른 뒤에도 같아야 한다")
    }

    @Test
    fun `비교하는 필드가 스냅샷의 필드를 전부 덮는다`() {
        // 새 필드가 생기면 비교에서 조용히 빠진다. 디스크립터와 대조한다.
        assertEquals(
            TaskSnapshot.getDescriptor().fields.map { it.name }.toSet(),
            setOf("task_id", "skill_type", "state", "revision", "attempt"),
            "TaskSnapshot에 새 필드가 생겼다 — 재구성 비교를 갱신하라",
        )
    }

    // ── 리듀서 (시험이 갖는다)

    private data class TaskView(
        val taskId: String,
        val skillType: String,
        val taskState: String,
        val revision: Int,
        val attempt: Int,
        val skillState: String,
    )

    /** 오라클을 같은 모양으로 내린다. */
    private fun oracle(): Map<String, TaskView> = dump().tasksList.associate {
        it.taskId to TaskView(
            it.taskId, it.skillType, it.taskState, it.revision, it.attempt, it.skillState,
        )
    }

    /**
     * 스냅샷과 이벤트를 접는다.
     *
     * **`else`를 쓰지 않는다.** 안 다루는 이벤트 종류가 항등원으로 접히면
     * 그 종류가 아예 발행되지 않아도 시험이 알아채지 못한다.
     */
    private fun reduce(snapshotBefore: Long?, events: List<Event>): Map<String, TaskView> {
        val tasks = mutableMapOf<String, TaskView>()
        val skills = mutableMapOf<String, String>()

        if (snapshotBefore != null) {
            val snapshot = snapshotAt(snapshotBefore)
            snapshot.first.forEach { tasks[it.taskId] = it }
            skills += snapshot.second
        }

        events.forEach { event ->
            when (event.bodyCase) {
                Event.BodyCase.TASK_TRANSITION -> {
                    val body = event.taskTransition
                    tasks[body.taskId] = TaskView(
                        taskId = body.taskId,
                        skillType = body.skillType,
                        taskState = body.to.name.removePrefix("TASK_STATE_"),
                        revision = body.revision,
                        attempt = body.attempt,
                        skillState = skills[body.taskId] ?: "",
                    )
                }
                Event.BodyCase.SKILL_TRANSITION -> {
                    val body = event.skillTransition
                    skills[body.taskId] = body.to.name.removePrefix("SKILL_STATE_")
                    tasks[body.taskId]?.let {
                        tasks[body.taskId] = it.copy(skillState = skills.getValue(body.taskId))
                    }
                }
                Event.BodyCase.FAULT_EVENT ->
                    fail("6a는 결함을 만들지 않는다 — 나오면 시나리오가 바뀐 것이다")
                Event.BodyCase.CAPABILITY_CHANGED ->
                    fail("6a는 능력을 바꾸지 않는다 — 나오면 시나리오가 바뀐 것이다")
                Event.BodyCase.BODY_NOT_SET ->
                    fail("본문이 없는 이벤트가 나왔다: ${event.header.eventId}")
                null -> fail("bodyCase가 null이다")
            }
        }
        return tasks
    }

    /** 그 시점의 스냅샷을 다시 만든다. 시나리오가 결정적이라 재현된다. */
    private fun snapshotAt(sequence: Long): Pair<List<TaskView>, Map<String, String>> {
        // 스냅샷은 지금 시점이 아니라 **그때**의 것이어야 하므로, 시나리오를
        // 다시 돌리는 대신 그때 떠 둔 것을 쓴다. 여기서는 재생 가능한
        // 결정적 시나리오이므로 캐시로 족하다.
        val cached = snapshots.getValue(sequence)
        return cached.tasksList.map {
            TaskView(
                it.taskId, it.skillType, it.state.name.removePrefix("TASK_STATE_"),
                it.revision, it.attempt,
                cached.skillsList.firstOrNull { s -> s.taskId == it.taskId }
                    ?.state?.name?.removePrefix("SKILL_STATE_") ?: "",
            )
        } to cached.skillsList.associate {
            it.taskId to it.state.name.removePrefix("SKILL_STATE_")
        }
    }

    /** 그때 뜬 스냅샷. 지금 다시 뜨면 그 시점의 것이 아니다. */
    private val snapshots = mutableMapOf<Long, GetSnapshotResponse>()

    private fun snapshotAndRemember(): Long {
        val response = snapshot()
        snapshots[response.sequence] = response
        return response.sequence
    }

    private companion object {
        const val ROBOT = "r1"
        const val CLIENT = "line-controller"
    }
}
