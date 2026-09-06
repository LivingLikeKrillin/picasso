package dev.picasso.mimic.engine

/**
 * 시드 기반 난수. 선언된 실패 모드의 추첨과 소요시간 지터가 여기서 나온다(§10.4).
 *
 * `java.util.Random`을 쓰는 것은 **JDK 간 재현성이 클래스 계약으로 보장되기
 * 때문이다** — javadoc이 알고리즘을 본문에 명시하고 "Java implementations
 * must use all the algorithms shown here … for the sake of absolute
 * portability"라고 못 박는다. `ThreadLocalRandom`과 `Math.random()`은 시드를
 * 지정할 수조차 없다.
 *
 * **그 보장은 "같은 순서의 같은 호출"에 대한 것이다.** `Random`은 스레드
 * 안전하되 인출 순서는 비결정적이므로, 한 프로세스가 여러 기체를 돌리는
 * 상황(§10.2)에서 하나를 공유하면 §12.1이 깨진다. **기체마다 하나씩 둔다.**
 */
class Seeded(seed: Long) {
    private val random = java.util.Random(seed)

    fun fraction(): Double = random.nextDouble()

    /** `base`를 ±`ratio` 안에서 흔든다. `ratio`가 0이면 그대로. */
    fun jitter(base: Double, ratio: Double): Double {
        require(ratio >= 0.0) { "지터 비율이 음수다: $ratio" }
        if (ratio == 0.0) return base
        return base * (1.0 + (fraction() * 2.0 - 1.0) * ratio)
    }
}
