package dev.picasso.middleware

import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ProgressBasis
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse

/**
 * **응답 유실**의 더블 — 요청은 하류에 닿고 답만 오지 않는다(보고서 17장 3번 *"요청 직후 연결 단절"*).
 *
 * 미믹의 gRPC 에는 장애 주입이 없다(`InjectTransportFault` 는 발행 축이다). 그리고 잃어버리는 것은 미들웨어와 하류
 * 사이의 선이므로 그 선의 더블이 여기 있는 것이 맞다 — 하류는 정상적으로 접수했다. 그래서 위임한 **뒤에** 던진다.
 */
class LossyRobotPort(private val delegate: RobotPort, private var dropStartResponses: Int) : RobotPort {
    var starts = 0
        private set

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
        starts += 1
        val response = delegate.start(robotId, taskId, revision, skillType, parameters)
        if (dropStartResponses > 0) {
            dropStartResponses -= 1
            throw IllegalStateException("response lost after the request reached the robot")
        }
        return response
    }

    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = delegate.watch(robotId, handle)
    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = delegate.cancel(robotId, handle)
    override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)
    override fun replay(robotId: String, from: Long): Replay? = delegate.replay(robotId, from)
}

/** 상태를 **못 물어보는** 포트 — 스냅샷이 안 열린다. `null` 은 "없음" 이 아니라 "모름" 이고, 엔진은 그 둘을 다르게 다뤄야 한다. */
class FaultBlindRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun snapshot(robotId: String): RobotSnapshot? = null
    override fun replay(robotId: String, from: Long): Replay? = null
}

/** 재생 버퍼가 **한 번 축출**된 것처럼 답하는 포트 — 소비자가 스냅샷부터 다시 세우는지 본다(17장 9번). */
class EvictingRobotPort(private val delegate: RobotPort, private var evictions: Int = 1) : RobotPort by delegate {
    var replays = 0
        private set

    override fun replay(robotId: String, from: Long): Replay? {
        replays += 1
        if (evictions > 0) {
            evictions -= 1
            return Replay.Evicted
        }
        return delegate.replay(robotId, from)
    }
}

/**
 * 플릿 쪽의 같은 더블 — 그리고 **조회 능력을 선언**한다. 조사한 실물 하류 셋이 전부 클라이언트 참조 키를 안 받으므로
 * (보고서 13.2), [ExecutionLookup.NONE] 인 플릿이 현실에 가깝다. 그때 `IN_DOUBT` 는 자동으로 안 풀리고 운영자에게 간다.
 */
class LossyFleet(
    private val delegate: AmrFleetPort,
    private var dropDispatchResponses: Int,
    override val executionLookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE,
) : AmrFleetPort {
    override fun dispatch(order: TransportOrder): TransportHandle? {
        val handle = delegate.dispatch(order)
        if (dropDispatchResponses > 0) {
            dropDispatchResponses -= 1
            throw IllegalStateException("response lost after the fleet accepted the order")
        }
        return handle
    }

    override fun status(handle: TransportHandle): TransportStatus = delegate.status(handle)
    override fun cancel(handle: TransportHandle): Boolean = delegate.cancel(handle)
}

/**
 * 진행률의 **근거**를 갈아 끼우는 포트 — 실물의 두 갈래를 미믹 위에서 만든다.
 *
 * 미믹은 언제나 잰다(경과 시간 비율). 실물은 그렇지 않다 — Orbit·Digit 은 세고 Spot·G1 은 못 잰다(§15.108).
 * 그 둘을 미들웨어에 보이려면 여기서 만드는 수밖에 없다. **값을 지어내는 것이 아니라 계약의 자격 필드를
 * 갈아 끼우는 것**이며, 실물이 그 자리에 무엇을 싣는지는 어댑터 시험이 따로 본다.
 */
