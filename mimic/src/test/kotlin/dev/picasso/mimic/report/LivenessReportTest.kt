package dev.picasso.mimic.report

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.mimic.transport.Publication
import dev.picasso.mimic.transport.Publisher
import dev.picasso.mimic.transport.RecordingPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 기체 생존 보고가 **침묵하는 기체에서도 나가는가.**
 *
 * ## 이 시험이 붙드는 것
 *
 * 하트비트를 상태 발행에만 묶으면 `publishStateIfDue`가 `ONLINE`일 때만
 * 도므로(§4.7) **의도적으로 절전한 기체가 관측선에서 사라진다.** 그러면
 * §9.3의 두 조회가 그 기체를 "관측선이 끊겼다"로 읽고, 계약이 *"침묵하지만
 * 정상"*을 표현하려고 만든 `HIBERNATING`이 **모든 축소를 영구히 막는
 * 차단자**가 된다.
 *
 * 그래서 관측선이 둘이다 — `ONLINE`은 주기 상태 발행이, 침묵은 연결 전이가
 * 나른다.
 */
class LivenessReportTest {

    private class Spy : LivenessObservations {
        val seen = mutableListOf<Pair<String, ConnectionState>>()
        val software = mutableListOf<String?>()
        val siteNames = mutableListOf<SiteNameSummary?>()
        var fail = false

        override fun onConnection(
            header: MessageHeader,
            state: ConnectionState,
            software: String?,
            siteNames: SiteNameSummary?,
        ) {
            if (fail) error("적재가 죽었다")
            seen += header.robotId to state
            this.software += software
            this.siteNames += siteNames
        }
    }

    private class Sink : TaskObservations {
        var states = 0
        override fun onState(message: dev.picasso.contracts.v1.StateMessage) {
            states++
        }

        override fun onEvent(event: dev.picasso.contracts.v1.Event) = Unit
    }

    private fun header(robotId: String = "r1") =
        MessageHeader.newBuilder().setRobotId(robotId).build()

    private fun stateMessage() = dev.picasso.contracts.v1.StateMessage.newBuilder()
        .setHeader(header())
        .build()

    private fun connectionMessage(state: ConnectionState) =
        dev.picasso.contracts.v1.ConnectionMessage.newBuilder()
            .setHeader(header())
            .setState(state)
            .build()

    @Test
    fun `상태 발행이 ONLINE 관측선을 세운다`() {
        val spy = Spy()
        val bridge = IngestBridge(RecordingPublisher(), Sink(), spy)

        bridge.publish(Publication("t/state", stateMessage(), 0))

        assertEquals(listOf("r1" to ConnectionState.CONNECTION_STATE_ONLINE), spy.seen)
    }

    @Test
    fun `HIBERNATING 에서도 관측선이 나간다`() {
        // **이 시험이 이 태스크의 요점이다.** 하트비트를 상태 발행에만 묶는
        // 구현은 여기서 잡힌다 — 절전한 기체는 상태를 안 내보낸다.
        val spy = Spy()
        val bridge = IngestBridge(RecordingPublisher(), Sink(), spy)

        bridge.publish(
            Publication(
                "t/connection",
                connectionMessage(ConnectionState.CONNECTION_STATE_HIBERNATING),
                0,
                retained = true,
            ),
        )

        assertEquals(
            listOf("r1" to ConnectionState.CONNECTION_STATE_HIBERNATING),
            spy.seen,
            "절전한 기체가 관측선에서 사라졌다",
        )
    }

    @Test
    fun `보고된 상태가 발행된 상태와 같다`() {
        // 상태를 통째로 무시하고 언제나 ONLINE 을 싣는 구현을 잡는다.
        val spy = Spy()
        val bridge = IngestBridge(RecordingPublisher(), Sink(), spy)

        listOf(
            ConnectionState.CONNECTION_STATE_OFFLINE,
            ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
            ConnectionState.CONNECTION_STATE_HIBERNATING,
        ).forEachIndexed { i, state ->
            bridge.publish(Publication("t/connection", connectionMessage(state), i.toLong()))
        }

        assertEquals(
            listOf(
                ConnectionState.CONNECTION_STATE_OFFLINE,
                ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
                ConnectionState.CONNECTION_STATE_HIBERNATING,
            ),
            spy.seen.map { it.second },
        )
    }

