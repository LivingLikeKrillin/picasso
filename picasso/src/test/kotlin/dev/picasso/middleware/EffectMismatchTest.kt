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
    fun `놓고 끝나야 하는 스킬이 든 채로 끝나면 미완료 파지로 판정한다`() {
        // 표 둘째 줄. 결함 통지는 «스킬이 실패했다» 까지만 말하고 적재가 어디 있는지는 말하지 않는다.
        // 효과가 있으면 그 자리를 «아직 들고 있다» 로 채울 수 있다.
        World(PRECOND).use { w ->
            val exec = w.failWhileHolding("SKILL_EXECUTION_FAILED")
            val unit = exec.units.first()

            assertEquals(HoldKind.HOLD_KIND_HOLDING, unit.hold.kind, "미믹이 실패에 파지를 비웠다 — 전제가 깨졌다")
            assertEquals(HoldMismatch.INCOMPLETE_RELEASE, unit.holdMismatch)
            assertEquals("INCOMPLETE_RELEASE", assertNotNull(w.mw.incidents().last()).effectMismatch)
        }
    }

    @Test
    fun `관측 불가면 판정하지 않는다 — 효과가 관측을 대체하지 않는다`() {
        // §5.2. 이 줄이 뚫리면 나머지 규율이 무의미해진다 — 선언을 근거로 현실을 단정하는 것이기 때문이다.
        // 같은 시나리오이고 다른 것은 파지를 볼 수 있는가뿐이다.
        World(PRECOND, port = { HoldBlindRobotPort(it) }).use { w ->
            val exec = w.failWhileHolding("SKILL_EXECUTION_FAILED")
            val unit = exec.units.first()

            assertEquals(HoldKind.HOLD_KIND_NOT_OBSERVABLE, unit.hold.kind, "관측 불가를 만들지 못했다")
            assertNull(unit.holdMismatch, "관측 없이 효과만으로 판정했다")
            assertNull(assertNotNull(w.mw.incidents().last()).effectMismatch, "번들이 근거 없는 판정을 실었다")
        }
    }

    @Test
    fun `적재 유실 통지가 온 실패는 어긋남이 아니다`() {
        // 결함 통지가 이미 답한 자리에 두 번째 판정을 적지 않는다 — 인과를 한 단위에 두 분류로 적는 일이다(§8).
        World(PRECOND).use { w ->
            val exec = w.failWhileHolding("PAYLOAD_LOST")
            val unit = exec.units.first()

            assertEquals(HoldKind.HOLD_KIND_EMPTY, unit.hold.kind)
            assertNull(unit.holdMismatch, "효과와 관측이 맞는데 어긋났다고 적었다")
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
