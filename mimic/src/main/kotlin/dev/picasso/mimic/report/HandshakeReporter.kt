package dev.picasso.mimic.report

import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse

/**
 * 오간 협상 하나. **요약하지 않고 그대로 싣는다.**
 *
 * 보고자가 "성공이었다"로 줄여 보내면 그 요약이 두 번째 진실이 되고,
 * 보고자가 여럿이 되는 날 서로 다른 원장이 쌓인다. 판정은 받는 쪽이 한다.
 *
 * @param site §5.5 토픽의 두 번째 레벨. **헤더에 없어서 따로 싣는다** —
 *   `consumer.site`가 §8.3이 정한 "토픽의 site"이고, 받는 쪽이 `robot` 표에서
 *   유추하면 등록되지 않은 기체와 협상한 소비자를 적재할 수 없게 된다.
 */
data class HandshakeReport(
    val site: String,
    val request: NegotiateRequest,
    val response: NegotiateResponse,
)

/**
 * §5.4의 *"결과는 성공·실패 모두 `registry`에 보고된다"*를 내보내는 쪽.
 *
 * ## `RegistrySource`와 같은 형태다
 *
 * §3.2가 `mimic ⇢ registry`를 **런타임 접근**으로 두었다 — 빌드 의존이
 * 아니며 상대가 없어도 모듈이 빌드·동작해야 한다. `mimic`이 `registry`에
 * 컴파일 의존하면 에뮬레이터 하나 띄우는 데 DB가 필요해진다.
 *
 * ## 던져도 된다
 *
 * §5.4가 *"보고 실패는 핸드셰이크 결과에 영향을 주지 않는다"*고 못박았다.
 * 부르는 쪽이 삼키므로 구현은 실패를 숨길 필요가 없고, 숨기면
 * [FallbackHandshakeReporter]가 파일로 넘길 기회를 잃는다.
 */
fun interface HandshakeReporter {

    fun report(report: HandshakeReport)

    companion object {
        /** 아무 데도 안 보낸다. 레지스트리 없이 도는 모드(§3.2의 "없을 때"). */
        val NONE = HandshakeReporter { }
    }
}

/**
 * 받은 것을 모은다. 시험과 `harness`가 보고 수신자 노릇을 한다.
 * [dev.picasso.mimic.transport.RecordingPublisher]와 같은 자리다.
 */
class RecordingHandshakeReporter : HandshakeReporter {

    private val received = mutableListOf<HandshakeReport>()

    val reports: List<HandshakeReport> get() = received.toList()

    override fun report(report: HandshakeReport) {
        received += report
    }
}

/**
 * 1차가 실패하면 2차로 넘긴다.
 *
 * **삼키는 것과 잃는 것은 다르다.** §5.4는 보고 실패가 협상을 막지 않는다고
 * 했을 뿐 보고를 버리라고 하지 않았다. 레지스트리가 잠깐 없는 동안의 협상이
 * 통째로 사라지면 원장은 그 소비자를 **한 번도 못 본 채** 축소를 승인한다.
 *
 * §3.2의 "없을 때: 로컬 파일에 기록"이 이 클래스다.
 */
class FallbackHandshakeReporter(
    private val primary: HandshakeReporter,
    private val fallback: HandshakeReporter,
) : HandshakeReporter {

    override fun report(report: HandshakeReport) {
        runCatching { primary.report(report) }.onFailure { fallback.report(report) }
    }
}
