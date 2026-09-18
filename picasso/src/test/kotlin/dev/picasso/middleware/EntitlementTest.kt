package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 자동 승인 자격 — **밖에서 선언되고 안에서 판정된다**(ADR 43 · §15.165).
 *
 * 계산된 자격은 계산이 틀리면 조용히 넓어진다. 선언된 자격은 사람의 행위이므로 감사 가능하고 만료를
 * 걸 수 있다. 런타임이 하는 일은 **선언 목록에 있는가** 를 보는 것뿐이고, 좁히는 방향으로만 작동한다.
 */
class EntitlementTest {

    /** 시험이 선언 목록을 든다. 값이 어디에 사는지는 이 층이 모른다 — 배치의 결정이다. */
    private class Declared(private val rows: Map<String, Entitlement>) : Entitlements {
        override fun declaredFor(approverId: String): Entitlement? = rows[approverId]
    }

    private class World(
        entitlements: Entitlements = Entitlements.None,
        withholdEvery: Int = 0,
    ) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            now = { harness.clock.now() },
            withholdEvery = withholdEvery,
            entitlements = entitlements,
        )

        private var racks = 0

        fun drive(rounds: Int = 80, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
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

        /** 든 채로 도는 랙 하나. 여기서 든 것이 점검 순회를 막는다. */
        fun holdingRack(): Middleware.Execution {
            val order = JobOrder(
                jobOrderId = "SEQ-${++racks}",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
                    EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
                ),
            )
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
            drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            return exec
        }

        /**
         * 든 채로 점검 순회를 밀어 넣어 제안을 하나 세운다.
         *
         * **돌려주는 것이 널일 수 있다** — 가릴 차례면 제안이 조회로 안 보인다. 그것이 가림의 성질이고,
         * 여기서 널이 아니라고 단정하면 가림을 쓰는 시험이 전제부터 틀린다.
         */
        fun standingProposal(jobOrderId: String = "PATROL-1"): Remedy.Found? {
            val exec = holdingRack()
            assertIs<Middleware.Submission.Rejected>(mw.submit(patrol(jobOrderId), ROBOT))
            drive(rounds = 250) { exec.physicalState.isSettled }
            return mw.proposal(ROBOT, jobOrderId)
        }

        override fun close() = harness.close()
    }

    // ── 사람과 에이전트가 다르다

    @Test
    fun `사람은 선언 없이 승인한다`() {
        // ★선언 목록은 «사람 대신» 누르는 것을 허락하는 자리다. 사람까지 대조하면 그 목록이 운영자
        //   명부가 되고, 한 명 빠진 날 라인이 선다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), OPERATOR))
        }
    }

    @Test
    fun `선언이 없으면 에이전트 승인은 거절된다`() {
        // ★deny by default(ADR 42). 아무것도 선언 안 한 배치에서 자동 승인은 하나도 안 통과한다 —
        //   그러나 사람이 누를 수 있으므로 라인은 서지 않는다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            val answer = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
            assertTrue("선언돼 있지 않다" in answer.reason, answer.reason)
        }
    }

    @Test
    fun `선언된 에이전트는 승인한다`() {
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
        }
    }

    // ── 선언이 좁히는 네 축

    @Test
    fun `만료된 선언으로는 승인하지 못한다`() {
        // ★만료 없는 선언은 다시 들여다볼 일이 없다. 그러면 프로파일이 바뀌어도 그 자격이 그대로 남는다.
        World(Declared(mapOf(AGENT.id to full(expiresAt = Instant.parse("2020-01-01T00:00:00Z"))))).use { w ->
            w.standingProposal()
            val answer = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
            assertTrue("만료됐다" in answer.reason, answer.reason)
        }
    }

    @Test
    fun `범위 밖 기체는 승인하지 못한다`() {
        World(Declared(mapOf(AGENT.id to full(robotIds = setOf("다른-기체"))))).use { w ->
            w.standingProposal()
            val answer = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
            assertTrue("범위 밖 기체다" in answer.reason, answer.reason)
        }
    }

    @Test
    fun `선언이 안 덮는 조치 유형은 승인하지 못한다`() {
        World(Declared(mapOf(AGENT.id to full(skillTypes = setOf("다른_스킬"))))).use { w ->
            val proposal = assertNotNull(w.standingProposal())
            val answer = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
            assertTrue("안 덮는 조치 유형이다" in answer.reason, answer.reason)
            assertTrue(proposal.steps.first().skillType in answer.reason, answer.reason)
        }
    }

    @Test
    fun `빈 조치 유형 집합은 전부가 아니라 아무것도 아니다`() {
        // ★빈 집합을 «전부» 로 읽으면 선언을 반쯤 적은 배치가 무제한 자격을 얻는다. 넓히려면 적어야 한다.
        // ★**다른 축은 채워 둔다** — 앞 관문이 먼저 막으면 이 시험이 무엇을 보는지 알 수 없다.
        World(Declared(mapOf(AGENT.id to full(skillTypes = emptySet())))).use { w ->
            w.standingProposal()
            val answer = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
            assertTrue("안 덮는 조치 유형이다" in answer.reason, answer.reason)
        }
    }

    @Test
    fun `빈 기체 집합도 전부가 아니라 아무것도 아니다`() {
        World(Declared(mapOf(AGENT.id to full(robotIds = emptySet())))).use { w ->
            w.standingProposal()
            val answer = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
            assertTrue("범위 밖 기체다" in answer.reason, answer.reason)
        }
    }

    @Test
    fun `가려 둔 제안은 자격이 있어도 승인하지 못한다`() {
        // 자격은 «누를 수 있는가» 이고 가림은 «지금 누를 것인가» 다. 자격이 가림을 뚫으면
        // 의도적 비자동화가 자동 승인 앞에서만 무의미해진다.
        World(Declared(mapOf(AGENT.id to full())), withholdEvery = 1).use { w ->
            assertNull(w.standingProposal(), "가릴 차례인데 제안이 조회로 보인다 — 이 시험의 전제가 틀렸다")
            assertTrue(w.mw.withheldProposal(ROBOT, "PATROL-1"))
            val answer = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))
            assertTrue("사람이 먼저 진단해야 한다" in answer.reason, answer.reason)
        }
    }

    // ── 거절이 사람의 길을 막지 않는다

    @Test
    fun `에이전트가 거절돼도 제안은 사람에게 남는다`() {
        // ★거부가 제안을 소모하면 «사람에게 남는다» 가 성립하지 않는다 — 자격이 없다는 이유로
        //   사람까지 누를 것을 잃는다. 자격은 좁히기만 하지 없애지 않는다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))

            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "에이전트 거절이 제안을 소모했다")
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), OPERATOR))
        }
    }

    @Test
    fun `선언이 만료돼도 사람 승인으로 복귀한다`() {
        // ★만료가 라인을 세우면 아무도 만료를 짧게 걸지 않는다. 내려오는 길이 있어야 비대칭이 유지된다.
        World(Declared(mapOf(AGENT.id to full(expiresAt = Instant.parse("2020-01-01T00:00:00Z"))))).use { w ->
            w.standingProposal()
            assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT))

            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), OPERATOR))
        }
    }

    // ── 승인자가 기록으로 남는다

    @Test
    fun `승인으로 시작한 실행의 사건이 누가 눌렀는지 싣는다`() {
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), AGENT)).execution
            w.drive(rounds = 250) { exec.physicalState.isSettled }

            val approved = w.mw.incidents().mapNotNull { it.approvedBy }
            assertTrue(approved.isNotEmpty(), "승인으로 시작한 실행의 사건에 승인자가 안 실렸다: ${w.mw.incidents().size}건")
            assertEquals(ApproverKind.AGENT, approved.first().kind)
            assertEquals(AGENT.id, approved.first().id)
        }
    }

    @Test
    fun `승인을 안 거친 사건에는 승인자가 없다`() {
        // 승인과 무관한 사건을 사람 쪽 통에 몰아 넣으면 사람 승인의 이의율이 그만큼 희석된다.
        World(Entitlements.None).use { w ->
            val exec = w.holdingRack()
            w.forceFault("PAYLOAD_LOST", exec.units.first().taskId)
            w.drive(rounds = 250) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            // ★전제가 비면 이 단언은 공짜로 통과한다. 사건이 실제로 있어야 «어느 통에도 안 들어간다» 가 말이 된다.
            assertTrue(w.mw.incidents().isNotEmpty(), "사건이 하나도 안 났다 — 이 시험의 전제가 비었다")
            assertTrue(w.mw.incidents().all { it.approvedBy == null }, "승인 없이 난 사건에 승인자가 붙었다")
            assertEquals(emptyMap(), w.mw.reviewMetricsByApprover())
        }
    }

    @Test
    fun `이의율은 승인자 종류로 갈라 잰다`() {
        // ★재는 대상이 **판단 주체의 성능**이다. 에이전트 승인이 그 시험 대상이므로 한 통에 담으면
        //   사람 승인이 그것을 희석한다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal("PATROL-1")
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol("PATROL-1"), ROBOT, listOf(PLACE), AGENT)).execution
            w.drive(rounds = 250) { exec.physicalState.isSettled }

            val opened = w.mw.incidents().filter { it.approvedBy != null }
            assertTrue(opened.isNotEmpty(), "승인으로 시작한 사건이 없다")
            assertTrue(w.mw.reviewIncident(opened.first().incidentId, ReviewVerdict.DISPUTED, "사람 쪽 판단"))

            val byKind = w.mw.reviewMetricsByApprover()
            val agent = assertNotNull(byKind[ApproverKind.AGENT], "에이전트 통이 없다: $byKind")
            assertEquals(1, agent.disputed)
            assertEquals(1.0, agent.disputeRate)
            assertNull(byKind[ApproverKind.PERSON], "사람 승인이 없는데 통이 생겼다")
        }
    }

    @Test
    fun `반복 카운터는 승인자 종류로 가르지 않는다`() {
        // ★★**가를지는 재는 대상이 자원인지 주체인지로 갈린다**(ADR 43 §4). 이 수가 재는 것은 자원의
        //   상태 — 같은 조치가 몇 번 반복됐나 — 이고 근본 원인은 누가 눌렀는지 모른다. 가르면 사람
        //   한 번과 에이전트 한 번이 둘이 아니라 하나와 하나가 되어 한도에 안 걸린다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal("PATROL-A")
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol("PATROL-A"), ROBOT, listOf(PLACE), OPERATOR))
            w.standingProposal("PATROL-B")
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol("PATROL-B"), ROBOT, listOf(PLACE), AGENT))

            val repeated = assertNotNull(w.mw.repeatedRemedies().singleOrNull(), "한 통이 아니다: ${w.mw.repeatedRemedies()}")
            assertEquals(2, repeated.approvals, "사람 승인과 에이전트 승인이 다른 통에 쌓였다")
        }
    }

    // ── 순수 판정

    @Test
    fun `다른 쪽이 승인한 같은 모양의 사건은 다른 사건이다`() {
        // 누가 눌렀는지가 이 사건에 대해 할 말을 바꾼다. 해시가 같으면 읽는 쪽이 둘을 한 사건으로 접는다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(patrol(), ROBOT, listOf(PLACE), OPERATOR)).execution
            w.drive(rounds = 250) { exec.physicalState.isSettled }

            val bundle = assertNotNull(w.mw.incidents().firstOrNull { it.approvedBy != null })
            assertEquals(bundle.digest(), bundle.copy().digest())
            assertTrue(bundle.digest() != bundle.copy(approvedBy = AGENT).digest(), "승인자가 해시에 안 들어갔다")
        }
    }

    @Test
    fun `아무것도 선언 안 한 목록은 누구에게도 자격을 안 준다`() {
        assertNull(Entitlements.None.declaredFor("아무나"))
    }

    companion object {
        const val ROBOT = "hum-02"

        /** 사람이 누른다 — 선언 목록을 안 본다. */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        /** 에이전트가 누른다 — 선언 목록에 있어야 한다. */
        val AGENT = Approver("narrator-1", ApproverKind.AGENT)

        private val PLACE = mapOf(
            PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A",
            PrepareSequencedRack.P_DESTINATION to "RACK-204.S01",
        )

        private fun full(
            skillTypes: Set<String> = setOf(PrepareSequencedRack.SKILL),
            robotIds: Set<String> = setOf(ROBOT),
            expiresAt: Instant = Instant.parse("2099-01-01T00:00:00Z"),
        ) = Entitlement("narrator-1", skillTypes, robotIds, expiresAt)

        private fun patrol(jobOrderId: String = "PATROL-1") = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
            ),
        )

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}
