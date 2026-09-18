package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.TaskState
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **한 벌을 실물로 만든다.** 읽는 쪽이 인코더 소스를 읽어 맞추면 그것은 관측된 계약이 아니라
 * 추론이고, 봉투 한 겹을 놓쳐도 아무도 모른다. 그래서 디스크에 한 벌을 놓는다.
 *
 * 같은 시나리오를 **두 번** 돌린다. 두 벌의 해시가 같고 구동 식별자만 다른 것이 `runId` 가 존재하는
 * 이유 그 자체이고, 읽는 쪽은 그 두 벌로 «두 번째 구동이 새 사건으로 들어온다» 를 회귀로 박는다.
 *
 * 산출물은 `picasso/build/export/run-1` · `run-2` 다.
 */
class ExportFixtureTest {

    private class Declared(private val rows: Map<String, Entitlement>) : Entitlements {
        override fun declaredFor(approverId: String): Entitlement? = rows[approverId]
    }

    private class World : AutoCloseable {
        val harness = Harness(mapOf(FOUND_ROBOT to PRECOND, NONE_ROBOT to NO_REMEDY, OTHER_ROBOT to PRECOND, SILENT_ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            now = { harness.clock.now() },
            // 셋째 제안이 가려진다 — 한 벌에 FOUND 와 WITHHELD 가 둘 다 있어야 읽는 쪽이 둘을 가른다.
            withholdEvery = 2,
            // 사람이 **목적지만** 적는다 — 무엇을 들었는지는 기체가 말한다(ADR 44).
            entitlements = Declared(
                mapOf(
                    AGENT.id to Entitlement(
                        AGENT.id,
                        listOf(DeclaredAction(PrepareSequencedRack.SKILL, mapOf(PrepareSequencedRack.P_DESTINATION to "RACK-204.S01"))),
                        setOf(FOUND_ROBOT),
                        FAR,
                    ),
                ),
            ),
        )

        private var racks = 0

        fun drive(rounds: Int = 120, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
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

        fun forceFault(robotId: String, errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(robotId).setErrorType(errorType).setTaskId(taskId).build(),
        )

        fun holdingRack(robotId: String): Middleware.Execution {
            val order = JobOrder(
                jobOrderId = "SEQ-${++racks}",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement("RACK-204.S0${racks}", "destination", mapOf("material" to "ENGINE-COVER-A")),
                    EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
                ),
            )
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, robotId)).execution
            drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            return exec
        }

