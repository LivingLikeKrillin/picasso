package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.capability.HoldEffects
import dev.picasso.capability.PreconditionCheck
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.ProgressKind
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 공통 실행 구조(설계 §2) — 접수 · 조합 · 실행 상태기계 · 근거 결합 · 취소 · 결과 통보.
 *
 * ## 구동
 *
 * 스레드가 없다. [submit]·[cancel]·[resolve]·[ack] 가 밖에서 오고, 하류의 전이는 [pump] 를
 * 부를 때 읽는다. 시험이 가상 시계를 밀고 `pump()` 를 부르는 결정적 구동에 맞춘
 * 것이며(§12.1), 운영 배치에서는 스케줄러가 `pump()` 를 돌린다. 시각은 [now] 로만
 * 읽는다 — 시간창 δ 가 시각을 비교하기 때문이다.
 *
 * ## 하류가 둘
 *
 * [Route.ROBOT] 은 계약(④)의 원자 스킬이고 [Route.FLEET] 은 D 수준 위임이다.
 * 둘을 가르는 것은 하류의 **종류**이지 기종이 아니다 — 여기에 기종 이름은 없고
 * 게이트 7번이 그것을 지킨다.
 *
 * ## 기체
 *
 * 실행 하나는 기체 하나에서 **차례로** 돈다 — 실물은 예외 없이 배타적 제어
 * 모델이고(§4.9) 어느 기체에 줄지(배차)는 이 모듈의 일이 아니다(ADR 38).
 */
