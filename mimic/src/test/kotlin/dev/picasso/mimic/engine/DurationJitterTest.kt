package dev.picasso.mimic.engine

import dev.picasso.profile.projection.CapabilityProjection
import dev.picasso.profile.ProfileDocument
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §10.4 ②의 소요시간 지터.
 *
 * **이 파일이 없는 동안 `jitter_ratio`는 아무 데도 안 닿았다.** 프로파일이
 * 선언하고, 스키마가 검증하고, 파서가 읽고, `Seeded.jitter`에 단위 시험까지
 * 있었는데 `TaskHost.durationOf`가 `seconds`를 그대로 돌려줬다 — 선언한 값이
 * 거동을 하나도 안 바꿨다(§10.1이 거기서 비어 있었다).
 *
 * 그래서 **소요시간을 직접 안 본다.** 그 값은 계약 표면에 없고(§7.2 — 소비자는
 * 진행률을 받지 소요시간을 받지 않는다), 게터를 뚫으면 지터가 파생에 안 닿아도
 * 초록인 시험이 된다. `progress()`에서 되짚는다.
 */
class DurationJitterTest {

    private val clock = VirtualClock(Instant.EPOCH)

    private fun host(
        document: ProfileDocument = TaskMachineFixtures.document(),
        random: Seeded = Seeded(0),
        clock: Clock = this.clock,
    ) = TaskHost(
        CapabilityProjection.of(document), document, clock,
        EngineListener.NONE, FaultRegistry(clock), random,
    )

    private val pickPlace = listOf(
        TaskMachineFixtures.param("object_id", "b"),
        TaskMachineFixtures.param("destination", "d"),
    )

    /**
     * 태스크 [count]개를 한꺼번에 세우고 [PROBE]초 뒤의 진행률에서 소요시간을
     * 되짚는다. 전부 같은 순간에 시작하므로 진행률의 차이가 곧 소요시간의
     * 차이다.
     */
    private fun durations(
        count: Int,
        skillType: String = "pick_place",
        document: ProfileDocument = TaskMachineFixtures.document(),
        seed: Long = 0,
    ): List<Double> {
        val local = VirtualClock(Instant.EPOCH)
        val tasks = host(document, Seeded(seed), local)
        val params = if (skillType == "pick_place") pickPlace else listOf(location)
        repeat(count) { i -> tasks.start("t$i", 1, skillType, params) }
        tasks.tick()
        local.advance(Duration.ofMillis((PROBE * 1000).toLong()))
        return tasks.all.map { PROBE / it.machine.progress() }
    }

    @Test
    fun `선언한 지터 비율만큼 흔들린다`() {
        // 픽스처: pick_place 45초, jitter_ratio 0.1 → 40.5..49.5.
        val seen = durations(200)

        assertTrue(
            seen.all { it in 40.5 - EPS..49.5 + EPS },
            "선언한 비율 밖으로 나갔다: ${seen.minOrNull()}..${seen.maxOrNull()}",
        )
        // **언제나 base인 구현을 잡는다.** 범위만 보면 45.0이 그 안에 들므로
        // 지터를 아예 안 붙여도 통과한다 — Chunk 1이 스스로 적은 실패다.
        assertTrue(seen.distinct().size > 150, "흔들리지 않았다: ${seen.distinct().size}가지")

        // 선언한 폭을 **실제로** 채우는가. 좁게만 흔들면 비율이 데이터가 아니다.
        assertTrue(seen.min() < 41.5, "아래쪽 끝에 안 닿는다: ${seen.min()}")
        assertTrue(seen.max() > 48.5, "위쪽 끝에 안 닿는다: ${seen.max()}")
    }

    @Test
    fun `지터 비율을 프로파일에서 읽는다`() {
        // 리터럴 0.1이면 프로파일이 선언하는 뜻이 없다. 비율을 키우면
        // 폭도 함께 커져야 한다.
        val raw = TaskMachineFixtures.fixtureRaw
        val wide = raw.replace("\"jitter_ratio\": 0.1", "\"jitter_ratio\": 0.4")
        check(wide != raw) { "치환이 아무것도 바꾸지 못했다" }

        val seen = durations(200, document = TaskMachineFixtures.document(wide))
        assertTrue(seen.min() < 30.0, "0.4를 선언했는데 아래로 안 내려간다: ${seen.min()}")
        assertTrue(seen.max() > 60.0, "0.4를 선언했는데 위로 안 올라간다: ${seen.max()}")
        assertTrue(
            seen.all { it in 27.0 - EPS..63.0 + EPS },
            "0.4 밖으로 나갔다: ${seen.minOrNull()}..${seen.maxOrNull()}",
        )
    }

