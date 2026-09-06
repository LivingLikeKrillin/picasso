package dev.picasso.mimic.transport

/**
 * §10.5의 `InjectTransportFault`. 발행 축에 장애를 입힌다.
 *
 * **번호가 붙은 **뒤에** 끼어든다.** [Publication]이 `sequence`를 이미 들고
 * 있는 이유가 그것이다 — 붙기 전에 하나를 버리면 나머지가 새로 매겨져
 * **소비자가 결손을 아예 못 본다.** 결손을 감지하는 능력이 그 자리에서
 * 사라지고 완료 기준 3이 조용히 통과한다.
 *
 * **난수를 안 쓴다.** §12.1의 결정성이 시드와 가상 시계만으로 성립해야
 * 하므로, 어느 발행이 희생될지는 **세는 것**으로 정한다.
 */
class TransportFaults(private val delegate: Publisher) : Publisher {

    /** §10.5가 이름을 못박은 다섯. `NONE`은 장애가 아니라 기본값이다. */
    enum class Kind { NONE, DISCONNECT, DELAY, EVENT_LOSS, DUPLICATE, REORDER }

    var kind: Kind = Kind.NONE
        private set

    /** [Kind.EVENT_LOSS]가 몇 번째마다 버리는가. */
    var lossEvery: Int = 3
        private set

    private var count = 0

    /** [Kind.DELAY]·[Kind.REORDER]가 붙들고 있는 것. */
    private var held: Publication? = null

    /**
     * 장애를 건다. **바꾸기 전에 붙들고 있던 것을 내보낸다** — 안 내보내면
     * 장애를 끄는 것이 곧 이벤트 하나를 영영 잃는 일이 된다.
     */
    fun inject(kind: Kind, lossEvery: Int = this.lossEvery) {
        require(lossEvery >= 2) { "매번 버리면 그것은 DISCONNECT다: $lossEvery" }
        flush()
        this.kind = kind
        this.lossEvery = lossEvery
        count = 0
    }

    /** 붙들고 있는 것을 내보낸다. 시험이 끝에서 부른다. */
    fun flush() {
        held?.let { delegate.publish(it) }
        held = null
    }

    override fun publish(publication: Publication) {
        count += 1
        when (kind) {
            Kind.NONE -> delegate.publish(publication)

            // 아무것도 안 나간다. 소비자에게는 침묵으로 보인다.
            Kind.DISCONNECT -> Unit

            // **늦지만 순서는 지킨다.** 붙들었다가 다음 것이 올 때 앞세워
            // 내보낸다 — 소비자의 재정렬 창이 흡수해야 하는 쪽이다.
            Kind.DELAY -> {
                val previous = held
                held = publication
                previous?.let { delegate.publish(it) }
            }

            // 셋째마다 버린다. **번호는 안 메운다** — 그것이 결손이다.
            Kind.EVENT_LOSS -> if (count % lossEvery != 0) delegate.publish(publication)

            // 같은 것을 두 번. `event_id`가 같으므로 소비자가 걸러야 한다.
            Kind.DUPLICATE -> {
                delegate.publish(publication)
                delegate.publish(publication)
            }

            // **이웃끼리 뒤집는다.** 하나를 붙들었다가 다음 것이 오면 그것을
            // **먼저** 내보내고 붙든 것을 뒤에 내보낸다 — p1·p2·p3·p4가
            // p2·p1·p4·p3로 나간다.
            Kind.REORDER -> {
                val previous = held
                if (previous == null) {
                    held = publication
                } else {
                    delegate.publish(publication)
                    delegate.publish(previous)
                    held = null
                }
            }
        }
    }
}
