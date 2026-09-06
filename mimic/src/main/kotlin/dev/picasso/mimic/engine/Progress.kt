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
 */
class Progress(private val clock: Clock, private val durationSeconds: Double) {

    init {
        require(durationSeconds > 0.0) { "소요시간이 0 이하다: $durationSeconds" }
    }

    private var startedAt: Instant = clock.now()

    fun restart() {
        startedAt = clock.now()
    }

    fun value(): Double =
        (Duration.between(startedAt, clock.now()).toNanos() / 1e9 / durationSeconds)
            .coerceIn(0.0, 1.0)
}