    @Test
    fun `생존 보고가 실패해도 발행은 나간다`() {
        // §5.4의 규칙과 같다 — 보고 실패가 본래 일을 막지 않는다. 막으면
        // 레지스트리가 죽은 날 로봇이 함께 멈춘다.
        val spy = Spy().also { it.fail = true }
        val recorder = RecordingPublisher()
        val bridge = IngestBridge(recorder, Sink(), spy)

        bridge.publish(
            Publication(
                "t/connection",
                connectionMessage(ConnectionState.CONNECTION_STATE_ONLINE),
                0,
            ),
        )

        assertEquals(1, recorder.publications.size, "적재 실패가 발행을 삼켰다")
    }

    @Test
    fun `태스크 적재와 생존 보고가 서로를 막지 않는다`() {
        // 상태 발행 하나가 둘을 부른다. 앞의 것이 죽으면 뒤의 것이 안 도는
        // 구현을 잡는다.
        val spy = Spy()
        val sink = object : TaskObservations {
            override fun onState(message: dev.picasso.contracts.v1.StateMessage) =
                error("적재가 죽었다")

            override fun onEvent(event: dev.picasso.contracts.v1.Event) = Unit
        }
        val bridge = IngestBridge(RecordingPublisher(), sink, spy)

        bridge.publish(Publication("t/state", stateMessage(), 0))

        assertTrue(spy.seen.isNotEmpty(), "태스크 적재 실패가 생존 보고를 막았다")
    }

    @Test
    fun `보고에 그 기체의 펌웨어가 실린다`() {
        // 조회 람다가 발행 시점에 평가되는지 본다 — 기체는 발행자를 감싼
        // 뒤에 만들어지므로 미리 평가하면 언제나 null 이다.
        val spy = Spy()
        val bridge = IngestBridge(RecordingPublisher(), Sink(), spy, software = { id ->
            if (id == "r1") "4.1.0" else null
        })

        bridge.publish(Publication("t/state", stateMessage(), 0))

        assertEquals(listOf<String?>("4.1.0"), spy.software)
    }

    @Test
    fun `보고에 사이트 이름 요약이 실린다`() {
        // ADR 35 — **이름이 아니라 요약이다.** 레지스트리는 이름의 주인이
        // 아니므로 목록을 받지 않는다.
        val spy = Spy()
        val bridge = IngestBridge(
            RecordingPublisher(),
            Sink(),
            spy,
            software = { null },
            siteNames = { id -> if (id == "r1") SiteNameSummary(unsupported = false, count = 3) else null },
        )

        bridge.publish(Publication("t/state", stateMessage(), 0))

        assertEquals(listOf<SiteNameSummary?>(SiteNameSummary(unsupported = false, count = 3)), spy.siteNames)
    }

    @Test
    fun `안 물어본 것과 못 하는 기종을 가른다`() {
        // **`null` 은 "아직 안 물어봤다" 이고 `unsupported` 는 "물어봤더니 못
        // 한다더라" 다.** 접으면 등록할 자리가 없는 기체와 아직 모르는 기체가
        // 같아 보이고, 그러면 앞의 것에게 등록을 요구하게 된다.
        val spy = Spy()
        IngestBridge(RecordingPublisher(), Sink(), spy)
            .publish(Publication("t/state", stateMessage(), 0))
        assertEquals(listOf<SiteNameSummary?>(null), spy.siteNames.toList())

        val other = Spy()
        IngestBridge(
            RecordingPublisher(),
            Sink(),
            other,
            siteNames = { SiteNameSummary(unsupported = true, count = 0) },
        ).publish(Publication("t/state", stateMessage(), 0))
        assertEquals(listOf<SiteNameSummary?>(SiteNameSummary(unsupported = true, count = 0)), other.siteNames)
    }

    @Test
    fun `못 읽는 기종은 빈 문자열이 아니라 null 이다`() {
        val spy = Spy()
        val bridge = IngestBridge(RecordingPublisher(), Sink(), spy, software = { null })

        bridge.publish(Publication("t/state", stateMessage(), 0))

        assertEquals(listOf<String?>(null), spy.software)
    }

    @Test
    fun `연결 발행은 태스크 적재를 부르지 않는다`() {
        val sink = Sink()
        val bridge = IngestBridge(RecordingPublisher(), sink, Spy())

        bridge.publish(
            Publication(
                "t/connection",
                connectionMessage(ConnectionState.CONNECTION_STATE_ONLINE),
                0,
            ),
        )

        assertEquals(0, sink.states, "연결 메시지가 태스크 적재로 샜다")
    }
}
