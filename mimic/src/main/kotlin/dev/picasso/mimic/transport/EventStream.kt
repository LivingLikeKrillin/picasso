package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.CapabilityChanged
import dev.picasso.contracts.v1.CapabilityChangeCause
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.FaultEvent
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.SkillTransition
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskTransition
import dev.picasso.contracts.v1.SkillSnapshot
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.EngineListener
import dev.picasso.mimic.engine.SkillState
import dev.picasso.mimic.engine.TaskState
import dev.picasso.mimic.engine.toProto
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import dev.picasso.uplink.Publication
import dev.picasso.uplink.Publisher
import dev.picasso.uplink.Topics

/**
 * 엔진이 보고한 전이에 **발행 축**을 입힌다(§4.7·§4.8).
 *
 * 엔진은 `sequence`를 모른다 — 그것은 발행 축이고 §3.5가 `update_index`와
 * **다른 축**이라고 못박았다. 나누지 않으면 gRPC에만 나가는 진행률이 소비한
 * 번호를 MQTT 소비자가 결손으로 오탐한다.
 *
 * **기체마다 하나다**(§4.8). 세션도 시퀀스도 재생 버퍼도 전부 기체 단위다.
 */
class EventStream(
    private val instance: RobotInstance,
    private val publisher: Publisher,
    private val site: String = "default",
    private val contractMajor: Int = ContractIdentity.major,
) : EngineListener {

    /**
     * §4.8 — 세션 안에서 **0부터** 단조 증가한다.
     *
     * `capability_epoch`처럼 1에서 시작해 "미설정과 구별" 문제를 피할 수 없다 —
     * §4.8이 0을 못박았다. 대신 `schema_id`와 토픽이 방향을 말하므로 실질
     * 문제가 없다(§15.25).
     */
    private val next = AtomicLong(0)

    /** 다음에 붙을 번호. 스냅샷이 "아직 반영 안 된 첫 번호"로 싣는다. */
    val nextSequence: Long get() = next.get()

    /** §4.8 — 세션 안에서 기체마다 마지막 `N`개. `N`은 프로파일이 선언한다. */
    private val buffer = ArrayDeque<Event>()

    private val bufferSize: Int = instance.document.replayBufferSize

    private val eventCounter = AtomicLong()

    val buffered: List<Event> get() = buffer.toList()

    /**
     * 재생 버퍼에서 **실제로 버려진** 가장 높은 . 아직 아무것도
     * 안 버렸으면 이다.
     */
    var evictedUpTo: Long? = null
        private set

    /**
     * 아직 브로커에 못 보낸 첫 `sequence`. 없으면 전부 나갔다.
     *
     * §10.6의 "단절 중 버퍼링"이 이 하나로 표현된다 — **따로 큐를 두지
     * 않는다.** 재생 버퍼가 이미 그 이벤트들을 들고 있고, 못 보낸 경계만
     * 알면 재연결 때 어디서부터 밀지 정해진다. 큐를 따로 두면 같은 이벤트가
     * 두 곳에 있게 되고, 둘이 어긋난 날 무엇이 진실인지 알 수 없다.
     */
    var unsentFrom: Long? = null
        private set

    /** 지금 못 보내고 쌓여 있는가. 시험과 진단이 본다. */
    val disconnected: Boolean get() = unsentFrom != null

    // ── 엔진의 보고를 이벤트로

    override fun onSkillTransition(
        taskId: String,
        skillType: String,
        from: SkillState,
        to: SkillState,
    ) = emit { header ->
        Event.newBuilder().setHeader(header).setSkillTransition(
            SkillTransition.newBuilder()
                .setSkillType(skillType).setTaskId(taskId)
                .setFrom(from.toProto()).setTo(to.toProto()),
        ).build()
    }

    override fun onTaskTransition(
        taskId: String,
        skillType: String,
        from: TaskState?,
        to: TaskState,
        revision: Int,
        attempt: Int,
    ) = emit { header ->
        Event.newBuilder().setHeader(header).setTaskTransition(
            TaskTransition.newBuilder()
                .setTaskId(taskId).setSkillType(skillType)
                // from이 없으면 접수다 — 계약에서는 UNSPECIFIED로 나간다.
                .setFrom(from?.toProto() ?: dev.picasso.contracts.v1.TaskState.TASK_STATE_UNSPECIFIED)
                .setTo(to.toProto())
                .setRevision(revision).setAttempt(attempt),
        ).build()
    }

    override fun onFault(fault: Fault, cleared: Boolean) = emit { header ->
        Event.newBuilder().setHeader(header)
            .setFaultEvent(FaultEvent.newBuilder().setFault(fault).setCleared(cleared))
            .build()
    }

    private inline fun emit(build: (MessageHeader) -> Event) {
        val sequence = next.getAndIncrement()
        val event = build(header(Event.getDescriptor().fullName, sequence))

        // **버퍼에 먼저 넣고 발행한다.** 발행이 장애 주입에 막혀도 재생
        // 버퍼에는 남아야 한다 — §10.6이 단절 중 쌓았다가 재생하라고 한다.
        buffer.addLast(event)
        evict()
        send(event)
    }

    /**
     * 버퍼가 넘치면 가장 오래된 것을 버린다.
     *
     * **버린 것이 아직 못 보낸 것이면 새 세션을 발급한다**(§10.6). 그 구간은
     * 소비자에게 영영 안 가므로, 세션을 바꿔 **스냅샷부터 다시 세우게** 하는
     * 것이 유일하게 정직한 답이다.
     *
     * 단절만으로 세션을 바꾸지 않는 이유가 여기 있다 — 그러면 버퍼링이
     * 무의미해진다. 넘칠 때만 바꾸므로 버퍼가 실제로 값을 한다.
     */
    private fun evict() {
        while (buffer.size > bufferSize) {
            // **실제로 버린 것만 축출이다.** 버퍼의 첫 항목보다 앞이라는
            // 이유로 축출이라 판정하면, `state`·`connection`이 쓴 번호를
            // 요청한 소비자에게 "잃었다"고 거짓말하게 된다 — 셋이 같은
            // `sequence` 축을 쓰기 때문이다(§5.5의 발행 열).
            val dropped = buffer.removeFirst().header.sequence
            evictedUpTo = dropped
            val unsent = unsentFrom
            if (unsent != null && dropped >= unsent) {
                instance.renewSession()
                // 세션이 바뀌었으니 옛 구간을 다시 밀 뜻이 없다.
                unsentFrom = null
                // **비우는 것도 버리는 것이다.** 경계를 첫 하나에 둔 채 비우면
                // 나머지 번호가 버퍼에도 없고 축출로도 안 적힌 채 사라지고,
                // 되짚기가 그 번호에 대고 "잃은 것 없다"고 답한다.
                buffer.lastOrNull()?.let { evictedUpTo = it.header.sequence }
                buffer.clear()
            }
        }
    }

    /**
     * 발행한다. 못 보내고 있던 것이 있으면 **그것부터 순서대로** 민다.
     *
     * 재연결을 따로 감지하지 않는다 — 다음 발행이 곧 재시도이고, 발행은
     * 계속 나온다. 별도 재연결 루프를 두면 그 루프의 주기가 조율할 축 하나를
     * 더 만든다.
     */
    private fun send(event: Event) {
        if (unsentFrom != null) {
            // **여기서 직접 발행하지 않는다.** 새 이벤트는 이미 버퍼에
            // 들어가 있으므로(emit이 넣고 부른다) `drain`이 함께 민다.
            // 둘 다 하면 마지막 하나가 두 번 나간다 — 실측으로 걸렸다.
            if (!drain()) markUnsent(event.header.sequence)
            return
        }
        try {
            publisher.publish(
                Publication(topic(Topics.Stream.event), event, event.header.sequence),
            )
        } catch (e: Exception) {
            markUnsent(event.header.sequence)
        }
    }

    /**
     * 쌓인 것을 순서대로 민다. 하나라도 실패하면 거기서 멈춘다.
     *
     * **순서를 지키는 것이 요점이다.** 뒤엣것을 먼저 보내면 소비자가
     * 재정렬로 복원할 수는 있으나(§10.4), 그것은 우리가 만들 필요 없는 일이다.
     */
    private fun drain(): Boolean {
        val from = unsentFrom ?: return true
        buffer.filter { it.header.sequence >= from }.forEach { pending ->
            try {
                publisher.publish(
                    Publication(topic(Topics.Stream.event), pending, pending.header.sequence),
                )
            } catch (e: Exception) {
                unsentFrom = pending.header.sequence
                return false
            }
        }
        unsentFrom = null
        return true
    }

    private fun markUnsent(sequence: Long) {
        if (unsentFrom == null) unsentFrom = sequence
    }

    /**
     * §4.7의 네 번째 이벤트. **전체 능력을 싣지 않는다**(§8.2) — 소비자는
     * delta로 캐시를 갱신하거나 `GetCapabilities`로 전량을 다시 가져오고,
     * 어느 쪽이든 판정 기준은 `capability_epoch`다.
     *
     * `cause`는 호출자가 정한다 — 런타임 축소(§8.2)와 바인딩 변경(§8.4 ④)이
     * 둘 다 여기로 오고, **소비자가 그 둘을 구별해야 한다.** 축소는 능력이
     * 줄어든 것이고 바인딩 변경은 개정판이 바뀐 것이라 대응이 다르다.
     */
    fun capabilityChanged(
        added: List<String> = emptyList(),
        removed: List<String> = emptyList(),
        cause: CapabilityChangeCause =
            CapabilityChangeCause.CAPABILITY_CHANGE_CAUSE_RUNTIME_DEGRADED,
    ) = emit { header ->
        Event.newBuilder().setHeader(header)
            .setCapabilityChanged(
                CapabilityChanged.newBuilder()
                    .setRobotId(instance.robotId)
                    .setCapabilityEpoch(instance.capabilityEpoch)
                    .addAllAdded(added)
                    .addAllRemoved(removed)
                    .setCause(cause),
            )
            .build()
    }

    // ── 연결 (§4.7 — 침묵의 세 원인을 구분한다)

    /**
     * §4.7의 연결 상태. **`ONLINE`이 아니면 주기 발행이 멈춘다.**
     *
     * 셋(`OFFLINE`·`HIBERNATING`·`CONNECTION_BROKEN`)이 **전부 침묵을
     * 만드는 것이 요점이다.** 침묵의 상한만 두면 절전 중인 로봇이 고장으로
     * 오판되므로 `HIBERNATING`이 따로 있고, 소비자는 침묵이 아니라 **연결
     * 스트림**으로 셋을 가른다.
     */
    var connectionState: ConnectionState = ConnectionState.CONNECTION_STATE_ONLINE
        private set

    /**
     * 연결 상태를 바꾸고 **retain으로** 발행한다.
     *
     * @return 실제로 바뀌었으면 참. 같은 상태를 두 번 넣으면 거짓이며 아무것도
     *   안 나간다 — 유령 전이를 보내면 소비자가 재연결로 오해한다.
     */
    fun setConnection(state: ConnectionState): Boolean {
        if (state == connectionState) return false
        connectionState = state
        publishConnection(state)
        return true
    }

    /**
     * §10.2의 기동 발행. 상태가 이미 `ONLINE`이라 [setConnection]으로는
     * 아무것도 안 나가므로 따로 둔다 — 소비자는 **retain된 값 하나**로
     * 기체가 살아 있음을 안다.
     */
    fun announceOnline() = publishConnection(connectionState)

    private fun publishConnection(state: ConnectionState) {
        val sequence = next.getAndIncrement()
        val message = ConnectionMessage.newBuilder()
            .setHeader(header(ConnectionMessage.getDescriptor().fullName, sequence))
            .setState(state)
            .build()
        publishCurrent(
            Publication(topic(Topics.Stream.connection), message, sequence, retained = true),
        )
    }

    /**
     * 현재값을 발행한다 — **못 보내면 버린다.**
     *
     * 이벤트와 다른 점이 여기다. 이벤트는 *일어난 사실*이라 재생 버퍼가 들고 있다가 재연결 때 밀지만,
     * 상태와 연결은 *현재값*이라 다음 것이 대신한다. 큐에 쌓으면 재연결 순간 낡은 현재값이 줄줄이
     * 나가고 마지막 것만 참이다.
     *
     * **예외는 위로 안 간다**(§10.6). 브로커가 죽었다고 로봇이 함께 멈추면 안 된다 — 이벤트 경로가
     * 이미 그 규율을 지키고 있었고, 기동 발행과 상태 발행만 그러지 않아 **브로커가 없으면 기동 자체가
     * 실패했다.**
     */
    private fun publishCurrent(publication: Publication) {
        try {
            publisher.publish(publication)
        } catch (e: Exception) {
            // 버린다. 다음 현재값이 대신한다.
        }
    }

    // ── 상태 발행 (§4.7 — 상태와 이벤트 둘 다 발행한다)

    /**
     * 현재값을 발행한다. **이벤트는 발생한 사실이고 이것은 현재값이다** —
     * 신규 구독자가 전이를 관찰하지 못하는 문제를 이 둘이 함께 푼다.
     */
    /**
     * §7.2의 **최대 발행 간격**을 넘겼으면 현재값을 발행한다.
     *
     * **스케줄러를 두지 않는다.** 배경 스레드는 자기 벽시계로 돌므로 §12.1의
     * "시드 + 가상 시계 고정 = 동일 이벤트 시퀀스"가 깨진다. 시계를 미는
     * [MimicServer.advance]가 이것을 부른다.
     *
     * **`ONLINE`이 아니면 안 나간다.** 그것이 침묵을 만드는 것이고, 완료
     * 기준 5는 소비자가 그 침묵의 원인 셋을 **연결 스트림으로** 가른다는
     * 주장이다.
     *
     * @return 실제로 발행했으면 참.
     */
    fun publishStateIfDue(): Boolean {
        if (connectionState != ConnectionState.CONNECTION_STATE_ONLINE) return false
        val now = instance.clock.now()
        val due = lastStateAt?.plusSeconds(maxIntervalSeconds.toLong())
        if (due != null && now.isBefore(due)) return false
        lastStateAt = now
        publishState()
        return true
    }

    /** **프로파일에서 읽는다.** 리터럴이면 기종마다 다른 값이 뜻을 잃는다. */
    private val maxIntervalSeconds: Int = instance.document.publishIntervalMaxSeconds

    private var lastStateAt: java.time.Instant? = null

    fun publishState() {
        lastStateAt = instance.clock.now()
        val sequence = next.getAndIncrement()
        val message = StateMessage.newBuilder()
            .setHeader(header(StateMessage.getDescriptor().fullName, sequence))
            .addAllSkills(skillSnapshots())
            .addAllTasks(taskSnapshots())
            .addAllFaults(instance.faults.active())
            .build()
        publishCurrent(Publication(topic(Topics.Stream.state), message, sequence))
    }

    fun taskSnapshots(): List<TaskSnapshot> = instance.tasks.all.map {
        TaskSnapshot.newBuilder()
            .setTaskId(it.taskId)
            .setSkillType(it.skillType)
            .setState(it.machine.state.toProto())
            .setRevision(it.machine.revision)
            .setAttempt(it.machine.attempt)
            .build()
    }

    /**
     * **`task_id`를 함께 싣는다.** 스킬 인스턴스는 태스크마다 하나이므로
     * `skill_type`만으로는 키가 되지 않는다 — 같은 타입의 태스크가 둘 돌면
     * 소비자의 맵에서 어느 쪽이 이겼는지 미정의가 된다.
     */
    fun skillSnapshots(): List<SkillSnapshot> = instance.tasks.all.mapNotNull { task ->
        task.machine.skillMachine?.let {
            SkillSnapshot.newBuilder()
                .setSkillType(task.skillType)
                .setState(it.state.toProto())
                .setTaskId(task.taskId)
                .build()
        }
    }

    // ── 헤더 (§5.5의 발행 열)

    private fun topic(stream: Topics.Stream) =
        Topics.robot(contractMajor, site, instance.robotId, stream)

    private fun header(schemaId: String, sequence: Long): MessageHeader {
        val now = DateTimeFormatter.ISO_INSTANT.format(instance.clock.now())
        return MessageHeader.newBuilder()
            .setSchemaId(schemaId)
            .setContractDigest(ContractIdentity.digest)
            .setContractSemver(ContractIdentity.semver)
            .setRobotId(instance.robotId)
            .setCapabilityEpoch(instance.capabilityEpoch)
            .setSessionId(instance.sessionId)
            .setSequence(sequence)
            .setProfileRef(
                ProfileRef.newBuilder()
                    .setProfileId("${instance.document.vendor}/${instance.document.model}")
                    .setRevision(instance.document.revision),
            )
            .setEventId("${instance.sessionId}-p%08d".format(eventCounter.incrementAndGet()))
            .setOccurredAt(now)
            .setStateAsOf(now)
            .build()
    }
}
