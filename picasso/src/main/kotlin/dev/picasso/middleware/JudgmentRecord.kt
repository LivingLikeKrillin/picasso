package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * **판단 기록**(§15.221, ADR 51 결정 3) — 실행 하나에서 이미 정해진 것. 이 층이 내린 판정과 사람이 한 일이다.
 *
 * 담는 쪽이 [Middleware.judgmentRecord] 로 받아 영속하고, 재기동 뒤 [Middleware.resume] 에 넘긴다. 그러면 다시 지은 실행이
 * 그 사실 위에서 이어 간다. 이 층은 영속하지 않는다 — 저장소와 그 가용성은 담는 쪽의 전제다(ADR 51 «미룬 것»).
 *
 * **사실만 든다. 다시 짓기는 옮기기만 하고 다시 판정하지 않는다.** 판정을 다시 하면 그 사이 바뀐 설비 값과 기체 상태로
 * 같은 단위가 다른 답을 받는다 — 이전 인스턴스가 확인한 완료가 불일치가 되고, 사람이 확인한 단위가 다시 보류에 선다.
 *
 * 드는 것은 한계 §15.213 이 «넘어오지 않는다» 고 적은 여섯이다(그 행은 §15.221 에서 닫혔다).
 *
 * 1. 운영자 판단 — [UnitRecord.decision]. 판단이 낸 결과(확인한 완료, 재작업의 시도 번호)는 [UnitRecord.verdict] 와
 *    [UnitRecord.attempt] 가 든다. 판단 자체는 누가 언제 무엇을 했는지의 기록이다.
 * 2. 감수한 결함 — [acknowledgedFaults]. 결함의 열쇠(모드 이름과 참조)다.
 * 3. 승인 소모 기록 — [approval]. 조치 열의 걸음과 값까지 든다. 다시 지을 때 그 걸음이 계획 앞에 다시 선다.
 * 4. 근거 윈도우의 요청 시각 — [UnitRecord.requestedAt]. 미확정 관측 창과 유예의 기준이다.
 * 5. 설비 대기의 기한 시작 시각 — 설비 대기 단위의 [UnitRecord.requestedAt]. 같은 칸이다. 대기 단위는 요청을 보내지 않으므로
 *    시작 시각이 곧 그 칸이고([Middleware] 의 `startUnit`), 두 칸으로 나누면 한쪽만 옮겨지는 날이 온다.
 * 6. 이전 근거 확인 결과 — [UnitRecord.verdict] 와 검증 대기 중이던 단위의 [UnitRecord.verifyWindow].
 *
 * **들지 않는 것**: 사건 번들, 조치 탐색 대장, 같은 조치의 승인 횟수, 결과 통보 아웃박스, 실행 자취, 파지 관측, 진행률 정체 표시,
 * 미확정 조회 횟수. 앞의 넷은 이 층의 다른 장부이고 저마다의 적재가 있거나(§15.166) 이 기록의 소비자가 없다(ADR 9). 뒤의 넷은
 * 판단이 아니라 관측이다 — 다시 지은 실행이 기체에서 다시 관측한다. 종료한 단위의 파지는 정보이지 판정이 아니다(§15.146).
 * 인시던트 번들은 옮기지 않지만, 운영자 보류로 옮긴 단위의 인시던트는 다시 지은 인스턴스가 새로 연다 — 사람이 판단할 자리가
 * 그것이기 때문이다. 이전 인스턴스의 번들 자체(그때의 근거 윈도우와 결함 원문)는 넘어오지 않는다.
 *
 * **세대 칸이 없다**(ADR 51 «정의»). 판단과 결정 기록은 기대 상태 버전만 든다 — 여기서는 [orderVersion] 과 단위마다의
 * [UnitRecord.attempt] 다. 세대는 결정을 적용하는 쪽의 기록에 있다.
 *
 * @param orderVersion 기록이 선 작업 지시 버전. [Middleware.resume] 에 넘긴 작업 지시와 같아야 한다.
 * @param acknowledgedFaults 운영자가 [Middleware.release] 로 감수한 결함의 열쇠.
 * @param approval 이 실행이 승인된 조치로 섰다면 그 소모. 아니면 널.
 * @param units 실행의 단위 전부, 계획 순서대로.
 */
data class JudgmentRecord(
    val jobOrderId: String,
    val orderVersion: Int,
    val acknowledgedFaults: List<String>,
    val approval: RecordedApproval?,
    val units: List<UnitRecord>,
)

