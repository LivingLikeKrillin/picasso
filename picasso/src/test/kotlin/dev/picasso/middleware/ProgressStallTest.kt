package dev.picasso.middleware

import dev.picasso.contracts.v1.ProgressBasis
import dev.picasso.contracts.v1.ProgressKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **정체를 사람에게 보인다 — 잴 수 있는 기체에만.**
 *
 * 계약 0.8.0 의 `progress_basis` 를 읽는 유일한 소비자가 여기다. ADR 9 가 요구하는 것이 그것이고, 이 시험이 그
 * 소비가 **판단을 바꾼다**는 것을 보인다 — 같은 `progress = 0.3` 이 한쪽에서는 정체이고 다른 쪽에서는 아무것도
 * 아니다. 그 차이를 나르는 것이 계약의 새 필드다.
 *
 * **정체는 실패가 아니다.** 느린 것과 멈춘 것을 이 층이 못 가르므로 상태를 안 바꾸고 자취와 통보로 보이기만 한다.
 */
class ProgressStallTest {

    private fun order() = JobOrder(
        jobOrderId = "SEQ-209",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    /** 유예를 짧게 잡는다 — 기본 5 분은 미믹의 태스크가 먼저 끝난다. 값은 능력 단위 설정이다. */
    private class Impatient(private val inner: LogicalCapability) : LogicalCapability by inner {
        override val stallWindow: Duration get() = Duration.ofSeconds(10)
    }

    private class World(basis: ProgressBasis?, pinned: Double? = null) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val mw = Middleware(
            ProgressPort(ClientRobotPort(harness.client()), basis, pinned),
            capabilities = listOf(Impatient(PrepareSequencedRack())),
            now = { harness.clock.now() },
        )

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun drive(rounds: Int = 20, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(5))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
        }

        override fun close() = harness.close()
    }

    private val task = "SEQ-209#$SLOT"

    @Test
    fun `잴 수 있는데 안 움직이면 알린다 — 실패로 적지 않고`() {
        val measured = ProgressBasis.newBuilder()
            .setKind(ProgressKind.PROGRESS_KIND_MEASURED).setBasis("행동 2/7").build()

        World(measured, pinned = 0.3).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(task) == "RUNNING" }
            val unit = exec.units.first { it.taskId == task }
            assertFalse(unit.progressStalled, "시작하자마자 정체라 했다")

            w.drive { unit.progressStalled }
            assertTrue(unit.progressStalled, "진행률이 묶여 있는데 아무 말이 없다")

            // **상태는 그대로다.** 느린 것과 멈춘 것을 못 가르므로 판단은 사람의 것이다.
            assertEquals(UnitState.RUNNING, unit.state)
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            assertTrue(unit.note!!.contains("행동 2/7"), unit.note!!)
            assertTrue(exec.eventTrail.any { it.kind == "PROGRESS_STALLED" }, exec.eventTrail.toString())

            // 상류가 그것을 본다 — 통보의 미완 단위에 사정이 실린다.
            val notice = w.mw.responses().last()
            assertTrue(notice.incompleteUnits[SLOT]!!.contains("행동 2/7"), notice.incompleteUnits.toString())
        }
    }

    @Test
    fun `못 재는 기체는 정체로 판정하지 않는다`() {
        // ★이것이 계약의 새 필드가 있는 이유다. 0.0 을 정체로 읽으면 진행률을 안 내는 기종(Spot·G1)이
        // **언제나 멈춰 있는 것으로** 보이고, 그러면 이 경보가 곧 무시된다.
        val blind = ProgressBasis.newBuilder()
            .setKind(ProgressKind.PROGRESS_KIND_NOT_OBSERVABLE)
            .setReason("국면만 있다 — 취득의 열한 상태는 순서이지 분수가 아니다")
            .build()

        World(blind, pinned = 0.0).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(task) == "RUNNING" }
            val unit = exec.units.first { it.taskId == task }

            w.drive { false } // 유예를 한참 넘긴다
            assertFalse(unit.progressStalled, "못 재는 기체를 정체로 적었다")
            assertFalse(exec.eventTrail.any { it.kind == "PROGRESS_STALLED" })

            // **모른다는 사실은 남긴다** — 한 번만. 운영자가 왜 그 판정이 없는지 알아야 한다.
            val blindNotes = exec.eventTrail.filter { it.kind == "PROGRESS_NOT_OBSERVABLE" }
            assertEquals(1, blindNotes.size, "모른다를 되풀이해 적었다: $blindNotes")
            assertTrue(blindNotes.single().detail.contains("국면만 있다"), blindNotes.single().detail)
        }
    }

    @Test
    fun `옛 발신자는 잰 것으로 읽지 않는다`() {
        // `progress_basis` 를 안 싣는 발신자는 `UNSPECIFIED` 로 온다. 그것을 측정으로 읽으면
        // 0.8.0 이전 발신자 전부가 정체 경보를 받는다 — 막는 방향으로 둔다(§15.41).
        // 미믹은 언제나 자격을 싣는다(0.8.0). **옛 발신자를 만들려면 그 자리를 비워야** 한다.
        World(ProgressBasis.getDefaultInstance(), pinned = 0.3).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(task) == "RUNNING" }
            val unit = exec.units.first { it.taskId == task }
            w.drive { false }
            assertFalse(unit.progressStalled)
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val SLOT = "RACK-209.S01"
        const val BIN = "SEQ-IN-01.BIN-A"
        const val PART = "PART-A"
    }
}
