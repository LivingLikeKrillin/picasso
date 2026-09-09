package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 시나리오 ③ — 설비 점검 순회를 **미들웨어 층에서**(보고서 7장, 17장 10번). 계획 단계 7.
 *
 * 합격 기준은 *"아무 코드도 수정하지 않는다"* 가 아니다. 새 점검 의미는 확장 지점([InspectAsset])에 더하되 공통
 * 실행 엔진에 능력·기종 분기가 퍼지지 않고 기존 두 업무(①②)의 뜻이 유지되는지, 그리고 이 기체가 다른 기체와
 * **다르게 답하는 취소·조회를 능력 명세에 드러내는지**를 본다 — 지원하지 않는 것을 지원하는 것처럼 감추지 않는다.
 *
 * 기체는 4족 픽스처 `quadruped-b`: `navigate_to` 는 취소 `NO`, `inspect` 는 취소 `YES`. 그래서 이동 중 취소와 점검 중
 * 취소의 답이 다르고, 그 차이가 `CancelReport.refusal` 에 나타난다. 하네스 `InspectionPatrolTest` 가 계약 표면에서
 * 같은 순회를 돌린다 — 여기는 그 위에서 상류의 모양(JobOrder → JobResponse)으로 본다.
 */
class InspectAssetTest {

    private data class Point(val location: String, val target: String)

    private val points = listOf(
        Point("PUMP-ROOM", "PUMP-01"),
        Point("SWITCHGEAR", "PANEL-3"),
        Point("COMPRESSOR-BAY", "COMP-2"),
    )