/**
 * 이 실행을 세운 승인의 소모(ADR 46) — [ConsumedApproval] 에서 실행 id 를 뺀 것과 제안의 열쇠가 된 기체.
 *
 * 실행 id 를 들지 않는 것은 그 값이 인스턴스 안의 셈이기 때문이다(ADR 48). 다시 지으면 새 실행 id 가 붙고, 승인 장부는 그
 * 새 id 로 소모를 단다.
 *
 * @param robotId 제안의 열쇠가 된 기체. 승인 장부는 (기체, 작업 지시)로 소모를 단다.
 * @param steps 그 승인으로 나간 걸음. 다시 지을 때 계획 앞에 이 순서로 다시 선다.
 */
data class RecordedApproval(
    val robotId: String,
    val approver: Approver,
    val at: Instant,
    val wallClockAt: Instant,
    val steps: List<ApprovedStep>,
)

/**
 * 단위 하나의 기록.
 *
 * 시각과 판정은 [attempt] 의 것이다. 다시 지을 때 스냅숏이 더 큰 시도를 보이면(기록이 그 재작업보다 먼저 적혔다) 그 단위의
 * 시각과 판정은 옮기지 않는다 — 앞 시도의 사실을 뒤 시도에 붙이면 끝난 태스크의 판정이 도는 태스크를 닫는다.
 *
 * @param taskId 이 시도에 낸 태스크 id. 내지 않았으면 빈 문자열이다.
 * @param requestedAt 이 시도의 요청을 보낸 시각. 설비 대기는 기다리기 시작한 시각이다. 내지 않았으면 널.
 * @param verifyWindow 하류가 끝났다고 했고 설비 신호를 기다리던 중이면 그 창. 아니면 널.
 * @param verdict 이 층이 이미 내린 판정. 정착하지 않았으면 널.
 * @param decision 이 단위에 운영자가 마지막으로 낸 판단. 없으면 널.
 */
data class UnitRecord(
    val unitId: String,
    val attempt: Int,
    val taskId: String,
    val requestedAt: Instant?,
    val verifyWindow: VerifyWindow?,
    val verdict: UnitVerdict?,
    val decision: RecordedDecision?,
)

/** 검증 대기의 창 — 하류가 끝났다고 한 시각과 그때 정한 근거 기한. 기한은 다시 계산하지 않는다(뒤 폭이 바뀌었을 수 있다). */
data class VerifyWindow(val doneAt: Instant, val deadline: Instant)

/**
 * 이 층이 이미 내린 판정. 다시 지으면 그대로 옮기고 그 단위를 다시 돌리지 않는다.
 *
 * 운영자 보류([UnitState.OPERATOR_HOLD])도 판정이다. 자동으로 못 풀어 사람에게 넘긴다는 결정이고, 다시 지은 뒤 다시 관측으로
 * 풀면 이전 인스턴스가 사람에게 물은 질문이 답 없이 사라진다. 그 질문을 물을 인시던트는 기록에 들지 않으므로 다시 지은
 * 인스턴스가 새로 연다(`Middleware.RESUME_HOLD_REOPENED`).
 */
data class UnitVerdict(
    val state: UnitState,
    val reached: Evidence,
    val verification: Verification,
    val evidenceAt: Instant?,
    val failureClass: String?,
    val note: String?,
    val result: String?,
) {
    init {
        require(state in STATES) { "종결하지 않은 상태는 판정이 아니다: $state" }
    }

    companion object {
        /** 판정으로 옮길 수 있는 상태. 도는 중·검증 대기·미확정·대기는 다시 지은 실행이 이어 간다. */
        val STATES: Set<UnitState> = setOf(
            UnitState.DONE, UnitState.UNVERIFIED, UnitState.FAILED, UnitState.ABORTED, UnitState.OPERATOR_HOLD,
        )
    }
}

/**
 * 운영자 판단 한 번([Middleware.resolve]).
 *
 * @param attempt 판단이 선 시도. 재작업이면 그 뒤 단위의 시도는 하나 크다.
 */
data class RecordedDecision(
    val decision: OperatorDecision,
    val approver: Approver,
    val at: Instant,
    val attempt: Int,
)

/**
 * 판단 기록의 **인코딩**. [ApprovalWire]·`LedgerExport` 와 같은 성질이다 — 문자열만 다루며 저장소를 모른다.
 *
 * **못 읽는 기록은 던진다.** 모르는 판, 모르는 칸, 빠진 칸, 모르는 값 모두다. 일부만 읽어 다시 지으면 읽지 못한 사실은
 * 조용히 «없었다» 가 되고, 그것이 이 기록이 막으려는 바로 그 손실이다. 그래서 칸이 하나만 늘어도 판을 올린다 — 모르는 칸을
 * 무시하는 읽는 쪽이 없다.
 */
object JudgmentRecordWire {

    /** 이 규약의 판. 칸이 늘거나 값이 늘면 올린다. */
    const val SCHEMA_VERSION: String = "1"

