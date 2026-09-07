package dev.picasso.mimic.report

import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
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
    /** 기체 생존 관측선. §9.3의 두 조회가 세기 전에 보는 것이다. */
    private val liveness: LivenessObservations = LivenessObservations.NONE,
    /**
     * 그 기체가 보고하는 로봇 소프트웨어를 찾는다.
     *
     * **람다인 것은 순서 때문이다** — 발행자를 감싸는 시점에는 아직 기체가
     * 만들어지지 않았고, 이 람다는 발행할 때 평가된다.
     */
    private val software: (String) -> String? = { null },
) : Publisher {

    override fun publish(publication: Publication) {
        delegate.publish(publication)

        // **둘 다 넘긴다.** 스냅샷만 받으면 발행 주기보다 짧은 태스크가
        // 비종착으로 한 번도 안 실리고, 이벤트만 받으면 유실된 전이를
        // 메울 길이 없다 — 전자는 축소를 **열고** 후자는 **막는다.**
        when (val message = publication.message) {
            // **상태 발행이 ONLINE 기체의 관측선이다.** `publishStateIfDue` 는
            // `ONLINE` 일 때만 도므로(§4.7) 여기 온 것은 상태가 ONLINE 이라는
            // 뜻이고, 주기가 있으니 살아 있으면 반드시 갱신된다.
            is StateMessage -> {
                runCatching { sink.onState(message) }
                runCatching {
                    liveness.onConnection(
                        message.header,
                        ConnectionState.CONNECTION_STATE_ONLINE,
                        software(message.header.robotId),
                    )
                }
            }

            is Event -> runCatching { sink.onEvent(message) }

            // **연결 전이가 침묵하는 기체의 관측선이다.** HIBERNATING 은
            // 의도적으로 상태를 안 내보내므로(§4.7) 이 전이가 마지막 신호이며,
            // 그것이 원장에 앉아야 절전한 기체가 "관측선이 끊겼다"로 읽혀
            // 모든 축소를 영구히 막는 일이 없다.
            is ConnectionMessage -> runCatching {
                liveness.onConnection(
                    message.header,
                    message.state,
                    software(message.header.robotId),
                )
            }

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
