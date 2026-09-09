package dev.picasso.uplink

import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import org.eclipse.paho.mqttv5.client.MqttClient
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttMessage

/**
 * §3.5의 MQTT 발행. **§15.30이 "발행자 추상만 두고"라 적어 둔 자리를 채운다.**
 *
 * ## 이것이 기본 발행자가 아니다
 *
 * 시험과 `harness`는 여전히 in-process [RecordingPublisher]를 쓴다. §12.1의
 * 결정성 — *"시드 + 가상 시계 고정 = 동일 이벤트 시퀀스"* — 이 브로커를
 * 붙이는 순간 깨지기 때문이다. 내장이든 컨테이너든 브로커는 자기 스레드를
 * 벽시계로 돌린다.
 *
 * 그래서 이 클래스는 **운영 배포와 브로커 통합 시험만** 쓴다.
 *
 * ## Last Will이 `CONNECTION_BROKEN`이다
 *
 * §4.7이 그렇게 규정했다. 연결이 정상 종료가 아니라 끊긴 경우에만 브로커가
 * 이 메시지를 대신 발행한다 — **기체가 자기 죽음을 알릴 수 없을 때 알리는
 * 유일한 방법**이고, 그래서 침묵의 세 원인(§4.7) 중 하나가 관측 가능해진다.
 *
 * 이것을 붙이지 않으면 소비자는 죽은 로봇과 조용한 로봇을 구별하지 못한다.
 *
 * ## `retain`은 발행 시점에 정해진다
 *
 * [Publication.retained]가 그 값이다. §4.7의 `connection` 스트림과 §9.6의
 * 사이트 카탈로그가 retain이어야 하는 이유는 같다 — **새로 붙은 구독자가
 * 다음 발행을 기다리지 않고 지금 상태를 알아야 한다.**
 */
class MqttPublisher(
    private val client: MqttClient,
    private val qos: Int = DEFAULT_QOS,
) : Publisher, AutoCloseable {

    override fun publish(publication: Publication) {
        val message = MqttMessage(publication.message.toByteArray()).apply {
            this.qos = this@MqttPublisher.qos
            isRetained = publication.retained
        }
        client.publish(publication.topic, message)
    }

    override fun close() {
        if (client.isConnected) client.disconnect()
        client.close()
    }

    companion object {

        /**
         * **1이다.** 0이면 유실이 조용하고, 2는 왕복이 둘이라 발행 경로가
         * 느려진다. §10.4가 결손 감지를 `sequence`로 하도록 만들어 뒀으므로
         * 중복은 소비자가 접을 수 있고 유실은 접을 수 없다 — 그 비대칭이
         * 최소 1을 요구한다.
         */
        const val DEFAULT_QOS = 1

        /**
         * Last Will을 걸어 접속한다.
         *
         * @param willTopic 그 기체의 `connection` 스트림 토픽.
         *   [Topics.robot] 이 만든 값이어야 한다.
         */
        fun connect(
            brokerUrl: String,
            clientId: String,
            willTopic: String,
            willHeader: MessageHeader,
        ): MqttPublisher {
            val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
            val will = ConnectionMessage.newBuilder()
                .setHeader(willHeader)
                .setState(ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN)
                .build()

            val options = MqttConnectionOptions().apply {
                isCleanStart = true
                // **retain이다.** 새 구독자가 붙었을 때 죽어 있는 기체를
                // 조용한 기체로 오해하면 안 된다(§4.7).
                setWill(willTopic, MqttMessage(will.toByteArray()).apply {
                    qos = DEFAULT_QOS
                    isRetained = true
                })
            }
            client.connect(options)
            return MqttPublisher(client)
        }
    }
}