    fun encode(record: JudgmentRecord): String = Obj()
        .str("schemaVersion", SCHEMA_VERSION)
        .str("jobOrderId", record.jobOrderId)
        .num("orderVersion", record.orderVersion)
        .raw("acknowledgedFaults", LedgerExport.arrayOfStrings(record.acknowledgedFaults))
        .raw("approval", record.approval?.let { approval(it) } ?: "null")
        .raw("units", record.units.joinToString(",", "[", "]") { unit(it) })
        .done()

    fun decode(json: String): JudgmentRecord {
        val root = try {
            Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build()
        } catch (e: Exception) {
            throw IllegalArgumentException("판단 기록을 읽지 못했다: ${e.message}", e)
        }
        val version = root.fieldsMap["schemaVersion"]
        if (version == null || !version.hasStringValue() || version.stringValue != SCHEMA_VERSION) {
            throw IllegalArgumentException(
                "모르는 판단 기록 판이다: ${version?.let { describe(it) } ?: "(없음)"} — 이 판은 $SCHEMA_VERSION 만 읽는다",
            )
        }
        val r = Fields("$", root, ROOT)
        return JudgmentRecord(
            jobOrderId = r.string("jobOrderId"),
            orderVersion = r.int("orderVersion"),
            acknowledgedFaults = r.list("acknowledgedFaults").mapIndexed { i, v -> stringOf("$.acknowledgedFaults[$i]", v) },
            approval = r.objOrNull("approval", APPROVAL)?.let { approvalOf(it) },
            units = r.list("units").mapIndexed { i, v -> unitOf(Fields("$.units[$i]", structOf("$.units[$i]", v), UNIT)) },
        )
    }

    private fun approval(a: RecordedApproval): String = Obj()
        .str("robotId", a.robotId)
        .str("approverId", a.approver.id)
        .str("approverKind", a.approver.kind.name)
        .str("at", a.at.toString())
        .str("wallClockAt", a.wallClockAt.toString())
        .raw("steps", a.steps.joinToString(",", "[", "]") { step(it) })
        .done()

    private fun step(s: ApprovedStep): String = Obj()
        .str("unitId", s.unitId)
        .str("skillType", s.skillType)
        .raw("parameters", LedgerExport.mapOfStrings(s.parameters))
        .done()

    private fun unit(u: UnitRecord): String = Obj()
        .str("unitId", u.unitId)
        .num("attempt", u.attempt)
        .str("taskId", u.taskId)
        .str("requestedAt", u.requestedAt?.toString())
        .raw("verifyWindow", u.verifyWindow?.let { w -> Obj().str("doneAt", w.doneAt.toString()).str("deadline", w.deadline.toString()).done() } ?: "null")
        .raw("verdict", u.verdict?.let { verdict(it) } ?: "null")
        .raw("decision", u.decision?.let { decision(it) } ?: "null")
        .done()

    private fun verdict(v: UnitVerdict): String = Obj()
        .str("state", v.state.name)
        .str("reached", v.reached.name)
        .str("verification", v.verification.name)
        .str("evidenceAt", v.evidenceAt?.toString())
        .str("failureClass", v.failureClass)
        .str("note", v.note)
        .str("result", v.result)
        .done()

    private fun decision(d: RecordedDecision): String = Obj()
        .str("decision", d.decision.name)
        .str("approverId", d.approver.id)
        .str("approverKind", d.approver.kind.name)
        .str("at", d.at.toString())
        .num("attempt", d.attempt)
        .done()

    private fun approvalOf(f: Fields) = RecordedApproval(
        robotId = f.string("robotId"),
        approver = Approver(f.string("approverId"), f.enum("approverKind", ApproverKind.entries)),
        at = f.instant("at"),
        wallClockAt = f.instant("wallClockAt"),
        steps = f.list("steps").mapIndexed { i, v ->
            val s = Fields("${f.path}.steps[$i]", structOf("${f.path}.steps[$i]", v), STEP)
            ApprovedStep(
                skillType = s.string("skillType"),
                parameters = s.obj("parameters", null).let { p -> p.struct.fieldsMap.mapValues { (k, v) -> stringOf("${p.path}.$k", v) } },
                unitId = s.string("unitId"),
            )
        },
    )

