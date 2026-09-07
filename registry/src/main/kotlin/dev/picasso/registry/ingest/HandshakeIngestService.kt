package dev.picasso.registry.ingest

import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.observe.ObservationService

/** 적재 하나의 결과. **거부를 조용히 삼키지 않는다** — 보고자가 고쳐야 한다. */
sealed interface HandshakeIngestOutcome {
    /** @param written 원장에 쓰인 요구 수, 또는 적재된 거절 수. */
    data class Recorded(val accepted: Boolean, val written: Int) : HandshakeIngestOutcome

    data class Refused(val detail: String) : HandshakeIngestOutcome
}

/**
 * §5.4의 *"결과는 성공·실패 모두 `registry`에 보고된다"*를 받는 자리.
 *
 * ## 이 서비스가 §15.40의 절반을 닫는다
 *
 * 여태 `consumer_requirement`를 채우는 것은 시험과 `harness`뿐이었다. 그래서
 * 운영에서는 §9.3의 조회 1이 언제나 `NotObservable`이었고, 검사 6번은 축소를
 * 승인도 거부도 하지 않았다.
 *
 * ## 입력이 계약 타입인 것이 경계다
 *
 * §3.2 규칙 1 — `registry`는 `mimic`을 모른다. 그래서 여기 들어오는 것은
 * `mimic`의 무엇이 아니라 **오간 계약 메시지 그대로**다. 보고자는 해석하지
 * 않고 넘기고 판정은 여기서 한다 — 보고자가 해석하면 그 해석이 두 번째
 * 진실이 되고, 보고자가 여럿이 되는 날 서로 다른 원장이 쌓인다.
 *
 * ## `site`는 왜 따로 받는가
 *
 * `consumer.site`는 §8.3이 "토픽의 site"라 정했는데 [MessageHeader][
 * dev.picasso.contracts.v1.MessageHeader]에는 site가 없다. `robot` 표에서
 * 유추하면 **등록되지 않은 기체와 협상한 소비자를 적재할 수 없게 된다** —
 * 그런 소비자야말로 원장이 놓치면 안 되는 쪽이다(§8.3이 미등록 소비자도
 * 자동 생성하는 이유와 같다).
 */
class HandshakeIngestService(
    private val ledger: LedgerService,
    private val observations: ObservationService,
) {

    fun record(
        request: NegotiateRequest,
        response: NegotiateResponse,
        site: String,
    ): HandshakeIngestOutcome {
        // **헤더가 권위다**(§5.4). 페이로드의 같은 필드는 복사본이고, 둘이
        // 어긋난 것은 협상이 이미 IDENTITY_MISMATCH로 잡는다 — 여기서 다시
        // 판정하면 협상과 두 번째 진실이 생긴다.
        val clientId = request.header.clientId
        val robotId = request.header.robotId

        if (clientId.isBlank()) return HandshakeIngestOutcome.Refused("헤더에 client_id가 없다")
        if (robotId.isBlank()) return HandshakeIngestOutcome.Refused("헤더에 robot_id가 없다")
        if (site.isBlank()) return HandshakeIngestOutcome.Refused("site가 없다")

        // **모순된 보고는 적재하지 않는다.** 거절을 달고 온 성공을 원장에
        // 넣으면 "쓰는 사람 0명임을 관측했다"의 근거가 거짓이 되고, 그
        // 거짓 위에서 축소가 열린다.
        val rejections = response.rejectionsList
        if (response.accepted && rejections.isNotEmpty()) {
            return HandshakeIngestOutcome.Refused(
                "성공인데 거절이 ${rejections.size}건 실려 있다",
            )
        }
        if (!response.accepted && rejections.isEmpty()) {
            return HandshakeIngestOutcome.Refused("실패인데 거절 사유가 없다")
        }

        val requirements = request.requirement.requirementsList

        if (!response.accepted) {
            rejections.forEach { observations.recordRejection(robotId, clientId, requirements, it) }
            return HandshakeIngestOutcome.Recorded(accepted = false, written = rejections.size)
        }

        // **요구가 빈 성공은 원장에 아무것도 안 남긴다.** 그것을 적재
        // 성공으로 부르면 소비자 행만 생기고 요구는 없는 유령이 쌓인다.
        if (requirements.isEmpty()) {
            return HandshakeIngestOutcome.Refused("성공인데 요구가 하나도 없다")
        }

        val written = ledger.observe(clientId, site, requirements)
        return HandshakeIngestOutcome.Recorded(accepted = true, written = written)
    }
}