class Middleware(
    private val robots: RobotPort,
    private val cell: CellSignals = CellSignals.None,
    private val fleet: AmrFleetPort = AmrFleetPort.None,
    capabilities: List<LogicalCapability> = listOf(PrepareSequencedRack(), DeliverContainer(), InspectAsset()),
    private val now: () -> Instant = { Instant.now() },
    /**
     * `IN_DOUBT` 에서 같은 참조로 다시 묻는 횟수의 상한(13.2 ①). 이만큼 물어도 답이 없으면 하류가 지금은 조회 불가인
     * 것이고, 12.3 셋째 행대로 **불가 시 운영자**다. `pump()` 한 번에 한 번 묻는다.
     */
    private val lookupRetries: Int = 3,
) {
    private val capabilities = capabilities.associateBy { it.workMasterId }
    private val executions = linkedMapOf<String, Execution>()
    private val views = mutableMapOf<String, RobotView>()

    /**
     * 기체 하나에 대한 이 층의 관측 — 계약 §4.8 의 소비자 패턴 그대로다. 스냅샷으로 세우고(`cursor` = 다음에 올 번호)
     * 재생으로 이어 붙이며, 버퍼를 벗어나면 스냅샷부터 다시 세운다(보고서 17장 9번 *단절 후 재동기화*).
     *
     * 결함과 연결 상태는 **스냅샷이 권위**다(현재값). 이벤트는 그 사이에 무슨 일이 있었는지 — 섰다가 사라진 결함,
     * 전이 — 를 자취로 남긴다. 둘 중 하나만 쓰면 신규 소비자가 놓친 전이를 세우지 못하거나(이벤트만) 그 사이를
     * 모른다(스냅샷만).
     */
    class RobotView internal constructor() {
        var cursor: Long? = null
            internal set
        var faults: Map<String, Fault> = emptyMap()
            internal set
        var connection: ConnectionState = ConnectionState.CONNECTION_STATE_ONLINE
            internal set
        var tasks: Map<String, dev.picasso.contracts.v1.TaskState> = emptyMap()
            internal set
        /** 마지막 동기화에서 기체를 봤는가. 거짓이면 위의 값은 낡은 것이다. */
        var observable: Boolean = false
            internal set
        var resyncs: Int = 0
            internal set
        var eventsSeen: Long = 0
            internal set
    }

    fun view(robotId: String): RobotView? = views[robotId]
    private val outbox = mutableListOf<JobResponse>()
    private var responseSeq = 0

    /** 실행 하나 — 주문·기체·단위 열·두 축의 상태. */
    inner class Execution(
        val executionId: String,
        var order: JobOrder,
        val robotId: String,
        val capability: LogicalCapability,
        val units: MutableList<ExecutionUnit>,
    ) {
        var physicalState: PhysicalState = PhysicalState.REQUESTED
            internal set
        var upstreamAck: UpstreamAck = UpstreamAck.NOT_SENT
            internal set
        internal var active: ExecutionUnit? = null
        internal var handle: TaskHandle? = null
        internal var transport: TransportHandle? = null
        internal var cancelRequested = false
        internal var lastCancel: CancelReport? = null
        /** 하류가 진행 중 단위의 중단을 거절한 사유 — 그 단위는 끝까지 가고 다음 경계에서 멈춘다. */
        internal var cancelRefusal: String? = null
        /** 지연 보고·운영자 보류·미확정 통보를 한 번만 내기 위한 표시. */
        internal var notedDelay: String? = null
        internal var notedHold: String? = null
        internal var notedDoubt: String? = null
        internal var notedBlock: String? = null

        /** 지금 다음 단위를 막고 있는 기체 수준 결함. 비어 있으면 막힌 것이 없다. */
        var blockedBy: List<Fault> = emptyList()
            internal set

        /** 운영자가 [release] 로 감수한 결함의 열쇠 — 같은 결함은 다시 막지 않는다. 새 결함은 막는다. 감사 기록이다. */
        val acknowledgedFaults: MutableSet<String> = linkedSetOf()

        /** 이 실행이 도는 동안 기체가 낸 이벤트와 이 층의 관측 — 감사 자취(보고서 7장 *실행 추적·이벤트 전달·감사 기록*). */
        val eventTrail: MutableList<ObservedEvent> = mutableListOf()

        /** 연결이 끊긴 채 도는 중인가 — 그동안 결과는 미확정이다(`scenarios.md` §4.4 넷째 행). */
        var linkBroken: Boolean = false
            internal set

        /** 지연 이벤트(15.1) — 옛 버전의 종착. 폐기하지 않는다. */
        val lateEvents: MutableList<LateEvent> = mutableListOf()
        private val seenLate = mutableSetOf<Triple<String, Int, String>>()

        internal fun noteLate(unit: ExecutionUnit, update: WatchTaskResponse) {
            val key = Triple(unit.unitId, update.revision, update.state.name)
            if (!seenLate.add(key)) return
            lateEvents += LateEvent(unit.unitId, update.revision, unit.revision, update.state.name, stateTime(update))
        }

        val completedUnits: List<String> get() = units.filter { it.state == UnitState.DONE }.map { it.unitId }
        val version: Int get() = order.version
    }

    sealed interface Submission {
        data class Accepted(val execution: Execution) : Submission
        data class Idempotent(val execution: Execution) : Submission
        data class Rejected(val reason: String) : Submission
    }

    fun execution(executionId: String): Execution? = executions[executionId]

    /** 상류에 갈 결과 통보 중 아직 ack 되지 않은 것 — 아웃박스. 통보 재시도의 자리다(보고서 13.3). */
    fun pending(): List<JobResponse> = outbox.filter { it.ack != UpstreamAck.ACKED }

    fun responses(): List<JobResponse> = outbox.toList()

    // ── 접수 (보고서 15 — 정체성과 버전)

    /**
     * JobOrder 를 받는다. 같은 `jobOrderId` 가 오면 버전으로 판정한다 — 같으면 멱등,
     * 낮으면 거절, 높으면 **갱신**(보고서 15.3: 새 버전은 확정된 단위 이후에만 붙는다).
     */
    fun submit(order: JobOrder, robotId: String): Submission {
        val capability = capabilities[order.workMasterId]
            ?: return Submission.Rejected("모르는 논리적 능력이다: ${order.workMasterId}")
        // 보고서 11.3 — 능력은 최고 등급을 선언하고 요청은 요구 등급을 지정한다. 확인 수단이 없으면 제공 불가다.
        // 받아 놓고 UNVERIFIED 로 끝내는 것은 상류에 "될지도 모른다" 고 말한 셈이다.
        if (order.requiredEvidence > capability.maxEvidence) {
            return Submission.Rejected(
                "요구 근거 등급 ${order.requiredEvidence} 은 ${capability.workMasterId} 의 최고 등급 ${capability.maxEvidence} 를 넘는다 — 확인 수단이 없다",
            )
        }

        val existing = executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }
        if (existing != null) return revise(existing, order)

        val planned = capability.plan(order)
        inconsistent(order, planned)?.let { return Submission.Rejected(it) }
        chainViolation(robotId, planned)?.let { return Submission.Rejected(it) }

        val execution = Execution(
            executionId = "exec-${executions.size + 1}",
            order = order,
            robotId = robotId,
            capability = capability,
            units = planned.toMutableList(),
        )
        execution.units.forEach { it.revision = order.version }
        execution.physicalState = PhysicalState.ACCEPTED
        executions[execution.executionId] = execution
        return Submission.Accepted(execution)
    }

    /**
     * 계획 시점 사슬 검사(설계안 §4) — 로봇이 선언한 사전 조건을 **접수 요청을 보내기 전에** 단위 사슬에 대고 본다.
     *
     * 출발점은 **지금 도는 단위의 관측**([liveHold])이고, 단위마다 카탈로그의 효과([HoldEffects])로 다음 파지를
     * 계산한다. 어느 단위의 조건이 그 시점의 파지와 어긋나면 주문을 받지 않는다 — 받아 놓고 돌리면 그 단위가 발신자에서
     * `PRECONDITION_UNMET` 으로 거절되기까지 앞 단위들이 물리적으로 움직인다.
     *
     * **권위는 발신자다.** 여기서 막는 것은 *알고도 보내는* 일뿐이다. 도는 단위가 없으면 관측이 없는 것이고 —
     * 종착한 단위의 파지는 낡을 수 있으며 다시 볼 길이 없으므로(리뷰 C2) 쓰지 않는다 — 발신자가 접수 때 판정한다.
     * 능력을 못 물은 로봇도, 계약에 없는 주어([PreconditionCheck.Unknown.DEFER])도 같다: 모르는 조건을 지어내지 않는다.
     */
    private fun chainViolation(robotId: String, planned: List<ExecutionUnit>): String? {
        var hold = liveHold(robotId) ?: return null
        val declared = robots.capabilities(robotId)?.skillsList?.associateBy { it.skillType } ?: return null
        for (unit in planned) {
            if (unit.route != Route.ROBOT) continue
            val violations = declared[unit.skillType]
                ?.let { PreconditionCheck.check(it, hold, PreconditionCheck.Unknown.DEFER) }
                .orEmpty()
            if (violations.isNotEmpty()) {
                return "단위 ${unit.unitId}(${unit.skillType}) 의 사전 조건이 어긋난다 — " +
                    PreconditionCheck.rejectionDetail(violations) + " (지금 도는 단위의 관측, 보내지 않았다)"
            }
            hold = HoldEffects.after(unit.skillType, hold)
        }
        return null
    }

    /**
     * 이 기체에서 **지금 도는** 단위들의 파지 관측. 든 것이 하나라도 있으면 든 채(손은 한 쌍이다), 아니면 빈손 관측이
     * 있으면 빈손, 도는 단위가 없으면 모른다(널). 종착한 실행의 관측은 쓰지 않는다.
     */
    private fun liveHold(robotId: String): HoldState? {
        val live = executions.values
            .filter { it.robotId == robotId && !it.physicalState.isSettled }
            .mapNotNull { it.active }
        return live.firstOrNull { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }?.hold
            ?: live.firstOrNull { it.hold.kind == HoldKind.HOLD_KIND_EMPTY }?.hold
    }

    /**
     * 주문이 **스스로 어긋나는가** — 선언한 자재 수량과 배정된 단위 수가 타입마다 같은가.
     *
     * 상류가 *A형 둘* 이라 선언했는데 슬롯이 셋을 요구하면 그 주문은 자기 안에서 모순이다. **정책이 아니라
     * 정합성**이다 — 우리가 재고를 판단하는 것이 아니라(그것은 WMS 의 일이다) 서로 안 맞는 주문을 안 받는 것이다.
     *
     * 받아 놓고 돌리면 어느 슬롯이 계획 밖이었는지가 **로봇이 실패한 뒤에야** 보인다. 그때는 이미 물리적으로
     * 움직인 뒤다.
     *
     * `MaterialRequirements` 를 안 싣는 주문(운반 ①)은 검사하지 않는다 — 없는 것과 어긋나는 것은 다르다.
     */
    private fun inconsistent(order: JobOrder, planned: List<ExecutionUnit>): String? {
        if (order.materialRequirements.isEmpty()) return null
        val declared = order.materialRequirements.associate { it.materialDefinitionId to it.quantity }
        val assigned = planned.mapNotNull { it.expectedIdentity }.groupingBy { it }.eachCount()
        if (declared == assigned) return null
        return "자재 선언과 배정이 어긋난다: 선언=$declared, 배정=$assigned — 주문이 자기 안에서 안 맞는다"
    }

    private fun revise(execution: Execution, order: JobOrder): Submission {
        if (order.version == execution.order.version) return Submission.Idempotent(execution)
        if (order.version < execution.order.version) {
            return Submission.Rejected("이미 지난 버전이다: 받은 값=${order.version}, 현재=${execution.order.version}")
        }
        if (execution.physicalState == PhysicalState.ABORTED || execution.physicalState == PhysicalState.PHYSICALLY_DONE) {
            return Submission.Rejected("종착한 실행에는 새 버전이 붙지 않는다 — 재작업은 새 주문이다(보고서 14.1)")
        }

        // 개정판도 사슬 검사를 받는다 — 단, 이 개정이 실제로 교체·추가할 단위만(리뷰 C3). 이미 끝났거나 도는 단위를
        // 다시 재단하면 끝난 일을 되돌리라는 말이 된다.
        val fresh = execution.capability.plan(order)
        val toPlan = fresh.filter { f ->
            val current = execution.units.firstOrNull { it.unitId == f.unitId }
            current == null || current.state == UnitState.PENDING ||
                (current.state == UnitState.FAILED && current.taskId.isEmpty())
        }
        chainViolation(execution.robotId, toPlan)?.let { return Submission.Rejected(it) }

        // **확정된 단위 이후에만 붙는다.** 종착한 단위는 그대로(래치 — 계약이 이미 그렇게 한다),
        // 아직 안 시작한 단위는 새 계획으로 교체, 도는 단위는 계약의 갱신 규칙(§4.4)을 탄다.
        val replanned = fresh.associateBy { it.unitId }
        val kept = execution.units.map { unit ->
            when (unit.state) {
                UnitState.DONE, UnitState.UNVERIFIED, UnitState.ABORTED,
                UnitState.VERIFYING, UnitState.OPERATOR_HOLD -> unit
                // 물리적으로 시도된 실패는 확정된 단위다. 계획 단계에서 못 시작한 것(태스크가 없다)은
                // 아니다 — C형 부족처럼 새 버전이 공급을 채우면 그 슬롯은 다시 계획된다.
                UnitState.FAILED -> if (unit.taskId.isEmpty()) {
                    (replanned[unit.unitId] ?: unit).also { it.revision = order.version }
                } else {
                    unit
                }
                UnitState.PENDING -> (replanned[unit.unitId] ?: unit).also { it.revision = order.version }
                // 도는 단위와 답을 못 받은 단위는 같은 길이다 — 새 버전을 보내는 것이 계약에서는 갱신이자 조회다(같은
                // task_id: 접수돼 있었으면 갱신, 아니었으면 새 접수. 어느 쪽이든 핸들이 돌아오면 이제 추적한다).
                UnitState.RUNNING, UnitState.IN_DOUBT -> {
                    val fresh = replanned[unit.unitId]
                    if (fresh != null && unit.route == Route.ROBOT) {
                        val previous = unit.revision
                        unit.revision = order.version
                        // **기대도 새 버전의 것이다.** 옛 기대를 들고 있으면 옛 버전의 완료가 옛 기대에 맞아 새 버전의 완료로 적힌다.
                        unit.parameters = fresh.parameters
                        unit.expectedIdentity = fresh.expectedIdentity
                        unit.source = fresh.source
                        unit.destination = fresh.destination
                        try {
                            val response = robots.start(
                                execution.robotId, unit.taskId, order.version, unit.skillType,
                                withOptionals(execution, unit, fresh.parameters),
                            )
                            when {
                                response.hasHandle() -> if (unit.state == UnitState.IN_DOUBT) {
                                    execution.handle = response.handle
                                    unit.state = UnitState.RUNNING
                                    unit.annotate("IN_DOUBT resolved by revision ${order.version} update")
                                }

                                // **이미 종착한 태스크다** — 갱신이 닿기 전에 끝났다(계약의 래치, §4.4). 그 종착은 옛 버전의
                                // 것이고 pump 가 **지연 이벤트**로 받는다(15.1). 여기서 실패로 적으면 물리 결과를 잃는다.
                                response.rejection.code == RejectionCode.REJECTION_CODE_INVALID_TRANSITION ->
                                    unit.annotate("revision ${order.version} update rejected: task already terminal under revision $previous")

                                else -> {
                                    unit.state = UnitState.FAILED
                                    unit.failureClass = response.rejection.code.name
                                }
                            }
                        } catch (_: RuntimeException) {
                            // 갱신의 답을 못 받았다 — 적용됐는지 모른다. 도는 중이었더라도 이제는 미확정이다.
                            unit.state = UnitState.IN_DOUBT
                            unit.requestedAt = now()
                            unit.lookups = 0
                            unit.annotate("revision ${order.version} update unanswered")
                            execution.physicalState = PhysicalState.IN_DOUBT
                        }
                    }
                    // 플릿에 맡긴 운반은 도중에 바꾸지 않는다 — 플릿 계약에 갱신이 없다. 끝난 뒤 새 주문이다.
                    unit
                }
            }
        }
        val added = replanned.values.filter { fresh -> kept.none { it.unitId == fresh.unitId } }
            .onEach { it.revision = order.version }
        execution.units.clear()
        execution.units.addAll(kept + added)
        execution.order = order
        if (execution.physicalState == PhysicalState.PARTIAL || execution.physicalState == PhysicalState.UNVERIFIED ||
            execution.physicalState == PhysicalState.FAILED
        ) {
            execution.physicalState = PhysicalState.RUNNING
        }
        return Submission.Accepted(execution)
    }

    // ── 구동

    /** 하류에서 온 것을 읽고 상태를 한 걸음 민다. 몇 번 불러도 같은 결과다(멱등). */
    fun pump() {
        val live = executions.values.filter { !(it.physicalState.isSettled && it.physicalState != PhysicalState.PARTIAL) }
        live.map { it.robotId }.distinct().forEach { sync(it, live.filter { e -> e.robotId == it }) }
        executions.values.forEach { pump(it) }
    }

    /**
     * 기체 하나를 동기화한다 — 스냅샷(현재값) + 재생(그 사이의 사실). 이벤트는 관계있는 실행의 자취에 남긴다:
     * 태스크 전이는 그 태스크를 든 실행에, 결함·능력 변경은 그 기체의 실행 전부에.
     */
    private fun sync(robotId: String, live: List<Execution>) {
        val view = views.getOrPut(robotId) { RobotView() }
        val snapshot = robots.snapshot(robotId)
        if (snapshot == null) {
            view.observable = false
            return
        }
        view.observable = true
        view.faults = snapshot.faults.associateBy { faultKey(it) }
        view.connection = snapshot.connection
        view.tasks = snapshot.tasks

        val cursor = view.cursor
        if (cursor == null) {
            view.cursor = snapshot.sequence
            view.resyncs += 1
            live.forEach { it.trail("RESYNC", "snapshot: next sequence=${snapshot.sequence}, faults=${snapshot.faults.size}, connection=${snapshot.connection.name}") }
            return
        }
        when (val replay = robots.replay(robotId, cursor)) {
            null -> Unit // 못 물어봤다 — 다음 펌프에 같은 cursor 로 다시 묻는다
            Replay.Evicted -> {
                // 버퍼를 벗어났다 — 놓친 이벤트가 있다. 스냅샷은 이미 세웠으니 거기서부터 이어 붙인다(17장 9번).
                view.cursor = snapshot.sequence
                view.resyncs += 1
                live.forEach { it.trail("RESYNC", "replay evicted at cursor=$cursor; rebuilt from snapshot sequence=${snapshot.sequence}") }
            }
            is Replay.Events -> {
                replay.events.forEach { event -> record(event, live); view.eventsSeen += 1 }
                replay.events.lastOrNull()?.let { view.cursor = it.header.sequence + 1 }
            }
        }
    }

    private fun record(event: Event, live: List<Execution>) {
        val at = event.header.occurredAt
        val seq = event.header.sequence
        when {
            event.hasTaskTransition() -> {
                val tr = event.taskTransition
                live.filter { e -> e.units.any { it.taskId == tr.taskId } }.forEach {
                    it.eventTrail += ObservedEvent(seq, at, "TASK_TRANSITION", "${tr.taskId} ${tr.from.name}->${tr.to.name} rev=${tr.revision} attempt=${tr.attempt}")
                }
            }
            event.hasFaultEvent() -> {
                val f = event.faultEvent
                val kind = if (f.cleared) "FAULT_CLEARED" else "FAULT_RAISED"
                live.forEach { it.eventTrail += ObservedEvent(seq, at, kind, "${f.fault.errorType} class=${canonicalClassOf(f.fault)} can_accept_new_task=${f.fault.canAcceptNewTask}") }
            }
            event.hasCapabilityChanged() -> {
                val c = event.capabilityChanged
                live.forEach { it.eventTrail += ObservedEvent(seq, at, "CAPABILITY_CHANGED", "epoch=${c.capabilityEpoch} added=${c.addedList} removed=${c.removedList} cause=${c.cause.name}") }
            }
            event.hasSkillTransition() -> {
                // 스킬 인스턴스의 전이(READY→ACTIVE→HALTED…). 태스크 전이가 결과를 말하지만 이것이 그 아래의 걸음이고,
                // **재생을 이어 붙이는 검사가 이것에 기댄다** — 마지막으로 읽은 이벤트가 스킬 전이일 때 커서가 안 나아가면 여기서 중복이 보인다.
                val st = event.skillTransition
                live.filter { e -> e.units.any { it.taskId == st.taskId } }.forEach {
                    it.eventTrail += ObservedEvent(seq, at, "SKILL_TRANSITION", "${st.taskId} ${st.skillType} ${st.from.name}->${st.to.name}")
                }
            }
            else -> Unit
        }
    }

    private fun Execution.trail(kind: String, detail: String) {
        eventTrail += ObservedEvent(views[robotId]?.cursor ?: 0, now().toString(), kind, detail)
    }

    private fun pump(execution: Execution) {
        if (execution.physicalState.isSettled && execution.physicalState != PhysicalState.PARTIAL) return

        // **연결이 끊기면 도는 단위의 결과는 미확정이다.** 계약이 OFFLINE 과 CONNECTION_BROKEN 을 가른다(완료 기준 5);
        // HIBERNATING 은 침묵하지만 정상이다(§4.7). 되돌아오면 스냅샷으로 태스크 상태를 다시 세워 이어 간다 —
        // 재실행이 아니다. 어느 쪽도 상류에 한 번씩 알린다(16장 — 결과가 아직 확인되지 않았다는 사실).
        val view = views[execution.robotId]
        val runningRobotUnit = execution.active?.takeIf { it.route == Route.ROBOT && it.state == UnitState.RUNNING }
        if (view != null && view.observable && runningRobotUnit != null) {
            val down = view.connection == ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN ||
                view.connection == ConnectionState.CONNECTION_STATE_OFFLINE
            if (down && !execution.linkBroken) {
                execution.linkBroken = true
                execution.trail("LINK_BROKEN", "${view.connection.name} while ${runningRobotUnit.unitId} is running — result unconfirmed")
                execution.physicalState = PhysicalState.IN_DOUBT
                notify(execution)
            } else if (!down && execution.linkBroken) {
                execution.linkBroken = false
                val downstream = view.tasks[runningRobotUnit.taskId]?.name ?: "(not in snapshot)"
                execution.trail("LINK_RESTORED", "snapshot says ${runningRobotUnit.taskId}=$downstream — resuming tracking, no re-execution")
                runningRobotUnit.annotate("link restored; snapshot state $downstream")
                execution.physicalState = PhysicalState.RUNNING
                notify(execution)
            }
        }

        val active = execution.active
        if (active != null) {
            val settled = when {
                active.state == UnitState.IN_DOUBT -> resolveDoubt(execution, active)
                active.state == UnitState.VERIFYING -> checkEvidence(execution, active)
                active.route == Route.ROBOT -> pumpRobotUnit(execution, active)
                else -> pumpFleetUnit(execution, active)
            }
            if (!settled) return
            execution.active = null
            execution.handle = null
            execution.transport = null
            if (execution.cancelRequested) {
                // 하류 갱신을 읽은 경로는 abort 를 이미 적었다. 검증 대기 중에 취소가 걸린 경우만 여기서 적는다.
                if (execution.physicalState != PhysicalState.ABORTED) abort(execution, inProgress = active, hold = active.hold, cleanup = "not_applicable")
                return
            }
        }

        if (execution.cancelRequested) {
            abort(execution, inProgress = null, hold = null, cleanup = "nothing_to_clean")
            return
        }

        // 운영자가 판단할 단위가 있으면 다음으로 가지 않는다 — 판단이 재작업이면 그 슬롯이 먼저다.
        val held = execution.units.firstOrNull { it.state == UnitState.OPERATOR_HOLD }
        if (held != null) {
            execution.physicalState = PhysicalState.OPERATOR_HOLD
            if (execution.notedHold != held.unitId) {
                execution.notedHold = held.unitId
                notify(execution)
            }
            return
        }

        val next = execution.units.firstOrNull { it.state == UnitState.PENDING }
        if (next == null) {
            settleExecution(execution)
            return
        }

        // **기체가 새 태스크를 못 받는다고 말하면 보내지 않는다.** 계약은 막지 않는다 — 결함은 인터록이 아니고
        // "판단은 밖으로"(§4.6·§15.87)이며, 그 밖이 여기다. 자동으로 넘어가지 않는다: 사람이 [release] 로 감수하거나
        // 결함이 사라져야 다음 단위가 나간다. 못 물어봤으면(null) 막지 않고 그 사실만 남긴다 — 관측 실패로 현장을
        // 세우지는 않되, 없다고도 하지 않는다.
        val blocking = blockingFaults(execution, next)
        if (blocking.isNotEmpty()) {
            execution.blockedBy = blocking
            execution.physicalState = PhysicalState.OPERATOR_HOLD
            val key = blocking.joinToString { faultKey(it) }
            if (execution.notedBlock != key) {
                execution.notedBlock = key
                notify(execution)
            }
            return
        }
        execution.blockedBy = emptyList()
        startUnit(execution, next)
    }

    private fun blockingFaults(execution: Execution, next: ExecutionUnit): List<Fault> {
        if (next.route != Route.ROBOT) return emptyList()
        val view = views[execution.robotId]
        if (view == null || !view.observable) {
            next.annotate("robot faults not observable before start — proceeding without the gate")
            return emptyList()
        }
        return view.faults.values.filter { !it.canAcceptNewTask && faultKey(it) !in execution.acknowledgedFaults }
    }

    /** 결함의 정체 — 모드 이름과 참조. 미믹의 `FaultRegistry` 가 같은 열쇠로 중복을 막는다. */
    private fun faultKey(fault: Fault): String =
        fault.errorType + fault.referencesList.joinToString(prefix = "[", postfix = "]") { "${it.key}=${it.value}" }

    /**
     * 운영자의 해제 — *지금 막고 있는* 결함을 감수하고 다음 단위로 간다. 결함을 지우지 않는다(그것은 기체 쪽 일이고
     * 계약에 그 표면이 없다). 감수한 열쇠가 남으므로 같은 결함은 다시 막지 않고, **새 결함은 다시 막는다.**
     */
    fun release(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        if (execution.blockedBy.isEmpty()) return false
        execution.blockedBy.forEach { execution.acknowledgedFaults += faultKey(it) }
        execution.blockedBy = emptyList()
        execution.notedBlock = null
        execution.physicalState = PhysicalState.RUNNING
        return true
    }

    /** @return 단위가 이 펌프에서 종착(또는 검증 대기로 이행)해 active 에서 내려와도 되는가. */
    /**
     * 진행률을 읽고 **정체를 보이게 한다**(계약 0.8.0 의 `progress_basis` 를 읽는 자리).
     *
     * 규칙 둘이다.
     *
     * 1. **못 재는 기체는 판정하지 않는다.** `NOT_OBSERVABLE` 이면 그 사실을 자취에 **한 번** 적고 끝이다 —
     *    진행률의 `0.0` 을 정체로 읽으면 진행률을 안 내는 기종(Spot·G1)이 언제나 멈춰 있는 것으로 보이고,
     *    그러면 이 경보 자체가 곧 무시된다. 옛 발신자(`UNSPECIFIED`)도 같게 다룬다 — **막는 방향**이다(§15.41).
     * 2. **잴 수 있는데 안 움직이면 알린다.** 실패로 적지 않고 상태도 안 바꾼다. 느린 것과 멈춘 것을 우리가
     *    못 가르기 때문이고, 그래서 판단은 사람의 것이다.
     */
    private fun noteProgress(execution: Execution, unit: ExecutionUnit, update: WatchTaskResponse) {
        val basis = update.progressBasis
        val measured = basis.kind == ProgressKind.PROGRESS_KIND_MEASURED
        if (unit.progressObservable == null) {
            unit.progressObservable = measured
            if (!measured) {
                val why = basis.reason.ifBlank { "기체가 진행률의 근거를 안 낸다(kind=${basis.kind.name})" }
                execution.trail("PROGRESS_NOT_OBSERVABLE", "${unit.unitId}: $why — 정체 판정을 하지 않는다")
            }
        }
        if (!measured) return

        val at = now()
        if (unit.progressAt == null || update.progress > unit.progress) {
            unit.progress = update.progress
            unit.progressAt = at
            unit.progressStalled = false
            return
        }
        if (unit.progressStalled) return
        val window = execution.capability.stallWindow
        if (Duration.between(unit.progressAt, at) < window) return

        unit.progressStalled = true
        unit.annotate("진행률이 $window 동안 안 움직였다 (${basis.basis})")
        execution.trail(
            "PROGRESS_STALLED",
            "${unit.unitId}: ${basis.basis} at ${update.progress} — 느린 것과 멈춘 것은 이 층이 못 가른다. 사람이 본다",
        )
        notify(execution)
    }

    private fun pumpRobotUnit(execution: Execution, unit: ExecutionUnit): Boolean {
        val updates = robots.watch(execution.robotId, execution.handle!!)
        // **결과 이벤트는 (실행, 버전)으로 맞춘다**(15.1). 이 단위의 지금 버전보다 낮은 버전의 종착은 지연 이벤트로
        // **보존**하고, 이 버전의 갱신만 상태로 옮긴다.
        val (late, current) = updates.partition { it.revision < unit.revision }
        late.filter { it.state.isTerminal() }.forEach { execution.noteLate(unit, it) }
        val last = current.lastOrNull()
            ?: return late.lastOrNull { it.state.isTerminal() }?.let { settleLate(execution, unit, it) } ?: false
        unit.hold = last.hold
        if (!last.state.isTerminal()) {
            noteProgress(execution, unit, last)
            execution.physicalState = when {
                execution.cancelRequested -> PhysicalState.CANCELING
                execution.linkBroken -> PhysicalState.IN_DOUBT
                else -> PhysicalState.RUNNING
            }
            return false
        }
        when (last.state) {
            TaskState.TASK_STATE_SUCCEEDED -> {
                // 하류가 실어 준 결과 참조(E0 의 내용). 비어 있으면 비어 있는 채로 — 지어내지 않는다.
                unit.result = last.partialResult.takeIf { it.isNotBlank() }
                beginVerify(execution, unit, reachedByDownstream = Evidence.E0, doneAt = stateTime(last))
                if (unit.state == UnitState.VERIFYING) return false // active 로 남아 시간창을 기다린다
            }

            TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> unit.state = UnitState.ABORTED

            else -> {
                // 계약이 실은 **정준 분류**로만 분기한다(어댑터가 벤더 코드에서 옮긴 것, 미믹은 프로파일
                // 모드에서). 하류 상태 이름·모드 이름·벤더 원문은 note 에 동반할 뿐이다 — 상류에는 분류만
                // 간다(보고서 16장). 분류가 없으면 UNCLASSIFIED 이지 지어낸 분류가 아니다.
                failWithEvidenceCheck(execution, unit, canonicalClass(last), detail = downstreamDetail(last), at = stateTime(last))
            }
        }
        if (execution.cancelRequested) {
            abort(
                execution, inProgress = unit, hold = last.hold,
                cleanup = when (last.state) {
                    TaskState.TASK_STATE_CANCELLED -> "done"
                    TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> "failed"
                    else -> "not_applicable"
                },
            )
        }
        return true
    }

    /**
     * 옛 버전의 종착만 있고 새 버전의 갱신은 오지 않는다 — 갱신이 닿기 전에 끝난 태스크다(보고서 17장 6번).
     *
     * **새 버전의 완료로 적지 않는다**(15.1). 물리적으로 일어난 일은 옛 버전의 파라미터로 한 일이므로, 그것이 새 버전의
     * 기대에 맞는지는 **설비가 새 버전의 기대에 대고** 판정한다 — 요구 등급이 E0 라도 설비를 묻고(`strict`), 설비가
     * 없으면 `UNVERIFIED` 다. 실패·중단은 버전과 무관한 물리 사실이라 그대로 옮기되 어느 버전의 것인지를 남긴다.
     */
    private fun settleLate(execution: Execution, unit: ExecutionUnit, update: WatchTaskResponse): Boolean {
        unit.hold = update.hold
        unit.annotate("late event: revision ${update.revision} ${update.state.name} arrived under revision ${unit.revision}")
        when (update.state) {
            TaskState.TASK_STATE_SUCCEEDED -> {
                beginVerify(execution, unit, reachedByDownstream = Evidence.E0, doneAt = stateTime(update), strict = true)
                if (unit.state == UnitState.VERIFYING) return false
            }
            TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> unit.state = UnitState.ABORTED
            else -> failWithEvidenceCheck(execution, unit, canonicalClass(update), detail = downstreamDetail(update), at = stateTime(update))
        }
        return true
    }

    /**
     * `IN_DOUBT` 의 해소 — 보고서 13.2 의 순서 그대로.
     *
     * 1. **하류의 클라이언트 참조 기반 조회** — 같은 참조로 다시 묻는다. 계약은 같은 핸들을 돌려주고 플릿은 같은 운반을
     *    돌려준다. 돌아오면 그 실행을 **이어서 추적**한다(새 실행이 아니다 — 17장 3번 *자동 재실행 없음*, 9번 재동기화는
     *    처음부터 되짚는 `WatchTask(0)` 이 한다). [lookupRetries] 번 물어도 답이 없으면 지금은 조회 불가다.
     * 2. **물리 상태 관측** — 하류에 물을 수 없을 때 설비를 본다. 요청 시각부터 [LogicalCapability.inDoubtGrace] 안에
     *    기대한 것이 목적지에 나타나면 **잠정 완료**다(12.3 셋째 행). 확정은 조회가 하고, 조회가 없으면 운영자가 한다.
     * 3. **운영자** — `OPERATOR_HOLD`. 자동 재실행은 없다. 재요청은 사람이 [OperatorDecision.REWORK] 로 명시적으로 낸다(13.3).
     *
     * @return 단위가 이 펌프에서 종착(운영자 보류 포함)했는가.
     */
    private fun resolveDoubt(execution: Execution, unit: ExecutionUnit): Boolean {
        val lookup = if (unit.route == Route.ROBOT) robots.executionLookup else fleet.executionLookup
        if (lookup == ExecutionLookup.CLIENT_REFERENCE && unit.lookups < lookupRetries) {
            unit.lookups += 1
            val found = try {
                lookupDownstream(execution, unit)
            } catch (_: RuntimeException) {
                false
            }
            if (found) {
                unit.state = UnitState.RUNNING
                unit.annotate("IN_DOUBT resolved by client-reference lookup after ${unit.lookups} lookup(s)")
                execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
                if (execution.cancelRequested) {
                    execution.handle?.let { handle ->
                        val response = robots.cancel(execution.robotId, handle)
                        if (!response.hasState()) execution.cancelRefusal = response.rejection.code.name
                    }
                    execution.transport?.let { fleet.cancel(it) }
                }
                return false
            }
            if (unit.state == UnitState.FAILED) return true // 하류가 이 요청을 거절했다 — 미실행이 확인된 것이고 그 사유는 우리 어휘다
            execution.physicalState = PhysicalState.IN_DOUBT
            return false
        }

        // ② 물리 관측 — 요청 시각부터의 신호만 이 요청의 것이다.
        val requestedAt = unit.requestedAt!!
        val window = execution.capability.evidenceWindow
        val signal = unit.destination?.let { cell.observe(it) }
        val observedAt = signal?.observedAt ?: now()
        val provisional = signal != null && signal.occupied &&
            (unit.expectedIdentity == null || signal.identity == unit.expectedIdentity) &&
            !observedAt.isBefore(requestedAt.minus(window.before))
        if (!provisional && now().isBefore(requestedAt.plus(execution.capability.inDoubtGrace))) {
            execution.physicalState = PhysicalState.IN_DOUBT
            return false
        }

        // ③ 운영자.
        val why = if (lookup == ExecutionLookup.NONE) "downstream has no client-reference lookup" else "lookup unanswered ${unit.lookups} time(s)"
        if (provisional) {
            unit.verification = Verification.MATCHED
            unit.evidenceAt = observedAt
            unit.annotate("IN_DOUBT: response lost; evidence present at ${unit.destination} (provisional done); $why — confirm")
        } else {
            unit.annotate("IN_DOUBT: response lost; no evidence at ${unit.destination} within grace; $why — no automatic re-execution")
        }
        unit.state = UnitState.OPERATOR_HOLD
        return true
    }

    /** 13.2 ① — 같은 참조로 다시 묻는다. 돌아오면 핸들을 잡고 참, 거절이면 단위를 실패로 적고 거짓, 답이 없으면 던진다. */
    private fun lookupDownstream(execution: Execution, unit: ExecutionUnit): Boolean = when (unit.route) {
        Route.ROBOT -> {
            val response = robots.start(
                execution.robotId, unit.taskId, unit.revision, unit.skillType,
                withOptionals(execution, unit, unit.parameters),
            )
            if (response.hasHandle()) {
                execution.handle = response.handle
                true
            } else {
                val rejection = response.rejection
                if (rejection.code == RejectionCode.REJECTION_CODE_PRECONDITION_UNMET) {
                    // 발신자가 사전 조건으로 거절했다 — 요청이 틀린 것이 아니라 **지금 못 받는** 것이다. 계약은 «기다리거나
                    // 앞 단위를 바꾼다» 고 했고, 그 판단은 밖(운영자)에 있다. FAILED 로 접어 다음 단위로 넘어가면 그 판단이
                    // 실행되지 않는다(리뷰 C10). 사유와 주어를 단위에 남긴다 — 응답만 보고 무엇이 걸렸는지 알 수 있게.
                    unit.state = UnitState.OPERATOR_HOLD
                    unit.failureClass = rejection.code.name
                    val subjects = rejection.referencesList
                        .filter { it.key == Reference.Key.KEY_PRECONDITION_SUBJECT }.joinToString { it.value }
                    unit.note = "sender refused: ${rejection.detail} [subject=$subjects]"
                } else {
                    unit.state = UnitState.FAILED
                    unit.failureClass = rejection.code.takeIf { it != RejectionCode.REJECTION_CODE_UNSPECIFIED }?.name ?: "REJECTED"
                }
                false
            }
        }

        Route.FLEET -> {
            val handle = fleet.dispatch(transportOrderOf(unit))
            if (handle != null) {
                execution.transport = handle
                true
            } else {
                unit.state = UnitState.FAILED
                unit.failureClass = FLEET_REJECTED
                false
            }
        }
    }

    private fun transportOrderOf(unit: ExecutionUnit) = TransportOrder(
        reference = unit.taskId,
        containerId = unit.expectedIdentity ?: unit.unitId,
        source = unit.source ?: "",
        destination = unit.destination ?: "",
    )

    /** @return 단위가 종착했는가. */
    private fun pumpFleetUnit(execution: Execution, unit: ExecutionUnit): Boolean {
        val status = fleet.status(execution.transport!!)
        unit.hold = holdOf(status, unit)
        when (status.state) {
            TransportState.ACCEPTED, TransportState.PICKED_UP, TransportState.IN_TRANSIT -> {
                unit.note = null
                execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
                return false
            }

            TransportState.WAITING_HANDOVER -> {
                // 목적지에 이전 용기가 남아 있다 — 인계 대기와 **지연 보고**. 임의의 다른 자리에 내려놓지 않는다.
                unit.note = WAITING_HANDOVER
                execution.physicalState = PhysicalState.RUNNING
                if (execution.notedDelay != unit.unitId) {
                    execution.notedDelay = unit.unitId
                    notify(execution)
                }
                return false
            }

            TransportState.DELIVERED -> {
                beginVerify(execution, unit, reachedByDownstream = Evidence.E1, doneAt = now())
                if (unit.state == UnitState.VERIFYING) return false
            }

            TransportState.REJECTED_AT_SOURCE -> {
                // 출발지의 용기가 요청과 다르다 — 인수하지 않고 불일치 보고. 상류(WMS)가 할당·현장 재고를 확인한다.
                unit.state = UnitState.FAILED
                unit.failureClass = SOURCE_MISMATCH
                unit.note = status.observedContainer?.let { "observed=$it at ${unit.source}" }
            }

            TransportState.CANCELLED -> unit.state = UnitState.ABORTED

            // 플릿 계약은 분류를 안 싣는다 — 프로젝트용 계약의 남은 자리이며 여기서는 UNCLASSIFIED 다.
            TransportState.FAILED -> failWithEvidenceCheck(
                execution, unit, UNCLASSIFIED, detail = "fleet=${TransportState.FAILED.name} ${status.detail.orEmpty()}".trim(), at = now(),
            )
        }
        if (execution.cancelRequested) {
            abort(
                execution, inProgress = unit, hold = unit.hold,
                cleanup = if (status.holding) "failed" else "done",
            )
        }
        return true
    }

    private fun holdOf(status: TransportStatus, unit: ExecutionUnit): HoldState =
        if (status.holding) {
            HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).setObjectRef(status.observedContainer ?: unit.unitId).build()
        } else {
            HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build()
        }

    /**
     * 능력이 쓰고 싶다는 **선택 파라미터**를 붙인다 — **로봇이 선언한 것만.**
     *
     * 선택 필드는 기종마다 있고 없다. 안 드는 기종에 보내면 코어 키는 fail-closed 라 태스크 자체가
     * `PARAMETER_INVALID` 로 거절되고(§5.3), 그러면 선택 필드 하나 때문에 그 기종에서 이 능력을 못 쓴다 —
     * 실측으로 그런 기종이 있다 — 어느 것인지는 이 모듈이 알 자리가 아니다(게이트 7번).
     *
     * ★**뺐다는 사실을 적는다.** 조용히 빼면 *파지 확인을 요구했다* 와 *못 해서 안 했다* 가 같아 보이고,
     * 그것은 이 저장소가 진행률·결함·파지에서 반복해 거절한 접기다. 기체마다 한 번만 적는다.
     *
     * 능력을 **못 물어봤으면 안 붙인다** — 널은 *아무것도 안 든다* 가 아니라 *모른다* 이고, 모를 때는
     * 막는 방향이다(§15.41).
     */
    private fun withOptionals(execution: Execution, unit: ExecutionUnit, base: Map<String, String>): Map<String, String> {
        val wanted = execution.capability.preferredOptionals
        if (wanted.isEmpty()) return base

        val declared = robots.capabilities(execution.robotId)
            ?.skillsList?.firstOrNull { it.skillType == unit.skillType }
            ?.parametersList?.map { it.key }?.toSet()

        val kept = if (declared == null) emptyMap() else wanted.filterKeys { it in declared }
        (wanted.keys - kept.keys).forEach { key ->
            if (!droppedOptionals.add(execution.robotId to key)) return@forEach
            val why = if (declared == null) "능력을 못 물어봤다" else "이 기종이 선언하지 않는다"
            execution.trail("OPTIONAL_NOT_SENT", "$key: $why — 요구하지 않고 보낸다")
        }
        return base + kept
    }

    /** 이미 적은 (기체, 선택 키). 같은 사실을 되풀이하면 운영자가 곧 무시한다. */
    private val droppedOptionals = mutableSetOf<Pair<String, String>>()

    private fun startUnit(execution: Execution, unit: ExecutionUnit) {
        // 재작업은 새 정체성이다 — 같은 task_id 는 계약이 같은(종착한) 핸들로 돌려준다.
        unit.taskId = "${execution.order.jobOrderId}#${unit.unitId}" + if (unit.attempt > 0) "@r${unit.attempt}" else ""
        unit.requestedAt = now()
        unit.lookups = 0
        execution.active = unit
        val accepted = try {
            lookupDownstream(execution, unit)
        } catch (_: RuntimeException) {
            // **요청은 갔는데 답이 없다.** 접수됐는지 모른다 — 다시 보내지 않는다(물리 작업이 둘이 될 수 있다).
            // 해소는 13.2 의 순서로 [resolveDoubt] 가 한다. 상류에는 미확정이라는 사실을 드러낸다(16장).
            unit.state = UnitState.IN_DOUBT
            execution.physicalState = PhysicalState.IN_DOUBT
            if (execution.notedDoubt != unit.unitId) {
                execution.notedDoubt = unit.unitId
                notify(execution)
            }
            return
        }
        if (!accepted) {
            execution.active = null
            return
        }
        unit.state = UnitState.RUNNING
        execution.physicalState = PhysicalState.RUNNING
    }

    // ── 근거 결합 (보고서 12장)

    /**
     * 하류의 완료를 받는다 — 그리고 **근거를 결합하기 시작한다**(보고서 10.2 — 번역만으로는 안 된다).
     *
     * 하류가 준 등급([reachedByDownstream]: 로봇 E0, 플릿 E1)이 요구 이상이면 그대로 완료.
     * 아니면 [UnitState.VERIFYING] 으로 옮기고 시간창이 닫힐 때까지([EvidenceWindow.after])
     * 설비에 묻는다 — PLC 는 폴링이라 신호가 보고보다 **늦게** 읽힐 수 있다. 첫 확인은 지금 한다.
     */
    private fun beginVerify(execution: Execution, unit: ExecutionUnit, reachedByDownstream: Evidence, doneAt: Instant, strict: Boolean = false) {
        unit.reached = reachedByDownstream
        unit.downstreamDoneAt = doneAt
        // `strict` — 하류의 보고만으로는 못 닫는다(지연 이벤트: 옛 버전의 완료). 설비가 지금 버전의 기대에 대고 봐야 한다.
        if (!strict && execution.order.requiredEvidence <= reachedByDownstream) {
            unit.verification = Verification.NOT_REQUESTED
            unit.state = UnitState.DONE
            return
        }
        unit.state = UnitState.VERIFYING
        unit.evidenceDeadline = doneAt.plus(execution.capability.evidenceWindow.after)
        checkEvidence(execution, unit)
    }

    /**
     * 시간창 안에서 설비 신호를 찾는다(보고서 12.1):
     * `t_p ∈ [t_r − before, t_r + after]` 이고 재석이면 **이 완료의** 근거다.
     *
     * - 맞으면 E2 `DONE`. 다르면 오인계 의심 — `FAILED` `VERIFICATION_MISMATCH`, 무엇을 어디서 봤는지 기록(12.3 넷째 행).
     * - 창 밖의 신호(옛 것)는 세지 않는다. 창이 닫힐 때까지 없으면 `UNVERIFIED` — 재작업 금지, 운영자 확인(첫째 행).
     * - 시각을 안 주는 설비는 **읽은 순간**을 `t_p` 로 친다.
     *
     * @return 단위가 종착했는가(아직 기다리는 중이면 `false`).
     */
    private fun checkEvidence(execution: Execution, unit: ExecutionUnit): Boolean {
        val doneAt = unit.downstreamDoneAt!!
        val window = execution.capability.evidenceWindow
        val current = now()
        unit.rechecks += 1

        val signal = unit.destination?.let { cell.observe(it) }
        val observedAt = signal?.observedAt ?: current
        val inWindow = signal != null && signal.occupied &&
            !observedAt.isBefore(doneAt.minus(window.before)) && !observedAt.isAfter(doneAt.plus(window.after))

        when {
            inWindow && unit.expectedIdentity != null && signal!!.identity != unit.expectedIdentity -> {
                unit.verification = Verification.MISMATCH
                unit.state = UnitState.FAILED
                unit.failureClass = MISMATCH
                unit.evidenceAt = observedAt
                unit.annotate("observed=${signal.identity} at ${unit.destination}")
            }

            inWindow -> {
                unit.verification = Verification.MATCHED
                unit.reached = Evidence.E2
                unit.evidenceAt = observedAt
                unit.state = UnitState.DONE
            }

            current.isAfter(unit.evidenceDeadline!!) -> {
                unit.verification = Verification.ABSENT
                unit.state = UnitState.UNVERIFIED
                unit.annotate(
                    if (signal != null && signal.occupied) {
                        "signal at $observedAt is outside [${doneAt.minus(window.before)}, ${doneAt.plus(window.after)}] — stale"
                    } else {
                        "no signal within window after ${unit.rechecks} rechecks"
                    },
                )
            }

            else -> {
                execution.physicalState = PhysicalState.RUNNING
                return false
            }
        }
        return true
    }

    /**
     * 하류는 실패라는데 설비에는 있을 수 있다(보고서 12.3 둘째 행 — *물리 완료 가능성*).
     * 요구 등급이 설비 확인을 포함하면 지금 묻고, 시간창 안에 기대한 것이 있으면 `FAILED` 로
     * 적지 않고 [UnitState.OPERATOR_HOLD] 로 세운다 — 운영자가 [resolve] 로 판단한다.
     */
    private fun failWithEvidenceCheck(execution: Execution, unit: ExecutionUnit, failureClass: String, detail: String, at: Instant) {
        unit.failureClass = failureClass
        unit.note = detail
        unit.downstreamDoneAt = at
        if (execution.order.requiredEvidence > Evidence.E1) {
            val window = execution.capability.evidenceWindow
            val signal = unit.destination?.let { cell.observe(it) }
            val observedAt = signal?.observedAt ?: now()
            val present = signal != null && signal.occupied &&
                (unit.expectedIdentity == null || signal.identity == unit.expectedIdentity) &&
                !observedAt.isBefore(at.minus(window.before)) && !observedAt.isAfter(at.plus(window.after))
            if (present) {
                unit.verification = Verification.MATCHED
                unit.evidenceAt = observedAt
                unit.state = UnitState.OPERATOR_HOLD
                unit.note = "downstream reported $failureClass ($detail) but evidence present at ${unit.destination}"
                return
            }
        }
        unit.state = UnitState.FAILED
    }

    /**
     * 운영자의 판단(12.3 둘째 행). [OperatorDecision.CONFIRM_DONE] 은 설비 근거로 완료(E2),
     * [OperatorDecision.REWORK] 는 그 단위를 **새 정체성**으로 다시 계획한다 — 자동으로 돌지 않았던 것을 사람이 돌린다.
     */
    fun resolve(executionId: String, unitId: String, decision: OperatorDecision): Boolean {
        val execution = executions[executionId] ?: return false
        val unit = execution.units.firstOrNull { it.unitId == unitId && it.state == UnitState.OPERATOR_HOLD } ?: return false
        when (decision) {
            OperatorDecision.CONFIRM_DONE -> {
                // 설비 근거가 있었으면 E2 다. 없이 사람이 확인한 것은 근거 등급을 올리지 않는다 — 그 사실이 note 에 남는다.
                if (unit.verification == Verification.MATCHED) unit.reached = Evidence.E2
                unit.state = UnitState.DONE
                unit.note = "operator confirmed" + (if (unit.verification == Verification.MATCHED) "" else " without equipment evidence") + ": ${unit.note}"
            }
            OperatorDecision.REWORK -> {
                unit.attempt += 1
                unit.state = UnitState.PENDING
                unit.taskId = ""
                unit.failureClass = null
                unit.verification = Verification.NOT_REQUESTED
                unit.note = "operator ordered rework"
            }
        }
        execution.notedHold = null
        execution.physicalState = PhysicalState.RUNNING
        return true
    }

    private fun settleExecution(execution: Execution) {
        val states = execution.units.map { it.state }
        val before = execution.physicalState
        execution.physicalState = when {
            states.any { it == UnitState.OPERATOR_HOLD } -> PhysicalState.OPERATOR_HOLD
            states.all { it == UnitState.DONE } -> PhysicalState.PHYSICALLY_DONE
            states.all { it == UnitState.FAILED } -> PhysicalState.FAILED
            states.any { it == UnitState.FAILED } -> PhysicalState.PARTIAL
            states.any { it == UnitState.UNVERIFIED } -> PhysicalState.UNVERIFIED
            states.any { it == UnitState.ABORTED } -> PhysicalState.ABORTED
            else -> PhysicalState.PHYSICALLY_DONE
        }
        if (before != execution.physicalState || execution.upstreamAck == UpstreamAck.NOT_SENT) notify(execution)
    }

    // ── 취소 (보고서 14)

    /** 취소를 건다. 실제 중단과 잔여 상태는 [pump] 가 하류를 읽고 [CancelReport] 로 확정한다. */
    fun cancel(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        if (execution.physicalState.isSettled && execution.physicalState != PhysicalState.PARTIAL) return false
        execution.cancelRequested = true
        execution.cancelRefusal = null
        execution.handle?.let { handle ->
            val response = robots.cancel(execution.robotId, handle)
            // **거절을 감추지 않는다.** 그 스킬이 취소를 안 들면(CANCEL_UNSUPPORTED) 진행 중 단위는 끝까지 가고 다음
            // 경계에서 멈춘다 — 그 사실이 취소 응답에 드러나야 상류가 중단점을 안다(16장).
            if (!response.hasState()) execution.cancelRefusal = response.rejection.code.name
        }
        execution.transport?.let { fleet.cancel(it) }
        execution.physicalState = PhysicalState.CANCELING
        return true
    }

    fun lastCancel(executionId: String): CancelReport? = executions[executionId]?.lastCancel

    private fun abort(execution: Execution, inProgress: ExecutionUnit?, hold: HoldState?, cleanup: String) {
        // 단위가 끝까지 갔으면(하류가 중단을 거절했거나, 취소가 닿기 전에 끝났거나) 중단된 단위가 아니라 **그 뒤에서 멈춘** 경계다.
        val refused = inProgress != null && (inProgress.state == UnitState.DONE || inProgress.state == UnitState.UNVERIFIED)
        val report = CancelReport(
            executionId = execution.executionId,
            version = execution.order.version,
            accepted = true,
            motionStopped = true,
            completedUnits = execution.completedUnits,
            inProgressUnit = if (refused) null else inProgress?.unitId,
            stoppedAfter = if (refused) inProgress.unitId else null,
            notStartedUnits = execution.units.filter { it.state == UnitState.PENDING }.map { it.unitId },
            // 든 단위가 없으면 마지막 관측(빈손) — JobResponse 와 같은 규칙이다(§15.145, 리뷰 C7).
            residualHold = hold ?: execution.units.lastOrNull { it.hold.kind != HoldKind.HOLD_KIND_UNSPECIFIED }?.hold ?: HoldState.getDefaultInstance(),
            cleanup = cleanup,
            finalState = PhysicalState.ABORTED,
            refusal = execution.cancelRefusal,
        )
        execution.lastCancel = report
        execution.physicalState = PhysicalState.ABORTED
        notify(execution)
    }

    // ── 결과 통보 (보고서 13.3 — 명령 재시도와 통보 재시도의 분리)

    private fun notify(execution: Execution) {
        val units = execution.units
        val response = JobResponse(
            jobResponseId = "resp-${++responseSeq}",
            jobOrderId = execution.order.jobOrderId,
            version = execution.order.version,
            physicalState = execution.physicalState,
            requiredEvidence = execution.order.requiredEvidence,
            reachedEvidence = units.filter { it.state == UnitState.DONE }.minOfOrNull { it.reached } ?: Evidence.E0,
            completedUnits = units.filter { it.state == UnitState.DONE }.map { it.unitId },
            unverifiedUnits = units.filter { it.state == UnitState.UNVERIFIED }.map { it.unitId },
            incompleteUnits = units.filter { it.state != UnitState.DONE && it.state != UnitState.UNVERIFIED }
                .associate { it.unitId to (it.failureClass ?: it.note ?: it.state.name) },
            operatorRequired = units.any {
                it.state == UnitState.UNVERIFIED || it.state == UnitState.OPERATOR_HOLD ||
                    it.verification == Verification.MISMATCH || it.failureClass == SOURCE_MISMATCH
            } || execution.lastCancel?.cleanup == "failed" || execution.blockedBy.isNotEmpty(),
            // 든 단위가 있으면 그것, 없으면 마지막으로 관측한 파지(빈손) — "빈손" 을 "말하지 않았다" 로 접지 않는다(§15.145).
            residualHold = units.lastOrNull { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }?.hold
                ?: units.lastOrNull { it.hold.kind != HoldKind.HOLD_KIND_UNSPECIFIED }?.hold
                ?: HoldState.getDefaultInstance(),
            inDoubtUnits = units.filter { it.state == UnitState.IN_DOUBT }.map { it.unitId },
            results = units.filter { it.state == UnitState.DONE && !it.result.isNullOrBlank() }.associate { it.unitId to it.result!! },
            blockedBy = execution.blockedBy.map { canonicalClassOf(it) },
            connection = (views[execution.robotId]?.takeIf { it.observable }?.connection ?: ConnectionState.CONNECTION_STATE_UNSPECIFIED).name,
            autoResolvesInDoubt = units.all {
                (if (it.route == Route.ROBOT) robots.executionLookup else fleet.executionLookup) == ExecutionLookup.CLIENT_REFERENCE
            },
        )
        outbox += response
        execution.upstreamAck = UpstreamAck.SENT_UNACKED
    }

    /** 상류가 결과를 확정했다 — 두 축 중 둘째가 닫힌다. */
    fun ack(jobResponseId: String): Boolean {
        val response = outbox.firstOrNull { it.jobResponseId == jobResponseId } ?: return false
        response.ack = UpstreamAck.ACKED
        executions.values.firstOrNull { it.order.jobOrderId == response.jobOrderId }?.upstreamAck = UpstreamAck.ACKED
        return true
    }

    /** 사정을 덧붙인다 — 앞의 것을 지우지 않는다. 지연 이벤트·미확정 같은 사정은 종착 사유와 함께 남아야 한다. */
    private fun ExecutionUnit.annotate(message: String) {
        note = if (note.isNullOrBlank()) message else "$note; $message"
    }

    /** 계약의 정준 분류 이름(접두사 없이). 결함이 없거나 분류가 비어 있으면 [UNCLASSIFIED]. */
    private fun canonicalClass(update: WatchTaskResponse): String =
        if (update.hasFault()) canonicalClassOf(update.fault) else UNCLASSIFIED

    private fun canonicalClassOf(fault: Fault): String =
        fault.failureClass
            .takeIf { it != FailureClass.FAILURE_CLASS_UNSPECIFIED && it != FailureClass.UNRECOGNIZED }
            ?.name?.removePrefix("FAILURE_CLASS_")
            ?: UNCLASSIFIED

    /** 하류가 말한 그대로 — 상태 이름, 모드 이름, 벤더 원문. 로그의 것이지 분기의 것이 아니다. */
    private fun downstreamDetail(update: WatchTaskResponse): String = buildString {
        append("downstream=").append(update.state.name)
        if (update.hasFault()) {
            if (update.fault.errorType.isNotBlank()) append(" error_type=").append(update.fault.errorType)
            if (update.fault.vendorDetail.isNotBlank()) append(" vendor=").append(update.fault.vendorDetail)
        }
    }

    /** 하류 갱신의 시각 — 헤더의 `state_as_of`(§5.5). 없거나 못 읽으면 지금. */
    private fun stateTime(update: WatchTaskResponse): Instant = try {
        update.header.stateAsOf.takeIf { it.isNotBlank() }?.let { Instant.parse(it) } ?: now()
    } catch (_: DateTimeParseException) {
        now()
    }

    private fun TaskState.isTerminal(): Boolean = this == TaskState.TASK_STATE_SUCCEEDED ||
        this == TaskState.TASK_STATE_FAILED || this == TaskState.TASK_STATE_CANCELLED ||
        this == TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED ||
        // 사람이 와야 진행되는 것은 이 층에서 단위의 종착이다 — 운영자 판단은 상류의 것.
        this == TaskState.TASK_STATE_NEEDS_INTERVENTION || this == TaskState.TASK_STATE_RETRIABLE

    companion object {
        /** 검증 불일치 — 하류는 성공이라는데 설비가 다른 것을 봤다(보고서 12.3 넷째 행). */
        const val MISMATCH = "VERIFICATION_MISMATCH"

        /** 출발지의 용기가 요청과 달라 인수하지 않았다(보고서 5장 상황표 둘째 행). */
        const val SOURCE_MISMATCH = "SOURCE_CONTAINER_MISMATCH"

        /** 목적지가 비어 있지 않아 인계를 기다린다(같은 표 첫째 행) — 실패가 아니라 지연이다. */
        const val WAITING_HANDOVER = "WAITING_HANDOVER"

        const val FLEET_REJECTED = "FLEET_REJECTED"

        /** 계약 `FailureClass.UNCLASSIFIED` 의 이름 — 하류가 분류를 안 실었을 때의 값. 지어낸 분류가 아니다. */
        const val UNCLASSIFIED = "UNCLASSIFIED"
    }
}
