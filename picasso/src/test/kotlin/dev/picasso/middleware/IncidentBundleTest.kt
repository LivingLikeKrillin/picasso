package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 사건 번들(`INCIDENT_AND_REMEDY_PROPOSAL.md` §4) — 하나의 실패를 사람이 원인을 말할 수 있는
 * 최소 단위로 묶는다.
 *
 * **새로운 사실을 만들지 않는다.** 실패 분류도 차단 결함도 잔여 파지도 이미 각자의 자리에 있다.
 * 번들은 그것들을 한 시점의 한 사건으로 묶고, 거기에 그때의 근거 창을 붙인다. 거동은 바뀌지
 * 않으므로 이 시험이 보는 것은 전부 조회다.
 */
class IncidentBundleTest {

    private class World(profile: Path, port: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(port(ClientRobotPort(harness.client())), cell, now = { harness.clock.now() })

        fun drive(rounds: Int = 40, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
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

    private fun rack(slots: Map<String, String> = mapOf("S01" to "A", "S02" to "A")) = JobOrder(
        jobOrderId = "SEQ-204",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 17,
        requiredEvidence = Evidence.E2,
        materialRequirements = slots.values.groupingBy { it }.eachCount().map { (m, n) -> MaterialRequirement("ENGINE-COVER-$m", n) },
        equipmentRequirements = slots.map { (slot, m) ->
            EquipmentRequirement("RACK-204.$slot", "destination", mapOf("material" to "ENGINE-COVER-$m"))
        } + listOf(EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A"))),
    )

    private fun inspection(jobOrderId: String = "PATROL-1") = JobOrder(
        jobOrderId = jobOrderId,
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
        ),
    )

    // ── 번들이 담는 사실

    @Test
    fun `발신자의 사전 조건 거절이 주어와 함께 번들에 실린다`() {
        // 설계안 §4.2 「위반된 사전 조건」 — 기존 조건 검사의 출력을 재사용한다. 다시 계산하면
        // 두 곳의 판정이 어긋날 수 있다.
        World(MINIMAL, port = { PreconditionRefusingRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection(), ROBOT)).execution
            w.drive { exec.units.first().state == UnitState.OPERATOR_HOLD }

            val bundle = assertNotNull(w.mw.incidents().singleOrNull(), "사건이 하나로 묶이지 않았다: ${w.mw.incidents()}")
            assertEquals(exec.executionId, bundle.executionId)
            assertEquals(exec.units.first().unitId, bundle.unitId)
            assertEquals("PATROL-1", bundle.jobOrderId)
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET.name, bundle.failureClass)
            assertEquals(listOf("HOLD"), bundle.preconditionSubjects, "조건 검사의 출력이 번들에 안 실렸다")
            assertTrue(bundle.unresolved, "운영자 판단으로 선 단위인데 미결로 안 적혔다")
            assertEquals(ContractIdentity.semver, bundle.contractSemver)
            assertTrue(bundle.profileRevision >= 1, "프로파일 개정판이 없다: ${bundle.profileRevision}")
        }
    }

