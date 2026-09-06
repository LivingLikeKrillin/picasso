package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.profile.ProfileDocument
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 5의 절반 — **침묵을 만드는 것과 그것을 구분하는 것**.
 *
 * §7.2의 최대 발행 간격이 없으면 "침묵"이라는 말 자체가 성립하지 않는다.
 * 그런데 그 상한만 두면 **절전 중인 로봇이 고장으로 오판된다** — 그래서
 * `HIBERNATING`이 있고, 소비자는 침묵이 아니라 **연결 스트림**으로 셋을
 * 가른다.
 */
class ConnectionStreamTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val publisher = RecordingPublisher()

    private fun instance(document: ProfileDocument = TaskMachineFixtures.document()) =
        RobotInstance("r1", document, clock, publisher = publisher, site = "line-a")

    private fun connections() =
        publisher.publications.filter { it.message is ConnectionMessage }

    private fun states() =
        publisher.publications.filter { it.message is StateMessage }

    private fun stateOf(index: Int) =
        (connections()[index].message as ConnectionMessage).state

    // ── 주기 발행

    @Test
    fun `최대 발행 간격을 넘기면 상태가 나간다`() {
        // **간격을 프로파일에서 읽는다.** 리터럴이면 기종마다 다른 값이
        // 아무 뜻도 안 갖는다. 픽스처는 30초다.
        val document = TaskMachineFixtures.document()
        val max = document.publishIntervalMaxSeconds
        assertTrue(max > 0, "픽스처가 최대 간격을 선언하지 않았다")

        val events = instance(document).events
        assertTrue(events.publishStateIfDue(), "첫 발행이 안 나갔다")
        assertEquals(1, states().size)

        clock.advance(Duration.ofSeconds(max.toLong() - 1))
        assertTrue(!events.publishStateIfDue(), "간격 안인데 나갔다")
        assertEquals(1, states().size)

        clock.advance(Duration.ofSeconds(1))
        assertTrue(events.publishStateIfDue(), "간격을 넘겼는데 안 나갔다")
        assertEquals(2, states().size)
    }

    @Test
    fun `간격이 프로파일에서 온다`() {
        // 값을 바꾸면 거동이 따라 바뀌어야 한다.
        val raw = TaskMachineFixtures.fixtureRaw
        val fast = raw.replace("\"max_seconds\": 30", "\"max_seconds\": 5")
        check(fast != raw) { "치환이 아무것도 바꾸지 못했다" }

        val events = instance(TaskMachineFixtures.document(fast)).events
        events.publishStateIfDue()
        clock.advance(Duration.ofSeconds(6))
        assertTrue(events.publishStateIfDue(), "5초를 선언했는데 6초에 안 나갔다")
        assertEquals(2, states().size)
    }

    @Test
    fun `매 호출마다 나가지 않는다`() {
        // 간격을 무시하고 매번 발행하면 소비자의 대역폭을 먹고 "침묵"의
        // 기준이 사라진다.
        val events = instance().events
        repeat(10) { events.publishStateIfDue() }
        assertEquals(1, states().size, "간격을 안 본다")
    }

    // ── 연결 상태

    @Test
    fun `기동하면 ONLINE을 retain으로 발행한다`() {
        val events = instance().events
        events.announceOnline()

        val message = connections().single()
        assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, stateOf(0))
        assertTrue(message.retained, "retain이 아니면 새 구독자가 생사를 모른다")
    }

    @Test
    fun `연결 상태 넷을 통째로 확인한다`() {
        // **enum 전수다.** 하나만 보면 나머지 셋이 침묵한다.
        val states = ConnectionState.entries.filterNot {
            it.name.endsWith("UNRECOGNIZED") || it == ConnectionState.CONNECTION_STATE_UNSPECIFIED
        }
        assertEquals(4, states.size, "연결 상태가 넷이 아니다: $states")

        val events = instance().events
        // ONLINE은 기본값이라 setConnection이 아무것도 안 낸다 — 나머지 셋을
        // 돌고 마지막에 ONLINE으로 돌아온다.
        val order = states.filterNot { it == ConnectionState.CONNECTION_STATE_ONLINE } +
            ConnectionState.CONNECTION_STATE_ONLINE
        order.forEach { assertTrue(events.setConnection(it), "$it 가 안 나갔다") }

        assertEquals(order, connections().map { (it.message as ConnectionMessage).state })
        assertTrue(connections().all { it.retained }, "retain이 아닌 것이 섞였다")
    }

    @Test
    fun `같은 상태를 두 번 넣으면 한 번만 나간다`() {
        // 유령 전이를 보내면 소비자가 재연결로 오해한다.
        val events = instance().events
        assertTrue(events.setConnection(ConnectionState.CONNECTION_STATE_OFFLINE))
        assertTrue(!events.setConnection(ConnectionState.CONNECTION_STATE_OFFLINE))
        assertEquals(1, connections().size)
    }

    // ── 침묵

    @Test
    fun `ONLINE이 아니면 주기 발행이 멈춘다`() {
        // **셋이 전부 침묵을 만드는 것이 요점이다.** 하나만 보면 나머지 둘이
        // 침묵하지 않아도 통과한다.
        val silent = listOf(
            ConnectionState.CONNECTION_STATE_OFFLINE,
            ConnectionState.CONNECTION_STATE_HIBERNATING,
            ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
        )
        assertEquals(3, silent.size)

        silent.forEach { state ->
            publisher.clear()
            val local = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
            val events = RobotInstance(
                "r1", TaskMachineFixtures.document(), local, publisher = publisher,
            ).events

            events.publishStateIfDue()
            val before = states().size
            events.setConnection(state)

            local.advance(Duration.ofSeconds(600))
            assertTrue(!events.publishStateIfDue(), "$state 인데 상태가 나갔다")
            assertEquals(before, states().size, "$state 가 침묵을 안 만든다")
        }
    }

    @Test
    fun `ONLINE으로 돌아오면 다시 나간다`() {
        // 멈추기만 하고 안 돌아오면 "의도된 정지"가 영구 고장과 같아진다.
        val events = instance().events
        events.publishStateIfDue()
        events.setConnection(ConnectionState.CONNECTION_STATE_HIBERNATING)
        clock.advance(Duration.ofSeconds(600))
        assertTrue(!events.publishStateIfDue())

        events.setConnection(ConnectionState.CONNECTION_STATE_ONLINE)
        assertTrue(events.publishStateIfDue(), "깨어났는데 안 나간다")
    }

    @Test
    fun `침묵만으로는 셋을 구분할 수 없다`() {
        // **이것이 없으면 완료 기준 5가 공허하다.** 셋이 서로 다른 침묵을
        // 만든다면 연결 스트림 없이도 구분되고, 그러면 그 스트림이 왜
        // 있는지 설명되지 않는다.
        val silence = listOf(
            ConnectionState.CONNECTION_STATE_OFFLINE,
            ConnectionState.CONNECTION_STATE_HIBERNATING,
            ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
        ).map { state ->
            publisher.clear()
            val local = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
            val events = RobotInstance(
                "r1", TaskMachineFixtures.document(), local, publisher = publisher,
            ).events
            events.publishStateIfDue()
            events.setConnection(state)
            local.advance(Duration.ofSeconds(600))
            events.publishStateIfDue()
            // 상태 스트림만 본 소비자가 관찰하는 것.
            states().size
        }
        assertEquals(1, silence.distinct().size, "셋이 서로 다른 침묵을 만든다: $silence")
    }

    @Test
    fun `연결 스트림이 셋을 구분한다`() {
        // 위 시험의 짝이다. 침묵은 같지만 연결 스트림은 다르다.
        val seen = listOf(
            ConnectionState.CONNECTION_STATE_OFFLINE,
            ConnectionState.CONNECTION_STATE_HIBERNATING,
            ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
        ).map { state ->
            publisher.clear()
            val events = RobotInstance(
                "r1", TaskMachineFixtures.document(),
                VirtualClock(Instant.parse("2026-09-06T00:00:00Z")), publisher = publisher,
            ).events
            events.setConnection(state)
            (connections().last().message as ConnectionMessage).state
        }
        assertEquals(3, seen.distinct().size, "연결 스트림도 셋을 못 가른다: $seen")
    }

    // ── 토픽

    @Test
    fun `연결은 connection 토픽으로 나간다`() {
        val events = instance().events
        events.setConnection(ConnectionState.CONNECTION_STATE_OFFLINE)
        assertTrue(
            connections().single().topic.endsWith("/connection"),
            "토픽이 다르다: ${connections().single().topic}",
        )
    }
}
