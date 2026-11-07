package dev.picasso.mimic.engine

import java.time.Duration
import java.time.Instant

/**
 * 진행률. 프로파일이 선언한 소요시간 대비 경과 비율이다(§4.4).
 *
 * **단조 비감소는 `(task_id, revision, attempt)` 세 값이 같은 구간 안에서만
 * 성립하는 불변식이다.** `revision`이 오르거나 `attempt`가 오르면 0에서 다시
 * 세며, `TaskUpdate`가 셋을 모두 싣고 있으므로 소비자는 재시작을 위반과
 * 구분한다.
 *
 * **그 불변식의 주인은 [TaskMachine]이다.** 여기는 시작 시각만 들고,
 * `(revision, attempt)`가 바뀌는 지점 전부가 [restart]를 부른다.
 *
 * **일시정지 동안은 흐르지 않는다**(§15.26). 경과에서 멈춘 구간을 빼며,
 * [TaskMachine]이 `PAUSED`로 들어갈 때 [pause]를, 나올 때 [resume]을
 * 부른다. 빼지 않으면 길게 멈췄다 재개한 태스크의 진행률이 튀고, 진행률
 * 1.0을 완료로 읽는 `TaskHost`가 재개 즉시 완료시킨다.
 */
class Progress(private val clock: Clock, private val durationSeconds: Double) {

    init {
        require(durationSeconds > 0.0) { "소요시간이 0 이하다: $durationSeconds" }
    }

    private var startedAt: Instant = clock.now()

    /** 멈춘 시각. 멈춰 있지 않으면 널이다. */
    private var pausedAt: Instant? = null

    /** [startedAt] 뒤로 멈춰 있던 구간의 합. 지금 멈춘 구간은 들지 않는다. */
    private var pausedTotal: Duration = Duration.ZERO

    /** 멈춘 채 다시 세면 0에서 멈춘 채로 남는다. */
    fun restart() {
        val now = clock.now()
        startedAt = now
        pausedTotal = Duration.ZERO
        if (pausedAt != null) pausedAt = now
    }

    fun pause() {
        if (pausedAt == null) pausedAt = clock.now()
    }

    fun resume() {
        val since = pausedAt ?: return
        pausedTotal = pausedTotal.plus(Duration.between(since, clock.now()))
        pausedAt = null
    }

    fun value(): Double {
        val elapsed = Duration.between(startedAt, pausedAt ?: clock.now()).minus(pausedTotal)
        return (elapsed.toNanos() / 1e9 / durationSeconds).coerceIn(0.0, 1.0)
    }
}
