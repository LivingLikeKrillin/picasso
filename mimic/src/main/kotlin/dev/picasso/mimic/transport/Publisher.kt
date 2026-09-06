package dev.picasso.mimic.transport

import com.google.protobuf.Message

/**
 * 발행 하나. `sequence`가 **이미 붙은** 상태다.
 *
 * 그것이 이 타입의 존재 이유다 — 전송 장애 주입(§10.4 ②)이 번호를 붙이기
 * **전에** 끼어들면 하나를 버렸을 때 나머지가 새로 매겨져 **소비자가 결손을
 * 아예 못 본다.** 결손을 감지하는 능력이 그 자리에서 사라지고, 완료 기준 3이
 * 조용히 통과한다.
 */
data class Publication(val topic: String, val message: Message, val sequence: Long)

/**
 * 발행 표면. §3.5는 MQTT라 하지만 **2단계는 브로커를 붙이지 않는다.**
 *
 * 이유는 §12.1의 결정성이다 — 내장 브로커는 자기 스레드를 벽시계로 돌리므로
 * "시드 + 가상 시계 고정 = 동일 이벤트 시퀀스"가 깨진다. 토픽 형식과 발행 열
 * 헤더는 그대로 지키되 붙이는 일이 남는다. 증명되지 않는 것은 §15.30에 있다.
 *
 * 6b의 전송 장애 다섯(`DISCONNECT`·`DELAY`·`EVENT_LOSS`·`DUPLICATE`·`REORDER`)이
 * 이 자리에 끼어든다.
 */
fun interface Publisher {
    fun publish(publication: Publication)

    companion object {
        /** 아무 데도 안 보낸다. */
        val NONE = Publisher { }
    }
}

/**
 * 받은 것을 그대로 모은다. `harness`와 시험이 구독자 노릇을 한다.
 */
class RecordingPublisher : Publisher {

    private val received = mutableListOf<Publication>()

    val publications: List<Publication> get() = received.toList()

    fun topic(topic: String): List<Publication> = received.filter { it.topic == topic }

    fun clear() = received.clear()

    override fun publish(publication: Publication) {
        received += publication
    }
}

/**
 * §5.5의 토픽. `picasso/{major}/{site}/robot/{robot_id}/{stream}`.
 *
 * 메이저 버전을 경로에 두는 것은 VDA5050에서 가져왔다 — 구독자가 이해하지
 * 못하는 메이저의 페이로드를 애초에 받지 않는다.
 */
object Topics {

    /** `{stream}` 값 집합은 셋이다(§3.5). */
    enum class Stream { state, event, connection }

    fun robot(major: Int, site: String, robotId: String, stream: Stream): String {
        require(major >= 0) { "메이저 버전이 음수다: $major" }
        legal("site", site)
        legal("robot_id", robotId)
        return "picasso/$major/$site/robot/$robotId/$stream"
    }

    /**
     * MQTT 토픽 레벨로 쓸 수 없는 값을 미리 막는다.
     *
     * **브로커를 안 붙였으므로 이것이 유일한 방어다.** 와일드카드가 섞인
     * `robot_id`는 실제 브로커가 거절하는데, 여기서 안 막으면 3단계에
     * 브로커를 붙이는 순간 드러난다.
     */
    private fun legal(what: String, value: String) {
        require(value.isNotEmpty()) { "$what 가 비었다 — 빈 토픽 레벨은 만들 수 없다" }
        require('+' !in value && '#' !in value) {
            "$what 에 MQTT 와일드카드가 있다: '$value'"
        }
        require('/' !in value) { "$what 에 레벨 구분자가 있다: '$value'" }
        require(value.none { it.isWhitespace() || it.code < 0x20 }) {
            "$what 에 공백이나 제어 문자가 있다: '$value'"
        }
    }
}