    private fun unitOf(f: Fields) = UnitRecord(
        unitId = f.string("unitId"),
        attempt = f.int("attempt"),
        taskId = f.string("taskId"),
        requestedAt = f.instantOrNull("requestedAt"),
        verifyWindow = f.objOrNull("verifyWindow", WINDOW)?.let { VerifyWindow(it.instant("doneAt"), it.instant("deadline")) },
        verdict = f.objOrNull("verdict", VERDICT)?.let { v ->
            val state = v.enum("state", UnitState.entries)
            if (state !in UnitVerdict.STATES) throw IllegalArgumentException("${v.path}.state: 종결하지 않은 상태는 판정이 아니다: $state")
            UnitVerdict(
                state = state,
                reached = v.enum("reached", Evidence.entries),
                verification = v.enum("verification", Verification.entries),
                evidenceAt = v.instantOrNull("evidenceAt"),
                failureClass = v.stringOrNull("failureClass"),
                note = v.stringOrNull("note"),
                result = v.stringOrNull("result"),
            )
        },
        decision = f.objOrNull("decision", DECISION)?.let { d ->
            RecordedDecision(
                decision = d.enum("decision", OperatorDecision.entries),
                approver = Approver(d.string("approverId"), d.enum("approverKind", ApproverKind.entries)),
                at = d.instant("at"),
                attempt = d.int("attempt"),
            )
        },
    )

    // 칸 이름 — 읽는 쪽이 모르는 칸을 거부하는 기준이다. 적는 쪽(위)과 한 벌로 고친다.
    private val ROOT = setOf("schemaVersion", "jobOrderId", "orderVersion", "acknowledgedFaults", "approval", "units")
    private val APPROVAL = setOf("robotId", "approverId", "approverKind", "at", "wallClockAt", "steps")
    private val STEP = setOf("unitId", "skillType", "parameters")
    private val UNIT = setOf("unitId", "attempt", "taskId", "requestedAt", "verifyWindow", "verdict", "decision")
    private val WINDOW = setOf("doneAt", "deadline")
    private val VERDICT = setOf("state", "reached", "verification", "evidenceAt", "failureClass", "note", "result")
    private val DECISION = setOf("decision", "approverId", "approverKind", "at", "attempt")

    /**
     * 객체 하나의 칸. **칸 목록이 정확히 맞아야 한다** — 빠진 칸도, 모르는 칸도 던진다. 널을 쓰는 칸도 키는 있어야 한다
     * («그 칸이 비었다» 와 «이 판이 그 칸을 모른다» 를 가르기 위해서다).
     */
    private class Fields(val path: String, val struct: Struct, expected: Set<String>?) {
        init {
            if (expected != null) {
                val keys = struct.fieldsMap.keys
                val unknown = keys - expected
                val missing = expected - keys
                if (unknown.isNotEmpty()) throw IllegalArgumentException("$path: 모르는 칸이다: ${unknown.sorted()}")
                if (missing.isNotEmpty()) throw IllegalArgumentException("$path: 칸이 없다: ${missing.sorted()}")
            }
        }

        private fun value(name: String): Value = struct.fieldsMap.getValue(name)
        private fun isNull(name: String) = value(name).hasNullValue()

        fun string(name: String): String = stringOf("$path.$name", value(name))
        fun stringOrNull(name: String): String? = if (isNull(name)) null else string(name)

        fun int(name: String): Int {
            val v = value(name)
            if (!v.hasNumberValue()) throw IllegalArgumentException("$path.$name: 수가 아니다")
            val d = v.numberValue
            if (d != Math.rint(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) throw IllegalArgumentException("$path.$name: 정수가 아니다: $d")
            return d.toInt()
        }

        fun instant(name: String): Instant = try {
            Instant.parse(string(name))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("$path.$name: 시각이 아니다: ${e.parsedString}", e)
        }

        fun instantOrNull(name: String): Instant? = if (isNull(name)) null else instant(name)

        fun <E : Enum<E>> enum(name: String, entries: List<E>): E {
            val s = string(name)
            return entries.firstOrNull { it.name == s } ?: throw IllegalArgumentException("$path.$name: 모르는 값이다: $s")
        }

        fun list(name: String): List<Value> {
            val v = value(name)
            if (!v.hasListValue()) throw IllegalArgumentException("$path.$name: 목록이 아니다")
            return v.listValue.valuesList
        }

        fun obj(name: String, expected: Set<String>?): Fields = Fields("$path.$name", structOf("$path.$name", value(name)), expected)
        fun objOrNull(name: String, expected: Set<String>): Fields? = if (isNull(name)) null else obj(name, expected)
    }

    private fun structOf(path: String, v: Value): Struct {
        if (!v.hasStructValue()) throw IllegalArgumentException("$path: 객체가 아니다")
        return v.structValue
    }

    private fun stringOf(path: String, v: Value): String {
        if (!v.hasStringValue()) throw IllegalArgumentException("$path: 문자열이 아니다")
        return v.stringValue
    }

    private fun describe(v: Value): String = if (v.hasStringValue()) v.stringValue else v.toString().trim()
}