class ProgressPort(
    private val delegate: RobotPort,
    /** 널이면 하류가 낸 것을 그대로 둔다. */
    private val basis: ProgressBasis?,
    /** 널이 아니면 진행률을 이 값에 **묶는다** — 재는데 안 움직이는 기체를 만든다. */
    private val pinned: Double? = null,
) : RobotPort by delegate {
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        delegate.watch(robotId, handle).map { response ->
            response.toBuilder()
                .also { b -> basis?.let(b::setProgressBasis) }
                .also { b -> pinned?.let(b::setProgress) }
                .build()
        }
}

/** 능력을 못 묻는 포트 — 계획 시점 사슬 검사가 건너뛰어야 하는 경우를 만든다(리뷰 C10). */
class CapabilityBlindRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun capabilities(robotId: String): Capability? = null
}

/** 로봇이 계약에 없는 주어(99)로 조건을 선언한 것처럼 보이게 하는 포트 — 새 계약의 발신자 흉내(리뷰 C5). */
class AlienSubjectRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun capabilities(robotId: String): Capability? {
        val real = delegate.capabilities(robotId) ?: return null
        val builder = real.toBuilder()
        builder.skillsBuilderList.filter { it.skillType == "navigate_to" }.forEach { skill ->
            skill.addPreconditions(Precondition.newBuilder().setSubjectValue(99).setRequires(HoldKind.HOLD_KIND_EMPTY))
        }
        return builder.build()
    }
}

/**
 * 무엇을 보내든 발신자가 **사전 조건으로 거절**하는 포트. 실물의 거절을 흉내내는 것이 목적이 아니라,
 * 사건이 **첫 pump 에** 일어나 가상 시계가 원점에 머무르게 하는 것이 목적이다 — 번들 해시를 두 판
 * 사이에서 대조하려면 사건 시각이 구동 횟수에 흔들리면 안 된다.
 */
class PreconditionRefusingRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun start(
        robotId: String,
        taskId: String,
        revision: Int,
        skillType: String,
        parameters: Map<String, String>,
    ): StartTaskResponse = StartTaskResponse.newBuilder()
        .setRejection(
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET)
                .setDetail("HOLD: 요구=EMPTY 관측=HOLDING")
                .addReferences(
                    Reference.newBuilder()
                        .setKey(Reference.Key.KEY_PRECONDITION_SUBJECT)
                        .setValue("HOLD"),
                ),
        )
        .build()
}

/**
 * 파지를 **볼 수 없는** 기체 — 갱신의 파지를 `NOT_OBSERVABLE` 로 덮는다.
 *
 * 미믹은 언제나 관측한다(못 보는 기종이 아니다). 그래서 설계안 §10.1 시나리오 4(관측 불가)를 만들 수단이
 * 엔진 쪽에 없고, 만들려고 미믹을 고치면 다른 시험들이 기대는 성질이 흔들린다. 못 보는 것은 **선의 성질**이
 * 아니라 기체의 성질이지만, 여기서는 그 기체를 흉내내는 더블이 이 자리에 있는 것이 맞다.
 */
class HoldBlindRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        delegate.watch(robotId, handle).map { update ->
            update.toBuilder()
                .setHold(HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_NOT_OBSERVABLE))
                .build()
        }
}

/**
 * **그리퍼가 안 열린 기체** — 하류는 성공했다는데 끝난 갱신의 파지가 여전히 든 채다.
 *
 * 미믹은 성공에 언제나 빈손으로 두므로 이 상황을 못 만든다. 설계안 §5.1 표 둘째 줄(놓고 끝나야 하는데
 * 들고 있음)이 겨냥하는 현실이고, 실물에서는 파지 해제 실패가 이 모양으로 온다.
 */
class StuckGripperRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        delegate.watch(robotId, handle).map { update ->
            if (update.state != TaskState.TASK_STATE_SUCCEEDED) {
                update
            } else {
                update.toBuilder()
                    .setHold(HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).setObjectRef("stuck"))
                    .build()
            }
        }
}
