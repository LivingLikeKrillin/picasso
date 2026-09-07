package dev.picasso.harness

import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.mimic.transport.Publication
import dev.picasso.mimic.transport.Publisher

/**
 * §3.2가 `registry ⇠ 브로커` **구독**으로 규정한 자리를 대신한다.
 *
 * ## 왜 어댑터인가
 *
 * 브로커를 붙이면 §12.1의 결정성이 깨진다 — 내장 브로커는 자기 스레드를
 * 벽시계로 돌리므로 *"시드 + 가상 시계 고정 = 동일 이벤트 시퀀스"*가
 * 성립하지 않는다. §15.30이 발행 쪽에서 같은 판단을 했고, 여기서 구독 쪽도
 * 같은 판단을 한다.
 *
 * **브로커가 붙는 날 이 클래스만 갈아 끼운다.** 그것이 가능한 것은 넘기는
 * 것이 [StateMessage] — 계약 타입 — 이기 때문이다. `registry`는 자기가
 * 어댑터에게서 받았는지 브로커에게서 받았는지 몰라도 된다.
 *
 * ## 발행을 삼키지 않는다
 *
 * 감싼 발행자를 **먼저** 부르고 그 결과와 무관하게 지나보낸다. 어댑터가
 * 발행을 가로채거나 순서를 바꾸면 §12.1의 이벤트 시퀀스가 달라지고,
 * 그러면 이 어댑터를 붙인 시험과 안 붙인 시험이 서로 다른 것을 본다.
 *
 * ## 적재 실패가 발행을 막지 않는다
 *
 * §5.4가 핸드셰이크 보고에 대해 정한 것과 같은 규칙이다. 적재가 멈추면
 * 워터마크가 늙어 §9.3의 조회가 `NotObservable`이 되고, 그것은 축소를
 * **막는** 쪽이라 안전하게 실패한다(§15.41의 "틀리는 방향").
 */
class IngestBridge(
    private val delegate: Publisher,
    /** 받은 관측을 적재하는 곳. `registry`의 타입은 여기 안 들어온다. */
    private val sink: TaskObservations,
) : Publisher {

    override fun publish(publication: Publication) {
        delegate.publish(publication)

        // **둘 다 넘긴다.** 스냅샷만 받으면 발행 주기보다 짧은 태스크가
        // 비종착으로 한 번도 안 실리고, 이벤트만 받으면 유실된 전이를
        // 메울 길이 없다 — 전자는 축소를 **열고** 후자는 **막는다.**
        when (val message = publication.message) {
            is StateMessage -> runCatching { sink.onState(message) }
            is Event -> runCatching { sink.onEvent(message) }
            else -> Unit
        }
    }
}

/**
 * 적재를 받는 쪽. **`harness`에 있는 것이 경계다** — `registry`의 타입이
 * `mimic` 쪽 코드에 들어오지 않아야 §3.2가 지켜진다(`registry`는 `mimic`을
 * 모르고, 그 역도 마찬가지다).
 *
 * 브로커가 붙는 날 구독기가 같은 두 메시지를 넘기면 되고, 이 인터페이스는
 * 그때 사라진다.
 */
interface TaskObservations {

    /** 주기 스냅샷(§7.2). 놓친 전이를 메운다. */
    fun onState(message: StateMessage)

    /** 전이 이벤트(§4.7). **드레인의 해상도가 여기서 정해진다.** */
    fun onEvent(event: Event)
}
