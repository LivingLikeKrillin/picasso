package dev.picasso.mimic

import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobotInstanceTest {

    private val document = TaskMachineFixtures.document()
    private fun clock() = VirtualClock(Instant.EPOCH)

    @Test
    fun `기체마다 다른 session_id를 발급한다`() {
        val a = RobotInstance("robot-a", document, clock())
        val b = RobotInstance("robot-b", document, clock())
        assertTrue(a.sessionId != b.sessionId)
    }

    @Test
    fun `재기동하면 새 session_id다`() {
        // §4.8 — 재기동하면 세션이 바뀌고 소비자는 스냅샷부터 다시 세운다.
        // **가상 시계를 EPOCH에 고정한 채로도 달라야 한다** — ULID의 난수부를
        // 시드에서 뽑으면 여기가 깨지고, UUID를 쓰면 §12.1이 깨진다.
        val first = RobotInstance("robot-a", document, clock()).sessionId
        val second = RobotInstance("robot-a", document, clock()).sessionId
        assertTrue(first != second, "같은 시각·같은 기체인데 세션이 같다")
    }

    @Test
    fun `session_id가 기체를 가리킨다`() {
        assertTrue(RobotInstance("robot-a", document, clock()).sessionId.startsWith("robot-a-"))
    }

    @Test
    fun `능력은 프로파일에서 투영된다`() {
        val instance = RobotInstance("robot-a", document, clock())
        assertEquals(document.vendor, instance.capability.vendor)
        assertEquals(document.skills.size, instance.capability.skillsCount)
    }

    /**
     * 태스크 [count]개를 한꺼번에 돌려 결말을 순서대로 모은다.
     *
     * **난수 객체를 직접 안 본다.** `random`이 `private`이기도 하지만, 그보다
     * 시험할 것이 "필드가 따로 있다"가 아니라 **"같은 시드가 같은 거동을
     * 낸다"**이기 때문이다. 필드를 보는 시험은 필드를 옮기면 깨지고, 필드를
     * 공유해도 거동이 같으면 통과한다.
     */
    private fun outcomes(seed: Long, count: Int = 200): List<String> {
        val clock = clock()
        val instance = RobotInstance("robot-a", document, clock, seed = seed)
        repeat(count) { i ->
            instance.tasks.start("t$i", 1, "pick_place", pickPlace)
        }
        instance.tasks.tick()
        clock.advance(java.time.Duration.ofSeconds(60))
        instance.tasks.tick()
        return instance.tasks.all.map { it.machine.state.name }
    }

    @Test
    fun `같은 시드가 같은 거동을 낸다`() {
        // 하나를 공유하면 인출 순서가 비결정적이라 §12.1이 깨진다.
        assertEquals(outcomes(7), outcomes(7), "같은 시드인데 결말이 다르다")
    }

    @Test
    fun `다른 시드가 다른 거동을 낸다`() {
        // 위 시험의 전제다. 추첨이 아예 안 일어나면 위가 자명하게 참이다.
        assertTrue(
            outcomes(7) != outcomes(99),
            "시드를 바꿔도 결말이 같다 — 시드가 거동에 안 닿는다",
        )
    }

    @Test
    fun `표본에 실패가 실제로 섞여 있다`() {
        // 위 둘의 전제다. 200개가 전부 SUCCEEDED면 "다르다"가 영영 참이 될 수 없고
        // "같다"는 아무것도 뜻하지 않는다.
        val states = outcomes(7).toSet()
        assertTrue(states.size >= 2, "결말이 한 가지뿐이다: $states")
        assertTrue(states.contains("SUCCEEDED"), "성공이 하나도 없다: $states")
    }

    private companion object {
        val pickPlace = listOf(
            TaskMachineFixtures.param("object_id", "b"),
            TaskMachineFixtures.param("destination", "d"),
        )
    }
}
