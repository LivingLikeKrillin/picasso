package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.TaskState
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.mimic.control.v1.SetConnectionRequest
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
        val harness = Harness(
            mapOf(
                FOUND_ROBOT to PRECOND,
                NONE_ROBOT to NO_REMEDY,
                OTHER_ROBOT to VENDOR_FAULT,
                SILENT_ROBOT to PRECOND,
                UNMAPPED_ROBOT to PRECOND,
                CUT_ROBOT to PRECOND,
            ),
        )
        val cell = CellMimic(now = { harness.clock.now() })

        /** **경로가 둘이어야 책임 소재를 가르는 것이 보인다**(§15.178). 운반은 플릿의 일이다. */
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            fleet,
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

        fun setConnection(robotId: String, state: ConnectionState) = harness.oracle.setConnection(
            SetConnectionRequest.newBuilder().setRobotId(robotId).setState(state.name).build(),
        )

        fun forceFault(robotId: String, errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(robotId).setErrorType(errorType).setTaskId(taskId).build(),
        )

        /**
          * @param slots 슬롯 수. **둘이면 첫 걸음이 끝난 뒤 둘째에서 깨뜨릴 수 있다** — 한 벌의 사건이
          *   전부 «1 / N» 이면 「몇 걸음 중 어디서」가 값으로는 있어도 가려 주는 것이 없다(§15.177).
          */
        fun holdingRack(robotId: String, slots: Int = 1): Middleware.Execution {
            val first = racks + 1
            val ids = (0 until slots).map { "RACK-204.S0${racks + 1 + it}" }
            racks += slots
            val order = JobOrder(
                jobOrderId = "SEQ-$first",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", slots)),
                equipmentRequirements = ids.map {
                    EquipmentRequirement(it, "destination", mapOf("material" to "ENGINE-COVER-A"))
                } + listOf(EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A"))),
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
        //
        //    **벤더 이름공간의 결함을 쓴다**(§15.177). 정준 분류 이름을 오류 유형 자리에 그대로 넣으면
        //    벤더 원문 칸이 통째로 비고, 읽는 쪽은 «이 벤더 코드가 이 분류로 왔다» 를 짚을 수 없다 —
        //    칸을 만들어도 시나리오가 안 채우면 빈 칸이다.
        val again = w.holdingRack(OTHER_ROBOT)
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-3"), OTHER_ROBOT))
        w.forceFault(OTHER_ROBOT, "X_FIXTURE_GRIPPER_SLIP", again.units.first().taskId)
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

        // ⑥ **안 좁혀지는 사건** — 분류가 `UNCLASSIFIED` 다. 한 벌에 이것이 없으면 읽는 쪽의 «1순위
        //    원인» 지표가 언제나 맞는 답만 보게 되고, 그 지표는 아무것도 재지 않는다. 합성이라
        //    주입한 쪽이 정답을 안다.
        //    **둘째 걸음에서 깨뜨린다** — 첫 걸음이 끝난 뒤라 「몇 걸음 중 어디서」가 1 이 아니고
        //    끝난 단위 목록도 비지 않는다.
        val unmapped = w.holdingRack(UNMAPPED_ROBOT, slots = 2)  // @formatter:off
        w.drive(rounds = 400) {
            unmapped.completedUnits.isNotEmpty() && unmapped.units[1].hold.kind == HoldKind.HOLD_KIND_HOLDING
        }
        w.forceFault(UNMAPPED_ROBOT, "X_FIXTURE_SIMULATED_HARDWARE_FAULT", unmapped.units[1].taskId)
        w.drive(rounds = 250) { unmapped.physicalState.isSettled || unmapped.physicalState == PhysicalState.OPERATOR_HOLD }

        // ⑦ **플릿의 일** — 출발지에 요청한 용기가 없어 인수되지 않는다. 경로가 `FLEET` 인 사건이
        //    한 벌에 하나는 있어야 «이 실패를 누구에게 물을 것인가» 가 값으로 갈린다.
        val delivery = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliver(), NONE_ROBOT)).execution
        w.drive(rounds = 250) { delivery.physicalState.isSettled || delivery.units.first().state == UnitState.FAILED }

        // ⑧ **선이 끊긴 채 난 사건** — 그동안의 결과는 미확정이고, 같은 등급이라도 같은 값이 아니다.
        //    돌려놓지 않는다: 복구하면 봉인 시점에는 이미 거짓이라 한 벌이 그 상태를 못 보여 준다.
        val cut = w.holdingRack(CUT_ROBOT)
        w.setConnection(CUT_ROBOT, ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN)
        w.mw.pump()
        w.forceFault(CUT_ROBOT, "PAYLOAD_LOST", cut.units.first().taskId)
        w.drive(rounds = 250) { w.mw.incidents().any { it.executionId == cut.executionId } }
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
        val vendorDetails = mutableListOf<List<String>>()
        val classes = mutableListOf<List<String?>>()
        val steps = mutableListOf<List<Int>>()
        val routes = mutableListOf<List<String>>()
        val trust = mutableListOf<List<Boolean>>()
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
                vendorDetails += w.mw.incidents()
                    .flatMap { b -> (listOfNotNull(b.fault) + b.blockedBy).map { it.vendorDetail } }
                    .filter { it.isNotBlank() }
                classes += w.mw.incidents().map { it.failureClass }
                steps += w.mw.incidents().map { it.step.at }
                routes += w.mw.incidents().map { it.route }
                trust += w.mw.incidents().map { it.observation.linkBroken }
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

        // ★**칸을 만들어도 시나리오가 안 채우면 빈 칸이다**(§15.177). 벤더 원문이 실린 사건과
        //   안 좁혀진 사건이 한 벌에 각각 있어야 읽는 쪽이 그 갈래를 실물로 본다.
        assertTrue(vendorDetails[0].isNotEmpty(), "벤더 원문이 실린 결함이 한 벌에 없다")
        assertTrue("UNCLASSIFIED" in classes[0], "안 좁혀진 사건이 한 벌에 없다: ${classes[0]}")
        assertTrue(steps[0].any { it > 1 }, "전부 첫 걸음에서 깨졌다 — 「몇 걸음 중 어디서」를 가려 주는 사건이 없다: ${steps[0]}")
        assertEquals(setOf("ROBOT", "FLEET"), routes[0].toSet(), "경로가 한 갈래뿐이다 — 책임 소재를 가르는 것이 안 보인다: ${routes[0]}")
        assertTrue(trust[0].any { it }, "선이 끊긴 채 난 사건이 없다 — 관측 신뢰가 늘 온전한 것으로 보인다")
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
        const val UNMAPPED_ROBOT = "hum-06"
        const val CUT_ROBOT = "hum-07"

        val AGENT = Approver("narrator-1", ApproverKind.AGENT)
        private val FAR: Instant = Instant.parse("2099-01-01T00:00:00Z")

        /**
         * 용기 공급 하나. **출발지를 비워 둔다** — 플릿이 접수 시점에 확인하고 인수하지 않으므로
         * 경로가 `FLEET` 인 사건이 여기서 열린다.
         */
        private fun deliver() = JobOrder(
            jobOrderId = "WT-781",
            workMasterId = DeliverContainer.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement("OUT-07", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to "HU-1042")),
                EquipmentRequirement("SEQ-IN-02", EquipmentUse.DESTINATION),
            ),
        )

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

        /**
         * 벤더 이름공간의 결함 모드를 하나 더 든 픽스처.
         *
         * ★**공용 픽스처에 모드를 더하면 안 된다.** 추첨이 해당하는 모드마다 한 번씩 뽑으므로
         *   모드가 하나 늘면 같은 시드가 다른 순서를 낸다 — `FailureDraw` 가 그 자리를 적어 뒀고,
         *   실제로 `WithholdingTest` 둘이 빨개졌다.
         */
        private val VENDOR_FAULT: Path = Path.of("..", "profile", "fixtures", "vendor-fault.json").normalize()
    }
}