    @Test
    fun `적재 유실은 실패 분류와 차단 결함과 잔여 파지를 한 번들로 묶는다`() {
        // 세 축은 이미 각자 답한다(§15.145). 번들은 그 셋을 한 사건으로 묶는 것이고,
        // 그래야 운영자가 "이 단위가" 와 "이 기체가" 를 한 화면에서 잇는다.
        // 셀에 증거를 두지 않는다 — 잃은 적재는 슬롯에 없다. 슬롯이 둘인 이유는 **막는 결함이 다음 단위를
        // 재려 할 때 실리기** 때문이다. 하나뿐이면 막을 것이 없어 그 축이 영영 비고, 이 시험은 그것을 못 본다.
        World(PRECOND).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack(), ROBOT)).execution
            w.drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault("PAYLOAD_LOST", exec.units.first().taskId)
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            val bundle = assertNotNull(w.mw.incidents().lastOrNull())
            assertEquals("PAYLOAD_LOST", bundle.failureClass)
            assertEquals(listOf("PAYLOAD_LOST"), bundle.blockedBy, "실행을 막는 결함이 번들에 없다")
            assertEquals(HoldKind.HOLD_KIND_EMPTY, bundle.residualHold.kind, "잔여 파지를 «말하지 않았다» 로 접었다")
        }
    }

    @Test
    fun `번들의 근거 창에 로봇 이벤트와 설비 신호가 함께 있다`() {
        // 설계안 §4.2 「근거 창」 — "그때 무슨 일이 있었나". 로봇 이벤트만 있으면 설비가 무엇을
        // 봤는지 모르고, 설비 신호만 있으면 로봇이 무엇을 했는지 모른다.
        World(PRECOND).use { w ->
            val order = rack()
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { w.cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault("PAYLOAD_LOST", exec.units.first().taskId)
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            val kinds = assertNotNull(w.mw.incidents().lastOrNull()).evidenceWindow.map { it.kind }.toSet()
            assertTrue("TASK_TRANSITION" in kinds, "로봇 이벤트가 창에 없다: $kinds")
            assertTrue("CELL_SIGNAL" in kinds, "설비 신호가 창에 없다 — 조회하고 버렸다: $kinds")
        }
    }

    @Test
    fun `근거 창은 시간창 밖의 관측을 싣지 않는다`() {
        // 창이 실행 전체면 그것은 창이 아니라 로그다. 그리고 잘랐으면 잘랐다고 적는다 —
        // 조용히 잘리면 읽는 사람이 창을 완전한 것으로 오해한다.
        // 시계를 통째로 밀 수는 없다 — 미믹의 `pick_place` 가 45초라 태스크가 먼저 끝나고 결함을 못 받는다.
        // 대신 **앞 슬롯을 정상으로 태워** 그 관측이 창(30초) 밖으로 나가게 한 뒤, 둘째 슬롯에서 사건을 낸다.
        World(PRECOND).use { w ->
            val order = rack()
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { w.cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive(rounds = 250) { exec.units[1].hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault("PAYLOAD_LOST", exec.units[1].taskId)
            w.drive(rounds = 250) { w.mw.incidents().isNotEmpty() }

            val bundle = assertNotNull(w.mw.incidents().lastOrNull())
            val window = PrepareSequencedRack().evidenceWindow
            bundle.evidenceWindow.forEach {
                val at = Instant.parse(it.occurredAt)
                assertFalse(at.isBefore(bundle.at.minus(window.before)), "창보다 이른 관측이 실렸다: $it")
                assertFalse(at.isAfter(bundle.at.plus(window.after)), "창보다 늦은 관측이 실렸다: $it")
            }
            assertTrue(bundle.evidenceWindow.isNotEmpty(), "다 잘라 버렸다 — 창이 비면 번들이 아무것도 못 말한다")
            assertTrue(bundle.evidenceWindow.size < exec.eventTrail.size, "앞 슬롯의 관측이 창 안에 남아 있다 — 창이 아니라 로그다")
            assertTrue(bundle.windowTruncated, "잘라 놓고 잘랐다고 안 적었다")
        }
    }

    // ── 번들이 생기지 않는 자리

    @Test
    fun `접수 전에 거절된 주문은 번들을 만들지 않는다`() {
        // 설계안 §10.1 시나리오 6 — 사슬 어긋남은 접수 전 거절이라 **실행이 없다.** 없는 실행의
        // 사건을 지어내면 번들이 "무엇이 실제로 일어났나" 를 말하지 못한다.
        World(PRECOND).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack(), ROBOT)).execution
            w.drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            assertIs<Middleware.Submission.Rejected>(w.mw.submit(inspection("PATROL-9"), ROBOT))
            assertTrue(
                w.mw.incidents().none { it.jobOrderId == "PATROL-9" },
                "접수 전 거절이 번들을 만들었다: ${w.mw.incidents().map { it.jobOrderId }}",
            )
        }
    }

    // ── 결정성

    @Test
    fun `같은 시나리오를 두 번 돌리면 같은 해시다`() {
        // 설계안 §4.4 — 같은 시드와 가상 시계면 같은 번들. 해시로 대조한다.
        fun run(): String = World(MINIMAL, port = { PreconditionRefusingRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection(), ROBOT)).execution
            w.drive { exec.units.first().state == UnitState.OPERATOR_HOLD }
            assertNotNull(w.mw.incidents().singleOrNull()).digest()
        }
        assertEquals(run(), run(), "두 판의 번들이 갈렸다")
    }

    @Test
    fun `해시는 실 시계와 사후 확인을 빼고 만든다`() {
        // 제외 목록이 문서에만 있고 코드가 안 지키면 그 문서는 주장일 뿐이다.
        World(MINIMAL, port = { PreconditionRefusingRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection(), ROBOT)).execution
            w.drive { exec.units.first().state == UnitState.OPERATOR_HOLD }

            val bundle = assertNotNull(w.mw.incidents().single())
            val before = bundle.digest()
            assertEquals(before, bundle.copy(wallClockAt = bundle.wallClockAt.plusSeconds(3600)).digest(), "실 시계가 해시에 들어갔다")
            assertTrue(w.mw.confirmIncident(bundle.incidentId, "그리퍼 정렬 불량"))
            assertEquals(before, assertNotNull(w.mw.incident(bundle.incidentId)).digest(), "사후 확인이 해시를 바꿨다")
        }
    }

    @Test
    fun `사후 확인을 적으면 번들이 그것을 든다`() {
        // 설계안 §7 — 원인 지목은 가설이고 정답은 정비 실적으로 나중에 나온다. 되먹일 자리가
        // 처음부터 없으면 그 전의 사건에는 영영 자리가 없다(§9 마지막 문단).
        World(MINIMAL, port = { PreconditionRefusingRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection(), ROBOT)).execution
            w.drive { exec.units.first().state == UnitState.OPERATOR_HOLD }

            val id = assertNotNull(w.mw.incidents().single()).incidentId
            assertFalse(w.mw.confirmIncident("없는-사건", "아무것도"), "없는 사건에 확인을 적었다")
            assertTrue(w.mw.confirmIncident(id, "그리퍼 정렬 불량"))
            assertEquals("그리퍼 정렬 불량", assertNotNull(w.mw.incident(id)).postHocCause)
        }
    }

    companion object {
        const val ROBOT = "hum-02"
        private val MINIMAL: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

        /** minimal 에 `navigate_to: HOLD requires EMPTY` 와 차단하는 `PAYLOAD_LOST` 를 더한 프로파일. */
        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}
