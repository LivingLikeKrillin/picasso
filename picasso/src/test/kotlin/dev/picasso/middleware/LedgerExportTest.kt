package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 두 대장을 밖으로 내보내는 규약(`docs/orchestration.md` §6).
 *
 * **JSON 인지 아닌지를 눈으로 안 본다.** 줄마다 계약의 JSON 파서로 다시 읽어 구조로 단언한다 —
 * 그래야 따옴표 하나가 새는 것도, 키가 조용히 빠지는 것도 같은 시험이 문다.
 */
class LedgerExportTest {

    // ── 줄을 구조로 되읽는다

    private fun parse(line: String): Map<String, Value> {
        val b = Struct.newBuilder()
        JsonFormat.parser().merge(line, b)
        return b.build().fieldsMap
    }

    private fun lines(text: String): List<Map<String, Value>> =
        text.trim().lines().filter { it.isNotBlank() }.map { parse(it) }

    // ── 탐색 결과의 세 갈래

    @Test
    fun `가려 둔 것은 가렸다고만 적고 걸음 칸 자체가 없다`() {
        // ★적재면의 방벽이다. 여기서 편의로 제안 표의 걸음을 꺼내 실으면 **조회 한 번으로 가림이
        //   풀린다.** 값이 비는 것이 아니라 키가 없는 것이 답이다.
        val line = parse(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Withheld))).trim())

        assertEquals("WITHHELD", line.getValue("outcome").stringValue)
        assertFalse("steps" in line, "가려 둔 제안의 걸음이 적재면으로 샜다: $line")
        assertFalse("cause" in line, "가린 것에 못 찾은 사유가 붙었다: $line")
    }

    @Test
    fun `대안 없음은 사유와 못 채운 조건을 함께 적는다`() {
        val outcome = RemedyOutcome.None(
            Remedy.None.Cause.DEPTH_LIMIT,
            listOf(PreconditionViolation(PreconditionSubject.PRECONDITION_SUBJECT_HOLD, HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING, "든 채다")),
        )
        val line = parse(LedgerExport.remedySearches(listOf(record(outcome))).trim())

        assertEquals("NONE", line.getValue("outcome").stringValue)
        assertEquals("DEPTH_LIMIT", line.getValue("cause").stringValue)
        val unmet = line.getValue("unmet").listValue.valuesList.single().structValue.fieldsMap
        assertEquals("HOLD_KIND_EMPTY", unmet.getValue("required").stringValue)
        assertEquals("든 채다", unmet.getValue("detail").stringValue)
    }

    @Test
    fun `대안 있음은 걸음과 그 걸음의 전제를 적는다`() {
        val step = RemedyStep(
            skillType = "pick_place",
            requires = listOf(Precondition.newBuilder().setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD).setRequires(HoldKind.HOLD_KIND_HOLDING).build()),
            expectedHold = HoldKind.HOLD_KIND_EMPTY,
            onFailureHold = HoldKind.HOLD_KIND_HOLDING,
        )
        val line = parse(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Found(listOf(step))))).trim())

        assertEquals("FOUND", line.getValue("outcome").stringValue)
        val steps = line.getValue("steps").listValue.valuesList.single().structValue.fieldsMap
        assertEquals("pick_place", steps.getValue("skillType").stringValue)
        assertEquals("HOLD_KIND_EMPTY", steps.getValue("expectedHold").stringValue)
        val requires = steps.getValue("requires").listValue.valuesList.single().structValue.fieldsMap
        assertEquals("PRECONDITION_SUBJECT_HOLD", requires.getValue("subject").stringValue)
    }

    // ── 없음을 키 누락으로 적지 않는다

    @Test
    fun `없는 값은 키를 빼지 않고 널로 적는다`() {
        // ★키를 빼면 «값이 없다» 와 «이 판이 아직 그 필드를 안 낸다» 가 같은 모양이 된다.
        val line = parse(LedgerExport.incidents(listOf(bundle())).trim())

        listOf("failureClass", "expectedHold", "effectMismatch", "review").forEach {
            assertTrue(it in line, "없는 값의 키가 빠졌다: $it")
            assertTrue(line.getValue(it).hasNullValue(), "$it 가 널이 아니다: ${line[it]}")
        }
        assertEquals(0, line.getValue("blockedBy").listValue.valuesCount)
        assertTrue("blockedBy" in line, "빈 목록의 키가 빠졌다")
    }

    @Test
    fun `말하지 않은 파지가 통째로 사라지지 않는다`() {
        // ★protobuf JSON 의 기본은 기본값 필드를 빼는 것이라, 그대로 쓰면 `HoldState` 가
        //   UNSPECIFIED 일 때 `{}` 가 된다 — **빈손과 침묵이 접힌다**(§15.145).
        val line = parse(LedgerExport.incidents(listOf(bundle())).trim())
        val hold = line.getValue("residualHold").structValue.fieldsMap

        assertEquals("HOLD_KIND_UNSPECIFIED", hold.getValue("kind").stringValue, "말하지 않은 파지가 접혔다: $hold")
        assertTrue("objectRef" in hold, "빈 문자열 필드가 빠졌다: $hold")
        assertTrue("reason" in hold, "빈 문자열 필드가 빠졌다: $hold")
    }

    @Test
    fun `사람이 적은 글이 줄을 깨지 않는다`() {
        // 진단 사유와 검토 의견은 사람이 적는다. 따옴표 하나가 새면 그 줄이 통째로 못 읽히고,
        // 읽는 쪽에는 «한 줄이 사라졌다» 로만 보인다.
        val nasty = "그리퍼에 \"부품\"이 남았다\n줄바꿈\t탭 \\역슬래시"
        val line = parse(LedgerExport.incidents(listOf(bundle(review = IncidentReview(ReviewVerdict.DISPUTED, nasty, AT)))).trim())

        assertEquals(nasty, line.getValue("review").structValue.fieldsMap.getValue("cause").stringValue)
    }

    @Test
    fun `되짚지 않고 읽을 칸이 값까지 실려 나간다`() {
        // ★★**키만 맞대는 시험은 값이 비는 것을 못 본다.** 인코더가 벤더 원문을 빈 문자열로 내도
        //   키는 그대로 있고, 인계본은 이미 찍힌 스냅샷이라 안 바뀐다 — 그 사이로 새는 자리가 있었다.
        val line = parse(LedgerExport.incidents(listOf(bundle(fault = FAULT))).trim())

        assertEquals(ROBOT, line.getValue("robotId").stringValue, "어느 기체인지가 값으로 안 나갔다")
        assertEquals("E2", line.getValue("requiredEvidence").stringValue)
        assertEquals("E0", line.getValue("reachedEvidence").stringValue)
        assertEquals("NOT_REQUESTED", line.getValue("verification").stringValue)

        val fault = line.getValue("fault").structValue.fieldsMap
        assertEquals("GRASP_FAILED", fault.getValue("failureClass").stringValue)
        assertEquals("X_FIXTURE_GRIPPER_SLIP", fault.getValue("errorType").stringValue)
        assertEquals("X_FIXTURE_GRIPPER_SLIP", fault.getValue("vendorDetail").stringValue, "벤더 원문이 값으로 안 나갔다")
        assertEquals("패드를 점검하라", fault.getValue("errorHint").stringValue)
        val reference = fault.getValue("references").listValue.getValues(0).structValue.fieldsMap
        assertEquals("KEY_SKILL_ID", reference.getValue("key").stringValue)
        assertEquals("pick_place", reference.getValue("value").stringValue)

        val step = line.getValue("step").structValue.fieldsMap
        assertEquals(2.0, step.getValue("at").numberValue)
        assertEquals(2, step.getValue("plan").listValue.valuesCount, "계획이 값으로 안 나갔다")
        assertEquals(1, step.getValue("completed").listValue.valuesCount)
    }

    @Test
    fun `번들의 해시를 함께 적는다`() {
        // 읽는 쪽이 같은 사건을 두 번 받았는지 가르는 유일한 결정적 열쇠다.
        val b = bundle()
        assertEquals(b.digest(), parse(LedgerExport.incidents(listOf(b)).trim()).getValue("digest").stringValue)
    }

    // ── 구동 식별자

    @Test
    fun `두 구동은 같은 시계에서도 다른 식별자를 갖는다`() {
        // ★★**이 값 하나만 결정적이지 않다.** 나머지는 같은 시드면 같은 값이고 그것이 재현의 근거인데,
        //   읽는 쪽이 멱등 열쇠를 그 값들로 들면 두 번째 구동이 전부 «이미 본 것» 으로 접혀
        //   **아무 신호 없이** 처리가 사라진다. 결정적으로 바꾸면 그 고장이 조용히 돌아온다.
        assertNotEquals(LedgerExport.newRunId(AT), LedgerExport.newRunId(AT))
    }

    @Test
    fun `한 벌의 명세가 구동 식별자와 개수를 싣는다`() {
        val runId = LedgerExport.newRunId(AT)
        val m = parse(LedgerExport.manifest(runId, AT, AT.plusSeconds(5), incidents = 2, remedySearches = 3))

        assertEquals(LedgerExport.SCHEMA_VERSION, m.getValue("schemaVersion").stringValue)
        assertEquals(runId, m.getValue("runId").stringValue)
        assertEquals(AT.plusSeconds(5).toString(), m.getValue("virtualNow").stringValue)
        val counts = m.getValue("counts").structValue.fieldsMap
        assertEquals(2.0, counts.getValue("incidents").numberValue)
        assertEquals(3.0, counts.getValue("remedySearches").numberValue)
    }

    // ── 한 벌을 실제로 내보낸다

    @Test
    fun `시나리오를 돌리면 세 파일이 한 벌로 나온다`() {
        // 사건 하나와 탐색 하나를 만들어 내보낸다. **두 대장을 합치지 않는다** — 읽는 쪽이 갈래를
        // 다시 나눠야 하고, 그러면 그 분류가 두 곳에 생긴다.
        World().use { w ->
            val order = rack()
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { w.cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            // 든 채로 점검 순회를 밀어 넣는다 — 탐색이 여기서 돈다. **둘을 넣는 이유는 순서다** —
            // 하나뿐이면 순서를 뒤집어도 같은 값이라 그 단언이 아무것도 안 문다.
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-2"), ROBOT))
            // 적재를 잃는다 — 사건이 여기서 열린다.
            w.forceFault("PAYLOAD_LOST", exec.units.first().taskId)
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            val dir = w.export()

            val manifest = parse(Files.readString(dir.resolve(LedgerExport.MANIFEST)))
            val incidents = lines(Files.readString(dir.resolve(LedgerExport.INCIDENTS)))
            val searches = lines(Files.readString(dir.resolve(LedgerExport.REMEDY_SEARCHES)))

            assertTrue(incidents.isNotEmpty(), "사건이 안 나왔다")
            assertTrue(searches.isNotEmpty(), "탐색 결과가 안 나왔다")
            assertEquals(incidents.size.toDouble(), manifest.getValue("counts").structValue.fieldsMap.getValue("incidents").numberValue)
            assertEquals(searches.size.toDouble(), manifest.getValue("counts").structValue.fieldsMap.getValue("remedySearches").numberValue)
            assertTrue(manifest.getValue("runId").stringValue.isNotBlank(), "구동 식별자가 비었다")

            // 순서가 보증된다. 읽는 쪽은 식별자를 뜯지 않고 이 순서에 기댄다.
            assertContentEquals(w.mw.incidents().map { it.incidentId }, incidents.map { it.getValue("incidentId").stringValue })
            assertContentEquals(w.mw.remedySearches().map { it.searchId }, searches.map { it.getValue("searchId").stringValue })
        }
    }

    // ── 재료

    private fun record(outcome: RemedyOutcome) =
        RemedySearchRecord("search-1", ROBOT, "PATROL-1", AT, AT, outcome)

    private val FAULT = FaultDetail(
        failureClass = "GRASP_FAILED",
        errorType = "X_FIXTURE_GRIPPER_SLIP",
        vendorDetail = "X_FIXTURE_GRIPPER_SLIP",
        errorHint = "패드를 점검하라",
        references = listOf(FaultReference("KEY_SKILL_ID", "pick_place")),
        canContinueCurrentTask = false,
        canAcceptNewTask = false,
        activeUntilKind = "KIND_UNTIL_CLEARED",
        activeUntilTime = "",
    )

    private fun bundle(review: IncidentReview? = null, fault: FaultDetail? = null) = IncidentBundle(
        incidentId = "incident-1",
        jobOrderId = "SEQ-204",
        executionId = "exec-1",
        robotId = ROBOT,
        unitId = "RACK-204.S01",
        at = AT,
        wallClockAt = AT,
        failureClass = fault?.failureClass,
        fault = fault,
        blockedBy = emptyList(),
        residualHold = HoldState.getDefaultInstance(),
        unresolved = false,
        preconditionSubjects = emptyList(),
        evidenceWindow = emptyList(),
        windowTruncated = false,
        expectedHold = null,
        observedHold = HoldKind.HOLD_KIND_EMPTY,
        effectMismatch = null,
        requiredEvidence = Evidence.E2,
        reachedEvidence = Evidence.E0,
        verification = Verification.NOT_REQUESTED,
        step = StepPosition(2, listOf("RACK-204.S01", "RACK-204.S02"), listOf("RACK-204.S01")),
        profileRevision = 1,
        contractSemver = "0.9.0",
        review = review,
    )

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

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

        /**
         * 두 대장을 파일로 낸다 — **임시 이름으로 쓰고 이름만 바꾼다.** 반쯤 쓰인 파일을 읽는 쪽이
         * 집어 가면 그것은 한 벌이 아니라 파편이고, 파편과 빈 한 벌은 같은 모양이다.
         * 명세는 맨 마지막이다. 그것이 나타나는 것이 «다 나왔다» 는 신호다.
         */
        fun export(dir: Path = Path.of("build", "export")): Path {
            Files.createDirectories(dir)
            val incidents = mw.incidents()
            val searches = mw.remedySearches()
            atomically(dir, LedgerExport.INCIDENTS, LedgerExport.incidents(incidents))
            atomically(dir, LedgerExport.REMEDY_SEARCHES, LedgerExport.remedySearches(searches))
            val wall = Instant.now()
            atomically(
                dir,
                LedgerExport.MANIFEST,
                LedgerExport.manifest(LedgerExport.newRunId(wall), wall, harness.clock.now(), incidents.size, searches.size),
            )
            return dir
        }

        private fun atomically(dir: Path, name: String, body: String) {
            val tmp = dir.resolve("$name.tmp")
            Files.writeString(tmp, body)
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

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

    private fun patrol(jobOrderId: String) = JobOrder(
        jobOrderId = jobOrderId,
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
        ),
    )

    companion object {
        const val ROBOT = "hum-02"
        private val AT: Instant = Instant.parse("2026-09-05T00:03:21Z")
        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}
