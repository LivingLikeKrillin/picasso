package dev.picasso.harness

import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.mimic.transport.Publication
import dev.picasso.mimic.transport.RecordingPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 어댑터가 **나르되 바꾸지 않는가.**
 *
 * ## end-to-end만으로는 이것을 못 본다
 *
 * [LedgerIngestEndToEndTest]는 적재가 됐는지를 `task` 표로 본다. 그래서
 * **어댑터가 발행을 삼켜도, 감싼 발행자를 버려도 통과한다** — 적재만 되면
 * 되기 때문이다. 실측으로 그 셋이 전부 안 잡혔다.
 *
 * 그런데 어댑터가 발행을 가로채거나 순서를 바꾸면 **§12.1의 이벤트 시퀀스가
 * 달라진다.** 그러면 이 어댑터를 붙인 시험과 안 붙인 시험이 서로 다른 것을
 * 보게 되고, 그 차이는 어댑터를 의심하기 전까지 안 보인다.
 */
class IngestBridgeTest {

    private val downstream = RecordingPublisher()
    private val received = mutableListOf<StateMessage>()

    private val bridge = IngestBridge(downstream) { received += it }

    @Test
    fun `발행은 그대로 지나간다`() {
        bridge.publish(publication(state(), sequence = 1))

        assertEquals(1, downstream.publications.size, "감싼 발행자가 못 받았다")
    }

    @Test
    fun `발행의 내용과 번호가 안 바뀐다`() {
        // `sequence`가 바뀌면 소비자가 결손을 오탐한다(§10.4의 결손 감지).
        val original = publication(state(), sequence = 7)

        bridge.publish(original)

        assertEquals(original, downstream.publications.single())
    }

    @Test
    fun `상태 메시지가 적재로 간다`() {
        bridge.publish(publication(state(), sequence = 1))

        assertEquals(1, received.size)
    }

    @Test
    fun `상태가 아닌 발행은 적재로 안 간다`() {
        // `event`·`connection`도 같은 발행자를 지난다(§5.5의 발행 열). 전부
        // 넘기면 `task` 표가 태스크가 아닌 것으로 채워진다.
        bridge.publish(publication(Event.newBuilder().setHeader(header()).build(), sequence = 2))

        assertEquals(0, received.size, "상태가 아닌 것이 적재로 갔다")
        assertEquals(1, downstream.publications.size, "그래도 발행은 지나가야 한다")
    }

    @Test
    fun `적재가 던져도 발행은 지나간다`() {
        // §5.4가 핸드셰이크 보고에 대해 정한 것과 같은 규칙이다 — 적재가
        // 발행을 막으면 레지스트리가 죽은 날 로봇이 조용해진다.
        val exploding = IngestBridge(downstream) { throw IllegalStateException("적재가 죽었다") }

        exploding.publish(publication(state(), sequence = 1))

        assertEquals(1, downstream.publications.size, "적재 실패가 발행을 막았다")
    }

    @Test
    fun `발행이 적재보다 먼저다`() {
        // 적재가 느리면 발행이 그만큼 밀린다. 순서를 뒤집으면 **적재
        // 지연이 곧 발행 지연**이 되고, 그것은 "영향 없음"이 아니다.
        val order = mutableListOf<String>()
        val ordered = IngestBridge({ order += "publish" }) { order += "ingest" }

        ordered.publish(publication(state(), sequence = 1))

        assertEquals(listOf("publish", "ingest"), order)
    }

    @Test
    fun `여러 발행이 순서대로 간다`() {
        // 하나만 보면 마지막 것만 넘기는 구현이 통과한다.
        repeat(3) { bridge.publish(publication(state("t$it"), sequence = it.toLong())) }

        assertEquals(3, downstream.publications.size)
        assertEquals(3, received.size)
    }

    // ── 씨앗

    private fun header() = MessageHeader.newBuilder().setRobotId("r1").build()

    private fun state(taskId: String = "t1") = StateMessage.newBuilder()
        .setHeader(header())
        .addTasks(
            dev.picasso.contracts.v1.TaskSnapshot.newBuilder()
                .setTaskId(taskId).setSkillType("navigate_to"),
        )
        .build()

    private fun publication(message: com.google.protobuf.Message, sequence: Long) =
        Publication("picasso/1/line-a/robot/r1/state", message, sequence)
}
