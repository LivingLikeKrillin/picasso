package dev.picasso.harness

import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.observe.MqttObservationSubscriber
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.store.Db
import dev.picasso.uplink.MqttPublisher
import dev.picasso.uplink.Publication
import dev.picasso.uplink.Topics
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 관측 구독기가 실제 브로커를 지나 발행자의 토픽을 받는가(§15.34).
 *
 * `registry` 는 `uplink` 를 모르므로 토픽 꼴을 따로 읽는다. 둘이 같은지는 **발행자가 만든 토픽으로** 대 보는 여기서만 안다 — 판정은
 * `registry` 의 `MqttObservationSubscriberTest` 가 브로커 없이 본다. 브로커는 `MqttBrokerTest` 와 같은 컨테이너다.
 */
class ObservationSubscriptionTest {

    private lateinit var broker: GenericContainer<*>
    private lateinit var url: String
    private lateinit var subscription: AutoCloseable

    @BeforeTest
    fun start() {
        PostgresSupport.reset()
        val db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        db.transaction { c ->
            c.prepareStatement(
                "INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1', 'line-a', 'sn-r1')",
            ).use { it.executeUpdate() }
        }
        broker = GenericContainer("eclipse-mosquitto:2")
            .withExposedPorts(1883)
            .withCommand("mosquitto", "-c", "/mosquitto-no-auth.conf")
            .waitingFor(Wait.forListeningPort())
        broker.start()
        url = "tcp://${broker.host}:${broker.getMappedPort(1883)}"
        subscription = MqttObservationSubscriber.connect(url, "registry-${System.nanoTime()}", MqttObservationSubscriber(ObservationService(db)))
    }

    @AfterTest
    fun stop() {
        subscription.close()
        broker.stop()
    }

    @Test
    fun `발행자의 토픽으로 낸 헤더가 세대 이력에 적힌다`() {
        val topic = Topics.robot(ContractIdentity.major, "line-a", "r1", Topics.Stream.state)
        val header = MessageHeader.newBuilder()
            .setRobotId("r1")
            .setCapabilityEpoch(4)
            .setProfileRef(ProfileRef.newBuilder().setProfileId("fixture/minimal").setRevision(1))
            .build()
        MqttPublisher.connect(url, "pub-${System.nanoTime()}", Topics.robot(ContractIdentity.major, "line-a", "r1", Topics.Stream.connection), header).use {
            it.publish(Publication(topic, StateMessage.newBuilder().setHeader(header).build(), sequence = 1))
        }

        val deadline = System.nanoTime() + 10_000_000_000L
        while (epochs().isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(listOf(4L), epochs(), "구독기가 발행자의 토픽을 못 받았다")
    }

    private fun epochs(): List<Long> = PostgresSupport.queryOne(
        "SELECT coalesce(array_agg(epoch ORDER BY epoch), '{}') FROM capability_epoch_log WHERE robot_id = 'r1'",
    ) { rs -> (rs.getArray(1).array as Array<*>).map { (it as Number).toLong() } }
}
