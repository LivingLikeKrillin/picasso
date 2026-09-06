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

    @Test
    fun `난수는 기체마다 따로다`() {
        // 하나를 공유하면 인출 순서가 비결정적이라 §12.1이 깨진다.
        val a = RobotInstance("robot-a", document, clock(), seed = 7)
        val b = RobotInstance("robot-b", document, clock(), seed = 7)
        assertTrue(a.random !== b.random)
        assertEquals(
            List(5) { a.random.fraction() },
            List(5) { b.random.fraction() },
            "같은 시드인데 수열이 다르다",
        )
    }
}
