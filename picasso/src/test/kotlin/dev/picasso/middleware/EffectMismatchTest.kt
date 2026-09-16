package dev.picasso.middleware

import dev.picasso.capability.HoldMismatch
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 효과와 관측의 어긋남(`INCIDENT_AND_REMEDY_PROPOSAL.md` §5) — 운영자에게 «모른다» 로 나갈 자리 중
 * **근거로 판정할 수 있는 것을 판정으로 바꾼다.**
 *
 * `IN_DOUBT` 는 정직하지만 정보가 아니다. "손에 없다"·"아직 들고 있다" 는 다음 행동을 가른다 — 회수할
 * 것인지, 재파지할 것인지, 사람이 가서 볼 것인지(§5.3).
 *
 * **셋째 시험이 이 묶음의 핵심이다.** 관측이 없으면 판정하지 않는다. 선언으로 현실을 단정하면 관측 경로가
 * 죽었을 때 고장난 기체가 멀쩡해 보인다(§5.2).
 */
class EffectMismatchTest {

    private class World(profile: Path, port: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(port(ClientRobotPort(harness.client())), cell, now = { harness.clock.now() })

        fun drive(rounds: Int = 60, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        override fun close() = harness.close()
    }

    private fun rack() = JobOrder(
        jobOrderId = "SEQ-204",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 17,
        requiredEvidence = Evidence.E2,
        materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 2)),
        equipmentRequirements = listOf(
            EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
            EquipmentRequirement("RACK-204.S02", "destination", mapOf("material" to "ENGINE-COVER-A")),
            EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
        ),
    )

    private fun inspection() = JobOrder(
        jobOrderId = "PATROL-1",
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
        ),
    )

    /** 파지 중인 `pick_place` 를 결함으로 세운다. 미믹은 실패가 파지를 바꾸지 않으므로 든 채로 끝난다. */
    private fun World.failWhileHolding(errorType: String): Middleware.Execution {
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(rack(), ROBOT)).execution
        drive { exec.units.first().hold.kind != HoldKind.HOLD_KIND_UNSPECIFIED }
        forceFault(errorType, exec.units.first().taskId)
        drive { mw.incidents().isNotEmpty() }
        return exec
    }

    @Test
    fun `쥐었다가 빈손으로 끝나면 적재 유실로 판정한다`() {
        // 표 첫째 줄. 쥐는 단계까지 갔다가 놓지 못한 채 끝났으면 기대는 든 채다. 빈손이 관측되면
        // 대상이 손에 없다는 뜻이고, 그것이 «모른다» 를 대신할 수 있는 판정이다(§1.1 · §10.1 둘째 시나리오).
        World(PRECOND).use { w ->
            val exec = w.failWhileHolding("PAYLOAD_LOST")
            val unit = exec.units.first()

            assertEquals(HoldKind.HOLD_KIND_EMPTY, unit.hold.kind)
            assertEquals(HoldMismatch.PAYLOAD_LOST, unit.holdMismatch)
            assertEquals("PAYLOAD_LOST", assertNotNull(w.mw.incidents().last()).effectMismatch)
        }
    }

    @Test
    fun `든 채로 실패한 것은 어긋남이 아니다`() {
        // 쥐고 실패했는데 여전히 들고 있으면 기대와 관측이 맞는다. 정상인 것을 어긋났다고 적으면
        // 운영자가 곧 이 판정을 무시한다.
        World(PRECOND).use { w ->
            val exec = w.failWhileHolding("SKILL_EXECUTION_FAILED")
            val unit = exec.units.first()

            assertEquals(HoldKind.HOLD_KIND_HOLDING, unit.hold.kind, "미믹이 실패에 파지를 비웠다 — 전제가 깨졌다")
            assertNull(unit.holdMismatch, "든 채로 실패한 것을 어긋남으로 적었다")
        }
    }

    @Test
    fun `놓았다는데 들고 있으면 미완료 파지로 판정하고 사건을 연다`() {
        // 표 둘째 줄. 하류는 성공이라는데 손에 남아 있다 — 사건을 안 열면 이 사실이 어디에도 안 실린다.
        World(PRECOND, port = { StuckGripperRobotPort(it) }).use { w ->
            val order = rack()
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { w.cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive(rounds = 250) { w.mw.incidents().isNotEmpty() }

            val unit = exec.units.first()
            assertEquals(HoldKind.HOLD_KIND_HOLDING, unit.hold.kind, "그리퍼가 걸린 상황을 만들지 못했다")
            assertEquals(HoldMismatch.INCOMPLETE_RELEASE, unit.holdMismatch)
            assertEquals("INCOMPLETE_RELEASE", assertNotNull(w.mw.incidents().last()).effectMismatch)
        }
    }

    @Test
    fun `관측 불가면 판정하지 않는다 — 효과가 관측을 대체하지 않는다`() {
        // §5.2. 이 줄이 뚫리면 나머지 규율이 무의미해진다 — 선언을 근거로 현실을 단정하는 것이기 때문이다.
        World(PRECOND, port = { HoldBlindRobotPort(it) }).use { w ->
            val exec = w.failWhileHolding("PAYLOAD_LOST")
            val unit = exec.units.first()

            assertEquals(HoldKind.HOLD_KIND_NOT_OBSERVABLE, unit.hold.kind, "관측 불가를 만들지 못했다")
            assertNull(unit.holdMismatch, "관측 없이 효과만으로 판정했다")
            assertNull(assertNotNull(w.mw.incidents().last()).effectMismatch, "번들이 근거 없는 판정을 실었다")
        }
    }

    @Test
    fun `효과를 선언하지 않은 스킬은 판정 대상이 아니다`() {
        // 카탈로그가 말하지 않은 것을 지어내지 않는다. 이동은 파지를 바꾸지 않으므로 어긋날 것이 없다.
        World(PRECOND).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection(), ROBOT)).execution
            // 미들웨어가 RUNNING 으로 적은 뒤에도 미믹의 태스크는 잠시 ACCEPTED 다 — 결함을 받는 상태가
            // 될 때까지 밀면서 시도한다. 시각이 아니라 상태로 기다린다.
            w.drive { runCatching { w.forceFault("LOCALIZATION_LOST", exec.units.first().taskId) }.isSuccess }
            w.drive { w.mw.incidents().isNotEmpty() }

            assertNull(exec.units.first().holdMismatch)
            assertNull(assertNotNull(w.mw.incidents().last()).effectMismatch)
        }
    }

    companion object {
        const val ROBOT = "hum-02"
        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}
