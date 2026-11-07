package dev.picasso.middleware

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 명령을 **소비자의 잠금 밖에서** 보내는 차선들(§15.237). [IssuingRobotPort] 는 기체마다, [IssuingFleetPort] 는 운반 참조마다 차선을 하나
 * 둔다.
 *
 * - **차선은 한 번에 명령 하나를 쥔다.** 쥔 명령과 다른 키로 물으면 걸지 않고 바쁘다고 답한다. 같은 차선에 명령 둘이 함께 나가 순서가
 *   뒤바뀌지 않게 한다. 답하지 않는 하위는 제 차선만 붙든다.
 * - **결과는 한 번만 주고, 줄 때까지 든다.** 받아 간 뒤에 차선을 놓는다. 같은 키로 다시 물으면 새 송신이다. 받아 가지 않으면 차선이
 *   풀리지 않는다 — 결과를 버리고 풀면 같은 명령이 두 번 나갈 수 있다.
 * - **보낸 시각은 실제로 보낸 시각이다.** 송신이 실행기에서 시작한 순간을 잰다.
 *
 * @param now 보낸 시각을 재는 시계.
 * @param executor 송신을 돌릴 실행기.
 */
internal class CommandLanes(private val now: () -> Instant, private val executor: Executor) {

    /** 차선 하나. 모든 칸은 이 객체의 모니터 아래에서 읽고 쓴다. */
    private class Lane {
        /** 차선을 쥔 송신 — 보내는 중이거나 결과를 아직 주지 않았다. 널이면 비었다. */
        var key: Any? = null

        /** 끝났고 아직 주지 않은 송신. */
        var result: Done? = null
    }

    /** 끝난 송신 하나. [value] 가 널이면 결과 없이 끝났다. */
    private class Done(val value: Any?, val sentAt: Instant)

    private val lanes = ConcurrentHashMap<String, Lane>()

    /** 이 차선의 송신이 도는 중인가. 시험이 송신이 끝나기를 기다리는 문이다. */
    fun sending(lane: String): Boolean = lanes[lane]?.let { synchronized(it) { it.key != null && it.result == null } } ?: false

    /**
     * [lane] 을 묻는다. 비었으면 [key] 로 쥐고 [send] 를 실행기에 건다. 같은 키가 쥐고 있으면 결과를 한 번 주거나 [pending] 을, 다른 키가
     * 쥐고 있으면 [busy] 를 준다. 실행기가 받지 않으면 차선을 놓고 [rejected] 를, 결과 없이 끝난 송신은 [lost] 를 준다.
     */
    @Suppress("UNCHECKED_CAST")
    fun <R : Any> claim(
        lane: String,
        key: Any,
        pending: R,
        busy: (Any) -> R,
        rejected: () -> R,
        lost: (Instant) -> R,
        send: (Instant) -> R,
    ): R {
        val held = lanes.computeIfAbsent(lane) { Lane() }
        synchronized(held) {
            val holder = held.key
            if (holder != null && holder != key) return busy(holder)
            if (holder == key) {
                val done = held.result ?: return pending
                held.key = null
                held.result = null
                return done.value as R? ?: lost(done.sentAt)
            }
            held.key = key
        }
        return try {
            executor.execute { run(held, send) }
            pending
        } catch (_: RejectedExecutionException) {
            synchronized(held) { held.key = null }
            rejected()
        }
    }

    /** 송신 한 번. 잠금 밖에서 돈다. 무엇으로 끝나든 결과를 남긴다 — 안 남기면 그 차선이 영영 비지 않는다. */
    private fun run(lane: Lane, send: (Instant) -> Any) {
        val sentAt = now()
        var value: Any? = null
        try {
            value = send(sentAt)
        } finally {
            synchronized(lane) { lane.result = Done(value, sentAt) }
        }
    }
}
