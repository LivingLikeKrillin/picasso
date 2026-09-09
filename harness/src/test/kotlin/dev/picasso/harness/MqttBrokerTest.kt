package dev.picasso.harness

import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.uplink.MqttPublisher
import dev.picasso.uplink.Publication
import dev.picasso.uplink.Topics
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§15.30이 "증명되지 않는다"고 적어 둔 것 중 셋을 줄인다.**
 *
 * 그 항목이 꼽은 넷은 이랬다 — `CONNECTION_BROKEN`이 브로커 Last Will이라는
 * 것, `retain`, 단절 중 버퍼링, 토픽 문자열의 브로커 수준 합법성. 앞의 둘과
 * 마지막을 여기서 실제 브로커로 본다.
 *
 * ## 기본 스위트는 여전히 in-process다
 *
 * §12.1의 결정성 — *"시드 + 가상 시계 고정 = 동일 이벤트 시퀀스"* — 이
 * 브로커를 붙이면 깨진다. 브로커는 자기 스레드를 벽시계로 돌린다.
 *
 * 그래서 이 시험만 컨테이너를 쓰고, `mimic`의 시험은 하나도 안 바뀐다.
 * **`harness`에 둔 것도 그래서다** — 여기는 이미 Docker를 요구한다(§15.39).
 *
 * ## 무엇이 여전히 증명되지 않는가
 *
 * 단절 중 버퍼링과 넘칠 때의 새 `session_id`(§10.6)는 여기서도 안 본다.
 * 그것은 브로커가 아니라 `mimic`의 거동이고, 브로커를 끊었다 붙이는 시나리오가
 * 필요하다. §15에 남긴다.
 */
class MqttBrokerTest {

    private lateinit var broker: GenericContainer<*>
    private lateinit var url: String

    @BeforeTest
    fun start() {
        broker = GenericContainer("eclipse-mosquitto:2")
            .withExposedPorts(1883)
            // 이미지가 들고 있는 익명 허용 설정. 인증은 이 시험의 대상이 아니다.
            .withCommand("mosquitto", "-c", "/mosquitto-no-auth.conf")
            .waitingFor(Wait.forListeningPort())
        broker.start()
        url = "tcp://${broker.host}:${broker.getMappedPort(1883)}"
    }

    @AfterTest
    fun stop() = broker.stop()

    // ── 토픽 문자열이 브로커에서 실제로 합법인가

    @Test
    fun `Topics가 만든 토픽으로 실제로 오간다`() {
        // `Topics`의 방어는 브로커가 없는 동안 **유일한 방어**였다. 그것이
        // 실제 브로커의 판정과 같은지는 여기서만 알 수 있다.
        val topic = Topics.robot(1, "line-a", "r1", Topics.Stream.state)
        val received = subscribe(topic)

        publisher().use { it.publish(Publication(topic, state(), sequence = 1)) }

        val payload = received.await()
        assertEquals("t1", StateMessage.parseFrom(payload).getTasks(0).taskId)
    }

    @Test
    fun `와일드카드가 섞인 토픽은 발행이 거부된다`() {
        // `Topics`가 `+`·`#`을 막는 이유가 이것이다. 막지 않으면 브로커를
        // 붙이는 날 드러난다 — 그때는 이미 그 robot_id로 배포가 나가 있다.
        val illegal = "picasso/1/line-a/robot/r+1/state"

        publisher().use { publisher ->
            val thrown = runCatching {
                publisher.publish(Publication(illegal, state(), sequence = 1))
            }.exceptionOrNull()

            assertTrue(thrown != null, "브로커가 와일드카드 토픽을 받아 줬다")
        }
    }