        override fun close() = harness.close()
    }

    /**
     * 한 벌에 들어갈 것을 실제로 만든다 — 탐색 세 갈래와 승인자 두 갈래.
     *
     * **`DEPTH_LIMIT` 은 여기서 안 나온다.** 파지 값이 넷인데 효과가 내는 것은 빈손과 든 채 둘뿐이라
     * 너비 우선이 상한(세 걸음)에 닿기 전에 볼 것이 없어진다. 이 층을 통해서는 도달할 수 없는 값이다
     * (§15.170).
     */
    private fun scenario(w: World) {
        // **기체를 셋으로 가른다.** 한 기체는 태스크를 하나씩만 든다(§15.98) — 한 기체에 몰면 앞
        // 실행이 끝나기를 기다리는 동안 시나리오가 서로를 막는다.

        // ① 대안 있음 — 든 채로 점검 순회를 밀어 넣는다.
        val held = w.holdingRack(FOUND_ROBOT)
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), FOUND_ROBOT))

        // ② **든 채 그대로** 에이전트가 밖의 문으로 승인한다 — 값을 하나도 안 싣는다(ADR 44).
        //    대상의 이름은 «지금 든 것» 의 관측에서 오므로, 그 랙을 먼저 완주시키면 출처가 사라진다.
        val approved = assertIs<ApprovalOutcome.Approved>(
            w.mw.attemptApproval(
                ApprovalAttempt(AGENT, FOUND_ROBOT, "PATROL-1", listOf(PrepareSequencedRack.SKILL)),
            ),
        )
        val remedied = assertNotNull(w.mw.execution(approved.executionId))
        // 조치는 그 랙이 끝나야 자기 차례가 온다 — 기체는 태스크를 하나씩만 든다(§15.98).
        w.drive(rounds = 250) { remedied.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
        w.forceFault(FOUND_ROBOT, "PAYLOAD_LOST", remedied.units.first().taskId)
        w.drive(rounds = 250) { remedied.physicalState == PhysicalState.OPERATOR_HOLD }
        w.drive(rounds = 250) { held.physicalState.isSettled }

        // ③ 대안 없음 — 든 채로는 딛을 스킬이 없는 기체다.
        val stuck = w.holdingRack(NONE_ROBOT)
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-2"), NONE_ROBOT))
        w.drive(rounds = 250) { stuck.physicalState.isSettled }

        // ④ 가려짐 — 둘째 제안이 가릴 차례다. 그리고 이 실행의 사건은 승인자가 없다.
        val again = w.holdingRack(OTHER_ROBOT)
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-3"), OTHER_ROBOT))
        w.forceFault(OTHER_ROBOT, "PAYLOAD_LOST", again.units.first().taskId)
        w.drive(rounds = 250) { again.physicalState == PhysicalState.OPERATOR_HOLD }

        // ⑤ 안 드는 단위가 실패한다 — 파지를 바꾸지 않는 스킬의 사건도 한 벌에 있어야 한다.
        //
        //    **잔여 파지가 UNSPECIFIED 인 줄은 여기서 안 나온다.** 하달된 단위는 기체가 파지를 답하고,
        //    하달 전에 실패한 것(출발 결품)은 사건을 열지 않는다. 기본값 출력이 서는지는 빈 문자열
        //    필드로 확인한다 — 표준 printer 였으면 그 키들이 통째로 빠진다.
        val patrolled = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-4"), SILENT_ROBOT)).execution
        w.drive {
            w.mw.view(SILENT_ROBOT)?.tasks?.get(patrolled.units.first().taskId) == TaskState.TASK_STATE_RUNNING
        }
        w.forceFault(SILENT_ROBOT, "LOCALIZATION_LOST", patrolled.units.first().taskId)
        w.drive(rounds = 250) { patrolled.physicalState == PhysicalState.OPERATOR_HOLD }
    }

    private fun write(w: World, dir: Path) {
        Files.createDirectories(dir)
        val incidents = w.mw.incidents()
        val searches = w.mw.remedySearches()
        atomically(dir, LedgerExport.INCIDENTS, LedgerExport.incidents(incidents))
        atomically(dir, LedgerExport.REMEDY_SEARCHES, LedgerExport.remedySearches(searches))
        val wall = Instant.now()
        atomically(
            dir,
            LedgerExport.MANIFEST,
            LedgerExport.manifest(LedgerExport.newRunId(wall), wall, w.harness.clock.now(), incidents.size, searches.size),
        )
    }

    private fun atomically(dir: Path, name: String, body: String) {
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, body)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `같은 시나리오를 두 번 돌려 한 벌을 둘 낸다`() {
        val dirs = listOf(Path.of("build", "export", "run-1"), Path.of("build", "export", "run-2"))
        val digests = mutableListOf<List<String>>()
        val runIds = mutableListOf<String>()
        val outcomes = mutableListOf<List<String>>()
        val approvers = mutableListOf<List<String?>>()
        val lines = mutableListOf<String>()

        dirs.forEach { dir ->
            World().use { w ->
                scenario(w)
                write(w, dir)
                digests += w.mw.incidents().map { it.digest() }
                outcomes += w.mw.remedySearches().map {
                    when (it.outcome) {
                        is RemedyOutcome.Found -> "FOUND"
                        is RemedyOutcome.None -> "NONE:${(it.outcome as RemedyOutcome.None).cause.name}"
                        RemedyOutcome.Withheld -> "WITHHELD"
                    }
                }
                approvers += w.mw.incidents().map { it.approvedBy?.kind?.name }
                lines += Files.readString(dir.resolve(LedgerExport.INCIDENTS))
                runIds += Regex(""""runId":"([^"]+)"""")
                    .find(Files.readString(dir.resolve(LedgerExport.MANIFEST)))!!.groupValues[1]
            }
        }

        // 한 벌이 들어야 할 것 — 읽는 쪽의 방벽이 이것들에 선다.
        assertTrue("FOUND" in outcomes[0], "탐색 결과에 FOUND 가 없다: ${outcomes[0]}")
        assertTrue("NONE:NO_CAPABILITY" in outcomes[0], "탐색 결과에 NO_CAPABILITY 가 없다: ${outcomes[0]}")
        assertTrue("WITHHELD" in outcomes[0], "탐색 결과에 WITHHELD 가 없다: ${outcomes[0]}")
        assertTrue(null in approvers[0], "승인을 안 거친 사건이 없다: ${approvers[0]}")
        assertTrue("AGENT" in approvers[0], "에이전트가 승인한 사건이 없다: ${approvers[0]}")
        // ★**기본값 필드가 실물 줄에 남아 있다.** protobuf JSON 의 표준 설정이었으면 빈 문자열 키가
        //   통째로 빠지고, 그러면 계약 메시지 안에서 «없다» 와 «이 판이 안 낸다» 가 접힌다(§15.145).
        assertTrue(
            """"objectRef":""""" in lines[0] && """"reason":""""" in lines[0],
            "기본값 필드가 빠졌다 — protobuf JSON 의 생략 설정이 되살아났다",
        )

        // ★**두 벌의 해시가 같고 구동 식별자만 다르다.** 이것이 `runId` 가 존재하는 이유다 —
        //   읽는 쪽이 해시로만 멱등을 걸면 둘째 구동이 통째로 접힌다.
        assertEquals(digests[0], digests[1], "같은 시나리오인데 해시가 갈렸다")
        assertEquals(outcomes[0], outcomes[1], "같은 시나리오인데 탐색 결과가 갈렸다")
        assertNotEquals(runIds[0], runIds[1], "두 구동의 식별자가 같다")
    }

    companion object {
        const val FOUND_ROBOT = "hum-02"
        const val NONE_ROBOT = "hum-03"
        const val OTHER_ROBOT = "hum-04"
        const val SILENT_ROBOT = "hum-05"

        val AGENT = Approver("narrator-1", ApproverKind.AGENT)
        private val FAR: Instant = Instant.parse("2099-01-01T00:00:00Z")

        private fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
            ),
        )

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
        private val NO_REMEDY: Path = Path.of("..", "profile", "fixtures", "no-remedy.json").normalize()
    }
}
