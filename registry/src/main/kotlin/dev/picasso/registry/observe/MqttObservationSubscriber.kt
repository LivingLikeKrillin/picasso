package dev.picasso.registry.observe

import com.google.protobuf.InvalidProtocolBufferException
import dev.picasso.contracts.v1.CapabilityChangeCause
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.wire.ContractIdentity
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import java.util.concurrent.atomic.AtomicInteger

/**
 * §3.2의 `registry ⇠ 브로커` 구독(§15.34). 기체 스트림 셋(`state`·`event`·`connection`)의 헤더를 받아 [ObservationService.recordHeader]
 * 에 적는다.
 *
 * ## 기본 경로가 아니다
 *
 * 시험은 여전히 [ObservationService] 를 직접 부른다. §12.1의 결정성 때문이다 — 브로커는 자기 스레드를 벽시계로 돌린다. 이 클래스는
 * 발행 쪽의 `MqttPublisher` 와 같이 **운영 배포와 브로커 통합 시험만** 쓴다. 판정은 [handle] 에 있고 브로커 없이 시험한다.
 *
 * ## 판정하지 않고 받아 적는다
 *
 * 사유는 기체가 말한 것만 옮긴다. `event` 스트림의 `CapabilityChanged` 가 싣는 사유(넷이 [EpochCause] 와 하나씩 맞는다)가 그것이고,
 * 나머지는 [EpochCause.BINDING_CHANGED] 다 — 헤더는 지금의 세대와 `profile_ref` 만 말하고 왜 바뀌었는지는 말하지 않는다. 같은 사실의
 * 반복은 적재가 접는다. 읽지 못한 것은 적지 않고 [dropped] 로만 센다 — 토픽이 기체 스트림 꼴이 아니거나, 페이로드가 그 스트림의 메시지가
 * 아니거나, 헤더가 없거나, 헤더의 `robot_id` 가 토픽의 것과 다르면 그렇다. 마지막 것을 적으면 세대 이력이 다른 기체의 줄이 된다.
 * **적재가 실패해도 센다**(등록되지 않은 기체는 세대 이력의 FK 에 걸린다). 예외가 콜백 밖으로 새면 Paho 가 연결을 끊고 구독이 조용히 멈춘다.
 *
 * `registry` 는 `uplink` 를 모르므로(§3.2) 토픽 꼴 `picasso/{major}/{site}/robot/{robot_id}/{stream}` 을 여기서도 읽는다. 둘이 같은지는
 * `harness` 의 통합 시험이 발행자로 대 본다.
 */
class MqttObservationSubscriber(
    private val observations: ObservationService,
    private val major: Int = ContractIdentity.major,
) {

    private val droppedCount = AtomicInteger()

    /** 읽지 못해 적지 않은 메시지 수. */
    val dropped: Int get() = droppedCount.get()

    /** 구독할 토픽 필터 — 이 계층이 읽는 메이저의 기체 스트림 전부. */
    val filter: String get() = "picasso/$major/+/robot/+/+"

    /**
     * 메시지 하나를 적는다. 적었으면 그 결과, 읽지 못했으면 널.
     */
    fun handle(topic: String, payload: ByteArray): Recorded? {
        val (header, cause) = headerOf(topic, payload) ?: run {
            droppedCount.incrementAndGet()
            return null
        }
        return try {
            observations.recordHeader(header, cause)
        } catch (_: Exception) {
            droppedCount.incrementAndGet()
            null
        }
    }

    private fun headerOf(topic: String, payload: ByteArray): Pair<MessageHeader, EpochCause>? {
        val levels = topic.split('/')
        if (levels.size != 6 || levels[0] != "picasso" || levels[1] != major.toString() || levels[3] != "robot") return null
        val robotId = levels[4]
        val read = try {
            when (levels[5]) {
                "state" -> StateMessage.parseFrom(payload).takeIf { it.hasHeader() }?.let { it.header to EpochCause.BINDING_CHANGED }
                "event" -> Event.parseFrom(payload).takeIf { it.hasHeader() }?.let { it.header to causeOf(it) }
                "connection" -> ConnectionMessage.parseFrom(payload).takeIf { it.hasHeader() }
                    ?.let { it.header to EpochCause.BINDING_CHANGED }
                else -> null
            }
        } catch (_: InvalidProtocolBufferException) {
            null
        }
        return read?.takeIf { it.first.robotId == robotId }
    }

    private fun causeOf(event: Event): EpochCause =
        if (!event.hasCapabilityChanged()) {
            EpochCause.BINDING_CHANGED
        } else {
            when (event.capabilityChanged.cause) {
                CapabilityChangeCause.CAPABILITY_CHANGE_CAUSE_RUNTIME_DEGRADED -> EpochCause.RUNTIME_DEGRADED
                CapabilityChangeCause.CAPABILITY_CHANGE_CAUSE_OPERATOR_BLOCKED -> EpochCause.OPERATOR_BLOCKED
                CapabilityChangeCause.CAPABILITY_CHANGE_CAUSE_RESTORED -> EpochCause.RESTORED
                else -> EpochCause.BINDING_CHANGED
            }
        }

    /** 이미 접속한 [client] 에 구독을 건다. 메시지는 [handle] 로 간다. 다시 붙으면 구독을 다시 건다 — 새로 시작한 세션은 구독을 잃는다. */
    fun subscribe(client: MqttClient) {
        // **리스너 오버로드를 안 쓴다.** Paho 1.2.5의 `subscribe(String, int, IMqttMessageListener)` 가 자기를 무한 재귀 호출한다
        // (`MqttBrokerTest` 실측). 콜백으로 받는다.
        client.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String, message: MqttMessage) {
                handle(topic, message.payload)
            }

            override fun disconnected(response: MqttDisconnectResponse?) = Unit
            override fun mqttErrorOccurred(exception: MqttException?) = Unit
            override fun deliveryComplete(token: IMqttToken?) = Unit
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) client.subscribe(filter, QOS)
            }
            override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) = Unit
        })
        client.subscribe(filter, QOS)
    }

    companion object {

        /** 발행 쪽과 같은 1이다 — 중복은 적재가 접고 유실은 접지 못한다. */
        const val QOS = 1

        /** 브로커에 붙어 구독을 건다. 닫으면 끊는다. */
        fun connect(brokerUrl: String, clientId: String, subscriber: MqttObservationSubscriber): AutoCloseable {
            val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
            client.connect(
                MqttConnectionOptions().apply {
                    isCleanStart = true
                    isAutomaticReconnect = true
                },
            )
            subscriber.subscribe(client)
            return AutoCloseable {
                if (client.isConnected) client.disconnect()
                client.close()
            }
        }
    }
}
