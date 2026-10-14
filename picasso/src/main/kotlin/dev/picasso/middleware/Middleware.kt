package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.capability.HoldEffects
import dev.picasso.capability.HoldMismatch
import dev.picasso.capability.Remedy
import dev.picasso.middleware.RemedyDesk.Judgment
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
 * 게이트 7번이 그것을 지킨다. 셋째 경로 [Route.SIGNAL] 은 하위가 아니라 설비 대기다 —
 * 요청을 보내지 않고 이름 있는 설비 신호를 기다린다.
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
    /**
     * 코드로 정의한 케이퍼빌리티 목록. 널이면 [MissionCatalog.codeCapabilities] 다. [missions] 를 주면 쓰지 않으므로
     * 둘을 함께 주면 생성이 실패한다 — 한쪽이 조용히 무시되는 길을 두지 않는다.
     */
    capabilities: List<LogicalCapability>? = null,
    private val now: () -> Instant = { Instant.now() },
    /**
     * `IN_DOUBT` 에서 같은 참조로 다시 묻는 횟수의 상한(13.2 ①). 이만큼 물어도 답이 없으면 하류가 지금은 조회 불가인
     * 것이고, 12.3 셋째 행대로 **불가 시 운영자**다. `pump()` 한 번에 한 번 묻는다.
     */
    private val lookupRetries: Int = 3,
    /**
     * 실 시계 — 사건 번들의 **현장 대조**용이다. [now] 와 나누는 이유는 [now] 가 시험에서 가상 시계이기
     * 때문이다. 둘을 접으면 재현 판정과 현장 대조 중 하나를 잃는다(설계안 §4.2).
     */
    wallClock: () -> Instant = { Instant.now() },
    /**
     * 몇 번에 한 번 제안을 **가릴 것인가**(설계안 §7.2 넷째 — 의도적 비자동화). 0 이면 가리지 않는다.
     *
     * 에이전트가 사건을 처리할수록 사람은 진단하는 연습을 잃고, 그러면 모델 밖 사건이 왔을 때 아무도
     * 들어가지 못한다. 가리는 것은 **비용이다** — 그 사건에서 운영자가 더 오래 걸린다. 비용임을 알고
     * 지불하는 결정이어야 유지되므로 기본값을 두지 않고 배치가 명시하게 한다. 0 을 고르는 것도 결정이다.
     */
    withholdEvery: Int = 0,
    /**
     * 배정 비용의 가중치(설계안 §7). **정책은 데이터로 밖에, 평가는 안에서** — 값은 배치가 주고
     * 계산은 tick 안에서 한다. 순위 계산을 통째로 밖에 두면 읽은 시점과 적용 시점이 갈라진다.
     */
    private val cost: AssignmentCost = AssignmentCost(),
    /**
     * 재할당의 진동 방지(설계안 §7). **라인을 멈추지 않는 것이 최적성보다 우선한다** — 값은 배치가 준다.
     */
    private val reassignPolicy: ReassignPolicy = ReassignPolicy(),
    /**
     * 자원 소유 대장(§15.154). **대장은 현장의 것이고 이 저장소 밖에 산다** — 붙이지 않은 배치에서는
     * 모든 자리가 «선언 안 됨» 이고 관문이 아무것도 막지 않는다.
     */
    floors: FloorOwnership = FloorOwnership.None,
    /**
     * 자리가 속한 작업 구역(§15.153). **붙이지 않은 배치에서는 모든 자리가 구역 밖**이고 관문이
     * 아무것도 막지 않는다 — 구역을 안 붙인 현장에서 라인이 서면 이 관문이 곧 꺼진다.
     */
    workspace: Workspace = Workspace.None,
    /**
     * 자동 승인 자격의 선언 목록(ADR 43). **기본값은 아무것도 선언 안 된 것**이고 그때 에이전트 승인은
     * 전부 거절된다 — 사람 승인은 선언을 안 보므로 라인은 서지 않는다.
     */
    entitlements: Entitlements = Entitlements.None,
    /**
     * 이 미들웨어 인스턴스가 뜬 한 번을 가리키는 값(ADR 48). 결과 통보의 바깥 형식과 승인 답에 같은 값이 실린다.
     *
     * **실행 식별자(`exec-N`)와 결과 통보 식별자(`resp-N`)는 프로세스 안의 셈이라 다시 뜨면 되풀이된다.** 밖의
     * 소비자가 «이 통보가 이번 시도의 것인가» 를 가르려면 이 값과 짝지어야 한다. 그래서 **결정적이지 않아야 한다** —
     * 같은 시드로 다시 띄운 인스턴스가 같은 값을 내면 앞 구동의 통보가 이번 시도의 것으로 읽힌다.
     */
    val instanceId: String = "mw-${java.util.UUID.randomUUID()}",
    /**
     * 작업 지시를 계획할 케이퍼빌리티의 출처(임무 버전). 널이면 [capabilities] 로 만든 카탈로그다(버전 없음).
     *
     * **새 작업 지시만 읽는다.** 실행은 생성 때 읽은 케이퍼빌리티와 버전을 쥐고 리비전까지 그것으로 돈다 —
     * 그래서 활성 버전이 바뀌어도 도는 실행은 옛 버전으로 끝난다.
     */
    missions: MissionCatalog? = null,
    /**
     * 현장 시간값의 출처. 기본값은 «없음» 이고, 그때는 케이퍼빌리티 값으로 판정한다.
     *
     * [pump] 가 시작할 때 한 번 읽어 그 라운드의 모든 판정에 같은 값을 쓴다. 바꾼 값은 다음 pump 부터다. 단위에 저장하는 값은
     * 근거 기한(완료 시각 + 뒤 폭) 하나뿐이라, 바꾼 `inDoubtGrace` 는 이미 `IN_DOUBT` 인 단위에도 다음 라운드부터 미친다.
     */
    private val siteTimings: SiteTimingsSource = SiteTimingsSource.NONE,
    /**
     * 작업 허가의 출처(ADR 52) — 발행 관문([IssuingGate])의 둘째 입력. **널이면 붙이지 않은 것이고 관문이 아무것도 막지 않는다** —
     * 오늘의 거동 그대로다. 붙이면 닫힌 쪽으로 판정한다. `PERMITTED` 가 아니면(모름 포함) 새 로봇 명령을 내지 않고 기다린다.
     *
     * 붙이지 않은 배치까지 닫힌 쪽으로 두지 않는 것은 그러면 허가 원천이 없는 현장이 통째로 서고, 그 관문은 곧 꺼지기 때문이다
     * ([floors]·[workspace] 와 같은 이유).
     */
    permits: WorkPermitPort? = null,
) {
    // ⚠ 생성자 인자 `missions`(널 허용)가 이 속성을 가린다 — 다른 속성의 초기화식에서 `missions` 는 인자다. 거기서는 `this.missions` 로 쓴다.
    private val missions: MissionCatalog = run {
        require(capabilities == null || missions == null) {
            "케이퍼빌리티 목록과 임무 카탈로그를 함께 줬다 — 카탈로그를 주면 목록은 쓰지 않는다"
        }
        missions ?: MissionCatalog.of(capabilities ?: MissionCatalog.codeCapabilities())
    }
    private val executions = linkedMapOf<String, Execution>()
    private val views = mutableMapOf<String, RobotView>()

    /** 배정 관문(설계안 §7). 실행 표를 같은 인스턴스로 넘긴다 — 관문은 이 층이 지금 든 것을 읽는다. */
    private val gate = AdmissionGate(robots, cell, floors, workspace, executions)

    /**
     * 발행 관문(ADR 51 결정 6, ADR 52). 배정 관문([gate])과 다르다 — 배정 관문은 작업 지시를 받을 때 한 번 묻고, 이것은 새 로봇
     * 명령이 나가기 직전마다 묻는다. 허가는 받은 뒤에도 바뀌기 때문이다.
     */
    private val issuing = IssuingGate(permits, now)

    /** 제안과 승인의 장부(설계안 §6.4). 승인 뒤의 접수는 [commit] 에 남고, 승인의 기록([RemedyDesk.settle])은 접수가 된 뒤에만 한다. */
    private val desk = RemedyDesk(robots, entitlements, withholdEvery, now, wallClock, gate::liveHold)

    /** 사건 번들의 장부(설계안 §4). 봉인은 [pump] 가 라운드 끝에 부른다. */
    private val incidentLog = IncidentLog(robots, now, wallClock)

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
        /** 지금 이 일을 든 기체. **재할당으로 바뀐다** — 옮긴 이력은 자취에 남는다. */
        var robotId: String,
        val capability: LogicalCapability,
        /**
         * 이 실행을 계획한 임무 버전. 코드 케이퍼빌리티면 `null` 이다. **생성 때 정해지고 바뀌지 않는다** —
         * 리비전도 [capability] 로 계획하므로, 활성 버전이 바뀌어도 이 실행은 이 버전으로 끝난다.
         */
        val missionVersion: Int?,
        val units: MutableList<ExecutionUnit>,
    ) {
        var physicalState: PhysicalState = PhysicalState.REQUESTED
            internal set

        /** 지금 기체에 배정된 시각. 최소 유지 시간의 기준이고 재할당 때 다시 찍힌다. */
        internal var assignedAt: Instant = now()

        /**
         * 이 실행이 **승인된 조치로 시작됐다면** 그것을 누른 쪽. 아니면 널이다.
         *
         * 사건이 이 실행에서 열리면 번들이 이 값을 싣고, 그래서 «에이전트가 승인한 조치의 이의율» 을
         * 사람 승인과 따로 잴 수 있다(ADR 43 §4).
         */
        internal var approvedBy: Approver? = null
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

        /**
         * 지금 이 실행의 새 로봇 명령을 막고 있는 작업 허가(ADR 52). 널이면 막힌 것이 없다.
         *
         * **[blockedBy] 와 칸이 다르다.** 결함은 사람이 [release] 로 감수할 수 있지만 허가는 감수할 값이 아니다 — 원천이 다시
         * `PERMITTED` 를 낼 때만 걷힌다. 한 칸에 두면 해제 한 번이 허가 막음까지 걷는다. 막힌 단위는 실패가 아니고 기다린다 —
         * 그래서 실행 상태([physicalState])도 운영자 보류로 바꾸지 않는다. 운영자가 판단할 것이 없다.
         */
        var permitBlock: PermitBlock? = null
            internal set

        /**
         * 개정이 도는 로봇 단위에 보내지 못한 갱신의 **직전 리비전**(기체에서 지금 도는 리비전). 널이면 미룬 갱신이 없다.
         * 허가가 돌아오면 [pump] 가 보낸다. 그 전에 옛 리비전으로 끝나면 보통의 지연 이벤트 길(새 판의 기대에 대고 설비로 확인)을 탄다.
         */
        internal var deferredUpdate: Int? = null

        /** 운영자가 [release] 로 감수한 결함의 열쇠 — 같은 결함은 다시 막지 않는다. 새 결함은 막는다. 감사 기록이다. */
        val acknowledgedFaults: MutableSet<String> = linkedSetOf()

        /** 이 실행이 도는 동안 기체가 낸 이벤트와 이 층의 관측 — 감사 자취(보고서 7장 *실행 추적·이벤트 전달·감사 기록*). */
        val eventTrail: MutableList<ObservedEvent> = mutableListOf()

        /**
         * 이번 라운드에 미완료로 닫혔거나 운영자 판단에 선 단위. **라운드 끝에** 번들로 봉한다 —
         * 전이 순간에는 실행 수준의 사실(막는 결함)이 아직 안 정해져 있다.
         */
        internal val pendingIncidents: MutableSet<String> = linkedSetOf()

        /** 연결이 끊긴 채 도는 중인가 — 그동안 결과는 미확정이다(`scenarios.md` §4.4 넷째 행). */
        var linkBroken: Boolean = false
            internal set

        /**
         * 설비 대기가 마지막으로 자취에 남긴 신호 값 — 태스크 id(시도마다 다르다)로 단다. 값이 바뀔 때만 남기기 위한
         * 표시다. 재작업하면 새 시도라 첫 값을 다시 남긴다.
         */
        internal val signalSeen: MutableMap<String, String> = mutableMapOf()

        /**
         * [resume] 이 마지막 로봇 태스크보다 앞선 것으로 둔 로봇 단위. 다시 관측한 성공에 설비 근거를 다시 묻지 않는다. 이전
         * 인스턴스가 이미 판정했고 설비 값은 그 뒤 바뀌었을 수 있다.
         */
        internal val resumedPrior: MutableSet<String> = mutableSetOf()

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

        /**
         * 이 단위를 맡은 기체 — 단위의 묶음([ExecutionUnit.robotBinding]), 없으면 이 실행의 기체([robotId]).
         *
         * **단위의 기체를 읽는 자리는 전부 이것을 부른다.** [robotId] 를 바로 읽으면 오늘은 같은 값이라 아무 시험도 안 깨지고,
         * 단위를 다른 기체에 묶는 날 그 자리만 옛 기체로 명령을 낸다.
         */
        fun robotOf(unit: ExecutionUnit): String = unit.robotBinding ?: robotId

        /** 이 실행이 쓰는 기체 전부 — 실행의 기체와 단위가 묶인 기체. 동기화와 부담 셈이 읽는다. */
        val robots: Set<String> get() = linkedSetOf(robotId).apply { units.forEach { add(robotOf(it)) } }

        /** 이 실행을 세운 승인의 소모. 승인된 조치로 서지 않았으면 널이다. 판단 기록([judgmentRecord])이 싣는다. */
        internal var approval: RecordedApproval? = null

        /** 운영자 판단 — 단위마다 마지막 하나. 판단 기록이 싣는다. */
        internal val decisions: MutableMap<String, RecordedDecision> = linkedMapOf()

        /**
         * 판단 기록이 넘긴 요청 시각 — 단위마다 (시도, 시각). `startUnit` 이 **그 시도를** 낼 때 한 번 쓰고 지운다. 다른 시도면
         * 쓰지 않는다 — 재작업은 새 요청이고 그 시각은 지금이다.
         */
        internal val restoredRequests: MutableMap<String, Pair<Int, Instant>> = mutableMapOf()

        /** 판단 기록이 넘긴 검증 대기의 창 — 단위마다 (시도, 창). 그 시도의 완료를 다시 관측할 때 한 번 쓰고 지운다. */
        internal val restoredWindows: MutableMap<String, Pair<Int, VerifyWindow>> = mutableMapOf()
    }

    sealed interface Submission {
        data class Accepted(val execution: Execution) : Submission
        data class Idempotent(val execution: Execution) : Submission
        /**
         * 받지 않았다. [remedy] 가 있으면 **깨진 전제를 충족시키는 조치 열**이 계산됐다는 뜻이며,
         * 사람이 [approveRemedy] 로 승인해야 실행된다 — 승인 없이 도는 길은 없다(설계안 §6.4).
         */
        data class Rejected(
            val reason: String,
            val remedy: Remedy? = null,
            /**
             * 조치 열이 **계산됐으나 가려졌다**(설계안 §7.2 넷째). [remedy] 가 널이면서 이것이 참이면
             * «대안이 없다» 가 아니라 «사람이 먼저 진단하라» 는 뜻이다. 둘을 접으면 의도적 비자동화가
             * 능력 부재와 구별되지 않아, 운영자가 시스템을 고장으로 읽는다.
             */
            val remedyWithheld: Boolean = false,
            /**
             * 출발 자리가 비었을 때 **그 자재를 든 다른 자리**(§15.153). 셀이 그 질문에 답하지 않으면 널이고,
             * 빈 목록은 «든 자리가 하나도 없다» 는 답이다 — 둘을 접으면 못 물어본 것이 재고 부족으로 읽힌다.
             *
             * 자리 이름만 낸다. 어느 자리를 쓸지는 주문을 고치는 쪽의 결정이고, 이 층은 고르지 않는다.
             */
            val alternativeLocations: List<String>? = null,
            /**
             * 관측을 요구한 단위의 출발 자리를 **확인하지 못했다**([SourceUnobserved]). 널이면 이 거절은 그 갈래가 아니다.
             * 결품이 아니므로 [alternativeLocations] 는 널이고, 다음 행동은 주문을 고치는 것이 아니라 사람의 확인이다.
             */
            val sourceUnobserved: SourceUnobserved? = null,
        ) : Submission
    }

    fun execution(executionId: String): Execution? = executions[executionId]

    /** 지금 이 층이 든 실행 전부. 조회만 한다. */
    fun executions(): List<Execution> = executions.values.toList()

    /** 상류에 갈 결과 통보 중 아직 ack 되지 않은 것 — 아웃박스. 통보 재시도의 자리다(보고서 13.3). */
    fun pending(): List<JobResponse> = outbox.filter { it.ack != UpstreamAck.ACKED }

    fun responses(): List<JobResponse> = outbox.toList()

    // ── 접수 (보고서 15 — 정체성과 버전)

    /**
     * JobOrder 를 받는다. 같은 `jobOrderId` 가 오면 버전으로 판정한다 — 같으면 멱등,
     * 낮으면 거절, 높으면 **갱신**(보고서 15.3: 새 버전은 확정된 단위 이후에만 붙는다).
     */
    fun submit(order: JobOrder, robotId: String): Submission = submit(order, robotId, prefix = emptyList())

    /**
     * @param prefix 승인된 조치 열(설계안 §6.4). **[approveRemedy] 만 채운다** — 밖에서 부를 길이 없으므로
     *   승인 없이 조치가 실행되는 경로가 생기지 않는다.
     */
    private fun submit(
        order: JobOrder,
        robotId: String,
        prefix: List<ExecutionUnit>,
        approvedBy: Approver? = null,
        /** [adopt] 가 관문에 대려고 이미 읽은 쌍. 널이면 지금 카탈로그를 읽는다 — 한 작업 수락이 카탈로그를 두 번 읽지 않게 한다. */
        mission: ActiveMission? = null,
    ): Submission {
        // **이미 있는 실행이면 카탈로그를 안 본다.** 리비전은 그 실행이 쥔 케이퍼빌리티로 계획한다 — 여기서 카탈로그를
        // 읽으면 활성 버전이 바뀐 뒤의 리비전이 새 버전의 단위를 옛 실행에 들인다.
        val existing = executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }
        if (existing != null) return revise(existing, order)

        val active = mission ?: missions.active(order.workMasterId)
            ?: return Submission.Rejected("모르는 논리적 능력이다: ${order.workMasterId}")
        val capability = active.capability
        evidenceRefusal(order, capability)?.let { return it }

        val planned = prefix + capability.plan(order)
        when (val admission = admits(order, robotId, planned)) {
            is Admission.Refused -> return desk.record(robotId, order, admission.rejection, admission.sourceMissing)
            Admission.Passed -> Unit
        }

        return Submission.Accepted(create(order, robotId, active, planned, approvedBy))
    }

    /** 실행 하나를 세워 표에 넣는다. [submit] 과 [resume] 이 함께 쓴다. 판정은 부르는 쪽이 이미 끝냈다. */
    private fun create(
        order: JobOrder,
        robotId: String,
        active: ActiveMission,
        planned: List<ExecutionUnit>,
        approvedBy: Approver?,
    ): Execution {
        val execution = Execution(
            executionId = "exec-${executions.size + 1}",
            order = order,
            robotId = robotId,
            capability = active.capability,
            missionVersion = active.missionVersion,
            units = planned.toMutableList(),
        )
        execution.units.forEach { it.revision = order.version }
        execution.approvedBy = approvedBy
        execution.physicalState = PhysicalState.ACCEPTED
        executions[execution.executionId] = execution
        return execution
    }

    /**
     * 담는 쪽이 재기동한 뒤 **자기 기록으로 실행을 다시 짓는 입구**다. 이전 인스턴스가 받은 작업 지시와 기체, 그때 계획한 임무
     * 정의와 버전([mission])을 넘겨받는다. 미들웨어를 담는 실행 호스트가 기동할 때 [pump] 를 켜기 전에 부르는 자리다.
     *
     * **상태를 되살리는 것이 아니라 같은 정체성으로 다시 짓는 것이다.** 실행 id 와 작업 응답 id 는 이 인스턴스의 셈으로 새로
     * 붙고, 로봇 단위와 플릿 단위는 대기에서 시작한다. 로봇 단위의 상태는 [pump] 가 기체에서 다시 관측해 세운다. 같은 태스크
     * id 와 리비전으로 `StartTask` 를 다시 보내면 기체가 기존 핸들을 돌려주므로(계약의 멱등) 새 명령이 나가지 않는다.
     *
     * **넘기는 작업 지시는 이전 인스턴스가 받은 마지막 버전이어야 한다.** 리비전은 단위의 `revision` 이 되고 태스크의 멱등은
     * 같은 `task_id`·`revision` 에만 서므로, 낮은 버전을 넘기면 기체가 기존 태스크에 붙여 주지 않는다.
     *
     * 같은 작업 지시 id 의 실행이 이미 있으면 [submit] 과 같이 리비전 경로로 간다. 같은 버전이면 [Submission.Idempotent] 다.
     *
     * 없으면 차례는 이렇다.
     *
     * 1. **넘겨받은 정의의 WorkMaster 를 작업 지시와 대조한다.** 다르면 거부한다.
     * 2. **단위가 묶인 기체마다 스냅숏을 한 번 읽는다.** 하나라도 못 읽으면(`null`) 실행을 만들지 않고 [RESUME_SNAPSHOT_UNREADABLE]
     *    로 시작하는 사유로 거부한다(사유에 못 읽은 기체가 실린다). 스냅숏 없이 지으면 첫 pump 가 `@rN` 없는 태스크 id 로 가서 이미 끝난 첫 태스크에 붙고, 도는 재작업 태스크는
     *    고아가 된다.
     * 3. **넘겨받은 정의로 계획한다.** 카탈로그의 지금 활성 버전은 읽지 않는다. 근거 등급 검사만 거치고 **배정 관문을 걸지
     *    않으며 조치 탐색 기록도 남기지 않는다.** 명령이 이미 나간 실행이라 관문이 다시 판정할 대상이 아니고, 관문은 이미 집어 간
     *    자재를 출발 결품으로 읽어 거부할 수 있다.
     * 4. **로봇 단위의 재작업 횟수를 태스크 id 에서 되찾는다.** 스냅숏 태스크 id 가운데 `jobOrderId#unitId` 이거나
     *    `jobOrderId#unitId@rN` 인 것의 가장 큰 N(접미사가 없으면 0)을 [ExecutionUnit.attempt] 에 넣는다. 계약 스냅숏의 태스크
     *    `attempt` 칸은 발신자의 재시도 횟수라 다른 값이다.
     * 5. **앞선 설비 대기를 통과한 것으로 둔다.** 스냅숏에 태스크가 있는 마지막 로봇 단위보다 앞선 설비 대기 단위는 `DONE` 이다.
     *    실행은 단위를 차례로 돌므로 뒤 로봇 단위가 기체에 있으면 앞 대기는 이미 통과했고, 지금 신호로 다시 판정하면 그 뒤 바뀐
     *    신호로 보류나 중단이 난다. 이 인스턴스가 관측하지 않았으므로 근거 등급은 E0 그대로이고, 그래서 작업 응답의 도달 근거
     *    등급도 E0 이다. 그 뒤의 설비 대기는 다시 기다리므로 재기동 뒤 바뀐 신호로 곧바로 보류나 중단이 날 수 있다.
     * 6. **앞선 로봇 단위의 완료를 다시 확인하지 않는다.** 같은 마지막 로봇 단위보다 앞선 로봇 단위는 [pump] 가 다시 관측한
     *    성공에 설비 근거를 다시 묻지 않고 근거 등급 E0 인 채 `DONE` 으로 둔다. 이전 인스턴스가 그 완료를 이미 확인했거나 못
     *    했고, 설비 값은 그 뒤 바뀌었을 수 있다. 실패로 다시 관측되면 보통의 실패 경로를 탄다. 마지막 로봇 단위는 보통대로 확인한다.
     *
     * 마지막 로봇 단위를 찾을 때 플릿 단위는 세지 않는다. 4·5·6 의 사실은 단위 메모가 아니라 실행 자취([Execution.eventTrail])에
     * [RESUME_ATTEMPT]·[RESUME_SIGNAL_PASSED]·[RESUME_PRIOR_ROBOT] 으로 남긴다. 단위 메모는 작업 응답의 미완 단위 값으로 나가기
     * 때문이다.
     *
     * **판단 기록([record])을 넘기면** 4·5·6 뒤에 그 사실을 그대로 옮긴다(7). 넘기지 않으면 아래의 것이 넘어오지 않는다.
     *
     * 7. **판단 기록을 옮긴다.** 감수한 결함, 승인 소모(조치 열의 걸음은 계획 앞에 다시 서고 승인 장부에 소모로 다시 선다),
     *    단위마다의 시도 번호, 요청 시각(근거 윈도우와 미확정 유예의 기준, 설비 대기의 기한 시작), 검증 대기의 창, 이미 내린
     *    판정, 운영자 판단이다. **다시 판정하지 않는다** — 판정이 있는 단위는 다시 돌리지 않고, 앞선 로봇 단위라도 기록의 근거
     *    등급을 그대로 쓴다. 시각은 그 시도를 다시 낼 때 쓰므로 기한은 재기동 시각이 아니라 처음 시작한 시각부터 이어 간다.
     *    스냅숏이 기록보다 큰 시도를 보이는 단위는 기록이 그 재작업보다 먼저 적힌 것이라 그 단위의 시각과 판정을 옮기지 않고
     *    [RESUME_RECORD_SUPERSEDED] 로 남긴다. 기록의 작업 지시 id 나 버전이 넘긴 작업 지시와 다르거나 계획에 없는 단위가 있으면
     *    [RESUME_RECORD_MISMATCH] 로 시작하는 사유로 거부하고 실행을 만들지 않는다 — 일부만 옮기면 옮기지 못한 사실이 «없었다» 가
     *    된다. 기록 대조는 스냅숏보다 먼저 한다.
     * 8. **운영자 보류로 옮긴 단위마다 인시던트를 다시 연다.** 인시던트는 기록에 들지 않으므로 이 인스턴스에서 새로 열고, 실행
     *    자취의 [RESUME_HOLD_REOPENED] 가 그 인시던트의 근거 윈도우에 실려 다시 연 것임을 가른다. 그 줄의 시각과 인시던트의
     *    `at` 은 시계를 한 번 읽은 같은 값이다(줄이 있는 것만으로는 가르지 못한다, [reopenHold]). 판단은 보통의 보류와 같이
     *    [resolve] 로 낸다. 같은 작업 지시로 다시 부르면 리비전 경로라 다시 열지 않는다.
     *
     * **기록 없이 넘어오지 않는 것**: 운영자 판단, 감수한 결함([Execution.acknowledgedFaults]), 승인 소모 기록, 근거 윈도우의 요청
     * 시각, 설비 대기의 기한 시작 시각, 앞선 로봇 단위에 이전 인스턴스가 낸 근거 확인 결과. 모두 이 인스턴스에서 새로 서거나(6 은
     * E0 으로) 비어 있다. 계약과 내보내기 형식은 그대로다.
     *
     * **스냅숏은 단위가 묶인 기체마다 읽는다**([Execution.robotOf]). 오늘은 단위가 전부 실행의 기체라 한 번이다. 기체가 N 이면 N 번
     * 읽고 하나만 못 읽어도 다시 짓기를 미룬다(ADR 51 «대가») — 그 기체의 단위는 재작업 횟수를 정할 근거가 없고, 못 읽은 기체를
     * 빼고 지으면 그 단위가 이미 끝난 태스크에 붙는다.
     */
    fun resume(order: JobOrder, robotId: String, mission: ActiveMission, record: JudgmentRecord? = null): Submission {
        val existing = executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }
        if (existing != null) return revise(existing, order)

        if (mission.capability.workMasterId != order.workMasterId) {
            return Submission.Rejected(
                "넘겨받은 임무 정의의 WorkMaster 가 작업 지시와 다르다: 정의=${mission.capability.workMasterId}, 작업 지시=${order.workMasterId}",
            )
        }
        // 승인된 조치 열은 계획 앞에 선다(`commit` 과 같은 모양). 기록이 그 걸음을 들고 오지 않으면 다시 지을 길이 없다.
        val planned = record?.approval?.steps.orEmpty().map { remedyUnit(it.unitId, it.skillType, it.parameters) } +
            mission.capability.plan(order)
        record?.let { recordRefusal(order, it, planned) }?.let { return it }
        val snapshots = linkedMapOf<String, RobotSnapshot>()
        for (robot in planned.map { it.robotBinding ?: robotId }.distinct()) {
            snapshots[robot] = robots.snapshot(robot)
                ?: return Submission.Rejected("$RESUME_SNAPSHOT_UNREADABLE: robot=$robot, 재작업 횟수와 지난 설비 대기를 정할 근거가 없다")
        }
        evidenceRefusal(order, mission.capability)?.let { return it }

        val execution = create(order, robotId, mission, planned, approvedBy = null)
        rebuildFrom(execution, snapshots)
        val reopenedAt = record?.let { restore(execution, it) }
        // 8 이 다시 연 인시던트를 지금 봉한다. 지금의 현장 시간값으로 — 라운드의 값([round])은 [pump] 의 것이라 건드리지 않는다.
        // 봉인 시각은 다시 연 줄의 시각이다. 따로 읽으면 실제 시계에서 둘이 어긋나 줄 시각 = `at` 으로 가르지 못한다.
        incidentLog.sealIncidents(execution, siteTimings.current(), reopenedAt)
        return Submission.Accepted(execution)
    }

    /** 판단 기록이 넘긴 작업 지시와 맞는가. 맞지 않으면 거부의 사유, 맞으면 널. */
    private fun recordRefusal(order: JobOrder, record: JudgmentRecord, planned: List<ExecutionUnit>): Submission.Rejected? {
        val why = when {
            record.jobOrderId != order.jobOrderId -> "작업 지시가 다르다: 기록=${record.jobOrderId}, 작업 지시=${order.jobOrderId}"
            record.orderVersion != order.version -> "버전이 다르다: 기록=${record.orderVersion}, 작업 지시=${order.version}"
            else -> (record.units.map { it.unitId } - planned.map { it.unitId }.toSet()).takeIf { it.isNotEmpty() }
                ?.let { "계획에 없는 단위가 있다: $it" }
        } ?: return null
        return Submission.Rejected("$RESUME_RECORD_MISMATCH: $why")
    }

    /**
     * [resume] 의 7 — 판단 기록을 옮긴다. 판정은 그대로 쓰고 다시 내리지 않는다. 옮긴 것은 실행 자취에 [RESUME_RECORD] 로 남긴다.
     *
     * 시도 번호는 스냅숏과 기록 가운데 큰 쪽이다. 기록이 크면 재작업 판단이 섰는데 아직 내지 않은 것이고, 스냅숏이 크면 기록이 그
     * 재작업보다 먼저 적힌 것이다 — 그때 그 단위의 시각과 판정은 앞 시도의 것이라 옮기지 않는다.
     *
     * 돌려주는 것은 운영자 보류를 다시 연 시각이다. 처음 다시 열 때 시계를 한 번 읽고 다시 연 보류 모두에 쓴다. 다시 연 것이
     * 없으면 널이다.
     */
    private fun restore(execution: Execution, record: JudgmentRecord): Instant? {
        var reopenedAt: Instant? = null
        execution.acknowledgedFaults += record.acknowledgedFaults
        if (record.acknowledgedFaults.isNotEmpty()) {
            execution.trail(RESUME_RECORD, "acknowledged faults: ${record.acknowledgedFaults.joinToString()}")
        }
        record.approval?.let { approval ->
            execution.approval = approval
            execution.approvedBy = approval.approver
            // **소모로 다시 세운다.** 실행 id 는 이 인스턴스의 것으로 단다 — 앞 인스턴스의 id 는 여기서 가리키는 것이 없다(ADR 48).
            desk.restore(
                approval.robotId,
                execution.order.jobOrderId,
                ConsumedApproval(approval.approver, approval.at, approval.wallClockAt, execution.executionId, approval.steps),
            )
            execution.trail(RESUME_RECORD, "approval consumed by ${approval.approver.id}(${approval.approver.kind}) at ${approval.at}")
        }
        val byId = execution.units.associateBy { it.unitId }
        record.units.forEach { r ->
            val unit = byId.getValue(r.unitId)
            r.decision?.let { execution.decisions[unit.unitId] = it }
            if (r.attempt < unit.attempt) {
                execution.trail(
                    RESUME_RECORD_SUPERSEDED,
                    "${unit.unitId}: 스냅숏의 시도 ${unit.attempt} 가 기록의 시도 ${r.attempt} 보다 크다, 기록의 시각과 판정을 옮기지 않는다",
                )
                return@forEach
            }
            unit.attempt = r.attempt
            r.requestedAt?.let { execution.restoredRequests[unit.unitId] = r.attempt to it }
            r.verifyWindow?.let { execution.restoredWindows[unit.unitId] = r.attempt to it }
            val verdict = r.verdict ?: return@forEach
            unit.state = verdict.state
            unit.reached = verdict.reached
            unit.verification = verdict.verification
            unit.evidenceAt = verdict.evidenceAt
            unit.failureClass = verdict.failureClass
            unit.note = verdict.note
            unit.result = verdict.result
            unit.taskId = r.taskId
            unit.requestedAt = r.requestedAt
            execution.resumedPrior -= unit.unitId
            execution.trail(
                RESUME_RECORD,
                "${unit.unitId}: verdict ${verdict.state.name} ${verdict.reached.name} ${verdict.verification.name} (attempt ${r.attempt}), 다시 판정하지 않는다",
            )
            if (verdict.state == UnitState.OPERATOR_HOLD) {
                val at = reopenedAt ?: now().also { reopenedAt = it }
                reopenHold(execution, unit, r, verdict, at)
            }
        }
        return reopenedAt
    }

    /**
     * [resume] 의 8 — 기록의 운영자 보류를 **이 인스턴스의 인시던트로 다시 연다.**
     *
     * 인시던트 번들은 판단 기록에 들지 않는다. 그래서 보류로 옮긴 단위는 사람이 판단할 자리인데 이 인스턴스에 열린 인시던트가
     * 없고, 담는 쪽의 판단 화면은 열린 인시던트에서 판단을 받으므로 사람이 그 보류를 풀 길을 잃는다.
     *
     * **관측을 지어내지 않는다.** 번들은 지금 단위가 든 것(기록에서 옮긴 실패 분류·근거 등급·대조 결과)만 싣고, 결함 원문·파지
     * 관측은 이 인스턴스가 아직 보지 않았으므로 비어 있다. 기록에만 있는 사실(기체, 시도, 요청 시각, 근거 시각, 보류 사유)은 실행
     * 자취에 [RESUME_HOLD_REOPENED] 로 남기고, 그 줄이 번들의 근거 윈도우에 실려 «재기동이 다시 연 인시던트» 의 표시가 된다.
     * 이 인스턴스가 보류를 낸 시각이 아니므로 번들의 `at` 은 다시 연 시각이다 — 기록에는 보류가 선 시각이 따로 없다.
     *
     * **줄 시각과 번들의 `at` 은 같은 한 번의 읽기다([at]).** 줄이 있는 것만으로는 가르지 못한다 — 다시 연 뒤 근거 윈도우 앞 폭
     * 안에 새로 선 보류의 윈도우에도 그 줄이 실린다. 가르는 것은 그 줄의 시각이 번들 `at` 과 같은 것이고, 시계가 흘러도 그렇다.
     *
     * 판단은 보통의 보류와 같다. [resolve] 가 보류를 풀고 판단 기록에 실으며, 판단은 이 인시던트에 붙는다.
     * 봉인은 [resume] 이 끝에서 한 번 한다 — 첫 [pump] 전에도 판단 화면이 이 인시던트를 읽는다.
     */
    private fun reopenHold(execution: Execution, unit: ExecutionUnit, r: UnitRecord, verdict: UnitVerdict, at: Instant) {
        execution.trail(
            RESUME_HOLD_REOPENED,
            "${unit.unitId}: 기록의 운영자 보류를 이 인스턴스의 인시던트로 다시 연다, robot=${execution.robotOf(unit)} " +
                "attempt=${r.attempt} failureClass=${verdict.failureClass} requestedAt=${r.requestedAt} " +
                "evidenceAt=${verdict.evidenceAt} note=${verdict.note}",
            at,
        )
        execution.markIncident(unit)
    }

    /**
     * 이 실행의 **판단 기록**(§15.213) — 담는 쪽이 영속하고 재기동 뒤 [resume] 에 넘길 사실. 모르는 실행이면 널이다.
     *
     * 언제 받아 둘지는 담는 쪽이 정한다. 판정과 판단은 [pump]·[resolve]·[release]·승인 안에서 서므로 그 뒤마다 받아 두면 빠지는 것이
     * 없다. 다시 지은 뒤 아직 내지 않은 시도의 시각도 싣는다 — 다시 지은 직후 다시 죽어도 기한의 시작을 잃지 않는다.
     */
    fun judgmentRecord(executionId: String): JudgmentRecord? {
        val execution = executions[executionId] ?: return null
        return JudgmentRecord(
            jobOrderId = execution.order.jobOrderId,
            orderVersion = execution.order.version,
            acknowledgedFaults = execution.acknowledgedFaults.toList(),
            approval = execution.approval,
            units = execution.units.map { unit ->
                val issued = unit.taskId.isNotEmpty()
                val pendingRequest = execution.restoredRequests[unit.unitId]?.takeIf { it.first == unit.attempt }?.second
                val pendingWindow = execution.restoredWindows[unit.unitId]?.takeIf { it.first == unit.attempt }?.second
                UnitRecord(
                    unitId = unit.unitId,
                    attempt = unit.attempt,
                    taskId = unit.taskId,
                    requestedAt = (if (issued) unit.requestedAt else null) ?: pendingRequest,
                    verifyWindow = if (unit.state == UnitState.VERIFYING) {
                        VerifyWindow(unit.downstreamDoneAt!!, unit.evidenceDeadline!!)
                    } else {
                        pendingWindow
                    },
                    verdict = unit.takeIf { it.state in UnitVerdict.STATES }?.let {
                        UnitVerdict(it.state, it.reached, it.verification, it.evidenceAt, it.failureClass, it.note, it.result)
                    },
                    decision = execution.decisions[unit.unitId],
                )
            },
        )
    }

    /** [resume] 의 4·5·6. 스냅숏의 태스크 id 로 재작업 횟수, 지난 설비 대기, 앞선 로봇 단위를 정하고 자취에 남긴다. */
    private fun rebuildFrom(execution: Execution, snapshots: Map<String, RobotSnapshot>) {
        val jobOrderId = execution.order.jobOrderId
        var lastHeld = -1
        execution.units.forEachIndexed { index, unit ->
            if (unit.route != Route.ROBOT) return@forEachIndexed
            val base = "$jobOrderId#${unit.unitId}"
            // 단위가 묶인 기체의 스냅숏이다. 실행의 기체로 읽으면 다른 기체에 묶인 단위의 재작업 횟수를 못 찾는다.
            val tasks = snapshots.getValue(execution.robotOf(unit)).tasks
            val found = tasks.keys.mapNotNull { taskId -> attemptOf(taskId, base)?.let { taskId to it } }
            val top = found.maxByOrNull { it.second } ?: return@forEachIndexed
            unit.attempt = top.second
            lastHeld = index
            execution.trail(RESUME_ATTEMPT, "${unit.unitId}: attempt=${top.second} (스냅숏 태스크 ${top.first})")
        }
        val last = execution.units.getOrNull(lastHeld) ?: return
        val before = execution.units.take(lastHeld)
        before.filter { it.route == Route.SIGNAL }.forEach {
            it.state = UnitState.DONE
            execution.trail(
                RESUME_SIGNAL_PASSED,
                "${it.unitId}: 뒤 로봇 단위 ${last.unitId} 의 태스크가 기체에 있어 통과한 것으로 둔다, 이 인스턴스가 관측하지 않아 근거 등급 E0",
            )
        }
        before.filter { it.route == Route.ROBOT }.forEach {
            execution.resumedPrior += it.unitId
            execution.trail(
                RESUME_PRIOR_ROBOT,
                "${it.unitId}: 뒤 로봇 단위 ${last.unitId} 보다 앞서 성공을 다시 관측해도 설비 근거를 다시 묻지 않는다, 근거 등급 E0",
            )
        }
    }

    /** [taskId] 가 [base] 의 태스크면 재작업 횟수, 아니면 널. 접미사가 없으면 0 이다. */
    private fun attemptOf(taskId: String, base: String): Int? {
        if (taskId == base) return 0
        if (!taskId.startsWith(base)) return null
        return RETRY_SUFFIX.matchEntire(taskId.substring(base.length))?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 보고서 11.3 — 능력은 최고 등급을 선언하고 요청은 요구 등급을 지정한다. 확인 수단이 없으면 제공 불가다.
     * 받아 놓고 UNVERIFIED 로 끝내는 것은 상류에 "될지도 모른다" 고 말한 셈이다.
     */
    private fun evidenceRefusal(order: JobOrder, capability: LogicalCapability): Submission.Rejected? =
        if (order.requiredEvidence > capability.maxEvidence) {
            Submission.Rejected(
                "요구 근거 등급 ${order.requiredEvidence} 은 ${capability.workMasterId} 의 최고 등급 ${capability.maxEvidence} 를 넘는다 — 확인 수단이 없다",
            )
        } else {
            null
        }

    /**
     * **배정 관문**(설계안 §7)의 공개 창구. 판정은 [AdmissionGate.admits] 가 한다.
     *
     * 이름을 여기 두는 이유: `orchestration.md` §2 와 ADR 42 가 `Middleware.admits` 로 대고,
     * 게이트는 §2 표의 점 기호마다 주인과 멤버가 한 파일에 선언돼 있는지 본다.
     */
    fun admits(order: JobOrder, robotId: String, planned: List<ExecutionUnit>): Admission =
        gate.admits(order, robotId, planned)

    /**
     * **제안 포트**(설계안 §7) — 순위 목록을 받아 위에서부터 첫 통과를 채택한다.
     *
     * 순서는 배정기의 것이고 이 층은 그것을 다시 정렬하지 않는다. 다만 **채택 시점에 관문을 다시 묻는다** —
     * 순위가 만들어진 뒤 그 기체의 상태가 바뀌었을 수 있고, 그 창은 통신 지연이 아니라 읽는 자와 쓰는 자가
     * 다르기 때문에 생긴다. 낡은 제안은 여기서 걸린다.
     *
     * 전부 떨어지면 [Unassigned] 다. **재계산을 요청하지 않는다** — 요청하면 이 층이 중재자가 된다.
     */
    fun adopt(order: JobOrder, ranked: List<String>): Submission {
        // **카탈로그를 한 번 읽고 그 쌍을 작업 수락까지 넘긴다.** 관문에 댄 계획과 실행이 쥘 계획이 같은 버전이어야
        // 한다 — 그 사이에 활성화가 끼면 관문이 본 적 없는 단위가 나간다.
        val mission = missions.active(order.workMasterId)
            ?: return Submission.Rejected("모르는 논리적 능력이다: ${order.workMasterId}")
        val planned = mission.capability.plan(order)
        val refusals = linkedMapOf<String, String>()
        val missing = linkedMapOf<String, RemedyOutcome.SourceMissing>()
        val unobserved = linkedMapOf<String, SourceUnobserved>()
        for (robotId in ranked) {
            when (val admission = admits(order, robotId, planned)) {
                Admission.Passed -> return submit(order, robotId, prefix = emptyList(), mission = mission)
                is Admission.Refused -> {
                    refusals[robotId] = admission.rejection.reason
                    admission.sourceMissing?.let { missing[robotId] = it }
                    admission.rejection.sourceUnobserved?.let { unobserved[robotId] = it }
                }
            }
        }
        return Unassigned(refusals, missing, unobserved)
    }

    /**
     * 비용으로 순위를 매기고 채택한다 — 배정기가 붙기 전의 기본 정책.
     *
     * 항은 이 층이 실제로 아는 것뿐이다([AssignmentCost]). 가중치를 바꾸면 순위가 바뀌고 **관문의 답은
     * 그대로다** — 정책이 판정을 흔들지 않는다는 것이 이 배선의 요점이다.
     */
    fun assign(order: JobOrder, candidates: List<String>): Submission =
        adopt(order, cost.rank(candidates, live = ::liveExecutionCount, holding = ::isHolding))

    /**
     * 이 기체에서 지금 도는 실행 수. 비용의 항이고 관문의 입력은 아니다.
     *
     * [excluding] 은 재할당이 쓴다 — **옮길 일 자신을 양쪽에서 빼야** 두 기체의 부담을 같은 자로 잰다.
     * 빼지 않으면 지금 든 쪽이 그 일 하나만큼 늘 불리해 보여, 이득이 없어도 옮기는 쪽으로 기운다.
     */
    private fun liveExecutionCount(robotId: String, excluding: String? = null): Int =
        executions.values.count {
            // 단위가 묶인 기체도 센다 — 그 기체는 이 실행의 단위를 맡았다.
            robotId in it.robots && !it.physicalState.isSettled && it.executionId != excluding
        }

    /** 지금 든 채인가. [AdmissionGate.liveHold] 의 답을 비용이 읽는 모양으로 줄인 것이다. */
    private fun isHolding(robotId: String): Boolean =
        gate.liveHold(robotId)?.kind == HoldKind.HOLD_KIND_HOLDING

    /**
     * 이 실행을 다른 기체로 옮긴다(설계안 §7) — **진동 방지가 붙은 자리.**
     *
     * 언제 부를지는 배정 정책의 몫이고(ADR 41), 트리거는 **이미 사건 번들이 생기는 그 자리**다 —
     * 실패 판정·깨진 전제·잔여 파지. 이 층이 하는 일은 «옮겨도 되는가» 를 판정하는 것뿐이며,
     * 진동을 막는 장치가 셋이다: 도는 단위, 최소 유지 시간, 이득의 문턱. 그 앞에서 반쯤 한 물리 작업을 거른다(아래) —
     * 그래서 잔여 파지가 트리거면 그 실행은 안 옮겨진다.
     *
     * **도는 단위가 있으면 안 옮긴다.** 물리적으로 움직이는 중인 일을 옮기면 한 물건에 소유자가 둘이 된다.
     *
     * **옮기는 것은 아직 물리적으로 손대지 않은 실행뿐이다.** 끝난 단위가 있거나, 로봇 단위가 한 번이라도 쥔 것이
     * 관측됐으면(지금 든 채든, 쥐었다가 지금은 모르든) 안 옮긴다 — 반쯤 한 물리 작업(놓인 자재·든 물건·채운 슬롯)을
     * 다른 기체가 이어받게 하는 인계는 이 함수가 보장하지 않는다. 플릿 경로의 파지는 플릿 AMR 이 용기를 든 것이라
     * 이 판정에 넣지 않는다.
     *
     * 관문은 **옮길 실행 자신을 빼고** 묻는다([AdmissionGate.admits] 의 `moving`). 그 실행은 아직 표에 있어서, 빼지
     * 않으면 자기 자리·자기 구역에 걸려 어느 후보도 통과하지 못한다. 빼는 것은 그 실행 하나이고 떠나는 기체의 다른
     * 실행은 그대로 센다.
     *
     * 후보가 실재하는지·연결됐는지·그 스킬을 드는지는 보지 않는다 — 능력을 못 물은 기체의 조건은 지어내지 않고
     * 접수 때 발신자가 판정한다(권위는 발신자다).
     */
    fun reassign(executionId: String, candidates: List<String>): Reassignment {
        val execution = executions[executionId]
            ?: return Reassignment.Kept("", "모르는 실행이다: $executionId")
        val from = execution.robotId
        if (execution.physicalState.isSettled) return Reassignment.Kept(from, "종착한 실행이다 — 옮길 것이 없다")
        if (execution.active != null) return Reassignment.Kept(from, "도는 단위가 있다 — 움직이는 중인 일은 안 옮긴다")
        execution.units.firstOrNull { it.state == UnitState.DONE }?.let {
            return Reassignment.Kept(
                from,
                "끝난 단위가 있다(${it.unitId}) — 반쯤 한 물리 작업의 인계는 재할당이 보장하지 않는다",
            )
        }
        execution.units.firstOrNull { it.route == Route.ROBOT && it.everHeld }?.let {
            return Reassignment.Kept(
                from,
                "자재를 다룬 단위가 있다(${it.unitId}, 마지막 파지 ${it.hold.kind.name}) — " +
                    "반쯤 한 물리 작업의 인계는 재할당이 보장하지 않는다",
            )
        }

        val held = Duration.between(execution.assignedAt, now())
        if (held < reassignPolicy.minHold) {
            return Reassignment.Kept(from, "최소 유지 시간 안이다 — ${held.seconds}초 지났고 ${reassignPolicy.minHold.seconds}초가 필요하다")
        }

        // 남은 단위로만 관문을 건다 — 끝난 단위는 사실이고 다시 재단하지 않는다. 위에서 끝난 단위가 있으면 돌려보냈으므로
        // 지금은 전부이고, 그래서 자재 선언과 배정의 대조도 주문 전체로 맞는다.
        val remaining = execution.units.filter { it.state != UnitState.DONE }
        val mine = liveExecutionCount(from, excluding = executionId)
        var best: Pair<String, Int>? = null
        for (robotId in candidates) {
            if (robotId == from) continue
            if (gate.admits(execution.order, robotId, remaining, moving = executionId) !is Admission.Passed) continue
            val load = liveExecutionCount(robotId, excluding = executionId)
            if (best == null || load < best.second) best = robotId to load
        }
        val target = best ?: return Reassignment.Kept(from, "관문을 통과하는 후보가 없다")

        // **더 싸야 옮긴다.** 같으면 그대로 둔다 — 동점에서 움직이는 것이 진동의 시작이다.
        val saved = mine - target.second
        if (saved <= reassignPolicy.margin) {
            return Reassignment.Kept(from, "이득이 문턱 이하다 — ${saved} 이고 문턱은 ${reassignPolicy.margin} 이다")
        }

        // 묶지 않은 단위는 실행의 기체를 따라 함께 옮긴다. 다른 기체에 묶인 단위는 그대로다 — 그 묶음은 이 실행의 기체와 따로 정한 것이다.
        execution.robotId = target.first
        execution.assignedAt = now()
        execution.trail("REASSIGNED", "$from -> ${target.first} (부담 차 $saved)")
        return Reassignment.Moved(from, target.first, saved)
    }

    private fun revise(execution: Execution, order: JobOrder): Submission {
        // **실행이 쥔 케이퍼빌리티로 판정한다**(카탈로그가 아니다). 다른 WorkMaster 로 바꾸는 리비전은 그 케이퍼빌리티로
        // 계획할 수 없으므로 받지 않는다 — 새 작업 지시다.
        if (order.workMasterId != execution.order.workMasterId) {
            return Submission.Rejected(
                "리비전이 WorkMaster 를 바꾼다: 받은 값=${order.workMasterId}, 현재=${execution.order.workMasterId} — 새 작업 지시로 낸다",
            )
        }
        evidenceRefusal(order, execution.capability)?.let { return it }
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
        gate.chainRefusal(execution.robotId, toPlan)?.let { return desk.record(execution.robotId, order, it, revision = true) }

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
                    // 경로마다 가른다. 로봇 단위만 계약의 갱신 규칙을 탄다 — 플릿에 맡긴 운반은 아래 이유로, 설비 대기는
                    // 같은 실행이 쥔 같은 정의의 같은 노드라 바꿀 것이 없어 그대로 둔다(기다림이 이어진다).
                    val updatable = when (unit.route) {
                        Route.ROBOT -> true
                        Route.FLEET, Route.SIGNAL -> false
                    }
                    if (fresh != null && updatable) {
                        val previous = unit.revision
                        unit.revision = order.version
                        // **기대도 새 버전의 것이다.** 옛 기대를 들고 있으면 옛 버전의 완료가 옛 기대에 맞아 새 버전의 완료로 적힌다.
                        unit.parameters = fresh.parameters
                        unit.expectedIdentity = fresh.expectedIdentity
                        unit.source = fresh.source
                        unit.destination = fresh.destination
                        // **갱신도 새 로봇 명령이다** — 같은 발행 관문을 지난다(ADR 51 결정 6). 막히면 단위의 리비전과 기대는
                        // 이미 새 판의 것으로 두고 보내기만 미룬다. 그래야 옛 리비전으로 끝난 결과가 지연 이벤트로 읽혀 새 판의
                        // 기대에 대고 확인된다 — 보내지 못한 갱신이 «옛 판대로 끝남» 을 새 판의 완료로 적게 하지 않는다.
                        if (issuable(execution, unit, WithheldIssue.REVISION_UPDATE)) {
                            // 앞 판의 갱신이 미뤄져 있었으면 이 갱신이 그것까지 싣는다 — 기체에서 도는 것은 그 앞 판이다.
                            val running = execution.deferredUpdate ?: previous
                            execution.deferredUpdate = null
                            sendRevisionUpdate(execution, unit, running)
                        } else if (unit.state == UnitState.RUNNING) {
                            // 막힌 채 판이 또 오르면 기체에서 도는 것은 여전히 처음 리비전이다 — 그것을 지킨다.
                            execution.deferredUpdate = execution.deferredUpdate ?: previous
                        }
                        // `IN_DOUBT` 이면 따로 미룰 것이 없다. 다음 조회가 새 리비전으로 나가고, 그 조회도 같은 관문을 지난다.
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

    /**
     * 도는 로봇 단위에 개정의 갱신을 보낸다 — 같은 태스크 id, 단위의 지금 리비전([ExecutionUnit.revision]). [revise] 와 미룬 갱신을
     * 허가가 돌아온 뒤 보내는 [pumpRound] 가 함께 쓴다. 부르기 전에 발행 관문을 지났어야 한다.
     *
     * @param previous 기체에서 지금 도는 리비전. 거절 사유에 «어느 판으로 끝났나» 로 남는다.
     */
    private fun sendRevisionUpdate(execution: Execution, unit: ExecutionUnit, previous: Int) {
        val revision = unit.revision
        try {
            val response = robots.start(
                execution.robotOf(unit), unit.taskId, revision, unit.skillType,
                withOptionals(execution, unit, unit.parameters),
            )
            when {
                response.hasHandle() -> if (unit.state == UnitState.IN_DOUBT) {
                    execution.handle = response.handle
                    unit.state = UnitState.RUNNING
                    unit.annotate("IN_DOUBT resolved by revision $revision update")
                }

                // **이미 종착한 태스크다** — 갱신이 닿기 전에 끝났다(계약의 래치, §4.4). 그 종착은 옛 버전의
                // 것이고 pump 가 **지연 이벤트**로 받는다(15.1). 여기서 실패로 적으면 물리 결과를 잃는다.
                response.rejection.code == RejectionCode.REJECTION_CODE_INVALID_TRANSITION ->
                    unit.annotate("revision $revision update rejected: task already terminal under revision $previous")

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
            unit.annotate("revision $revision update unanswered")
            execution.physicalState = PhysicalState.IN_DOUBT
        }
    }

    /**
     * **발행 관문을 묻는다**(ADR 51 결정 6, ADR 52) — 이 실행의 기체에 [unit] 으로 향하는 새 로봇 명령([withheld])을 내도 되는가.
     *
     * 막히면 [Execution.permitBlock] 에 어느 기체가·무엇 때문에·무엇이 바뀌면 풀리는지를 두고 자취에 한 번 남긴다. 원천은 관측
     * 시각을 계속 새로 찍으므로 그 시각은 «같은 막음» 의 열쇠에서 뺀다. 통과하면 앞의 막음을 걷고 무엇이 허가했는지를 남긴다.
     *
     * 사건을 열지 않는다. 실패가 아니라 기다림이고, 운영자가 판단할 것이 없다.
     */
    private fun issuable(execution: Execution, unit: ExecutionUnit, withheld: WithheldIssue): Boolean =
        when (val verdict = issuing.check(setOf(execution.robotOf(unit)))) {
            is IssueVerdict.Issue -> {
                execution.permitBlock?.let { lifted ->
                    execution.permitBlock = null
                    execution.trail(
                        ISSUE_RESUMED,
                        "${lifted.unitId} ${lifted.withheld.name}: " + verdict.basis.joinToString { p ->
                            "${p.robotId}=${p.permit.name} " + p.reasons.joinToString { "by ${it.source} at ${it.observedAt}" }
                        },
                    )
                }
                true
            }

            is IssueVerdict.Withheld -> {
                val previous = execution.permitBlock
                val sameTarget = previous != null && previous.unitId == unit.unitId && previous.withheld == withheld
                val block = PermitBlock(unit.unitId, withheld, verdict.robots, since = if (sameTarget) previous!!.since else now())
                execution.permitBlock = block
                if (previous?.signature != block.signature) {
                    execution.trail(
                        ISSUE_WITHHELD,
                        "${block.unitId} ${withheld.name}: " + block.robots.joinToString { r ->
                            "${r.robotId}=${r.permit.name} " + r.reasons.joinToString {
                                "${it.cause.name} source=${it.source} subject=${it.subject} resume=${it.resumeBasis}"
                            }
                        },
                    )
                }
                false
            }
        }

    // ── 구동

    /**
     * 이번 라운드의 현장 시간값. [pump] 가 시작할 때 [siteTimings] 를 한 번 읽어 둔다. 널이면 케이퍼빌리티 값이다.
     *
     * 라운드 안에서는 다시 읽지 않는다. 판정마다 읽으면 한 라운드 안에서 두 버전의 값이 섞인다.
     */
    private var round: SiteTimings? = null

    /** 이 라운드의 근거 시간 윈도우. 현장 값이 있으면 그것, 없으면 케이퍼빌리티 값이다. */
    private fun windowOf(execution: Execution): EvidenceWindow = round?.evidenceWindow ?: execution.capability.evidenceWindow

    /** 이 라운드의 `IN_DOUBT` 유예. 단위에 저장하지 않는다. */
    private fun inDoubtGraceOf(execution: Execution): Duration = round?.inDoubtGrace ?: execution.capability.inDoubtGrace

    /** 이 라운드의 진행 정체 유예. */
    private fun stallWindowOf(execution: Execution): Duration = round?.stallWindow ?: execution.capability.stallWindow

    /** 하류에서 온 것을 읽고 상태를 한 걸음 민다. 몇 번 불러도 같은 결과다(멱등). */
    fun pump() {
        round = siteTimings.current()
        val live = executions.values.filter { it.physicalState.pumps }
        // 단위가 묶인 기체까지 동기화한다 — 안 하면 그 기체의 결함·연결은 «못 봤다» 로 남아 관문이 그 단위를 막지 않는다.
        live.flatMap { it.robots }.distinct().forEach { robot -> sync(robot, live.filter { robot in it.robots }) }
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

    /** [at] 은 이 줄의 시각이다. 기본은 지금이고, 같은 시각을 다른 사실과 나눠 써야 하는 자리만 넘긴다([reopenHold]). */
    private fun Execution.trail(kind: String, detail: String, at: Instant = now()) {
        eventTrail += ObservedEvent(views[robotId]?.cursor ?: 0, at.toString(), kind, detail, local = true)
    }

    private fun pump(execution: Execution) {
        pumpRound(execution)
        incidentLog.sealIncidents(execution, round)
    }

    private fun pumpRound(execution: Execution) {
        if (!execution.physicalState.pumps) return

        // **연결이 끊기면 도는 단위의 결과는 미확정이다.** 계약이 OFFLINE 과 CONNECTION_BROKEN 을 가른다(완료 기준 5);
        // HIBERNATING 은 침묵하지만 정상이다(§4.7). 되돌아오면 스냅샷으로 태스크 상태를 다시 세워 이어 간다 —
        // 재실행이 아니다. 어느 쪽도 상류에 한 번씩 알린다(16장 — 결과가 아직 확인되지 않았다는 사실).
        val runningRobotUnit = execution.active?.takeIf { it.route == Route.ROBOT && it.state == UnitState.RUNNING }
        val view = runningRobotUnit?.let { views[execution.robotOf(it)] }
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

        // **미룬 개정의 갱신**(ADR 52). 허가가 돌아왔으면 지금 보낸다. 취소가 걸렸으면 보내지 않는다 — 멈추라는 말 뒤에 새 판을
        // 보내면 기체가 그 판으로 다시 움직일 수 있다.
        execution.active?.let { unit ->
            val previous = execution.deferredUpdate ?: return@let
            if (execution.cancelRequested || unit.state != UnitState.RUNNING) return@let
            if (issuable(execution, unit, WithheldIssue.REVISION_UPDATE)) {
                execution.deferredUpdate = null
                sendRevisionUpdate(execution, unit, previous)
            }
        }

        val active = execution.active
        if (active != null) {
            val settled = when {
                active.state == UnitState.IN_DOUBT -> resolveDoubt(execution, active)
                active.state == UnitState.VERIFYING -> checkEvidence(execution, active)
                else -> when (active.route) {
                    Route.ROBOT -> pumpRobotUnit(execution, active)
                    Route.FLEET -> pumpFleetUnit(execution, active)
                    Route.SIGNAL -> pumpSignalUnit(execution, active)
                }
            }
            if (!settled) return
            // 미룬 갱신이 끝내 안 나갔다 — 단위는 옛 리비전으로 끝났고 그 결과는 지연 이벤트로 새 판의 기대에 대고 확인됐다.
            // 그 사실을 자취에 남기고 이 단위에 걸린 막음을 걷는다. 다음 단위의 출발은 따로 관문을 묻는다.
            execution.deferredUpdate?.let { previous ->
                execution.trail(ISSUE_WITHHELD, "${active.unitId}: revision ${active.revision} update never sent; task ended under revision $previous")
                execution.deferredUpdate = null
            }
            if (execution.permitBlock?.unitId == active.unitId) execution.permitBlock = null
            execution.active = null
            execution.handle = null
            execution.transport = null
            if (execution.cancelRequested) {
                // 하류 갱신을 읽은 경로는 abort 를 이미 적었다. 검증 대기 중에 취소가 걸린 경우만 여기서 적는다.
                if (execution.physicalState != PhysicalState.ABORTED) abort(execution, inProgress = active, hold = active.hold, cleanup = "not_applicable")
                return
            }
            // **설비 대기의 기한이 실행을 중단했다**(`onDeadline = ABORTED`). 취소 요청이 아니라서 위 분기에 안 걸리고,
            // 여기서 돌아가지 않으면 아래가 다음 단위를 찾아 출발시킨다 — 신호를 못 본 채 로봇이 움직인다.
            if (execution.physicalState == PhysicalState.ABORTED) return
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
        // **작업 허가는 결함과 함께 묻고 따로 둔다**(ADR 52). 둘 다 서 있으면 둘 다 보여야 하고, 해제([release])는 결함만
        // 감수한다. 그리고 ★**두 침묵은 다르다** — 위의 결함 관문은 못 물으면 통과시키고(관측 실패로 현장을 세우지 않는다),
        // 이 관문은 모르면(UNKNOWN) 막는다. 허가를 모른 채 내는 것은 «막혀 있을지 모르는 셀에 새 일을 낸다» 이다.
        // 로봇 경로만 묻는다 — 플릿 운반은 어느 기체가 갈지 플릿이 정하고, 설비 대기는 아무것도 보내지 않는다.
        val permitted = if (next.route == Route.ROBOT) {
            issuable(execution, next, WithheldIssue.START)
        } else {
            if (execution.permitBlock?.withheld == WithheldIssue.START) execution.permitBlock = null
            true
        }
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
        // 막히면 단위는 `PENDING` 그대로 기다린다. 실패도 운영자 보류도 아니고, 허가가 돌아오는 라운드에 나간다.
        if (!permitted) return
        startUnit(execution, next)
    }

    private fun blockingFaults(execution: Execution, next: ExecutionUnit): List<Fault> {
        if (next.route != Route.ROBOT) return emptyList()
        val view = views[execution.robotOf(next)]
        if (view == null || !view.observable) {
            // 한 번만 적는다 — 허가가 막는 동안 이 관문은 라운드마다 다시 불리고, 같은 메모가 쌓이면 응답의 미완 사유가 넘친다.
            val note = "robot faults not observable before start — proceeding without the gate"
            if (next.note?.contains(note) != true) next.annotate(note)
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
     *
     * **작업 허가의 막음([Execution.permitBlock])은 건드리지 않는다**(ADR 52). 허가는 사람이 감수할 값이 아니라 원천이 다시
     * `PERMITTED` 를 낼 때만 걷힌다. 결함 없이 허가만 막고 있으면 거짓을 돌려준다.
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
        val window = stallWindowOf(execution)
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
        val updates = robots.watch(execution.robotOf(unit), execution.handle!!)
        // **결과 이벤트는 (실행, 버전)으로 맞춘다**(15.1). 이 단위의 지금 버전보다 낮은 버전의 종착은 지연 이벤트로
        // **보존**하고, 이 버전의 갱신만 상태로 옮긴다.
        val (late, current) = updates.partition { it.revision < unit.revision }
        late.filter { it.state.isTerminal() }.forEach { execution.noteLate(unit, it) }
        val last = current.lastOrNull()
            ?: return late.lastOrNull { it.state.isTerminal() }?.let { settleLate(execution, unit, it) } ?: false
        unit.observeHold(last.hold)
        if (!last.state.isTerminal()) {
            noteProgress(execution, unit, last)
            execution.physicalState = when {
                execution.cancelRequested -> PhysicalState.CANCELING
                execution.linkBroken -> PhysicalState.IN_DOUBT
                else -> PhysicalState.RUNNING
            }
            return false
        }
        judgeEffectMismatch(execution, unit, completed = last.state == TaskState.TASK_STATE_SUCCEEDED)
        when (last.state) {
            TaskState.TASK_STATE_SUCCEEDED -> {
                // 하류가 실어 준 결과 참조(E0 의 내용). 비어 있으면 비어 있는 채로 — 지어내지 않는다.
                unit.result = last.partialResult.takeIf { it.isNotBlank() }
                val prior = unit.unitId in execution.resumedPrior
                // 판단 기록이 이 시도의 검증 대기 창을 넘겼으면 그 창에 대고 본다 — 완료 시각과 기한을 다시 정하지 않는다(§15.213).
                val window = execution.restoredWindows.remove(unit.unitId)?.takeIf { it.first == unit.attempt }?.second
                beginVerify(
                    execution, unit, reachedByDownstream = Evidence.E0, doneAt = window?.doneAt ?: stateTime(last), prior = prior,
                    deadline = window?.deadline,
                )
                if (unit.state == UnitState.VERIFYING) return false // active 로 남아 시간창을 기다린다
            }

            TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> unit.state = UnitState.ABORTED

            else -> {
                // 계약이 실은 **정준 분류**로만 분기한다(어댑터가 벤더 코드에서 옮긴 것, 미믹은 프로파일
                // 모드에서). 하류 상태 이름·모드 이름·벤더 원문은 note 에 동반할 뿐이다 — 상류에는 분류만
                // 간다(보고서 16장). 분류가 없으면 UNCLASSIFIED 이지 지어낸 분류가 아니다.
                failWithEvidenceCheck(
                    execution, unit, canonicalClass(last),
                    detail = downstreamDetail(last), at = stateTime(last),
                    fault = last.fault.takeIf { last.hasFault() },
                )
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
        unit.observeHold(update.hold)
        unit.annotate("late event: revision ${update.revision} ${update.state.name} arrived under revision ${unit.revision}")
        when (update.state) {
            TaskState.TASK_STATE_SUCCEEDED -> {
                beginVerify(execution, unit, reachedByDownstream = Evidence.E0, doneAt = stateTime(update), strict = true)
                if (unit.state == UnitState.VERIFYING) return false
            }
            TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> unit.state = UnitState.ABORTED
            else -> failWithEvidenceCheck(
                execution, unit, canonicalClass(update),
                detail = downstreamDetail(update), at = stateTime(update),
                fault = update.fault.takeIf { update.hasFault() },
            )
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
        val lookup = lookupOf(unit.route)
        if (lookup == ExecutionLookup.CLIENT_REFERENCE && unit.lookups < lookupRetries) {
            // **로봇 경로의 조회는 같은 참조의 `StartTask` 재전송이다** — 원 요청이 안 닿았으면 새 접수가 되어 기체가 움직인다.
            // 그래서 새 발행과 같은 관문을 지난다(ADR 52). 막히면 조회 횟수를 쓰지 않고 미확정인 채 기다린다 — 횟수를 쓰면
            // 허가가 막은 동안에 운영자 판단으로 넘어가, «하류가 답하지 않았다» 가 아닌 것이 그렇게 적힌다.
            if (unit.route == Route.ROBOT && !issuable(execution, unit, WithheldIssue.IN_DOUBT_LOOKUP)) {
                execution.physicalState = PhysicalState.IN_DOUBT
                return false
            }
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
                        val response = robots.cancel(execution.robotOf(unit), handle)
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
        val window = windowOf(execution)
        val signal = execution.observeCell(unit)
        val observedAt = signal?.observedAt ?: now()
        val provisional = signal != null && signal.occupied &&
            (unit.expectedIdentity == null || signal.identity == unit.expectedIdentity) &&
            !observedAt.isBefore(requestedAt.minus(window.before))
        if (!provisional && now().isBefore(requestedAt.plus(inDoubtGraceOf(execution)))) {
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
        execution.markIncident(unit)
        return true
    }

    /** 13.2 ① — 같은 참조로 다시 묻는다. 돌아오면 핸들을 잡고 참, 거절이면 단위를 실패로 적고 거짓, 답이 없으면 던진다. */
    private fun lookupDownstream(execution: Execution, unit: ExecutionUnit): Boolean = when (unit.route) {
        Route.ROBOT -> {
            val response = robots.start(
                execution.robotOf(unit), unit.taskId, unit.revision, unit.skillType,
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
                    unit.preconditionSubjects = rejection.referencesList
                        .filter { it.key == Reference.Key.KEY_PRECONDITION_SUBJECT }.map { it.value }
                    unit.note = "sender refused: ${rejection.detail} [subject=${unit.preconditionSubjects.joinToString()}]"
                } else {
                    unit.state = UnitState.FAILED
                    unit.failureClass = rejection.code.takeIf { it != RejectionCode.REJECTION_CODE_UNSPECIFIED }?.name ?: "REJECTED"
                }
                execution.markIncident(unit)
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
                execution.markIncident(unit)
                false
            }
        }

        // 설비 대기는 하위에 보낼 요청이 없다 — 시작이 곧 기다림의 시작이고, 읽기는 [pumpSignalUnit] 이 한다.
        Route.SIGNAL -> true
    }

    /**
     * 경로마다 하위가 클라이언트 참조로 기존 실행을 찾아 주는가(13.2 ①).
     *
     * 설비 대기는 요청을 보내지 않으므로 미확정이 생기지 않는다 — 실행의 미확정 자동 해소를 막지 않는 쪽으로 응답한다.
     */
    private fun lookupOf(route: Route): ExecutionLookup = when (route) {
        Route.ROBOT -> robots.executionLookup
        Route.FLEET -> fleet.executionLookup
        Route.SIGNAL -> ExecutionLookup.CLIENT_REFERENCE
    }

    /**
     * 설비 대기 단위를 한 단계 민다([Route.SIGNAL]) — 이름 있는 신호를 당겨 읽고, 기대 값이면 끝낸다.
     *
     * 규칙 넷이다.
     *
     * 1. **취소가 걸렸으면 기다림을 끝낸다.** 하위 손잡이가 없어 취소가 하위로 가지 않으므로 여기서 본다. 대기 단위는
     *    `ABORTED`, 실행은 취소 경로(정리 «해당 없음»)로 끝난다.
     * 2. **지금 값이 기대 값이면 `DONE` 이다.** 설비가 관측한 사실이라 근거 등급은 E2 다. 관측 시각은 자취에만 남긴다.
     * 3. **못 읽으면(`null`) 계속 기다린다.** 못 읽은 것을 «기대 값이 아님» 으로 접지 않는다 — 기한이 판정한다.
     * 4. **기한(시작 시각 + 기한)이 지나면** [WaitSpec.onDeadline] 으로 간다. 마지막으로 읽은 값을 자취에 한 번 남기고
     *    인시던트를 연다(`SIGNAL_DEADLINE`).
     *
     * 신호는 **값이 바뀔 때만** 자취에 남긴다. 진행마다 남기면 자취가 넘쳐 근거 윈도우가 그 줄로 찬다.
     *
     * @return 단위가 이 진행에서 종료(운영자 보류 포함)했는가.
     */
    private fun pumpSignalUnit(execution: Execution, unit: ExecutionUnit): Boolean {
        val wait = unit.wait ?: error("설비 대기 단위에 대기 사양이 없다: ${unit.unitId}")
        if (execution.cancelRequested) {
            unit.state = UnitState.ABORTED
            unit.annotate("cancelled while waiting for ${wait.signal}")
            abort(execution, inProgress = unit, hold = null, cleanup = "not_applicable")
            return true
        }

        val reading = cell.signal(wait.signal)
        val seen = reading?.value ?: NO_SIGNAL
        if (execution.signalSeen[unit.taskId] != seen) {
            execution.signalSeen[unit.taskId] = seen
            execution.trail("CELL_SIGNAL", "signal ${wait.signal}: ${describe(reading)}")
        }

        if (reading != null && reading.value == wait.expect) {
            unit.reached = Evidence.E2
            unit.verification = Verification.MATCHED
            unit.evidenceAt = reading.observedAt ?: now()
            unit.state = UnitState.DONE
            return true
        }

        val startedAt = unit.requestedAt!!
        if (!now().isAfter(startedAt.plus(wait.deadline))) {
            execution.physicalState = PhysicalState.RUNNING
            return false
        }

        // 기한이 지났다. 마지막으로 읽은 값을 **기한 시점의 관측으로** 한 번 더 남긴다 — 값이 안 바뀌었으면 그 줄은 시작
        // 무렵에 한 번 적혔을 뿐이라 인시던트의 근거 윈도우 밖에 있다.
        execution.trail("CELL_SIGNAL", "signal ${wait.signal} at deadline: ${describe(reading)}")
        unit.failureClass = WaitSpec.SIGNAL_DEADLINE
        unit.annotate("signal ${wait.signal} did not read '${wait.expect}' within ${wait.deadline} — ${wait.onDeadline.name}")
        execution.markIncident(unit)
        when (wait.onDeadline) {
            DeadlineOutcome.OPERATOR_HOLD -> unit.state = UnitState.OPERATOR_HOLD
            DeadlineOutcome.ABORTED -> {
                unit.state = UnitState.FAILED
                // **남은 단위는 내보내지 않는다** — 먼저 중단으로 적고 실행을 끝낸다. `PENDING` 으로 두면 그 단위가
                // «아직 안 시작함» 으로 남아, 진행 루프가 출발시킬 자리와 정산 규칙이 부분 완료로 읽을 자리가 남는다.
                execution.units.filter { it.state == UnitState.PENDING }.forEach {
                    it.state = UnitState.ABORTED
                    it.annotate("not started: ${wait.signal} deadline aborted the execution")
                }
                abort(execution, inProgress = unit, hold = null, cleanup = "not_applicable", requested = false)
            }
        }
        return true
    }

    private fun describe(reading: NamedSignal?): String =
        if (reading == null) NO_SIGNAL else "value=${reading.value} at=${reading.observedAt?.toString() ?: "(read now)"}"

    private fun transportOrderOf(unit: ExecutionUnit) = TransportOrder(
        reference = unit.taskId,
        containerId = unit.expectedIdentity ?: unit.unitId,
        source = unit.source ?: "",
        destination = unit.destination ?: "",
    )

    /** @return 단위가 종착했는가. */
    private fun pumpFleetUnit(execution: Execution, unit: ExecutionUnit): Boolean {
        val status = fleet.status(execution.transport!!)
        unit.observeHold(holdOf(status, unit))
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
                execution.markIncident(unit)
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

        val robot = execution.robotOf(unit)
        val declared = robots.capabilities(robot)
            ?.skillsList?.firstOrNull { it.skillType == unit.skillType }
            ?.parametersList?.map { it.key }?.toSet()

        val kept = if (declared == null) emptyMap() else wanted.filterKeys { it in declared }
        (wanted.keys - kept.keys).forEach { key ->
            if (!droppedOptionals.add(robot to key)) return@forEach
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
        // 판단 기록이 이 시도의 요청 시각을 넘겼으면 그것이다(§15.213). 다시 지은 뒤 같은 시도를 다시 보내는 것이지 새 요청이 아니므로,
        // 미확정 유예·관측 창·설비 대기의 기한은 처음 보낸 시각부터 이어 잰다. 다른 시도(재작업)면 쓰지 않는다.
        unit.requestedAt = execution.restoredRequests.remove(unit.unitId)?.takeIf { it.first == unit.attempt }?.second ?: now()
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

    /** 이 단위가 이번 라운드에 닫혔다. 봉하는 것은 [IncidentLog.sealIncidents] 다. */
    private fun Execution.markIncident(unit: ExecutionUnit) {
        pendingIncidents += unit.unitId
    }

    /**
     * 설계안 §5 — 선언된 효과와 마지막 관측을 대조해, 운영자에게 «모른다» 로 나갈 자리 중 **근거로 판정할 수
     * 있는 것을 판정으로 바꾼다.** 미결이 «손에 없다»·«아직 들고 있다» 가 되면 다음 행동이 갈린다(§5.3).
     *
     * **플릿의 운반은 보지 않는다** — 카탈로그에 없는 단위의 효과를 지어내지 않는다. 그리고 관측이 없거나
     * 볼 수 없으면 [HoldEffects.mismatch] 가 판정하지 않는다(§5.2).
     */
    private fun judgeEffectMismatch(execution: Execution, unit: ExecutionUnit, completed: Boolean) {
        if (unit.route != Route.ROBOT) return
        val expected = HoldEffects.expectedAtEnd(unit.skillType, unit.everHeld, completed) ?: return
        // **어긋나지 않아도 기대를 남긴다.** 판단 경로는 판정이 났을 때만 필요한 것이 아니다 —
        // «무엇을 기대했고 무엇을 봤는가» 가 있어야 읽는 사람이 왜 아무 판정도 안 났는지까지 따라간다.
        unit.holdExpected = expected
        val mismatch: HoldMismatch = HoldEffects.compare(expected, unit.hold.kind) ?: return
        if (unit.holdMismatch == mismatch) return
        unit.holdMismatch = mismatch
        unit.annotate("effect/observation mismatch: ${mismatch.name} (expected=${expected.name} hold=${unit.hold.kind.name})")
        // **성공으로 끝났는데 어긋난 경우에도 사건을 연다.** 하류는 끝났다는데 손에 남아 있다 —
        // 운영자가 봐야 하는 사실이고, 사건이 없으면 그 사실이 어디에도 안 실린다.
        execution.markIncident(unit)
    }

    /** 관측을 한 곳으로 — 쥔 것을 본 적이 있는지는 중단 시점의 기대를 정한다(설계안 §5). */
    private fun ExecutionUnit.observeHold(observed: HoldState) {
        hold = observed
        if (observed.kind == HoldKind.HOLD_KIND_HOLDING) everHeld = true
    }

    /** 설비에 묻고 **그 사실을 자취에 남긴다** — 조회하고 버리면 번들의 근거 창에서 설비 쪽이 빈다. */
    private fun Execution.observeCell(unit: ExecutionUnit): SlotSignal? {
        val where = unit.destination ?: return null
        val signal = cell.observe(where)
        val what = if (signal == null) {
            "no signal"
        } else {
            "occupied=${signal.occupied} identity=${signal.identity ?: "(none)"} at=${signal.observedAt?.toString() ?: "(read now)"}"
        }
        trail("CELL_SIGNAL", "$where: $what")
        return signal
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

    // ── 근거 결합 (보고서 12장)

    /**
     * 하류의 완료를 받는다 — 그리고 **근거를 결합하기 시작한다**(보고서 10.2 — 번역만으로는 안 된다).
     *
     * 하류가 준 등급([reachedByDownstream]: 로봇 E0, 플릿 E1)이 요구 이상이면 그대로 완료.
     * 아니면 [UnitState.VERIFYING] 으로 옮기고 시간창이 닫힐 때까지([EvidenceWindow.after])
     * 설비에 묻는다 — PLC 는 폴링이라 신호가 보고보다 **늦게** 읽힐 수 있다. 첫 확인은 지금 한다.
     */
    private fun beginVerify(
        execution: Execution,
        unit: ExecutionUnit,
        reachedByDownstream: Evidence,
        doneAt: Instant,
        strict: Boolean = false,
        prior: Boolean = false,
        /** 판단 기록이 넘긴 근거 기한. 널이면 지금 정한다. */
        deadline: Instant? = null,
    ) {
        unit.reached = reachedByDownstream
        unit.downstreamDoneAt = doneAt
        // `strict` — 하류의 보고만으로는 못 닫는다(지연 이벤트: 옛 버전의 완료). 설비가 지금 버전의 기대에 대고 봐야 한다.
        // `prior` 는 [resume] 이 앞선 것으로 둔 로봇 단위다. 설비를 다시 묻지 않고 하류 등급 그대로 닫는다.
        if (!strict && (prior || execution.order.requiredEvidence <= reachedByDownstream)) {
            unit.verification = Verification.NOT_REQUESTED
            unit.state = UnitState.DONE
            return
        }
        unit.state = UnitState.VERIFYING
        // **이 순간의 뒤 폭으로 정해 저장한다.** 나중에 뒤 폭이 바뀌어도 다시 계산하지 않는다.
        unit.evidenceDeadline = deadline ?: doneAt.plus(windowOf(execution).after)
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
        val window = windowOf(execution)
        val current = now()
        unit.rechecks += 1

        val signal = execution.observeCell(unit)
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
                execution.markIncident(unit)
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
    private fun failWithEvidenceCheck(
        execution: Execution,
        unit: ExecutionUnit,
        failureClass: String,
        detail: String,
        at: Instant,
        /** 분류가 그 값이 된 근거. 하류가 결함 없이 실패를 알렸으면 널이다(§15.177). */
        fault: Fault? = null,
    ) {
        unit.failureClass = failureClass
        unit.fault = fault
        unit.note = detail
        unit.downstreamDoneAt = at
        if (execution.order.requiredEvidence > Evidence.E1) {
            val window = windowOf(execution)
            val signal = execution.observeCell(unit)
            val observedAt = signal?.observedAt ?: now()
            val present = signal != null && signal.occupied &&
                (unit.expectedIdentity == null || signal.identity == unit.expectedIdentity) &&
                !observedAt.isBefore(at.minus(window.before)) && !observedAt.isAfter(at.plus(window.after))
            if (present) {
                unit.verification = Verification.MATCHED
                unit.evidenceAt = observedAt
                unit.state = UnitState.OPERATOR_HOLD
                unit.note = "downstream reported $failureClass ($detail) but evidence present at ${unit.destination}"
                execution.markIncident(unit)
                return
            }
        }
        unit.state = UnitState.FAILED
        execution.markIncident(unit)
    }

    /**
     * 운영자의 판단(12.3 둘째 행). [OperatorDecision.CONFIRM_DONE] 은 설비 근거로 완료(E2),
     * [OperatorDecision.REWORK] 는 그 단위를 **새 정체성**으로 다시 계획한다 — 자동으로 돌지 않았던 것을 사람이 돌린다.
     *
     * **사람만 판단한다**(ADR 47). 확인은 설비 근거 없이도 단위를 완료로 올리고 재작업은 실물을 다시 돌린다 — 둘 다
     * 자동으로 하지 않기로 한 것을 사람이 하는 자리라, 에이전트가 누르면 그 결정이 자동화로 되돌아간다.
     * **누가 판단했는지 없이는 판단이 안 된다** — 기본값을 두면 그 기본값이 무기명 판단 경로가 된다(ADR 43 과 같은 이유).
     */
    fun resolve(executionId: String, unitId: String, decision: OperatorDecision, approver: Approver): ResolveOutcome {
        if (approver.kind == ApproverKind.AGENT) {
            return ResolveOutcome.Refused("에이전트는 운영자 판단을 내지 못한다 — 확인과 재작업은 사람의 것이다: ${approver.id}")
        }
        val execution = executions[executionId] ?: return ResolveOutcome.NotHeld
        val unit = execution.units.firstOrNull { it.unitId == unitId && it.state == UnitState.OPERATOR_HOLD }
            ?: return ResolveOutcome.NotHeld
        // 판단 기록이 싣는다(§15.213). 판단이 선 시도를 단다 — 재작업이면 아래에서 시도가 하나 오른다.
        execution.decisions[unitId] = RecordedDecision(decision, approver, now(), unit.attempt)
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

        // ★**사람의 걸음을 사건에 남긴다.** 안 남기면 사건과 그 뒤의 탐색 사이가 비어 보이고, 읽는
        //   쪽은 그 사이를 «자동으로 회복했다» 로 메운다(§15.188). 아직 판단이 안 실린 **가장 최근의**
        //   사건에 붙인다 — 같은 단위가 두 번 깨지면 걸음도 둘이고 각각 제 사건에 속한다.
        incidentLog.noteResolution(executionId, unitId, decision, approver)
        return ResolveOutcome.Resolved
    }

    // ── 제안과 승인(설계안 §6.4)

    /**
     * 탐색이 답한 것들, 답한 순서대로 — [RemedyDesk.remedySearches].
     *
     * 순서는 이 층이 보증한다 — 읽는 쪽이 `searchId` 를 뜯어 번호를 꺼내면 그 순간 형식에 묶이고,
     * 그 형식을 대는 시험은 어디에도 없다.
     */
    fun remedySearches(): List<RemedySearchRecord> = desk.remedySearches()

    /** 이 (기체, 주문)에 서 있는 제안. 없거나 가려져 있으면 널 — [RemedyDesk.proposal]. */
    fun proposal(robotId: String, jobOrderId: String): Remedy.Found? = desk.proposal(robotId, jobOrderId)

    /** 가려 둔 제안이 있는가 — [RemedyDesk.withheldProposal]. */
    fun withheldProposal(robotId: String, jobOrderId: String): Boolean = desk.withheldProposal(robotId, jobOrderId)

    /**
     * 사람이 먼저 진단한다 — [RemedyDesk.diagnose].
     *
     * @return 가려 둔 제안이 실제로 있었는가. 없으면 적히지 않는다 — 없는 사건에 진단을 남기지 않는다.
     */
    fun diagnose(robotId: String, jobOrderId: String, cause: String): Boolean = desk.diagnose(robotId, jobOrderId, cause)

    /** 사람이 먼저 적은 진단 — [RemedyDesk.diagnosis]. */
    fun diagnosis(robotId: String, jobOrderId: String): String? = desk.diagnosis(robotId, jobOrderId)

    /** 같은 조치가 거듭 승인된 것 — [RemedyDesk.repeatedRemedies]. */
    fun repeatedRemedies(atLeast: Int = 2): List<RepeatedRemedy> = desk.repeatedRemedies(atLeast)

    /**
     * **사람의 문**(설계안 §6.4) — 운영자가 그 자리의 값을 들고 제안을 승인한다.
     *
     * 파라미터는 **사람이 준다.** 탐색기는 어느 스킬을 딛을지까지만 계산하며, 그 스킬이 요구하는 값(어디에
     * 놓을 것인가 같은)은 지어낼 수 없다 — 지어내면 승인은 무엇을 승인하는지 모르는 채 누르는 단추가 된다.
     *
     * **주문을 안 받는다**(ADR 44). 승인은 «무엇을 하라» 가 아니라 «그것을 하라» 이므로, 가리킬 제안의
     * 열쇠만 받고 주문은 제안과 함께 이 층이 들고 있던 것을 쓴다.
     *
     * **에이전트는 이 문으로 못 들어온다.** 값을 실을 수 있다는 것이 곧 조치를 기술할 수 있다는 뜻이고,
     * 그러면 선언이 덮는 것이 «어느 스킬» 까지라서 실제 권한이 선언보다 넓어진다. 에이전트의 문은
     * [attemptApproval] 이며 그쪽에는 값을 실을 칸이 없다.
     *
     * **누가 눌렀는지 없이는 승인이 안 된다**(ADR 43). 기본값을 두면 그 기본값이 무기명 승인 경로가 되고,
     * 자원 소유 대장이 비워 두면 안 된다고 적은 자리가 바로 거기다.
     *
     * @param parameters 걸음마다 하나씩, 걸음 순서대로.
     */
    fun approveRemedy(
        robotId: String,
        jobOrderId: String,
        parameters: List<Map<String, String>>,
        approver: Approver,
    ): Submission {
        if (approver.kind == ApproverKind.AGENT) {
            return Submission.Rejected("에이전트는 값을 실어 승인하지 못한다 — 값은 선언과 관측에서 온다: ${approver.id}")
        }
        return when (val judgment = desk.judge(robotId, jobOrderId, approver, given = parameters, saw = null)) {
            is Judgment.No -> Submission.Rejected(judgment.reason)
            is Judgment.Go -> when (val submission = commit(judgment)) {
                // **접힌 접수는 승인이 아니다** — 밖의 문과 같은 사유로 거절한다(ADR 46). 멱등으로 돌려주면 받는 쪽이
                // «이미 받았다» 로 읽고, 조치 열이 안 나간 것이 성공의 모양으로 보인다.
                is Submission.Idempotent -> Submission.Rejected(notApplied(submission.execution))
                else -> submission
            }
        }
    }

    /**
     * **밖의 문**(ADR 44) — 값을 싣지 못하는 승인 시도.
     *
     * 부르는 쪽의 권한이 여기서 «승인 시도 한 번» 을 넘지 않는 것은 규율이 아니라 **표면의 모양** 때문이다:
     * 주문도 걸음도 값도 실을 칸이 없다. 값은 사람의 선언과 기체의 관측에서만 오며 이 함수를 부르는 쪽은
     * 둘 다 못 바꾼다.
     *
     * **선언이 없으면 거절이다 — 승인자 종류를 안 가린다.** 사람이 선언 없이 누를 수 있는 것은 값을 들고
     * 오기 때문이고(ADR 43), 값 없이 들어오는 문에서는 선언이 곧 값의 출처다. 종류로 가르면 사람 이름을
     * 단 시도가 값 없이 통과하는 길이 생긴다.
     */
    fun attemptApproval(attempt: ApprovalAttempt): ApprovalOutcome {
        val judgment = desk.judge(
            attempt.robotId,
            attempt.jobOrderId,
            attempt.approver,
            given = null,
            saw = attempt.sawSkillTypes,
        )
        return when (judgment) {
            // 소모 기록은 판정이 든 그대로 옮긴다 — 부르는 쪽이 «내 요청» 을 가르는 재료다(ADR 46).
            is Judgment.No -> ApprovalOutcome.Refused(judgment.refusal, judgment.reason, judgment.consumed)
            is Judgment.Go -> when (val submission = commit(judgment)) {
                // **실린 값을 돌려준다** — 부르는 쪽이 고르지 않았으므로, 자기 이름으로 무엇이 나갔는지
                // 아는 길이 이것뿐이다.
                is Submission.Accepted -> ApprovalOutcome.Approved(
                    submission.execution.executionId,
                    judgment.prefix.map { ApprovedStep(it.skillType, it.parameters, it.unitId) },
                )
                is Submission.Rejected -> ApprovalOutcome.Refused(ApprovalRefusal.REFUSED_BY_GATE, submission.reason)
                // **접힌 접수는 승인이 아니다.** 그 주문이 이미 실행으로 서 있으면 조치 열은 안 나간다
                // (§15.175, ADR 46). 성공으로 내면 «승인했는데 아무 일도 안 일어났다» 가 초록으로 보인다.
                is Submission.Idempotent -> ApprovalOutcome.Refused(
                    ApprovalRefusal.REMEDY_NOT_APPLIED,
                    notApplied(submission.execution),
                )
                // **[adopt] 만 내는 값이다.** 여기로 오면 접수 경로가 바뀐 것이고, 조용히 삼키면
                // 승인이 안 된 채로 답만 돌아간다.
                is Unassigned -> error("접수가 이 값을 낼 자리가 아니다: $submission")
            }
        }
    }

    /**
     * 판정이 선 뒤 실제로 내린다.
     *
     * **관문이 거절하면 제안을 안 지운다.** 자리 경쟁으로 못 들어간 것은 자격의 문제가 아니고, 그 순간
     * 제안을 소모하면 사람이 나중에 누를 것까지 함께 사라진다 — 조건이 풀리면 같은 제안이 그대로 선다.
     */
    private fun commit(go: Judgment.Go): Submission {
        // **그 주문이 이미 실행으로 서 있으면 조치 열을 실을 길이 없다**(ADR 46). 판이 같으면 접수가 멱등으로 접히고,
        // 낮으면 지난 판으로 거절되며, 더 높으면 개정으로 가는데 개정은 조치 열을 안 받는다(§15.175). 더 높은 판이
        // 개정을 지나가면 승인이 성공을 내고 조치는 안 나가며 그 거짓이 소모 기록에 남는다 — 버전이 거꾸로 도착하면
        // 닿는 길이다. 판과 무관하게 여기서 접는다.
        executions.values.firstOrNull { it.order.jobOrderId == go.order.jobOrderId }
            ?.let { return Submission.Idempotent(it) }
        val submission = submit(go.order, go.robotId, go.prefix, approvedBy = go.approver)
        if (submission !is Submission.Accepted) return submission
        val consumed = desk.settle(go, submission.execution.executionId)
        // 판단 기록이 싣는다(§15.213). 다시 지을 때 걸음이 계획 앞에 다시 서고 승인 장부에 소모로 다시 선다.
        submission.execution.approval = RecordedApproval(go.robotId, consumed.approver, consumed.at, consumed.wallClockAt, consumed.steps)
        return submission
    }

    /** 접힌 승인의 사유 — 두 문이 같은 말을 한다(ADR 46). */
    private fun notApplied(execution: Execution): String =
        "그 주문이 이미 실행으로 서 있다 — 조치 열이 안 나갔다: " +
            "${execution.executionId} · ${execution.robotId} · 판 ${execution.version}"

    // ── 사건 번들(설계안 §4) — 장부는 IncidentLog 에 있고, 여기는 조회와 검토의 창구다.

    /** 열린 순서대로 — [IncidentLog.incidents]. */
    fun incidents(): List<IncidentBundle> = incidentLog.incidents()

    fun incident(incidentId: String): IncidentBundle? = incidentLog.incident(incidentId)

    /**
     * 사람이 사건을 읽고 판정을 남긴다 — [IncidentLog.reviewIncident].
     * **자동으로 채우지 않는다.** 동의도 사람이 눌러야 동의다.
     */
    fun reviewIncident(incidentId: String, verdict: ReviewVerdict, cause: String): Boolean =
        incidentLog.reviewIncident(incidentId, verdict, cause)

    /**
     * 승인자 종류별 검토 지표 — [IncidentLog.reviewMetricsByApprover].
     *
     * @param since 실 시계 기준 이 시각부터의 사건만. 널이면 전부.
     */
    fun reviewMetricsByApprover(since: Instant? = null): Map<ApproverKind, ReviewMetrics> =
        incidentLog.reviewMetricsByApprover(since)

    /**
     * 검토가 실제로 일어나는가 — [IncidentLog.reviewMetrics].
     *
     * @param since 실 시계 기준 이 시각부터의 사건만. 교대 단위로 보라고 있는 자리다. 널이면 전부.
     */
    fun reviewMetrics(since: Instant? = null): ReviewMetrics = incidentLog.reviewMetrics(since)

    // ── 취소 (보고서 14)

    /** 취소를 건다. 실제 중단과 잔여 상태는 [pump] 가 하류를 읽고 [CancelReport] 로 확정한다. */
    fun cancel(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        if (!execution.physicalState.pumps) return false
        execution.cancelRequested = true
        execution.cancelRefusal = null
        execution.handle?.let { handle ->
            // 핸들은 도는 단위의 것이다 — 그 단위가 묶인 기체로 보낸다.
            val response = robots.cancel(execution.active?.let(execution::robotOf) ?: execution.robotId, handle)
            // **거절을 감추지 않는다.** 그 스킬이 취소를 안 들면(CANCEL_UNSUPPORTED) 진행 중 단위는 끝까지 가고 다음
            // 경계에서 멈춘다 — 그 사실이 취소 응답에 드러나야 상류가 중단점을 안다(16장).
            if (!response.hasState()) execution.cancelRefusal = response.rejection.code.name
        }
        execution.transport?.let { fleet.cancel(it) }
        execution.physicalState = PhysicalState.CANCELING
        return true
    }

    fun lastCancel(executionId: String): CancelReport? = executions[executionId]?.lastCancel

    /**
     * @param requested 상위의 취소 요청([cancel])으로 멈췄는가. 거짓이면 이 계층이 정해 둔 규칙(설비 대기의 기한 뒤
     *   `ABORTED`)으로 멈춘 것이고, **취소 응답([CancelReport])을 남기지 않는다** — 아무도 취소를 안 했는데 취소 응답이
     *   있으면 «누가 멈췄나» 에 거짓으로 응답한다.
     */
    private fun abort(
        execution: Execution,
        inProgress: ExecutionUnit?,
        hold: HoldState?,
        cleanup: String,
        requested: Boolean = true,
    ) {
        // 끝난 실행에는 낼 것이 없다 — 막음과 미룬 갱신을 걷는다. 막혔던 사실은 자취의 `ISSUE_WITHHELD` 에 남아 있다.
        execution.permitBlock = null
        execution.deferredUpdate = null
        if (!requested) {
            execution.physicalState = PhysicalState.ABORTED
            notify(execution)
            return
        }
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
            executionId = execution.executionId,
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
            residualHold = residualHoldOf(units),
            inDoubtUnits = units.filter { it.state == UnitState.IN_DOUBT }.map { it.unitId },
            results = units.filter { it.state == UnitState.DONE && !it.result.isNullOrBlank() }.associate { it.unitId to it.result!! },
            blockedBy = execution.blockedBy.map { canonicalClassOf(it) },
            // 기체 연결은 도는 단위가 묶인 기체의 것이다. 도는 단위가 없으면 실행의 기체다.
            connection = (views[execution.active?.let(execution::robotOf) ?: execution.robotId]?.takeIf { it.observable }?.connection ?: ConnectionState.CONNECTION_STATE_UNSPECIFIED).name,
            autoResolvesInDoubt = units.all { lookupOf(it.route) == ExecutionLookup.CLIENT_REFERENCE },
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

        /** 설비 대기가 이름 있는 신호를 못 읽었다 — 자취의 값 자리. «기대 값이 아님» 과 다르다. */
        private const val NO_SIGNAL = "no signal"

        /**
         * [resume] 이 기체 스냅숏을 못 읽어 거부할 때 사유의 앞머리. 담는 쪽은 이것으로 «나중에 다시 시도» 와 그 밖의 거부를
         * 나눈다. 뒤에 기체 id 가 붙는다.
         */
        const val RESUME_SNAPSHOT_UNREADABLE = "기체 스냅숏을 못 읽어 다시 짓지 않는다"

        /** [resume] 이 로봇 단위의 재작업 횟수를 스냅숏 태스크 id 에서 맞췄다는 실행 자취의 종류. */
        const val RESUME_ATTEMPT = "RESUME_ATTEMPT"

        /** [resume] 이 마지막 로봇 태스크 앞의 설비 대기를 관측 없이 끝난 것으로 뒀다는 실행 자취의 종류. */
        const val RESUME_SIGNAL_PASSED = "RESUME_SIGNAL_PASSED"

        /** [resume] 이 마지막 로봇 태스크 앞의 로봇 단위를 설비 근거 재확인 없이 닫기로 했다는 실행 자취의 종류. */
        const val RESUME_PRIOR_ROBOT = "RESUME_PRIOR_ROBOT"

        /**
         * [resume] 이 판단 기록을 받지 않을 때의 사유 앞머리 — 기록의 작업 지시 id·버전이 넘긴 작업 지시와 다르거나 계획에 없는 단위가
         * 있다. [RESUME_SNAPSHOT_UNREADABLE] 과 달리 다시 시도해도 같다. 담는 쪽이 넘긴 기록이 이 실행의 것이 아니다.
         */
        const val RESUME_RECORD_MISMATCH = "판단 기록이 이 작업 지시의 것이 아니어서 다시 짓지 않는다"

        /** [resume] 이 판단 기록의 사실(감수한 결함, 승인 소모, 단위의 판정)을 옮겼다는 실행 자취의 종류. */
        const val RESUME_RECORD = "RESUME_RECORD"

        /** [resume] 이 스냅숏보다 앞선 시도의 기록이라 그 단위의 시각과 판정을 옮기지 않았다는 실행 자취의 종류. */
        const val RESUME_RECORD_SUPERSEDED = "RESUME_RECORD_SUPERSEDED"

        /**
         * [resume] 이 기록의 운영자 보류를 이 인스턴스의 인시던트로 다시 열었다는 실행 자취의 종류. 그 인시던트의 근거 윈도우에
         * 실려 «재기동이 다시 연 인시던트» 를 가르는 표시다. 세부에 기록의 기체·시도·요청 시각·근거 시각·보류 사유가 든다.
         */
        const val RESUME_HOLD_REOPENED = "RESUME_HOLD_REOPENED"

        /** 발행 관문이 새 로봇 명령을 막았다는 실행 자취의 종류(ADR 52). 같은 막음은 한 번만 남는다. */
        const val ISSUE_WITHHELD = "ISSUE_WITHHELD"

        /**
         * 발행 관문이 더는 막지 않는다는 실행 자취의 종류. 무엇이 허가했는지(원천·관측 시각)를 싣는다. 결함 관문이 따로 막고 있으면
         * 명령은 아직 안 나간다 — 이 줄은 «허가가 돌아왔다» 이지 «나갔다» 가 아니다.
         */
        const val ISSUE_RESUMED = "ISSUE_RESUMED"

        /** 재작업 태스크 id 의 접미사. `startUnit` 이 재작업한 단위에 `@rN` 으로 붙인다. */
        private val RETRY_SUFFIX = Regex("""@r([1-9]\d*)""")
    }
}
