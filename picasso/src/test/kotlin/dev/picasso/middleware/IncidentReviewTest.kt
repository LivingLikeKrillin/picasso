package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 사후 대조 고리와 교대 종료 검토(`INCIDENT_AND_REMEDY_PROPOSAL.md` §7.2).
 *
 * **이 절은 기능이 아니라 정책이다.** 없으면 앞의 기능이 시간이 지나며 해로워진다 — 같은 분류가 오백 번
 * 나왔는데 그중 오십 번이 오판이어도 아무도 모른다. 원인 지목은 가설이고 정답은 나중에 나오므로,
 * 되먹이지 않으면 정답 라벨 없는 자동 진단이 영원히 검증되지 않는다.
 *
 * **이의율만 보면 안 된다.** 0 으로 수렴하면 완벽해진 것이 아니라 아무도 안 읽는 것이다.
 */
class IncidentReviewTest {

    private class World(profile: Path) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(StuckGripperRobotPort(ClientRobotPort(harness.client())), cell, now = { harness.clock.now() })

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

        /**
         * 사건 하나를 낸다 — **기체를 막지 않는 사건으로.**
         *
         * 적재 유실을 주입하면 `can_accept_new_task=false` 결함이 실행을 세우고 다음 주문이 못 나가,
         * 지표를 보려고 사건 둘을 만드는 길이 막힌다. 그리퍼가 안 열린 기체는 성공으로 끝나므로
         * 사건만 남기고 기체는 계속 받는다.
         */
        fun anIncident(): IncidentBundle {
            val order = JobOrder(
                jobOrderId = "SEQ-${++orders}",
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
            assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT))
            val before = mw.incidents().size
            drive(rounds = 250) { mw.incidents().size > before }
            return mw.incidents().last()
        }

        private var orders = 0

        override fun close() = harness.close()
    }

    @Test
    fun `읽기 전에는 검토가 비어 있다`() {
        World(PRECOND).use { w ->
            assertNull(w.anIncident().review, "아무도 안 읽었는데 검토가 적혀 있다")
        }
    }

    @Test
    fun `사람이 동의하거나 이의를 단다`() {
        World(PRECOND).use { w ->
            val id = w.anIncident().incidentId
            assertTrue(w.mw.reviewIncident(id, ReviewVerdict.DISPUTED, "적재 유실이 아니라 그리퍼 정렬 불량"))

            val review = assertNotNull(assertNotNull(w.mw.incident(id)).review)
            assertEquals(ReviewVerdict.DISPUTED, review.verdict)
            assertEquals("적재 유실이 아니라 그리퍼 정렬 불량", review.cause)
        }
    }

    @Test
    fun `아무도 안 읽으면 이의율이 0 이 아니라 없다`() {
        // §7.2 둘째 — 0 으로 수렴하면 완벽해진 것이 아니라 아무도 안 읽는 것이다. 0 을 돌려주면
        // 그 자리에서 «완벽하다» 로 읽히므로 타입이 그 길을 막는다.
        World(PRECOND).use { w ->
            w.anIncident()
            val metrics = w.mw.reviewMetrics()

            assertEquals(1, metrics.total)
            assertEquals(0, metrics.reviewed)
            assertNull(metrics.disputeRate, "안 읽은 채로 이의율 0 을 냈다")
            assertEquals(0.0, metrics.reviewRate, "검토율은 0 이 맞다 — 읽을 것은 있었다")
        }
    }

    @Test
    fun `읽은 것이 있으면 이의율이 검토된 것 위에서 나온다`() {
        World(PRECOND).use { w ->
            val first = w.anIncident().incidentId
            w.anIncident()
            assertTrue(w.mw.reviewIncident(first, ReviewVerdict.DISPUTED, "오판이다"))

            val metrics = w.mw.reviewMetrics()
            assertEquals(2, metrics.total)
            assertEquals(1, metrics.reviewed)
            assertEquals(0.5, metrics.reviewRate)
            assertEquals(1.0, metrics.disputeRate, "이의율의 분모가 검토된 것이 아니라 전체다")
        }
    }

    @Test
    fun `교대 단위로 볼 수 있다`() {
        // 사후에 사람을 넣는 자리는 교대 종료다. 전체 누적만 내면 «이번 교대에 무엇이 있었나» 를 못 묻는다.
        World(PRECOND).use { w ->
            val first = w.anIncident()
            val shiftStart = first.wallClockAt.plusMillis(1)
            w.anIncident()

            assertEquals(2, w.mw.reviewMetrics().total)
            assertEquals(1, w.mw.reviewMetrics(since = shiftStart).total, "교대 경계가 안 먹는다")
        }
    }

    @Test
    fun `번들이 판단 경로를 싣는다`() {
        // §7.2 셋째 — 분류만 내면 아무도 배우지 않는다. 무엇을 기대했고 무엇을 봤는지가 있어야
        // 읽는 사람이 판단 경로를 따라간다. 근거를 내는 이유는 신뢰가 아니라 학습이다.
        World(PRECOND).use { w ->
            val bundle = w.anIncident()

            assertEquals("INCOMPLETE_RELEASE", bundle.effectMismatch)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, bundle.expectedHold, "무엇을 기대했는지가 없다")
            assertEquals(HoldKind.HOLD_KIND_HOLDING, bundle.observedHold, "무엇을 봤는지가 없다")
        }
    }

    companion object {
        const val ROBOT = "hum-02"
        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}
