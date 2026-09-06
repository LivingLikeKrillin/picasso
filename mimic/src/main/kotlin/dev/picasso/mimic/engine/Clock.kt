package dev.picasso.mimic.engine

import java.time.Duration
import java.time.Instant

/**
 * 엔진이 보는 시간.
 *
 * **`VIRTUAL`이 없으면 §12.1의 결정성 규율이 성립하지 않는다**(§10.3).
 * 30초짜리 태스크를 실시간으로 기다리는 시험은 CI에서 못 쓴다.
 */
sealed interface Clock {
    fun now(): Instant

    /** `VIRTUAL`에서만 합법. `REAL`에서 부르면 실패한다. */
    fun advance(by: Duration)
}

/** 데모용. 시험에서 쓰면 결정성이 깨진다. */
class RealClock : Clock {
    override fun now(): Instant = Instant.now()

    override fun advance(by: Duration): Nothing =
        // 조용히 무시하면 시험이 시간이 흐른 줄 알고 통과한다.
        error("실시간 시계는 전진시킬 수 없다. SetClockMode(VIRTUAL)로 바꿔라")
}

class VirtualClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant

    override fun advance(by: Duration) {
        // 되감으면 진행률 단조 비감소가 시계 때문에 깨진다.
        require(!by.isNegative) { "가상 시계를 되감을 수 없다: $by" }
        instant = instant.plus(by)
    }
}
