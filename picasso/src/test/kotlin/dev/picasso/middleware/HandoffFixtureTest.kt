package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.wire.ContractIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `handoff/narrator/` 에 **커밋된 한 벌이 지금의 인코더와 같은 칸을 쓰는가.**
 *
 * ## 스냅샷은 낡는다
 *
 * 인계 지점의 한 벌은 한 번 찍은 것이라, 인코더가 칸을 늘리거나 줄여도 그대로 남는다. 그러면 받는
 * 쪽은 **없는 계약에 맞춘 수신기**를 들고 있게 되고, 그 어긋남은 실물을 다시 받는 날까지 안 보인다.
 * 그래서 커밋된 한 벌을 지금의 인코더로 다시 재서 **칸이 같은지**만 본다.
 *
 * **값은 안 본다.** `runId` 와 `wallClockAt` 은 구동마다 다르고 그것이 설계다 — 값을 고정하면 이
 * 시험이 그 설계와 싸운다. 보는 것은 **키의 집합**과 판 표기뿐이다.
 */
class HandoffFixtureTest {

    private fun parse(line: String): Map<String, Value> {
        val b = Struct.newBuilder()
        JsonFormat.parser().merge(line, b)
        return b.build().fieldsMap
    }

    private fun lines(run: String, name: String): List<String> =
        Files.readString(HANDOFF.resolve(run).resolve(name)).trim().lines().filter { it.isNotBlank() }

    private fun keys(line: String): Set<String> = parse(line).keys

    // ── 커밋된 한 벌이 지금의 인코더와 같은 칸을 쓴다

    @Test
    fun `인계한 사건 줄이 지금 인코더와 같은 칸을 쓴다`() {
        val expected = keys(LedgerExport.incidents(listOf(bundle())).trim())
        RUNS.forEach { run ->
            lines(run, LedgerExport.INCIDENTS).forEachIndexed { at, line ->
                assertEquals(expected, keys(line), "$run 의 ${at + 1}번째 사건 줄이 지금의 칸과 다르다")
            }
        }
    }

    @Test
    fun `인계한 탐색 줄이 갈래마다 지금 인코더와 같은 칸을 쓴다`() {
        val expected = mapOf(
            "FOUND" to keys(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Found(listOf(STEP))))).trim()),
            "NONE" to keys(LedgerExport.remedySearches(listOf(record(NONE))).trim()),
            "WITHHELD" to keys(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Withheld))).trim()),
        )
        RUNS.forEach { run ->
            lines(run, LedgerExport.REMEDY_SEARCHES).forEach { line ->
                val tag = parse(line).getValue("outcome").stringValue
                assertEquals(expected.getValue(tag), keys(line), "$run 의 $tag 줄이 지금의 칸과 다르다")
            }
        }
    }

    @Test
    fun `인계한 명세가 지금의 판과 계약 버전을 댄다`() {
        // 판이 올라갔는데 인계한 한 벌이 옛 판이면, 받는 쪽은 멈추는 대신 조용히 옛 규약으로 읽는다.
        RUNS.forEach { run ->
            val m = parse(Files.readString(HANDOFF.resolve(run).resolve(LedgerExport.MANIFEST)))
            assertEquals(LedgerExport.SCHEMA_VERSION, m.getValue("schemaVersion").stringValue, "$run 의 판이 낡았다")
            assertEquals(ContractIdentity.semver, m.getValue("contractSemver").stringValue, "$run 의 계약 버전이 낡았다")
        }
    }

    // ── 두 벌이 있는 이유

    @Test
    fun `두 벌의 해시가 같고 구동 식별자만 다르다`() {
        // ★이것이 `runId` 가 존재하는 이유다. 받는 쪽이 이 두 벌로 «둘째 구동이 새 사건으로 들어온다» 를
        //   회귀로 박으므로, 두 벌이 이 성질을 잃으면 그 회귀가 아무것도 안 막는다.
        val digests = RUNS.map { run ->
            lines(run, LedgerExport.INCIDENTS).map { parse(it).getValue("digest").stringValue }
        }
        val runIds = RUNS.map { run ->
            parse(Files.readString(HANDOFF.resolve(run).resolve(LedgerExport.MANIFEST))).getValue("runId").stringValue
        }

        assertTrue(digests[0].isNotEmpty(), "인계한 한 벌에 사건이 없다")
        assertEquals(digests[0], digests[1], "두 벌의 해시가 갈렸다 — 같은 시드의 두 구동이 아니다")
        assertNotEquals(runIds[0], runIds[1], "두 벌의 구동 식별자가 같다")
    }

    @Test
    fun `인계한 한 벌이 탐색 세 갈래와 승인자 두 갈래를 든다`() {
        // 받는 쪽의 방벽이 이것들에 선다. 하나라도 빠지면 그쪽 시험이 전제부터 빈다.
        val outcomes = lines("run-1", LedgerExport.REMEDY_SEARCHES).map { parse(it).getValue("outcome").stringValue }
        assertEquals(setOf("FOUND", "NONE", "WITHHELD"), outcomes.toSet(), "탐색 갈래가 빠졌다: $outcomes")

        val approvers = lines("run-1", LedgerExport.INCIDENTS).map { parse(it).getValue("approvedBy") }
        assertTrue(approvers.any { it.hasNullValue() }, "승인을 안 거친 사건이 없다")
        assertTrue(approvers.any { it.hasStructValue() }, "승인을 거친 사건이 없다")
    }

    companion object {
        private val HANDOFF: Path = Path.of("..", "handoff", "narrator").normalize()
        private val RUNS = listOf("run-1", "run-2")
        private val AT: Instant = Instant.parse("2026-09-05T00:03:21Z")

        private val STEP = RemedyStep("pick_place", emptyList(), HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING)

        private val NONE = RemedyOutcome.None(
            Remedy.None.Cause.NO_CAPABILITY,
            listOf(
                PreconditionViolation(
                    PreconditionSubject.PRECONDITION_SUBJECT_HOLD,
                    HoldKind.HOLD_KIND_EMPTY,
                    HoldKind.HOLD_KIND_HOLDING,
                    "든 채다",
                ),
            ),
        )

        private fun record(outcome: RemedyOutcome) =
            RemedySearchRecord("search-1", "hum-02", "PATROL-1", AT, AT, outcome)

        private fun bundle() = IncidentBundle(
            incidentId = "incident-1",
            jobOrderId = "SEQ-204",
            executionId = "exec-1",
            unitId = "RACK-204.S01",
            at = AT,
            wallClockAt = AT,
            failureClass = null,
            blockedBy = emptyList(),
            residualHold = HoldState.getDefaultInstance(),
            unresolved = false,
            preconditionSubjects = emptyList(),
            evidenceWindow = emptyList(),
            windowTruncated = false,
            expectedHold = null,
            observedHold = HoldKind.HOLD_KIND_EMPTY,
            effectMismatch = null,
            profileRevision = 1,
            contractSemver = ContractIdentity.semver,
            approvedBy = null,
            review = null,
        )
    }
}
