package dev.picasso.harness

import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.SetConnectionRequest
import dev.picasso.mimic.control.v1.SetSingleStepRequest
import dev.picasso.contracts.v1.ConnectionMessage
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 시나리오 ② — 부품 시퀀싱([`docs/scenarios.md`](../../../../../../../docs/scenarios.md) §4)을
 * 계약 표면에서 돌린다.
 *
 * MES가 *"작업 SEQ-204, 생산 순서 버전 17에 따라 랙 RACK-204의 슬롯 넷에
 * 지정 부품을 배치하라"* 고 했고, 층 ③은 그것을 **슬롯마다 `StartTask` 하나**로
 * 나눴다(§15.80 — 분류는 원자가 아니다). 생산 순서 버전이 곧 태스크의
 * `revision`이다.
 *
 * | 슬롯 | 제시 자리(`object_id`) | 목적지(`destination`) |
 * |---|---|---|
 * | S01 | `SEQ-IN-02.BIN-A` | `RACK-204.S01` |
 * | S02 | `SEQ-IN-02.BIN-B` | `RACK-204.S02` |
 * | S03 | `SEQ-IN-02.BIN-A` | `RACK-204.S03` |
 * | S04 | `SEQ-IN-02.BIN-C` | `RACK-204.S04` |
 *
 * 여기서 붙드는 것은 시나리오 문서 §4.4의 상황표에서 **계약이 답하는 것**이다.
 * 답하지 않는 것(B형 슬롯에 A형이 들어갔는가 — E2, 층 ③)은 여기 없고,
 * 없는 것이 맞다.
 *
 * 기체는 하나이므로 슬롯은 **차례로** 돈다 — 실물은 예외 없이 배타적 제어
 * 모델이고(§4.9), 순서를 정하는 것은 층 ③이지 로봇이 아니다.
 */
class SequencingCellTest {

    private data class Slot(val id: String, val bin: String, val rack: String) {
        val taskId get() = "SEQ-204#$id"
        val parameters
            get() = listOf(
                ParameterValue.newBuilder().setKey("object_id").setStringValue(bin).build(),
                ParameterValue.newBuilder().setKey("destination").setStringValue(rack).build(),
            )
    }

    private val slots = listOf(
        Slot("S01", "SEQ-IN-02.BIN-A", "RACK-204.S01"),
        Slot("S02", "SEQ-IN-02.BIN-B", "RACK-204.S02"),
        Slot("S03", "SEQ-IN-02.BIN-A", "RACK-204.S03"),
        Slot("S04", "SEQ-IN-02.BIN-C", "RACK-204.S04"),
    )
    private val s01 get() = slots[0]
    private val s02 get() = slots[1]
    private val s03 get() = slots[2]
    private val s04 get() = slots[3]

    private fun harness() = Harness(mapOf(ROBOT to CancellablePickPlace.profile()))

