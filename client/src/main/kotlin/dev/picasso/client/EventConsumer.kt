package dev.picasso.client

import dev.picasso.contracts.v1.Event
import java.time.Duration
import java.time.Instant

/**
 * 발행 축에서 받은 것 하나. **세 스트림이 다 여기로 온다.**
 *
 * `event`가 `null`이면 상태·연결 메시지다 — 접을 것은 없지만 **번호는
 * 차지한다.** 그것을 빼면 소비자가 그 자리를 결손으로 오탐한다(§5.5의 발행
 * 열이 `state`·`event`·`connection`을 다 덮는다).
 */
data class Received(
    val sessionId: String,
    val sequence: Long,
    val eventId: String,
    val event: Event?,
    val at: Instant,
)

/**
 * 결손·중복·순서 역전에서 복원하는 소비자(완료 기준 3).
 *
 * **`client`에 있는 것이 요점이다.** 지금까지 이벤트를 접는 코드는 시험
 * 안에만 있었는데, 완료 기준 3은 "소비자가 복원한다"이므로 시험 안의 접기로
 * 증명하면 *소비자가 그럴 수 있다*가 아니라 **우리가 시험에서 그렇게 했다**는
 * 주장이 된다.
 *
 * ## 셋을 다르게 다룬다
 *
 * | 장애 | 하는 일 | 무엇으로 |
 * |---|---|---|
 * | 중복 | **무시한다** | `event_id` 멱등 키 |
 * | 역전 | **재정렬한다** | 재정렬 창 안에서 `sequence` |
 * | 결손 | **감지한다. 고치지 않는다** | `sequence`의 구멍 |
 *
 * **결손을 고칠 수 있다고 주장하면 거짓말이다.** 잃은 이벤트는
 * `ReplayEvents`로 가져오거나 스냅샷부터 다시 세워야 한다. 소비자가 할 수
 * 있는 것은 **구멍이 있다는 사실을 아는 것**이고, 그것이 §4.8이 `sequence`를
 * 기체 단위 단조 증가로 둔 이유다.
 *
 * ## 재정렬 창
 *
 * §3.5 — 불연속을 만나면 **뒤따르는 이벤트 [windowEvents]개 또는
 * [windowDuration] 중 먼저 오는 쪽**까지 판정을 보류하고, 지나도 안 오면
 * 결손으로 확정한다. **소비자 설정이지 프로파일 값이 아니다** — 얼마나
 * 기다릴지는 소비자의 지연 예산이지 로봇의 성질이 아니기 때문이다.
 *
 * 이 임계가 없으면 `REORDER`와 `EVENT_LOSS`를 구분하는 시험이 결정적이지
 * 않다. 창이 무한이면 메모리가 새고 결손을 영영 확정하지 못한다.
 */
class EventConsumer(
    private val windowEvents: Int = 8,
    private val windowDuration: Duration = Duration.ofSeconds(2),
) {
    init {
        require(windowEvents > 0) { "재정렬 창이 0이면 역전을 절대 못 흡수한다" }
        require(!windowDuration.isNegative && !windowDuration.isZero) {
            "재정렬 창의 시간이 0 이하다: $windowDuration"
        }
    }

    private var session: String? = null
    private var expected: Long = 0

    /** 아직 자리가 안 온 것들. 번호 순으로 든다. */
    private val held = sortedMapOf<Long, Received>()
    private var heldSince: Instant? = null

    private val seen = HashSet<String>()

    private val delivered = mutableListOf<Event>()

    private val gaps = mutableListOf<Long>()

    /** 순서대로 접힌 이벤트들. 결손은 **메우지 않고 건너뛴다.** */
    val events: List<Event> get() = delivered.toList()

    /** 결손으로 확정한 번호들. **비어 있지 않으면 소비자의 상태는 불완전하다.** */
    val missing: List<Long> get() = gaps.toList()

    /** 지금까지 무시한 중복의 수. 0이면 중복 시험이 공허했다는 뜻이다. */
    var duplicatesIgnored: Int = 0
        private set

    /** 세션이 바뀌어 처음부터 다시 센 횟수. */
    var resets: Int = 0
        private set

    fun accept(item: Received) {
        // §4.8 — 세션이 바뀌면 재기동이다. 번호가 0부터 다시 시작하므로
        // 이전 세션의 기대값을 들고 있으면 전부 결손으로 오탐한다.
        if (session != null && session != item.sessionId) reset(item.sequence)
        if (session == null) {
            session = item.sessionId
            expected = item.sequence
        }

        // 중복은 **무시한다.** 다시 접으면 멱등이 아닌 리듀서에서 상태가 어긋난다.
        if (item.eventId.isNotEmpty() && !seen.add(item.eventId)) {
            duplicatesIgnored += 1
            return
        }

        // 이미 지나간 번호다 — 창을 넘겨 결손으로 확정한 뒤에 도착했다.
        if (item.sequence < expected) return

        if (held.put(item.sequence, item) == null && heldSince == null) heldSince = item.at
        drain()
        expire(item.at)
    }

    /** 창이 지났는지 시간으로만 본다. 조용한 구간에서도 결손이 확정돼야 한다. */
    fun tick(now: Instant) = expire(now)

    private fun drain() {
        while (true) {
            val next = held.remove(expected) ?: break
            next.event?.let { delivered += it }
            expected += 1
        }
        heldSince = if (held.isEmpty()) null else heldSince
    }

    /**
     * 창이 지난 구멍을 **결손으로 확정한다.**
     *
     * 확정하지 않으면 뒤따르는 것이 영영 안 나가고, 소비자는 조용히 멈춘
     * 것처럼 보인다 — 결함이 실패가 아니라 정지로 나타나는 그 모양이다.
     */
    private fun expire(now: Instant) {
        while (held.isNotEmpty()) {
            val since = heldSince ?: now
            val byCount = held.size >= windowEvents
            val byTime = !Duration.between(since, now).minus(windowDuration).isNegative
            if (!byCount && !byTime) return

            gaps += expected
            expected += 1
            heldSince = now
            drain()
        }
    }

    private fun reset(from: Long) {
        resets += 1
        held.clear()
        heldSince = null
        seen.clear()
        expected = from
        session = null
    }
}