    private fun order(required: Evidence = Evidence.E0) = JobOrder(
        jobOrderId = "PATROL-7",
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = required,
        parameters = mapOf("shift" to "night"),
        equipmentRequirements = points.map {
            EquipmentRequirement(it.target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to it.location, EquipmentUse.PROP_ITEM to "thermal"))
        },
    )

    private class World(port: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val mw = Middleware(port(ClientRobotPort(harness.client())), now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        fun drive(rounds: Int = 120, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null
    private fun taskOf(unitId: String) = "PATROL-7#$unitId"

    @Test
    fun `지점 셋은 단위 여섯이고 태스크 여섯이다 — 공통 엔진이 새 능력을 그대로 돈다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertEquals(6, exec.units.size)
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            val expected = points.flatMap { listOf("${it.target}${InspectAsset.TRAVEL_SUFFIX}", it.target) }
            assertEquals(expected, exec.completedUnits, "순회 순서 = 목록 순서, 지점마다 이동 뒤 점검")
            assertEquals(expected.map(::taskOf), w.tasks().map { it.taskId }, "지점 셋 = 태스크 여섯")
            assertTrue(w.tasks().all { it.taskState == "SUCCEEDED" })

            // 점검은 대상을 참조할 뿐 쥐지 않는다(§15.87) — 순회 내내 빈손이다.
            assertTrue(exec.units.all { it.hold.kind == HoldKind.HOLD_KIND_EMPTY }, exec.units.map { it.hold.kind }.toString())

            val response = w.mw.pending().single()
            assertEquals(Evidence.E0, response.reachedEvidence)
            assertEquals(Evidence.E0, response.requiredEvidence)
            assertEquals(expected, response.completedUnits)
            assertTrue(response.autoResolvesInDoubt)
        }
    }

    @Test
    fun `요구 등급이 능력의 최고 등급을 넘으면 접수하지 않는다 — 확인 수단이 없으면 제공 불가`() {
        World().use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(required = Evidence.E2), ROBOT))
            assertTrue(rejected.reason.contains("E0"), rejected.reason)
            assertEquals(0, w.tasks().size)
        }
    }

    @Test
    fun `점검 결과를 실을 자리가 비어 있다 — 이 시험은 통과하는 것이 좋은 상태가 아니다`() {
        // 시나리오 ③의 결과는 *항목별 수행 상태와 측정값 또는 증거 자료 참조*다. 상태는 온다(위 시험). 측정값·증거 참조가
        // 올 자리는 계약의 partial_result 하나이고 아무도 채우지 않는다(§15.76). 채우는 쪽이 생기면 여기가 빨개진다 —
        // 그때 이 시험을 지우고 results 의 내용을 단언한다. 하네스 InspectionPatrolTest 가 계약 표면에서 같은 것을 고정한다.
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }
            assertTrue(w.mw.pending().single().results.isEmpty(), "점검 결과를 채우는 쪽이 생겼다 — 이 시험을 지우고 결과 단언을 세울 때다")
            assertTrue(exec.units.all { it.result == null })
        }
    }

    @Test
    fun `점검 중 위치를 잃으면 그 항목은 LOCALIZATION_LOST 이고, 기체가 새 태스크를 못 받는다 하니 다음 지점으로 가지 않는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }

            assertEquals("NEEDS_INTERVENTION", w.forceFault("LOCALIZATION_LOST", taskOf("PUMP-01")))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            val pump = exec.units.single { it.unitId == "PUMP-01" }
            assertEquals(UnitState.FAILED, pump.state)
            // 정준 분류가 프로파일 모드에서 유도된다 — 벤더 이름도 상태 이름도 상류에는 없다.
            assertEquals("LOCALIZATION_LOST", pump.failureClass)

            // 계약은 다음 태스크를 막지 않는다(결함은 인터록이 아니다). 판단은 이 층의 것이고 — 기체가 can_accept_new_task=false
            // 라 말하는 동안 다음 단위를 보내지 않는다. 자동으로 넘어가지도 않는다.
            assertEquals(2, w.tasks().size, "기체가 못 받는다는데 다음 지점을 보냈다")
            val held = w.mw.pending().last()
            assertEquals(PhysicalState.OPERATOR_HOLD, held.physicalState)
            assertEquals(listOf("LOCALIZATION_LOST"), held.blockedBy)
            assertTrue(held.operatorRequired)
            assertEquals(mapOf("PUMP-01" to "LOCALIZATION_LOST"), held.incompleteUnits.filterKeys { it == "PUMP-01" })

            // 사람이 조치하고 감수한다 — 결함은 기체에 그대로 있어도(계약에 지우는 표면이 없다) 같은 결함은 다시 막지 않는다.
            assertTrue(w.mw.release(exec.executionId))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(5, exec.completedUnits.size)
            assertEquals(6, w.tasks().size)
            assertEquals(setOf("LOCALIZATION_LOST[]"), exec.acknowledgedFaults, "감수한 결함이 기록에 남는다")
            assertTrue(w.mw.pending().last().blockedBy.isEmpty())
        }
    }

    @Test
    fun `결함을 못 물어보면 막지 않는다 — 없다고도 하지 않고 그 사실을 남긴다`() {
        World(port = { FaultBlindRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }
            w.forceFault("LOCALIZATION_LOST", taskOf("PUMP-01"))
            w.drive { exec.settled() }

            // 관측 실패로 현장을 세우지는 않는다 — 다음 단위가 나간다. 그러나 그 단위에 "못 물어봤다" 가 남는다.
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(6, w.tasks().size)
            val next = exec.units.single { it.unitId == "PANEL-3${InspectAsset.TRAVEL_SUFFIX}" }
            assertTrue(next.note!!.contains("not observable"), next.note)
            assertTrue(w.mw.pending().last().blockedBy.isEmpty())
        }
    }

    @Test
    fun `이동 중 취소는 하류가 거절하고 다음 경계에서 멈춘다 — 그 차이가 취소 응답에 드러난다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val travel = "PUMP-01${InspectAsset.TRAVEL_SUFFIX}"
            w.drive { w.state(taskOf(travel)) == "RUNNING" }

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.settled() }

            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals("REJECTION_CODE_CANCEL_UNSUPPORTED", report.refusal, "navigate_to 는 취소를 안 든다 — 감추면 격리가 아니다")
            assertEquals(listOf(travel), report.completedUnits, "거절된 단위는 끝까지 간다")
            assertNull(report.inProgressUnit, "중단된 단위는 없다 — 끝까지 갔다")
            assertEquals(travel, report.stoppedAfter, "그 뒤에서 멈췄다")
            assertEquals(5, report.notStartedUnits.size, "다음 경계에서 멈춘다 — 점검은 시작하지 않는다")
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.tasks().size)
            assertEquals("SUCCEEDED", w.state(taskOf(travel)))
        }
    }

    @Test
    fun `점검 중 취소는 받아들여지고 빈손으로 끝난다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.settled() }

            val report = w.mw.lastCancel(exec.executionId)!!
            assertNull(report.refusal)
            assertNull(report.stoppedAfter)
            assertEquals("done", report.cleanup)
            assertEquals("PUMP-01", report.inProgressUnit)
            assertEquals(listOf("PUMP-01${InspectAsset.TRAVEL_SUFFIX}"), report.completedUnits)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, report.residualHold.kind, "쥔 것이 없으니 복구할 것도 없다")
            assertEquals("CANCELLED", w.state(taskOf("PUMP-01")))
            assertEquals(2, w.tasks().size)
        }
    }

    @Test
    fun `살필 자리가 없는 대상은 계획에서 드러난다`() {
        World().use { w ->
            val blind = order().copy(equipmentRequirements = listOf(EquipmentRequirement("VALVE-9", EquipmentUse.INSPECTION_TARGET)))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(blind, ROBOT)).execution
            w.drive { exec.settled() }
            assertEquals(PhysicalState.FAILED, exec.physicalState)
            assertEquals(mapOf("VALVE-9" to InspectAsset.NO_LOCATION), w.mw.pending().single().incompleteUnits)
            assertEquals(0, w.tasks().size)
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
    }
}
