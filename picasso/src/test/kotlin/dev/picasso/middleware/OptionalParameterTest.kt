package dev.picasso.middleware

import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **선택 파라미터는 드는 기종에만 간다** — 시나리오 ② §4.2 의 `verify_grasp`.
 *
 * 능력이 *쓰고 싶다* 고 선언하고(`PrepareSequencedRack.preferredOptionals`), 미들웨어가 **로봇이 선언했는지**
 * 보고 붙인다. 안 드는 기종에 보내면 코어 키는 fail-closed 라(§5.3) 태스크 자체가 거절되고, 그러면 선택 필드
 * 하나 때문에 그 기종에서 이 능력을 못 쓴다.
 *
 * **그 차이가 지어낸 것이 아니다** — 이 시험의 둘째가 쓰는 `agility-digit` 프로파일이 실제로
 * `pick_place` 에 `verify_grasp` 를 선언하지 않는다(벤더 조사 결과).
 */
class OptionalParameterTest {

    /** 계약 요청에 무엇이 실렸는지 본다. 미믹의 덤프는 파라미터를 안 내므로 선에서 잡는다. */
    private class RecordingRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
        val sent = mutableListOf<Pair<String, Map<String, String>>>()

        override fun start(
            robotId: String,
            taskId: String,
            revision: Int,
            skillType: String,
            parameters: Map<String, String>,
        ): StartTaskResponse {
            sent += taskId to parameters
            return delegate.start(robotId, taskId, revision, skillType, parameters)
        }
    }

    /** 능력을 **못 물어보는** 포트. 널은 *아무것도 안 든다* 가 아니라 *모른다* 다. */
    private class CapabilityBlindPort(private val delegate: RobotPort) : RobotPort by delegate {
        override fun capabilities(robotId: String): Capability? = null
    }

    private fun order(slots: List<String> = listOf(SLOT)) = JobOrder(
        jobOrderId = "SEQ-210",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements =
        slots.map { EquipmentRequirement(it, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)) } +
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
    )

    private class World(profile: Path, wrap: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val port = RecordingRobotPort(wrap(ClientRobotPort(harness.client())))
        val mw = Middleware(port, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        fun drive(rounds: Int = 20, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(5))
                Thread.sleep(20)
            }
        }

        override fun close() = harness.close()
    }

    @Test
    fun `드는 기종에는 verify_grasp 가 실려 간다`() {
        World(MINIMAL).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT))
            w.drive { w.port.sent.isNotEmpty() }

            val parameters = w.port.sent.single().second
            assertEquals("true", parameters["verify_grasp"], "선언한 기종인데 안 실렸다: $parameters")
            // 필수는 그대로다 — 선택이 필수를 덮으면 안 된다.
            assertEquals(BIN, parameters["object_id"])
            assertEquals(SLOT, parameters["destination"])
        }
    }

    @Test
    fun `안 드는 기종에는 안 보내고, 안 보냈다는 사실을 남긴다`() {
        // ★`agility-digit` 의 `pick_place` 는 `verify_grasp` 를 선언하지 않는다 — 지어낸 픽스처가 아니라 조사 결과다.
        // **슬롯 둘이다** — 하나면 *한 번만 적는다* 를 못 본다. 단위마다 적으면 운영자가 곧 무시한다.
        World(DIGIT).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(listOf(SLOT, SLOT2)), ROBOT)).execution
            w.drive { w.port.sent.size == 2 }

            assertEquals(2, w.port.sent.size, "둘째 슬롯이 안 갔다 — 이 시험의 전제가 무너졌다")
            w.port.sent.forEach { (taskId, parameters) ->
                assertNull(parameters["verify_grasp"], "$taskId: 안 드는 기종에 보냈다 — 태스크가 통째로 거절된다")
                assertEquals(BIN, parameters["object_id"], "$taskId: 선택을 빼면서 필수까지 빠졌다")
            }

            // **조용히 빼지 않는다.** 요구했다와 못 해서 안 했다가 같아 보이면 안 된다. 그리고 **한 번만** 적는다.
            val notes = exec.eventTrail.filter { it.kind == "OPTIONAL_NOT_SENT" }
            assertEquals(1, notes.size, "되풀이해 적었거나 아예 안 적었다: $notes")
            assertTrue("verify_grasp" in notes.single().detail && "선언하지 않는다" in notes.single().detail, notes.single().detail)
        }
    }

    @Test
    fun `능력을 못 물어보면 안 붙인다 — 모름은 없음이 아니다`() {
        World(MINIMAL, wrap = { CapabilityBlindPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.port.sent.isNotEmpty() }

            assertNull(w.port.sent.single().second["verify_grasp"], "모르는 채로 붙였다")
            val note = exec.eventTrail.single { it.kind == "OPTIONAL_NOT_SENT" }
            assertTrue("못 물어봤다" in note.detail, note.detail)
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val SLOT = "RACK-210.S01"
        const val SLOT2 = "RACK-210.S02"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "PART-A"
        val MINIMAL: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        val DIGIT: Path = Path.of("..", "profile", "profiles", "agility-digit.json").normalize()
    }
}
