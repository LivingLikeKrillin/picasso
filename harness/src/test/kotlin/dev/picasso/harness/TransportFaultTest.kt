package dev.picasso.harness

import com.google.protobuf.Message
import dev.picasso.client.EventConsumer
import dev.picasso.client.Received
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.InjectTransportFaultRequest
import dev.picasso.uplink.Publication
import dev.picasso.mimic.transport.TransportFaults
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 완료 기준 3 — 결손·중복·순서 역전을 각각 주입하면 **결손은 감지, 중복은
 * 무시, 역전은 재정렬해 같은 최종 상태**에 도달한다.
 *
 * `EventConsumerTest`가 규칙 자체를 보고, 여기서는 **실제 발행 축 위에서**
 * 본다. 오라클은 `DumpInternalState`다 — 소비자가 역경 속에서 세운 상태를
 * 내부 상태와 견주는 것이 이 기준의 문자 그대로의 뜻이다.
 *
 * **다섯을 전부 "복원된다"로 적으면 안 된다.** 결손만은 예외이고, 그것을
 * 얼버무리면 결손을 조용히 메우는 구현이 통과한다 — 소비자에게 **없던
 * 사실을 있다고 말하는** 것이다.
 */
class TransportFaultTest {

    private val profile = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
    )

    private fun harness() = Harness(mapOf(ROBOT to profile))

    private fun Harness.dump() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.inject(kind: TransportFaults.Kind, lossEvery: Int = 0) =
        oracle.injectTransportFault(
            InjectTransportFaultRequest.newBuilder()
                .setRobotId(ROBOT).setKind(kind.name).setLossEvery(lossEvery).build(),
        )

    /**
     * 발행 하나를 소비자의 입력으로 옮긴다.
     *
     * **헤더를 descriptor 로 읽는다** — 메시지 종류마다 손으로 쓰면 새
     * 스트림이 생길 때 조용히 빠진다.
     */
    private fun received(publication: Publication, at: Instant): Received {
        val message = publication.message
        val field = message.descriptorForType.findFieldByName("header")
        val header = message.getField(field) as MessageHeader
        return Received(
            sessionId = header.sessionId,
            sequence = header.sequence,
            eventId = header.eventId,
            event = message as? Event,
            at = at,
        )
    }

    /** 하네스가 발행한 것을 소비자에게 순서대로 먹인다. */
    private fun Harness.consume(window: Int = 8): EventConsumer {
        val consumer = EventConsumer(windowEvents = window)
        var at = Instant.parse("2026-09-06T00:00:00Z")
        publisher.publications.forEach {
            consumer.accept(received(it, at))
            at = at.plusMillis(10)
        }
        // 창이 시간으로도 닫히게 한다 — 마지막 구멍이 미확정으로 남으면
        // "결손을 감지했다"가 그 자리에서 거짓이 된다.
        consumer.tick(at.plusSeconds(10))
        return consumer
    }

    /**
     * 태스크 하나를 접수 → 실행 → 완주까지 민다.
     *
     * 장애를 **건 뒤에** 돌려야 그 장애가 발행 축에 실린다.
     */
    private fun Harness.runTask(taskId: String = "t1") {
        client().start(ROBOT, taskId, 1, "navigate_to", parameters)
        advance(Duration.ofSeconds(1))
        advance(Duration.ofSeconds(30))
    }

    /** 소비자가 접은 태스크 전이의 마지막 상태들. */
    private fun EventConsumer.terminalStates(): Map<String, TaskState> =
        events.filter { it.hasTaskTransition() }
            .associate { it.taskTransition.taskId to it.taskTransition.to }

    // ── 다섯을 전부 본다

    @Test
    fun `장애 다섯을 통째로 확인한다`() {
        // **enum 전수다.** 하나만 보면 나머지가 침묵한다.
        val kinds = TransportFaults.Kind.entries.filterNot { it == TransportFaults.Kind.NONE }
        assertEquals(5, kinds.size, "§10.5가 못박은 다섯이 아니다: $kinds")

        harness().use { harness ->
            kinds.forEach { kind ->
                assertEquals(kind.name, harness.inject(kind).kind, "$kind 를 못 걸었다")
            }
        }
    }

    @Test
    fun `장애 없이 돌리면 소비자가 내부 상태에 도달한다`() {
        // **아래 시험들의 전제다.** 장애 없이도 못 도달하면 "복원했다"가
        // 아무 뜻도 안 갖는다.
        harness().use { harness ->
            harness.runTask()
            val consumer = harness.consume()

            assertEquals(emptyList(), consumer.missing, "장애가 없는데 구멍이 있다")
            assertEquals(0, consumer.duplicatesIgnored)
            assertEquals(
                mapOf("t1" to TaskState.TASK_STATE_SUCCEEDED),
                consumer.terminalStates(),
            )
            assertEquals(
                "SUCCEEDED", harness.dump().tasksList.single().taskState,
                "오라클과 소비자가 다른 것을 본다",
            )
        }
    }

    // ── 중복은 무시한다

    @Test
    fun `중복에서 최종 상태가 같다`() {
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.DUPLICATE)
            harness.runTask()
            val consumer = harness.consume()

            assertTrue(consumer.duplicatesIgnored > 0, "중복이 안 만들어졌다 — 시험이 공허하다")
            assertEquals(emptyList(), consumer.missing, "중복을 결손으로 봤다")
            assertEquals(
                mapOf("t1" to TaskState.TASK_STATE_SUCCEEDED),
                consumer.terminalStates(),
            )
        }
    }

    // ── 역전은 재정렬한다

    @Test
    fun `역전에서 최종 상태가 같다`() {
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.REORDER)
            harness.runTask()
            harness.registry.byId(ROBOT)!!.instance.transport.flush()

            val consumer = harness.consume()
            assertEquals(emptyList(), consumer.missing, "역전을 결손으로 봤다")
            assertEquals(
                mapOf("t1" to TaskState.TASK_STATE_SUCCEEDED),
                consumer.terminalStates(),
            )
        }
    }

    @Test
    fun `역전이 실제로 일어났다`() {
        // 위 시험의 전제. 순서가 그대로면 "재정렬했다"가 자명하게 참이다.
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.REORDER)
            harness.runTask()
            harness.registry.byId(ROBOT)!!.instance.transport.flush()

            val order = harness.publisher.publications.map { it.sequence }
            assertTrue(
                order != order.sorted(),
                "발행 순서가 그대로다 — 역전이 안 만들어졌다: $order",
            )
        }
    }

    @Test
    fun `지연은 순서를 안 바꾼다`() {
        // `DELAY`와 `REORDER`가 같은 것이면 둘을 나눈 뜻이 없다.
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.DELAY)
            harness.runTask()
            harness.registry.byId(ROBOT)!!.instance.transport.flush()

            val order = harness.publisher.publications.map { it.sequence }
            assertEquals(order.sorted(), order, "지연이 순서를 바꿨다")

            val consumer = harness.consume()
            assertEquals(emptyList(), consumer.missing)
            assertEquals(
                mapOf("t1" to TaskState.TASK_STATE_SUCCEEDED),
                consumer.terminalStates(),
            )
        }
    }

    // ── 결손만은 다르다

    @Test
    fun `결손에서는 같지 않고, 같지 않다는 것을 안다`() {
        // **이 파일에서 가장 중요한 시험이다.** 다섯을 전부 "복원된다"로
        // 적으면 결손을 조용히 메우는 구현이 통과하고, 그것은 소비자에게
        // 없던 사실을 있다고 말하는 것이다.
        // 깨끗한 실행과 견준다 — 그것 없이는 "덜 받았다"를 말할 수 없다.
        val clean = harness().use { h ->
            h.runTask()
            h.consume(window = 2).events.size
        }
        assertTrue(clean > 0, "깨끗한 실행에서도 아무것도 안 접혔다")

        harness().use { harness ->
            harness.inject(TransportFaults.Kind.EVENT_LOSS, lossEvery = 2)
            harness.runTask()

            val consumer = harness.consume(window = 2)

            // ① 잃었다는 것을 **안다**.
            assertTrue(consumer.missing.isNotEmpty(), "구멍을 못 봤다 — 결손을 감지 못 한다")

            // ② 그리고 **메우지 않았다.** 접힌 것이 깨끗한 실행보다 적어야
            // 한다 — 같으면 없던 이벤트를 만들어 낸 것이다.
            assertTrue(
                consumer.events.size < clean,
                "결손인데 깨끗한 실행과 같은 수를 접었다: ${consumer.events.size} vs $clean",
            )

            // ③ 그리고 **잃은 개수만큼만** 모자라야 한다. 더 잃으면 재정렬
            // 창이 멀쩡한 것까지 버린 것이다.
            assertEquals(
                clean - consumer.events.size <= consumer.missing.size, true,
                "구멍 수(${consumer.missing.size})보다 많이 잃었다: ${clean - consumer.events.size}",
            )
        }
    }

    @Test
    fun `결손이 실제로 일어났다`() {
        // 위 시험의 전제. 아무것도 안 버렸으면 "감지했다"가 공허하다.
        harness().use { harness ->
            val clean = harness().use { other ->
                other.runTask()
                other.publisher.publications.size
            }

            harness.inject(TransportFaults.Kind.EVENT_LOSS, lossEvery = 2)
            harness.runTask()
            assertTrue(
                harness.publisher.publications.size < clean,
                "버린 것이 없다: ${harness.publisher.publications.size} vs $clean",
            )
        }
    }

    @Test
    fun `버린 자리가 번호로 남는다`() {
        // **번호가 붙은 뒤에 버려야 구멍이 보인다.** 붙기 전에 버리면
        // 나머지가 새로 매겨져 소비자가 결손을 아예 못 본다 — 완료 기준 3이
        // 그 자리에서 조용히 통과한다.
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.EVENT_LOSS, lossEvery = 2)
            harness.runTask()

            val sequences = harness.publisher.publications.map { it.sequence }
            assertEquals(sequences.sorted(), sequences, "순서가 흐트러졌다")
            assertTrue(
                sequences.zipWithNext().any { (a, b) -> b - a > 1 },
                "번호가 촘촘하다 — 버린 뒤에 새로 매겼다: $sequences",
            )
        }
    }

    // ── 끊김

    @Test
    fun `끊기면 아무것도 안 나간다`() {
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.DISCONNECT)
            val before = harness.publisher.publications.size
            harness.runTask()
            assertEquals(before, harness.publisher.publications.size, "끊겼는데 나갔다")
        }
    }

    @Test
    fun `끊김을 풀면 다시 나간다`() {
        // 풀 수 없으면 재구독 시나리오를 만들 수 없다.
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.DISCONNECT)
            harness.runTask()

            harness.inject(TransportFaults.Kind.NONE)
            val before = harness.publisher.publications.size
            harness.runTask("t2")
            assertTrue(harness.publisher.publications.size > before, "풀었는데 안 나간다")
        }
    }

    @Test
    fun `장애를 풀면 붙들고 있던 것이 나간다`() {
        // 안 내보내면 장애를 끄는 것이 곧 이벤트 하나를 영영 잃는 일이 된다.
        harness().use { harness ->
            harness.inject(TransportFaults.Kind.DELAY)
            harness.runTask()
            val held = harness.publisher.publications.size

            harness.inject(TransportFaults.Kind.NONE)
            assertTrue(harness.publisher.publications.size > held, "붙든 것을 잃었다")
        }
    }

    // ── 거절

    @Test
    fun `모르는 장애는 거절한다`() {
        // 조용히 NONE으로 접으면 시험이 장애를 건 줄 알고 "복원됐다"를
        // 단언한다 — 아무 장애도 없었으므로 당연히 참이다.
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> {
                harness.oracle.injectTransportFault(
                    InjectTransportFaultRequest.newBuilder()
                        .setRobotId(ROBOT).setKind("PACKET_STORM").build(),
                )
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
        }
    }

    @Test
    fun `매번 버리는 손실률은 거절한다`() {
        // 그것은 EVENT_LOSS 가 아니라 DISCONNECT 다. 이름이 사실과 달라지면
        // 시험이 무엇을 증명했는지 알 수 없다.
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> {
                harness.inject(TransportFaults.Kind.EVENT_LOSS, lossEvery = 1)
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
        }
    }

    @Test
    fun `모르는 기체는 NOT_FOUND다`() {
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> {
                harness.oracle.injectTransportFault(
                    InjectTransportFaultRequest.newBuilder()
                        .setRobotId("r9").setKind(TransportFaults.Kind.DISCONNECT.name).build(),
                )
            }
            assertEquals(Status.Code.NOT_FOUND, error.status.code)
        }
    }

    private companion object {
        const val ROBOT = "r1"
    }
}
