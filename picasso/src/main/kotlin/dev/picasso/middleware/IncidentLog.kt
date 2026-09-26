package dev.picasso.middleware

import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.middleware.Middleware.Execution
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 사건 번들의 장부(설계안 §4) — 흩어진 사실을 한 사건으로 묶고, 사람의 검토와 판단을 그 위에 싣는다.
 *
 * **이 층의 거동을 바꾸지 않는다.** 봉인은 [Middleware] 가 라운드 끝에 부르고([sealIncidents]), 여기서는
 * 실행을 읽기만 한다. 실행에 쓰는 것은 단 하나, 봉인한 단위의 표시(`pendingIncidents`)를 비우는 일이다.
 */
internal class IncidentLog(
    private val robots: RobotPort,
    private val now: () -> Instant,
    private val wallClock: () -> Instant,
) {

    /** 열린 사건들(설계안 §4). **조회만 한다** — 이 목록이 이 층의 거동을 바꾸지 않는다. */
    private val incidentLog = mutableListOf<IncidentBundle>()
    private var incidentSeq = 0

    /** 열린 순서대로. */
    fun incidents(): List<IncidentBundle> = incidentLog.toList()

    fun incident(incidentId: String): IncidentBundle? = incidentLog.firstOrNull { it.incidentId == incidentId }

    /**
     * 사람이 사건을 읽고 판정을 남긴다(설계안 §7.2). 원인 지목은 가설이고 정답은 정비 실적과 재발
     * 여부로 나중에 나온다 — 되먹이지 않으면 정답 라벨 없는 자동 진단이 영원히 검증되지 않는다.
     * **자동으로 채우지 않는다.** 동의도 사람이 눌러야 동의다.
     */
    fun reviewIncident(incidentId: String, verdict: ReviewVerdict, cause: String): Boolean {
        val at = incidentLog.indexOfFirst { it.incidentId == incidentId }
        if (at < 0) return false
        incidentLog[at] = incidentLog[at].copy(review = IncidentReview(verdict, cause, wallClock()))
        return true
    }

    /**
     * 승인자 종류별로 가른 것(ADR 43 §4) — **에이전트 승인의 이의율은 사람 승인과 따로 잰다.**
     *
     * 이 지표가 재는 것은 자원의 상태가 아니라 **판단 주체의 성능**이다. 에이전트 승인이 바로 그
     * 시험 대상이므로 한 통에 담으면 사람 승인이 그것을 희석한다. 반복 카운터를 안 가르는 것과
     * 반대인 이유가 그것이다 — 가를지는 재는 대상이 자원인지 주체인지로 갈린다.
     *
     * **승인을 거치지 않은 사건은 어느 통에도 안 들어간다.** 대부분의 사건이 그렇고, 그것을 사람 쪽에
     * 몰아 넣으면 사람 승인의 이의율이 승인과 무관한 사건으로 희석된다.
     */
    fun reviewMetricsByApprover(since: Instant? = null): Map<ApproverKind, ReviewMetrics> = incidentLog
        .filter { since == null || !it.wallClockAt.isBefore(since) }
        .mapNotNull { bundle -> bundle.approvedBy?.let { it.kind to bundle } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, scope) ->
            ReviewMetrics(
                total = scope.size,
                reviewed = scope.count { it.review != null },
                disputed = scope.count { it.review?.verdict == ReviewVerdict.DISPUTED },
            )
        }

    /**
     * 검토가 실제로 일어나는가, 그리고 자동 진단이 맞는가(설계안 §7.2 둘째).
     *
     * @param since 실 시계 기준 이 시각부터의 사건만. 교대 단위로 보라고 있는 자리다. 널이면 전부.
     */
    fun reviewMetrics(since: Instant? = null): ReviewMetrics {
        val scope = incidentLog.filter { since == null || !it.wallClockAt.isBefore(since) }
        return ReviewMetrics(
            total = scope.size,
            reviewed = scope.count { it.review != null },
            disputed = scope.count { it.review?.verdict == ReviewVerdict.DISPUTED },
        )
    }

    /**
     * 라운드 끝에 번들을 봉한다. **전이 순간이 아니다** — 단위가 닫히는 그 자리에서는 실행 수준의
     * 사실(무엇이 다음 단위를 막는가)이 아직 안 정해져 있고, 그것 없이 묶으면 번들이 "단위의 문제인지
     * 기체의 문제인지" 를 가르지 못한다(설계안 §4.2 넷째 줄).
     */
    fun sealIncidents(execution: Execution) {
        if (execution.pendingIncidents.isEmpty()) return
        val at = now()
        val wall = wallClock()
        val window = execution.capability.evidenceWindow
        val inWindow = execution.eventTrail.filter { within(it, at.minus(window.before), at.plus(window.after)) }
        execution.pendingIncidents.forEach { unitId ->
            val unit = execution.units.firstOrNull { it.unitId == unitId } ?: return@forEach
            incidentLog += IncidentBundle(
                incidentId = "incident-${++incidentSeq}",
                jobOrderId = execution.order.jobOrderId,
                executionId = execution.executionId,
                robotId = execution.robotId,
                unitId = unit.unitId,
                at = at,
                wallClockAt = wall,
                failureClass = unit.failureClass,
                fault = unit.fault?.let { faultDetailOf(it) },
                blockedBy = execution.blockedBy.map { faultDetailOf(it) },
                residualHold = residualHoldOf(execution.units),
                unresolved = unit.state == UnitState.OPERATOR_HOLD || unit.state == UnitState.IN_DOUBT,
                preconditionSubjects = unit.preconditionSubjects,
                evidenceWindow = inWindow,
                windowTruncated = inWindow.size < execution.eventTrail.size,
                effectMismatch = unit.holdMismatch?.name,
                expectedHold = unit.holdExpected,
                observedHold = unit.hold.kind,
                requiredEvidence = execution.order.requiredEvidence,
                reachedEvidence = unit.reached,
                verification = unit.verification,
                step = StepPosition(
                    at = execution.units.indexOfFirst { it.unitId == unit.unitId } + 1,
                    plan = execution.units.map { it.unitId },
                    completed = execution.completedUnits,
                ),
                route = unit.route.name,
                intent = Intent(
                    workMasterId = execution.order.workMasterId,
                    orderVersion = execution.order.version,
                    orderParameters = execution.order.parameters,
                    materials = execution.order.materialRequirements,
                    equipment = execution.order.equipmentRequirements,
                    capabilityMaxEvidence = execution.capability.maxEvidence,
                    evidenceWindowBefore = window.before.toString(),
                    evidenceWindowAfter = window.after.toString(),
                    skillType = unit.skillType,
                    unitParameters = unit.parameters,
                    source = unit.source,
                    destination = unit.destination,
                    expectedIdentity = unit.expectedIdentity,
                ),
                observation = ObservationTrust(
                    linkBroken = execution.linkBroken,
                    lateEvents = execution.lateEvents.filter { it.unitId == unit.unitId },
                    progressObservable = unit.progressObservable,
                    progressStalled = unit.progressStalled,
                ),
                profileRevision = robots.capabilities(execution.robotId)?.profileRevision ?: 0,
                contractSemver = ContractIdentity.semver,
                approvedBy = execution.approvedBy,
            )
        }
        execution.pendingIncidents.clear()
    }

    /**
     * 창 안인가. **시각을 못 읽는 관측은 버리지 않는다** — 읽을 수 없다는 것이 창 밖이라는 뜻은 아니고,
     * 조용히 빼면 창이 완전한 것처럼 보인다.
     */
    private fun within(event: ObservedEvent, from: Instant, to: Instant): Boolean {
        val at = try {
            Instant.parse(event.occurredAt)
        } catch (_: DateTimeParseException) {
            return true
        }
        return !at.isBefore(from) && !at.isAfter(to)
    }

    /**
     * 운영자의 판단을 **아직 판단이 안 실린 가장 최근의** 사건에 붙인다. 부르는 쪽은 [Middleware.resolve] 다.
     */
    fun noteResolution(executionId: String, unitId: String, decision: OperatorDecision) {
        val opened = incidentLog.indexOfLast {
            it.executionId == executionId && it.unitId == unitId && it.resolution == null
        }
        if (opened >= 0) {
            incidentLog[opened] = incidentLog[opened]
                .copy(resolution = IncidentResolution(decision, now(), wallClock()))
        }
    }
}
