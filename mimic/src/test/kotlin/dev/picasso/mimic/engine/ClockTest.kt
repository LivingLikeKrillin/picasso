package dev.picasso.mimic.engine

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClockTest {

    @Test
    fun `가상 시계는 advance로만 전진한다`() {
        val clock = VirtualClock(Instant.EPOCH)
        assertEquals(Instant.EPOCH, clock.now())

        // 벽시계가 흘러도 가상 시계는 그대로다. 이것이 결정성의 근거다.
        Thread.sleep(5)
        assertEquals(Instant.EPOCH, clock.now())

        clock.advance(Duration.ofSeconds(30))
        assertEquals(Instant.EPOCH.plusSeconds(30), clock.now())
    }

    @Test
    fun `가상 시계를 되감을 수 없다`() {
        // 되감으면 진행률 단조 비감소가 시계 때문에 깨진다.
        val clock = VirtualClock(Instant.EPOCH)
        assertFailsWith<IllegalArgumentException> { clock.advance(Duration.ofSeconds(-1)) }
        assertEquals(Instant.EPOCH, clock.now(), "거절했는데 시계가 움직였다")
    }

    @Test
    fun `실시간 시계는 advance를 거절한다`() {
        // 조용히 무시되면 시험이 시간이 흐른 줄 알고 통과한다 — 데모용
        // 시계로 시험을 돌리는 사고를 여기서 막는다.
        assertFailsWith<IllegalStateException> { RealClock().advance(Duration.ofSeconds(1)) }
    }

    @Test
    fun `실시간 시계는 실제로 흐른다`() {
        // >= 로만 보면 상수를 반환하는 구현이 통과한다.
        val clock = RealClock()
        val before = clock.now()
        Thread.sleep(20)
        assertTrue(clock.now() > before, "실시간 시계가 멈춰 있다")
    }
}

class SeededTest {

    @Test
    fun `같은 시드는 같은 수열을 낸다`() {
        // 선언된 실패 모드의 추첨과 소요시간 지터가 여기서 나온다(§10.4).
        val a = Seeded(42).let { r -> List(20) { r.fraction() } }
        val b = Seeded(42).let { r -> List(20) { r.fraction() } }
        assertEquals(a, b)
    }

    @Test
    fun `다른 시드는 다른 수열을 낸다`() {
        assertTrue(
            Seeded(1).let { r -> List(20) { r.fraction() } } !=
                Seeded(2).let { r -> List(20) { r.fraction() } },
        )
    }

    @Test
    fun `fraction은 0 이상 1 미만이다`() {
        val r = Seeded(7)
        repeat(1000) {
            val v = r.fraction()
            assertTrue(v >= 0.0 && v < 1.0, "범위를 벗어났다: $v")
        }
    }

    @Test
    fun `지터는 선언한 비율을 실제로 채운다`() {
        // 범위 안인지만 보면 **언제나 base를 반환하는 구현이 통과한다**(실측).
        // Chunk 1 Task 4가 스스로 적은 실패와 같은 형태다 — 픽스처 값을
        // 리터럴로 적으면 파생과 상수가 구분되지 않는다.
        val r = Seeded(3)
        val samples = List(1000) { r.jitter(base = 45.0, ratio = 0.1) }

        assertTrue(samples.all { it in 40.5..49.5 }, "범위를 벗어났다")
        assertTrue(samples.toSet().size > 900, "지터가 흔들리지 않았다: ${samples.toSet().size}가지")
        // 한쪽으로만 흔드는 구현(base * (1 + f*ratio))을 잡는다.
        assertTrue(samples.min() < 41.0, "하단을 채우지 못했다: ${samples.min()}")
        assertTrue(samples.max() > 49.0, "상단을 채우지 못했다: ${samples.max()}")
    }

    @Test
    fun `지터 비율이 0이면 그대로다`() {
        assertEquals(45.0, Seeded(3).jitter(base = 45.0, ratio = 0.0))
    }

    @Test
    fun `음수 지터 비율을 거절한다`() {
        assertFailsWith<IllegalArgumentException> { Seeded(3).jitter(base = 45.0, ratio = -0.1) }
    }
}