    @Test
    fun `Topics는 그 값을 애초에 막는다`() {
        // 브로커가 막는 것과 우리가 막는 것이 같은 집합인지 본다. 우리가
        // 더 늦게 막으면 그 사이의 코드가 이미 잘못된 값을 들고 있다.
        listOf("r+1", "r#1", "a/b", "", " ").forEach { bad ->
            val thrown = runCatching {
                Topics.robot(1, "line-a", bad, Topics.Stream.state)
            }.exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException, "'$bad' 을 막지 않았다")
        }
    }

    // ── retain

    @Test
    fun `retain으로 발행하면 나중에 붙은 구독자가 받는다`() {
        // §4.7·§9.6이 retain을 요구하는 이유 — **새 구독자가 다음 발행을
        // 기다리지 않고 지금 상태를 알아야 한다.**
        val topic = Topics.robot(1, "line-a", "r1", Topics.Stream.connection)

        publisher().use {
            it.publish(Publication(topic, online(), sequence = 1, retained = true))
        }

        // 발행이 끝난 **뒤에** 붙는다.
        val received = subscribe(topic)
        val payload = received.await()
        assertEquals(
            ConnectionState.CONNECTION_STATE_ONLINE,
            ConnectionMessage.parseFrom(payload).state,
        )
    }

    @Test
    fun `retain이 아니면 나중에 붙은 구독자가 못 받는다`() {
        // 반대쪽이 없으면 "언제나 retain"이 위 시험을 통과한다.
        val topic = Topics.robot(1, "line-a", "r2", Topics.Stream.connection)

        publisher().use {
            it.publish(Publication(topic, online(), sequence = 1, retained = false))
        }

        val received = subscribe(topic)
        assertTrue(!received.arrived(), "retain이 아닌데 뒤늦은 구독자가 받았다")
    }

    // ── Last Will

    @Test
    fun `연결이 끊기면 브로커가 CONNECTION_BROKEN을 발행한다`() {
        // §4.7 — **기체가 자기 죽음을 알릴 수 없을 때 알리는 유일한 방법.**
        // 이것이 없으면 소비자는 죽은 로봇과 조용한 로봇을 구별하지 못한다.
        val topic = Topics.robot(1, "line-a", "r3", Topics.Stream.connection)
        val received = subscribe(topic)

        val dying = MqttPublisher.connect(url, "robot-r3", topic, header())
        // **정상 종료가 아니라 끊는다.** disconnect()는 Last Will을 안 낸다.
        forceDisconnect(dying)

        val payload = received.await(seconds = 20)
        assertEquals(
            ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
            ConnectionMessage.parseFrom(payload).state,
        )
    }

    @Test
    fun `정상 종료면 Last Will이 안 나간다`() {
        // 나가면 멀쩡히 내린 로봇이 고장으로 보인다.
        val topic = Topics.robot(1, "line-a", "r4", Topics.Stream.connection)
        val received = subscribe(topic)

        MqttPublisher.connect(url, "robot-r4", topic, header()).close()

        assertTrue(!received.arrived(), "정상 종료인데 Last Will이 나갔다")
    }

    @Test
    fun `Last Will은 retain이라 늦게 붙어도 받는다`() {
        // **미리 구독하면 retain 여부와 무관하게 받는다.** 죽은 뒤에 붙는
        // 소비자가 죽음을 알아야 하고(§4.7), 그것이 retain의 이유다.
        val topic = Topics.robot(1, "line-a", "r5", Topics.Stream.connection)

        val dying = MqttPublisher.connect(url, "robot-r5", topic, header())
        forceDisconnect(dying)
        Thread.sleep(500)

        // 죽은 **뒤에** 붙는다.
        val received = subscribe(topic)

        assertEquals(
            ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
            ConnectionMessage.parseFrom(received.await()).state,
        )
    }

    // ── 발행 경로가 실제로 브로커를 문다

    @Test
    fun `CLI가 브로커를 주면 발행이 실제로 나간다`() {
        // **§15.30의 마지막 구멍.** `MqttPublisher`가 브로커와 말한다는 것과
        // `mimic`이 그것을 쓴다는 것은 다른 얘기다. 여기서 후자를 본다.
        val major = dev.picasso.contracts.wire.ContractIdentity.major
        // **기체 둘이다.** MQTT clientId가 같으면 두 번째 접속이 첫 번째를
        // 끊어 한 기체의 발행이 통째로 사라지는데, **로그에는 아무것도 안
        // 남는다.** 하나로 보면 그 결함이 안 보인다.
        val first = subscribe(Topics.robot(major, "line-a", "r1", Topics.Stream.event))
        val second = subscribe(Topics.robot(major, "line-a", "r2", Topics.Stream.event))

        val cli = dev.picasso.mimic.cli.MimicCli()
        val out = StringBuilder()
        val err = StringBuilder()
        try {
            val code = cli.run(
                arrayOf(
                    "--robot", "r1=" + java.nio.file.Path.of(
                        "..", "profile", "fixtures", "minimal.json",
                    ).normalize().toString(),
                    "--robot", "r2=" + java.nio.file.Path.of(
                        "..", "profile", "fixtures", "minimal.json",
                    ).normalize().toString(),
                    "--schema", java.nio.file.Path.of(
                        "..", "profile", "schema", "capability-profile.schema.json",
                    ).normalize().toString(),
                    "--port", "0", "--site", "line-a", "--broker", url,
                ),
                out, err,
            )
            assertEquals(0, code, err.toString())
            assertTrue("브로커 발행" in out.toString(), out.toString())

            // 태스크를 시작하면 전이 이벤트가 발행된다.
            val port = requireNotNull(cli.started).server.port
            startTask(port, "r1")
            startTask(port, "r2")
        } finally {
            cli.started?.server?.shutdown()
        }

        assertTrue(
            dev.picasso.contracts.v1.Event.parseFrom(first.await()).hasTaskTransition(),
            "r1의 전이가 안 왔다",
        )
        assertTrue(
            dev.picasso.contracts.v1.Event.parseFrom(second.await()).hasTaskTransition(),
            "r2의 전이가 안 왔다 — 두 기체가 같은 clientId로 붙었는가",
        )
    }

    private fun startTask(port: Int, robotId: String) {
        val channel = io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1", port)
            .usePlaintext().build()
        try {
            dev.picasso.contracts.v1.TaskServiceGrpc.newBlockingStub(channel).startTask(
                dev.picasso.contracts.v1.StartTaskRequest.newBuilder()
                    .setHeader(
                        dev.picasso.contracts.wire.RequestHeaders.build(
                            "picasso.v1.StartTaskRequest", robotId, "c1",
                        ),
                    )
                    .setTaskId("t1").setRevision(1).setRobotId(robotId)
                    .setSkillType("navigate_to")
                    .addParameters(
                        dev.picasso.contracts.v1.ParameterValue.newBuilder()
                            .setKey("location").setStringValue("dock-1"),
                    ).build(),
            )
        } finally {
            channel.shutdownNow()
        }
    }

    // ── 씨앗

    private fun publisher() = MqttPublisher.connect(
        url, "pub-${System.nanoTime()}",
        Topics.robot(1, "line-a", "unused", Topics.Stream.connection),
        header(),
    )

    /** 연결을 정상 종료 없이 끊어 브로커가 Last Will을 내게 한다. */
    private fun forceDisconnect(publisher: MqttPublisher) {
        val field = MqttPublisher::class.java.getDeclaredField("client")
        field.isAccessible = true
        (field.get(publisher) as MqttClient).disconnectForcibly(0, 0, false)
    }

    private class Received(private val latch: CountDownLatch) {
        @Volatile var payload: ByteArray? = null

        fun await(seconds: Long = 10): ByteArray {
            assertTrue(latch.await(seconds, TimeUnit.SECONDS), "메시지가 안 왔다")
            return requireNotNull(payload)
        }

        /** 짧게 기다려 **안 오는 것**을 확인한다. */
        fun arrived(seconds: Long = 2): Boolean = latch.await(seconds, TimeUnit.SECONDS)
    }

    private fun subscribe(topic: String): Received {
        val latch = CountDownLatch(1)
        val received = Received(latch)
        val client = MqttClient(url, "sub-${System.nanoTime()}", MemoryPersistence())
        // **리스너 오버로드를 안 쓴다.** Paho 1.2.5의
        // `subscribe(String, int, IMqttMessageListener)` 가 자기를 무한 재귀
        // 호출해 StackOverflowError가 난다(실측). 콜백으로 받는다.
        client.setCallback(object : MqttCallback {
            override fun messageArrived(t: String, message: org.eclipse.paho.mqttv5.common.MqttMessage) {
                received.payload = message.payload
                latch.countDown()
            }

            override fun disconnected(response: MqttDisconnectResponse?) = Unit
            override fun mqttErrorOccurred(exception: MqttException?) = Unit
            override fun deliveryComplete(token: org.eclipse.paho.mqttv5.client.IMqttToken?) = Unit
            override fun connectComplete(reconnect: Boolean, serverURI: String?) = Unit
            override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) = Unit
        })
        client.connect(MqttConnectionOptions().apply { isCleanStart = true })
        client.subscribe(topic, MqttPublisher.DEFAULT_QOS)
        return received
    }

    private fun header() = MessageHeader.newBuilder().setRobotId("r1").build()

    private fun state() = StateMessage.newBuilder()
        .setHeader(header())
        .addTasks(TaskSnapshot.newBuilder().setTaskId("t1").setSkillType("navigate_to"))
        .build()

    private fun online() = ConnectionMessage.newBuilder()
        .setHeader(header())
        .setState(ConnectionState.CONNECTION_STATE_ONLINE)
        .build()
}