    private fun Harness.tasks() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    ).tasksList

    private fun Harness.internal(slot: Slot) = tasks().single { it.taskId == slot.taskId }

    private fun Harness.start(slot: Slot, version: Int = V17): StartTaskResponse =
        client().start(ROBOT, slot.taskId, version, "pick_place", slot.parameters)

    private fun Harness.accepted(slot: Slot, version: Int = V17): TaskHandle {
        val response = start(slot, version)
        assertTrue(response.hasHandle(), "${slot.id} 접수가 거절됐다: ${response.rejection}")
        return response.handle
    }

    /** 접수하고 팔로워를 붙인 뒤 `RUNNING`까지 민다. */
    private fun Harness.running(slot: Slot, version: Int = V17): Pair<TaskHandle, TaskFollower> {
        val handle = accepted(slot, version)
        val follower = client().follow(ROBOT, handle)
        advance(Duration.ofSeconds(1))
        assertEquals("RUNNING", internal(slot).taskState, "${slot.id} 가 RUNNING 까지 못 갔다")
        return handle to follower
    }

    /** 종착까지 넉넉히 민다. 소요시간을 시험이 알면 지터가 붙는 날 빨개진다. */
    private fun Harness.push() = repeat(3) { advance(Duration.ofSeconds(30)) }

    /** 슬롯 하나를 처음부터 끝까지 — 접수 → 완주. 완주한 스트림을 돌려준다. */
    private fun Harness.complete(slot: Slot): List<dev.picasso.contracts.v1.WatchTaskResponse> {
        val (_, follower) = running(slot)
        push()
        val updates = follower.awaitTerminal()
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, updates.last().state, "${slot.id} 가 완주하지 못했다")
        return updates
    }

    /** §10.5 — 켜면 RPC 가 tick 을 안 부른다. ACCEPTED 를 붙들어 두는 유일한 길이다. */
    private fun Harness.singleStep(enabled: Boolean) = oracle.setSingleStep(
        SetSingleStepRequest.newBuilder().setRobotId(ROBOT).setEnabled(enabled).build(),
    )

    private fun Harness.setConnection(state: ConnectionState) = oracle.setConnection(
        SetConnectionRequest.newBuilder().setRobotId(ROBOT).setState(state.name).build(),
    )

    // ── 정상 흐름

    @Test
    fun `슬롯 넷은 태스크 넷이고, 각각 든 채로 돌다 빈손으로 끝난다`() {
        harness().use { harness ->
            for (slot in slots) {
                val updates = harness.complete(slot)

                // 도는 동안 무엇을 들었는지가 실린다 — 그 슬롯의 제시 자리 이름으로.
                val holding = updates.filter { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }
                assertTrue(holding.isNotEmpty(), "${slot.id} 가 든 적이 없다")
                assertEquals(setOf(slot.bin), holding.map { it.hold.objectRef }.toSet(), "${slot.id} 가 든 것의 이름")

                // 완주하면 빈손이다.
                assertEquals(HoldKind.HOLD_KIND_EMPTY, updates.last().hold.kind, "${slot.id} 완주 뒤에도 든 채다")
            }
            assertEquals(slots.map { it.taskId }.toSet(), harness.tasks().map { it.taskId }.toSet(), "슬롯 넷 = 태스크 넷")
        }
    }

    // ── 상황표

    @Test
    fun `C형이 부족하다 — 완료한 슬롯은 보존되고, 재발행은 재실행이 아니다`() {
        // S01·S02 를 마친 뒤 C형이 없어 S04 를 못 시작한다. 층 ③이 부족을
        // 보고하는 동안 **완료 슬롯은 그대로**여야 한다.
        harness().use { harness ->
            harness.complete(s01)
            harness.complete(s02)
            val before = harness.tasks().size

            // 같은 revision 재발행 — 같은 핸들이고 새 태스크가 아니다(§4.4 멱등).
            val again = harness.start(s01)
            assertTrue(again.hasHandle(), "재발행이 거절됐다: ${again.rejection}")
            assertEquals(before, harness.tasks().size, "재발행이 새 태스크를 만들었다")
            assertEquals("SUCCEEDED", harness.internal(s01).taskState, "재발행이 완료 슬롯을 다시 돌렸다")
            assertEquals("SUCCEEDED", harness.internal(s02).taskState)

            // S04 는 시작하지 않았으므로 태스크가 없다 — 있으면 층 ③이 없는 부품을 시켰다는 뜻이다.
            assertFalse(harness.tasks().any { it.taskId == s04.taskId }, "못 시작할 슬롯에 태스크가 있다")
        }
    }

    @Test
    fun `버전 18 — 완료 슬롯은 새 버전을 상속하고, 갱신은 거절로 드러난다`() {
        // 외부 문서의 규칙 *"새 버전은 확정된 단위 이후에만 붙는다"* 가 계약에서는
        // 저절로 성립한다 — 종착은 래치되어 갱신을 받지 않는다(§4.4).
        harness().use { harness ->
            harness.complete(s01)

            val bumped = harness.start(s01, V18)
            assertFalse(bumped.hasHandle(), "완료한 슬롯이 새 버전을 받아들였다")
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, bumped.rejection.code)
            assertEquals("SUCCEEDED", harness.internal(s01).taskState, "거절하면서 상태를 바꿨다")
        }
    }

    @Test
    fun `버전 18 — 아직 안 시작한 슬롯은 파라미터만 바뀐다`() {
        // S04 를 접수만 해 두고(ACCEPTED) 버전이 바뀐다 — C형이 A형으로.
        // 모든 RPC 가 tick 을 부르므로 ACCEPTED 에 머물게 하려면 단일 스텝이어야 한다.
        harness().use { harness ->
            harness.singleStep(true)
            val handle = harness.accepted(s04)
            val follower = harness.client().follow(ROBOT, handle)
            assertEquals("ACCEPTED", harness.internal(s04).taskState, "전제 — 아직 ACCEPTED 여야 한다")

            val retargeted = s04.copy(bin = "SEQ-IN-02.BIN-A")
            val response = harness.client().start(ROBOT, s04.taskId, V18, "pick_place", retargeted.parameters)
            assertTrue(response.hasHandle(), "안 시작한 슬롯의 갱신이 거절됐다: ${response.rejection}")
            assertEquals(V18, response.handle.revision)
            assertEquals("ACCEPTED", harness.internal(s04).taskState, "파라미터만 바뀌어야 한다 — 스킬 전이 없음")

            // 돌기 시작하면 **새 파라미터의 대상**을 든다.
            harness.singleStep(false)
            harness.advance(Duration.ofSeconds(1))
            val updates = follower.awaitUpdates(3)
            val held = updates.last { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            assertEquals("SEQ-IN-02.BIN-A", held.hold.objectRef, "옛 파라미터의 대상을 들었다")
            assertEquals(V18, held.revision)
        }
    }

    @Test
    fun `버전 18 — 도는 슬롯은 든 채로 다시 선다, 그리고 그것이 열린 질문이다`() {
        // §4.4 갱신 규칙: RUNNING 에서 갱신은 Halt → Reset → Start 이고 태스크는
        // RUNNING 을 유지한다. 계약은 지금 이렇게 한다 — 부품을 든 채의 Reset 이
        // 무엇인지는 §15.84 후보 ③으로 열려 있다. 이 시험은 그 거동을 **고정**하지
        // 않고 **드러낸다**: 바뀌는 날 여기가 빨개져서 결정이 조용히 지나가지 않게.
        harness().use { harness ->
            val (_, follower) = harness.running(s03)
            harness.advance(Duration.ofSeconds(10))

            val response = harness.start(s03, V18)
            assertTrue(response.hasHandle(), "도는 슬롯의 갱신이 거절됐다: ${response.rejection}")
            assertEquals("RUNNING", harness.internal(s03).taskState, "갱신이 태스크를 RUNNING 밖으로 보냈다")

            // 갱신 자체가 갱신 로그 한 줄이다(ACCEPTED · RUNNING · 다시 선 RUNNING).
            val restarted = follower.awaitUpdates(3).last()
            assertEquals(V18, restarted.revision)
            assertEquals(0.0, restarted.progress, "갱신은 진행률을 0 에서 다시 센다(§4.4)")
            assertEquals(HoldKind.HOLD_KIND_HOLDING, restarted.hold.kind, "다시 서는 동안에도 든 채다 — 후보 ③")
        }
    }

    @Test
    fun `S03 파지 중 취소 — 복구하면 빈손이고, 재작업은 새 정체성으로 온다`() {
        harness().use { harness ->
            val (handle, follower) = harness.running(s03)
            harness.advance(Duration.ofSeconds(10))

            val cancelled = harness.client().cancel(ROBOT, handle)
            assertEquals(TaskState.TASK_STATE_CANCELLING, cancelled.state)
            harness.advance(Duration.ofSeconds(1))

            val updates = follower.awaitTerminal()
            assertEquals(TaskState.TASK_STATE_CANCELLED, updates.last().state)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, updates.last().hold.kind, "복구했다면서 든 채다")
            assertTrue(updates.any { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }, "든 적이 없다면 취소가 공허하다")

            // 종착한 슬롯에 버전 18 을 같은 task_id 로 보내면 거절이다 — 래치.
            val sameIdentity = harness.start(s03, V18)
            assertFalse(sameIdentity.hasHandle())
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, sameIdentity.rejection.code)

            // 재작업은 **새 task_id** 다. 취소가 정체성을 지우지 않으므로 옛 것은 남는다.
            val rework = harness.client().start(ROBOT, "${s03.taskId}@18", V18, "pick_place", s03.parameters)
            assertTrue(rework.hasHandle(), "재작업이 거절됐다: ${rework.rejection}")
            assertEquals("CANCELLED", harness.internal(s03).taskState, "재작업이 옛 태스크를 건드렸다")
        }
    }

    @Test
    fun `부품을 든 채 연결이 끊긴다 — 접수 여부는 같은 요청이 답하고, 든 채는 되짚어 보인다`() {
        // 외부 문서의 IN_DOUBT 해소 경로 1번(클라이언트 참조로 기존 실행 조회)이
        // 계약에서는 **같은 (task_id, revision) 재전송**이다. 벤더가 참조 키를 안
        // 받아도 어댑터가 그 매핑을 든다(어댑터 재시작은 밖 — §1.3 B-1).
        harness().use { harness ->
            val (handle, _) = harness.running(s03)
            harness.advance(Duration.ofSeconds(10))

            harness.setConnection(ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN)
            harness.advance(Duration.ofSeconds(5))
            val lastConnection = harness.publisher.publications
                .mapNotNull { it.message as? ConnectionMessage }.last().state
            assertEquals(ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN, lastConnection, "연결 스트림이 단절을 안 말한다")

            // 접수됐는가 — 같은 요청을 다시 보낸다. 같은 핸들, 새 태스크 없음.
            val before = harness.tasks().size
            val again = harness.start(s03)
            assertTrue(again.hasHandle())
            assertEquals(handle, again.handle, "같은 (task_id, revision) 인데 다른 핸들이다")
            assertEquals(before, harness.tasks().size, "재전송이 물리 작업을 다시 만들었다")

            // 무엇을 들고 있는가 — 처음부터 되짚으면 마지막 갱신에 실려 있다.
            val replay = harness.client().follow(ROBOT, handle, from = 0).awaitUpdates(2)
            assertEquals(HoldKind.HOLD_KIND_HOLDING, replay.last().hold.kind)
            assertEquals(s03.bin, replay.last().hold.objectRef)

            // 연결이 돌아오면 그 태스크가 이어서 끝난다 — 재실행이 아니다.
            harness.setConnection(ConnectionState.CONNECTION_STATE_ONLINE)
            harness.push()
            assertEquals("SUCCEEDED", harness.internal(s03).taskState)
            assertEquals(before, harness.tasks().size)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"

        /** 생산 순서 버전 — 그대로 태스크의 revision 이다. */
        const val V17 = 17
        const val V18 = 18
    }
}
