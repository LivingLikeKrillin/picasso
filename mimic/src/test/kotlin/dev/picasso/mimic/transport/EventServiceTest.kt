package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.ReplayEventsResponse
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.HeaderColumns
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.profile.ProfileDocument
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventServiceTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val fixture = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()), clock)

    @AfterTest fun close() = fixture.close()

    private fun location(value: String = "dock-3") =
        ParameterValue.newBuilder().setKey("location").setStringValue(value).build()

    private val host get() =
        fixture.registry.require(GrpcFixture.requestHeader("r1")).instance

    private fun snapshot(): GetSnapshotResponse = fixture.eventsService.getSnapshot(
        GetSnapshotRequest.newBuilder().setHeader(GrpcFixture.requestHeader("r1")).build(),
    )

    /** **마감을 건다.** 안 닫히는 스트림이 실패가 아니라 정지로 나타나면 CI가 선다. */
    private fun replay(from: Long): List<ReplayEventsResponse> =
        fixture.eventsService
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .replayEvents(
                ReplayEventsRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1"))
                    .setFromSequence(from).build(),
            ).asSequence().toList()

    private fun runTask(taskId: String = "t1") {
        host.tasks.start(taskId, 1, "navigate_to", listOf(location()))
        host.tasks.tick()
        clock.advance(Duration.ofSeconds(20))
        host.tasks.tick()
    }

    // ── GetSnapshot

    @Test
    fun `스냅샷이 현재 태스크와 스킬을 담는다`() {
        runTask()
        val response = snapshot()
        assertEquals(1, response.tasksCount)
        assertEquals("t1", response.tasksList.single().taskId)
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, response.tasksList.single().state)
        assertEquals("t1", response.skillsList.single().taskId)
    }

    @Test
    fun `스냅샷의 sequence가 다음에 올 번호다`() {
        // "대응하는 번호"로 두면 0이 두 가지 뜻을 갖는다.
        //
        // **0이 아니라 1이다.** §10.2의 기동 순서 마지막이 `ONLINE` 발행이고
        // `sequence`는 기체 단위 하나이므로 그것이 0번을 쓴다. 발행된 것과
        // 견줘서 이 값을 만든다 — 리터럴로 적으면 기동 발행이 하나 늘 때
        // 조용히 틀린 값을 단언한다.
        assertEquals(
            fixture.publisher.publications.map { it.sequence }.max() + 1,
            snapshot().sequence,
            "태스크 전에도 다음 번호여야 한다",
        )

        runTask()
        val published = fixture.publisher.publications.map { it.sequence }
        assertEquals(published.max() + 1, snapshot().sequence, "다음 번호가 아니다")
    }

    @Test
    fun `스냅샷 다음부터 이어 붙이면 겹치지도 빠지지도 않는다`() {
        // sequence의 뜻이 정확한지를 재생과 맞물려 본다 — off-by-one이면
        // 첫 이벤트를 두 번 세거나 통째로 놓친다.
        host.tasks.start("t1", 1, "navigate_to", listOf(location()))
        host.tasks.tick()
        val mid = snapshot().sequence

        clock.advance(Duration.ofSeconds(20))
        host.tasks.tick()

        val after = replay(mid).mapNotNull { it.takeIf { r -> r.hasEvent() }?.event }
        // **`event` 스트림만 고른다.** `sequence`는 기체 단위 하나이고
        // `state`·`connection`이 같은 축을 쓰므로(§5.5의 발행 열이 셋을 다
        // 덮는다), 전부 세면 재생에 없는 번호가 섞인다.
        val all = fixture.publisher.events().map { it.header.sequence }
        assertEquals(all.filter { it >= mid }, after.map { it.header.sequence })
        assertTrue(after.isNotEmpty(), "스냅샷 뒤에 아무 일도 없었다 — 시험이 공허하다")
    }

    @Test
    fun `응답 헤더가 §5-5를 따른다`() {
        val header = snapshot().header
        HeaderColumns.RESPONSE.forEach { assertTrue(HeaderColumns.isSet(header, it), "$it") }
        (HeaderColumns.ALL - HeaderColumns.RESPONSE).forEach {
            assertFalse(HeaderColumns.isSet(header, it), "$it 를 응답에 실었다")
        }
    }

    // ── ReplayEvents

    @Test
    fun `버퍼 안이면 이어받는다`() {
        runTask()
        val events = replay(0).mapNotNull { it.takeIf { r -> r.hasEvent() }?.event }
        assertEquals(
            fixture.publisher.events().map { it.header.sequence },
            events.map { it.header.sequence },
        )
    }

    @Test
    fun `재생한 이벤트가 원본 그대로다`() {
        // 다시 찍으면 event_id가 바뀌어 소비자의 멱등 처리가 무너지고,
        // occurred_at이 바뀌어 30초 전 사건이 방금 일어난 것으로 보인다.
        runTask()
        val original = fixture.publisher.events()
        val replayed = replay(0).map { it.event }

        assertEquals(original.map { it.header.eventId }, replayed.map { it.header.eventId })
        assertEquals(original.map { it.header.occurredAt }, replayed.map { it.header.occurredAt })
        assertEquals(original, replayed)
    }

    @Test
    fun `바깥 헤더는 응답 열이고 안쪽 이벤트는 발행 열이다`() {
        // 한 메시지가 두 열을 함께 든다. 바깥에 sequence를 실으면 소비자가
        // 재생 응답의 번호와 이벤트의 번호를 헷갈린다.
        runTask()
        val response = replay(0).first()
        assertFalse(HeaderColumns.isSet(response.header, "sequence"), "바깥에 sequence를 실었다")
        assertTrue(HeaderColumns.isSet(response.event.header, "sequence") ||
            response.event.header.sequence == 0L)
        assertEquals("picasso.v1.ReplayEventsResponse", response.header.schemaId)
        assertEquals("picasso.v1.Event", response.event.header.schemaId)
    }

    @Test
    fun `버퍼 밖이면 SEQUENCE_EVICTED이고 스트림이 닫힌다`() {
        val small = ProfileDocument.parse(
            "small",
            TaskMachineFixtures.fixtureRaw.replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 2"),
        ).getOrThrow()

        GrpcFixture(mapOf("r1" to small), clock).use { f ->
            val robot = f.registry.require(GrpcFixture.requestHeader("r1")).instance
            robot.tasks.start("t1", 1, "navigate_to", listOf(location()))
            robot.tasks.tick()
            clock.advance(Duration.ofSeconds(20))
            robot.tasks.tick()

            // 축출이 실제로 일어났는지 먼저 본다 — 안 일어났으면 아래가 공허하다.
            assertTrue(
                f.publisher.publications.size > 2,
                "발행이 N보다 적어 축출이 없다: ${f.publisher.publications.size}",
            )
            assertEquals(2, robot.events.buffered.size)

            val responses = f.eventsService
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .replayEvents(
                    ReplayEventsRequest.newBuilder()
                        .setHeader(GrpcFixture.requestHeader("r1")).setFromSequence(0).build(),
                ).asSequence().toList()

            assertEquals(1, responses.size, "거절 뒤에 더 보냈다")
            assertTrue(responses.single().hasRejection())
            assertEquals(
                RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED,
                responses.single().rejection.code,
            )
        }
    }

    @Test
    fun `거절이 gRPC 에러가 아니라 스트림 안의 oneof다`() {
        // 에러로 던지면 소비자가 "그런 기체 없음"과 "버퍼를 벗어남"을
        // 같은 분기에서 다루게 된다.
        val small = ProfileDocument.parse(
            "small",
            TaskMachineFixtures.fixtureRaw.replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 1"),
        ).getOrThrow()
        GrpcFixture(mapOf("r1" to small), clock).use { f ->
            val robot = f.registry.require(GrpcFixture.requestHeader("r1")).instance
            robot.tasks.start("t1", 1, "navigate_to", listOf(location()))
            robot.tasks.tick()

            val responses = f.eventsService
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .replayEvents(
                    ReplayEventsRequest.newBuilder()
                        .setHeader(GrpcFixture.requestHeader("r1")).setFromSequence(0).build(),
                ).asSequence().toList()
            assertTrue(responses.single().hasRejection())
        }
    }

    @Test
    fun `버퍼가 비면 축출이 아니다`() {
        // 아직 아무 일도 없었을 뿐이다. 축출로 답하면 소비자가 스냅샷부터
        // 다시 세우는 헛수고를 한다.
        assertEquals(emptyList(), replay(0))
    }

    @Test
    fun `모르는 robot_id는 NOT_FOUND다`() {
        val error = assertFailsWith<StatusRuntimeException> {
            fixture.eventsService.withDeadlineAfter(10, TimeUnit.SECONDS).replayEvents(
                ReplayEventsRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r9")).build(),
            ).asSequence().toList()
        }
        assertEquals(Status.Code.NOT_FOUND, error.status.code)
    }
}
