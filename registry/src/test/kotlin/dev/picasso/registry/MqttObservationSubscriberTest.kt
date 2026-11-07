package dev.picasso.registry

import dev.picasso.contracts.v1.CapabilityChangeCause
import dev.picasso.contracts.v1.CapabilityChanged
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.registry.observe.MqttObservationSubscriber
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.observe.Recorded
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 관측 구독기의 판정(§15.34) — **브로커 없이** 본다. 무엇을 적고 무엇을 안 적는지가 여기 있고, 브로커를 지나 실제로 오가는지는
 * `harness` 의 `ObservationSubscriptionTest` 가 본다.
 */
class MqttObservationSubscriberTest {

    private lateinit var subscriber: MqttObservationSubscriber

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        val db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        db.transaction { c ->
            c.prepareStatement(
                "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1', 'line-a', 'sn-r1')",
            ).use { it.executeUpdate() }
        }
        subscriber = MqttObservationSubscriber(ObservationService(db), major = MAJOR)
    }

    private fun header(robotId: String = "r1", epoch: Long = 1): MessageHeader = MessageHeader.newBuilder()
        .setRobotId(robotId)
        .setCapabilityEpoch(epoch)
        .setProfileRef(ProfileRef.newBuilder().setProfileId("fixture/minimal").setRevision(1))
        .build()

    private fun topic(stream: String, robotId: String = "r1", major: Int = MAJOR) = "picasso/$major/line-a/robot/$robotId/$stream"

    private fun state(header: MessageHeader = header()) = StateMessage.newBuilder().setHeader(header).build().toByteArray()

    private fun rows(): Int = PostgresSupport.queryOne("SELECT count(*) FROM capability_epoch_log") { it.getInt(1) }

    @Test
    fun `기체 스트림 셋의 헤더를 적고 같은 사실은 접는다`() {
        assertEquals(Recorded.INSERTED, subscriber.handle(topic("state"), state()))
        assertEquals(Recorded.FOLDED, subscriber.handle(topic("state"), state()), "같은 사실이 새 줄이 됐다")
        val event = Event.newBuilder().setHeader(header(epoch = 2)).build().toByteArray()
        assertEquals(Recorded.INSERTED, subscriber.handle(topic("event"), event), "event 스트림의 헤더를 안 적었다")
        val connection = ConnectionMessage.newBuilder().setHeader(header(epoch = 3))
            .setState(ConnectionState.CONNECTION_STATE_ONLINE).build().toByteArray()
        assertEquals(Recorded.INSERTED, subscriber.handle(topic("connection"), connection), "connection 스트림의 헤더를 안 적었다")
        assertEquals(3, rows())
        assertEquals(0, subscriber.dropped)
    }

    @Test
    fun `능력 변경 이벤트의 사유를 세대 이력의 사유로 옮긴다`() {
        // 사유는 기체가 말한 것만 옮긴다. 다른 메시지의 헤더는 왜 바뀌었는지를 말하지 않는다.
        val degraded = Event.newBuilder().setHeader(header(epoch = 5)).setCapabilityChanged(
            CapabilityChanged.newBuilder().setRobotId("r1").setCapabilityEpoch(5)
                .setCause(CapabilityChangeCause.CAPABILITY_CHANGE_CAUSE_RUNTIME_DEGRADED),
        ).build().toByteArray()
        assertEquals(Recorded.INSERTED, subscriber.handle(topic("event"), degraded))
        assertEquals(Recorded.INSERTED, subscriber.handle(topic("state"), state(header(epoch = 6))))
        val causes = PostgresSupport.queryOne(
            "SELECT string_agg(epoch || ':' || cause, ',' ORDER BY epoch) FROM capability_epoch_log",
        ) { it.getString(1) }
        assertEquals("5:RUNTIME_DEGRADED,6:BINDING_CHANGED", causes)
    }

    @Test
    fun `토픽이 기체 스트림 꼴이 아니면 적지 않고 센다`() {
        // 다른 메이저는 이 계층이 읽지 못하는 페이로드다 — 메이저가 토픽에 있는 이유가 그것이다.
        listOf(
            topic("state", major = MAJOR + 1),
            topic("catalog"),
            "picasso/$MAJOR/line-a/site/catalog",
            "other/$MAJOR/line-a/robot/r1/state",
        ).forEach { assertNull(subscriber.handle(it, state()), "적었다: $it") }
        assertEquals(0, rows())
        assertEquals(4, subscriber.dropped)
    }

    @Test
    fun `헤더의 기체가 토픽의 기체와 다르면 적지 않는다`() {
        // 적으면 세대 이력에 다른 기체의 줄이 선다.
        assertNull(subscriber.handle(topic("state", robotId = "r1"), state(header(robotId = "r2"))))
        assertEquals(0, rows())
        assertEquals(1, subscriber.dropped)
    }

    @Test
    fun `등록되지 않은 기체의 헤더는 적재가 실패해도 예외 없이 센다`() {
        // 세대 이력의 FK 가 막는다. 예외가 콜백 밖으로 새면 브로커 연결이 끊기고 구독이 조용히 멈춘다.
        assertNull(subscriber.handle(topic("state", robotId = "r9"), state(header(robotId = "r9"))))
        assertEquals(1, subscriber.dropped)
        assertEquals(Recorded.INSERTED, subscriber.handle(topic("state"), state()), "실패 뒤 다음 메시지를 못 적었다")
    }

    @Test
    fun `읽지 못하는 페이로드와 헤더 없는 메시지는 적지 않는다`() {
        assertNull(subscriber.handle(topic("state"), byteArrayOf(0x0a, 0x7f)), "깨진 페이로드를 적었다")
        assertNull(subscriber.handle(topic("state"), StateMessage.getDefaultInstance().toByteArray()), "헤더 없는 메시지를 적었다")
        assertEquals(0, rows())
        assertEquals(2, subscriber.dropped)
    }

    private companion object {
        const val MAJOR = 0
    }
}
