package dev.picasso.middleware

/**
 * AMR Fleet Mock — 운반 전체를 D 수준으로 위임받는 하류의 더블(보고서 4.1·5장).
 *
 * **프로젝트용 계약([AmrFleetPort])의 구현이지 특정 벤더 API 의 재현이 아니다.**
 * 벤더 AMR API 나 VDA 5050 은 여기 없다(`scenarios.md` §1 규칙 1).
 *
 * 세계 하나를 든다 — 어느 자리에 어느 용기가 있는가([containersAt]), 어느 목적지가
 * 막혀 있는가([blocked]). 운반은 [tick] 마다 한 단계 나아간다: 접수 → 인수 → 이동 →
 * (목적지가 막혀 있으면 인계 대기) → 인계. 인계는 세계를 바꾼다 — 용기가 목적지에
 * 놓이고 AMR 은 빈다. 그것이 플릿 계약이 보장하는 [TransportState.DELIVERED] 의 뜻이다.
 *
 * 같은 참조로 다시 맡기면 같은 운반이다 — 클라이언트 참조 기반 멱등(보고서 13.2).
 */
class AmrFleetMimic : AmrFleetPort {

    /** 세계 — 자리 → 용기. 시험이 채우고, 인계가 바꾼다. */
    val containersAt = mutableMapOf<String, String>()

    /** 이전 용기가 남아 있어 인계할 수 없는 목적지들. 시험이 치운다. */
    val blocked = mutableSetOf<String>()

    private class Transport(val order: TransportOrder) {
        var state = TransportState.ACCEPTED
        var observed: String? = null
        var holding = false
        var detail: String? = null
    }

    private val transports = linkedMapOf<String, Transport>()
    private val byReference = mutableMapOf<String, String>()
    private var seq = 0

    /** 맡긴 횟수 — 재전송이 재실행이 되지 않았는지를 시험이 이것으로 본다. */
    var dispatches = 0
        private set

    override fun dispatch(order: TransportOrder): TransportHandle? {
        byReference[order.reference]?.let { return TransportHandle(it) }
        dispatches += 1
        val id = "tr-${++seq}"
        val transport = Transport(order)
        // 출발지 확인은 접수 시점이다 — 요청한 용기가 거기 없으면 **인수하지 않는다**.
        val there = containersAt[order.source]
        if (there != order.containerId) {
            transport.state = TransportState.REJECTED_AT_SOURCE
            transport.observed = there
            transport.detail = "요청 ${order.containerId}, 출발지에는 ${there ?: "없음"}"
        }
        transports[id] = transport
        byReference[order.reference] = id
        return TransportHandle(id)
    }

    override fun status(handle: TransportHandle): TransportStatus {
        val t = transports.getValue(handle.id)
        return TransportStatus(t.state, t.observed, t.holding, t.detail)
    }

    override fun cancel(handle: TransportHandle): Boolean {
        val t = transports.getValue(handle.id)
        if (t.state == TransportState.DELIVERED || t.state == TransportState.REJECTED_AT_SOURCE) return false
        // 싣고 있던 것은 출발지로 되돌려 놓는다 — 이 플릿 계약의 정리 동작.
        if (t.holding) {
            containersAt[t.order.source] = t.order.containerId
            t.holding = false
        }
        t.state = TransportState.CANCELLED
        return true
    }

    /** 세계의 시간 한 칸 — 모든 운반이 한 단계 나아간다. */
    fun tick() {
        transports.values.forEach { t ->
            when (t.state) {
                TransportState.ACCEPTED -> {
                    containersAt.remove(t.order.source)
                    t.observed = t.order.containerId
                    t.holding = true
                    t.state = TransportState.PICKED_UP
                }
                TransportState.PICKED_UP -> t.state = TransportState.IN_TRANSIT
                TransportState.IN_TRANSIT, TransportState.WAITING_HANDOVER -> {
                    if (t.order.destination in blocked) {
                        t.state = TransportState.WAITING_HANDOVER
                    } else {
                        containersAt[t.order.destination] = t.order.containerId
                        t.holding = false
                        t.state = TransportState.DELIVERED
                    }
                }
                TransportState.DELIVERED, TransportState.REJECTED_AT_SOURCE,
                TransportState.CANCELLED, TransportState.FAILED -> Unit
            }
        }
    }
}