    @Test
    fun `지터 비율이 없으면 소요시간이 선언값 그대로다`() {
        // 픽스처의 navigate_to는 20초이며 jitter_ratio가 없다.
        val seen = durations(50, skillType = "navigate_to").distinct()
        assertEquals(listOf(20.0), seen.map { kotlin.math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun `지터 비율이 없으면 인출하지 않는다`() {
        // **인출 수가 프로파일에 달리는 것은 괜찮다** — 프로파일이 입력이다.
        // 여기서 보는 것은 0을 선언했는데도 뽑아서 **뒤따르는 실패 추첨을
        // 밀어 버리는** 구현이다. 그러면 §12.1의 골든이 통째로 흔들린다.
        val used = Seeded(3)
        host(random = used).start("t1", 1, "navigate_to", listOf(location))
        assertEquals(0, TaskMachineFixtures.drawsBetween(Seeded(3), used), "0을 선언했는데 뽑았다")

        // 전제: 비율이 있는 스킬은 정확히 한 번 뽑는다.
        val other = Seeded(3)
        host(random = other).start("t1", 1, "pick_place", pickPlace)
        assertEquals(1, TaskMachineFixtures.drawsBetween(Seeded(3), other), "태스크당 한 번이 아니다")
    }

    @Test
    fun `관측을 늘려도 소요시간이 안 바뀐다`() {
        // **진행률을 물을 때마다 뽑으면 관측이 소요시간을 바꾼다**(§12.1).
        // 그런 구현도 "범위 안"·"흔들린다" 시험은 통과한다.
        val tasks = host()
        tasks.start("t1", 1, "pick_place", pickPlace)
        tasks.tick()
        clock.advance(Duration.ofMillis((PROBE * 1000).toLong()))

        val machine = tasks.find("t1")!!.machine
        val seen = List(20) { machine.progress() }.distinct()
        assertEquals(1, seen.size, "같은 시각에 진행률이 흔들린다: $seen")
        assertTrue(seen.single() > 0.0, "진행률이 0이라 아무것도 확인 못 한다")
    }

    @Test
    fun `멱등 재전송이 소요시간을 안 바꾼다`() {
        // §4.4가 같은 revision의 재전송을 **같은 핸들**이라고 못박았다.
        // 거기서 다시 뽑으면 재전송이 태스크를 바꾼다.
        val tasks = host()
        tasks.start("t1", 1, "pick_place", pickPlace)
        tasks.tick()
        clock.advance(Duration.ofMillis((PROBE * 1000).toLong()))
        val before = tasks.find("t1")!!.machine.progress()

        val again = tasks.start("t1", 1, "pick_place", pickPlace)
        assertTrue(again is StartOutcome.Idempotent, "전제가 무너졌다: $again")
        assertEquals(before, tasks.find("t1")!!.machine.progress(), "재전송이 소요시간을 바꿨다")
    }

    @Test
    fun `멱등 재전송이 추첨 스트림도 안 건드린다`() {
        // **소요시간만 보면 부족했다**(실측). 재전송 때 `durationOf`를 다시
        // 불러도 기체는 이미 자기 소요시간을 들고 있어 진행률이 안 바뀐다 —
        // 바뀌는 것은 **난수 스트림**이고, 그러면 뒤따르는 실패 추첨이 통째로
        // 밀려 §12.1의 골든이 흔들린다. 인출 수를 세는 것만이 그것을 본다.
        // **`drawsBetween`은 `used`에서 한 번 뽑는다.** 같은 난수에 두 번
        // 부르면 두 번째가 1을 더 세고, 그것을 프로덕션의 결함으로 읽게 된다
        // (실측: 이 시험을 그렇게 썼다가 깨끗한 트리에서 빨갛게 났다).
        // 시나리오마다 새 난수를 쓴다.
        fun drawsAfter(resends: Int): Int {
            val random = Seeded(3)
            val tasks = host(random = random)
            tasks.start("t1", 1, "pick_place", pickPlace)
            repeat(resends) { tasks.start("t1", 1, "pick_place", pickPlace) }
            return TaskMachineFixtures.drawsBetween(Seeded(3), random)
        }

        assertEquals(1, drawsAfter(0), "태스크 생성에 한 번이 아니다")
        assertEquals(drawsAfter(0), drawsAfter(3), "멱등 재전송이 난수를 더 뽑았다")
    }

    @Test
    fun `같은 시드가 같은 소요시간을 낸다`() {
        // §12.1. 다른 시드로 달라지는 것은 위 `흔들린다`가 이미 본다.
        assertEquals(durations(30, seed = 9), durations(30, seed = 9))
        assertTrue(durations(30, seed = 9) != durations(30, seed = 10), "시드가 안 닿는다")
    }

    private val location = TaskMachineFixtures.param("location", "dock-3")

    private companion object {
        /** 진행률을 재는 시점. 소요시간보다 충분히 짧아야 1.0에 붙지 않는다. */
        const val PROBE = 10.0

        /** 부동소수 되짚기의 여유. 경계값이 반올림으로 밖에 서지 않게 한다. */
        const val EPS = 1e-6
    }
}
