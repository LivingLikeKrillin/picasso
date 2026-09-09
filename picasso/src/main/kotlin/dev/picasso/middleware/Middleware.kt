package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse

/**
 * 공통 실행 구조(설계 §2) — 접수 · 조합 · 실행 상태기계 · 근거 결합 · 취소 · 결과 통보.
 *
 * ## 구동
 *
 * 스레드가 없다. [submit]·[cancel]·[ack] 가 밖에서 오고, 하류의 전이는 [pump] 를
 * 부를 때 읽는다. 시험이 가상 시계를 밀고 `pump()` 를 부르는 결정적 구동에 맞춘
 * 것이며(§12.1), 운영 배치에서는 스케줄러가 `pump()` 를 돌린다.
 *
 * ## 기체
 *
 * 실행 하나는 기체 하나에서 **차례로** 돈다 — 실물은 예외 없이 배타적 제어
 * 모델이고(§4.9) 어느 기체에 줄지(배차)는 이 모듈의 일이 아니다(ADR 38). 상류가
 * 지정하거나 배치가 정한 기체를 [submit] 이 받는다.
 */
class Middleware(
    private val robots: RobotPort,
    private val cell: CellSignals = CellSignals.None,
    capabilities: List<LogicalCapability> = listOf(PrepareSequencedRack()),
) {
    private val capabilities = capabilities.associateBy { it.workMasterId }
    private val executions = linkedMapOf<String, Execution>()
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
        internal var cancelRequested = false
        internal var lastCancel: CancelReport? = null

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

        val existing = executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }
        if (existing != null) return revise(existing, order)

        val execution = Execution(
            executionId = "exec-${executions.size + 1}",
            order = order,
            robotId = robotId,
            capability = capability,
            units = capability.plan(order).toMutableList(),
        )
        execution.units.forEach { it.revision = order.version }
        execution.physicalState = PhysicalState.ACCEPTED
        executions[execution.executionId] = execution
        return Submission.Accepted(execution)
    }

    private fun revise(execution: Execution, order: JobOrder): Submission {
        if (order.version == execution.order.version) return Submission.Idempotent(execution)
        if (order.version < execution.order.version) {
            return Submission.Rejected("이미 지난 버전이다: 받은 값=${order.version}, 현재=${execution.order.version}")
        }
        if (execution.physicalState == PhysicalState.ABORTED || execution.physicalState == PhysicalState.PHYSICALLY_DONE) {
            return Submission.Rejected("종착한 실행에는 새 버전이 붙지 않는다 — 재작업은 새 주문이다(보고서 14.1)")
        }

        // **확정된 단위 이후에만 붙는다.** 종착한 단위는 그대로(래치 — 계약이 이미 그렇게 한다),
        // 아직 안 시작한 단위는 새 계획으로 교체, 도는 단위는 계약의 갱신 규칙(§4.4)을 탄다.
        val replanned = execution.capability.plan(order).associateBy { it.unitId }
        val kept = execution.units.map { unit ->
            when (unit.state) {
                UnitState.DONE, UnitState.UNVERIFIED, UnitState.ABORTED -> unit
                // 물리적으로 시도된 실패는 확정된 단위다. 계획 단계에서 못 시작한 것(태스크가 없다)은
                // 아니다 — C형 부족처럼 새 버전이 공급을 채우면 그 슬롯은 다시 계획된다.
                UnitState.FAILED -> if (unit.taskId.isEmpty()) {
                    (replanned[unit.unitId] ?: unit).also { it.revision = order.version }
                } else {
                    unit
                }
                UnitState.PENDING -> (replanned[unit.unitId] ?: unit).also { it.revision = order.version }
                UnitState.RUNNING -> {
                    val fresh = replanned[unit.unitId]
                    if (fresh != null) {
                        unit.revision = order.version
                        val response = robots.start(execution.robotId, unit.taskId, order.version, unit.skillType, fresh.parameters)
                        if (!response.hasHandle()) {
                            unit.state = UnitState.FAILED
                            unit.failureClass = response.rejection.code.name
                        }
                    }
                    unit
                }
            }
        }
        // 새 버전에 새로 생긴 슬롯
        val added = replanned.values.filter { fresh -> kept.none { it.unitId == fresh.unitId } }
            .onEach { it.revision = order.version }
        execution.units.clear()
        execution.units.addAll(kept + added)
        execution.order = order
        // 부분 완료로 정착해 통보까지 나갔던 실행이 다시 산다.
        if (execution.physicalState == PhysicalState.PARTIAL || execution.physicalState == PhysicalState.UNVERIFIED) {
            execution.physicalState = PhysicalState.RUNNING
        }
        return Submission.Accepted(execution)
    }

    // ── 구동

    /** 하류에서 온 것을 읽고 상태를 한 걸음 민다. 몇 번 불러도 같은 결과다(멱등). */
    fun pump() {
        executions.values.forEach { pump(it) }
    }

    private fun pump(execution: Execution) {
        if (execution.physicalState.isSettled && execution.physicalState != PhysicalState.PARTIAL) return

        val active = execution.active
        if (active != null) {
            val updates = robots.watch(execution.robotId, execution.handle!!)
            val last = updates.lastOrNull() ?: return
            active.hold = last.hold
            if (!last.state.isTerminal()) {
                execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
                return
            }
            settleUnit(execution, active, last)
            execution.active = null
            execution.handle = null
            if (execution.cancelRequested) {
                abort(execution, active, last)
                return
            }
        }

        if (execution.cancelRequested) {
            abort(execution, inProgress = null, last = null)
            return
        }

        val next = execution.units.firstOrNull { it.state == UnitState.PENDING }
        if (next == null) {
            settleExecution(execution)
            return
        }
        startUnit(execution, next)
    }

    private fun startUnit(execution: Execution, unit: ExecutionUnit) {
        unit.taskId = "${execution.order.jobOrderId}#${unit.unitId}"
        val response = robots.start(execution.robotId, unit.taskId, unit.revision, unit.skillType, unit.parameters)
        if (!response.hasHandle()) {
            unit.state = UnitState.FAILED
            unit.failureClass = response.rejection.code.takeIf { it != RejectionCode.REJECTION_CODE_UNSPECIFIED }?.name
                ?: "REJECTED"
            return
        }
        unit.state = UnitState.RUNNING
        execution.active = unit
        execution.handle = response.handle
        execution.physicalState = PhysicalState.RUNNING
    }

    /**
     * 원자 태스크의 종착을 단위의 종착으로 — 그리고 **근거를 결합**한다(보고서 10.2 —
     * 번역만으로는 안 된다).
     *
     * 로봇의 `SUCCEEDED` 는 E0 다. 요구 등급이 그보다 높으면 셀 검증 장치에 묻는다:
     * 신호가 기대와 맞으면 E2 도달, 없으면 `UNVERIFIED`(재작업 금지, 운영자 확인),
     * 다르면 오인계 의심으로 `FAILED`(보고서 12.3). 시간창 δ 는 다음 단계에서 붙는다.
     */
    private fun settleUnit(execution: Execution, unit: ExecutionUnit, last: WatchTaskResponse) {
        when (last.state) {
            TaskState.TASK_STATE_SUCCEEDED -> {
                unit.reached = Evidence.E0
                if (execution.order.requiredEvidence <= Evidence.E0) {
                    unit.verification = Verification.NOT_REQUESTED
                    unit.state = UnitState.DONE
                    return
                }
                val signal = unit.destination?.let { cell.observe(it) }
                when {
                    signal == null || !signal.occupied -> {
                        unit.verification = Verification.ABSENT
                        unit.state = UnitState.UNVERIFIED
                    }
                    unit.expectedMaterial != null && signal.material != unit.expectedMaterial -> {
                        unit.verification = Verification.MISMATCH
                        unit.state = UnitState.FAILED
                        unit.failureClass = MISMATCH
                    }
                    else -> {
                        unit.verification = Verification.MATCHED
                        unit.reached = Evidence.E2
                        unit.state = UnitState.DONE
                    }
                }
            }

            TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> {
                unit.state = UnitState.ABORTED
            }

            else -> {
                unit.state = UnitState.FAILED
                // 계약이 실은 정준 분류(어댑터가 벤더 코드에서 옮긴 것). 안 실렸으면 상태 이름으로 남긴다 —
                // 지어내지 않는다.
                unit.failureClass = last.fault.errorType.takeIf { it.isNotBlank() } ?: last.state.name
            }
        }
    }

    private fun settleExecution(execution: Execution) {
        val states = execution.units.map { it.state }
        val before = execution.physicalState
        execution.physicalState = when {
            states.all { it == UnitState.DONE } -> PhysicalState.PHYSICALLY_DONE
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
        val handle = execution.handle
        if (handle != null) {
            val response = robots.cancel(execution.robotId, handle)
            if (!response.hasState()) {
                // 취소 불가 스킬(CANCEL_UNSUPPORTED) — 감추지 않는다. 도는 단위는 끝까지 가고 그 뒤에 멈춘다.
                execution.physicalState = PhysicalState.CANCELING
                return true
            }
        }
        execution.physicalState = PhysicalState.CANCELING
        return true
    }

    fun lastCancel(executionId: String): CancelReport? = executions[executionId]?.lastCancel

    private fun abort(execution: Execution, inProgress: ExecutionUnit?, last: WatchTaskResponse?) {
        val report = CancelReport(
            executionId = execution.executionId,
            version = execution.order.version,
            accepted = true,
            motionStopped = last == null || last.state.isTerminal(),
            completedUnits = execution.completedUnits,
            inProgressUnit = inProgress?.unitId,
            notStartedUnits = execution.units.filter { it.state == UnitState.PENDING }.map { it.unitId },
            residualHold = last?.hold ?: HoldState.getDefaultInstance(),
            cleanup = when (last?.state) {
                TaskState.TASK_STATE_CANCELLED -> "done"
                TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> "failed"
                null -> "nothing_to_clean"
                else -> "not_applicable"
            },
            finalState = PhysicalState.ABORTED,
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
            incompleteUnits = units.filter { it.state == UnitState.FAILED || it.state == UnitState.ABORTED || it.state == UnitState.PENDING }
                .associate { it.unitId to (it.failureClass ?: it.state.name) },
            operatorRequired = units.any { it.state == UnitState.UNVERIFIED || it.verification == Verification.MISMATCH } ||
                execution.lastCancel?.cleanup == "failed",
            residualHold = units.lastOrNull { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }?.hold ?: HoldState.getDefaultInstance(),
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

    private fun TaskState.isTerminal(): Boolean = this == TaskState.TASK_STATE_SUCCEEDED ||
        this == TaskState.TASK_STATE_FAILED || this == TaskState.TASK_STATE_CANCELLED ||
        this == TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED ||
        // 사람이 와야 진행되는 것은 이 층에서 단위의 종착이다 — 운영자 판단은 상류의 것.
        this == TaskState.TASK_STATE_NEEDS_INTERVENTION || this == TaskState.TASK_STATE_RETRIABLE

    companion object {
        /** 검증 불일치 — 로봇은 성공이라는데 설비가 다른 자재를 봤다(보고서 12.3 넷째 행). */
        const val MISMATCH = "VERIFICATION_MISMATCH"
    }
}
