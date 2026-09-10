package dev.picasso.adapter.orbit

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.AdapterIdentity
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.adapter.core.classifiedFault
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.isTerminal
import java.time.Instant

/**
 * 계약의 태스크 하나를 **플릿에** 시킨다 — 어댑터 넷째이고, 기체에 직결하지 않는 첫 어댑터다.
 *
 * ## 기체마다 인스턴스 하나다
 *
 * 링크([OrbitLink])는 기체 여럿을 아는 서버 하나이고, 이 어댑터는 그중 **한 기체**를 맡는다([nickname]). 계약이
 * 기체 단위이기 때문이며(ADR 37 결정 6), 그래서 한 프로세스가 링크 하나를 공유하는 어댑터를 여럿 들 수 있다.
 *
 * ## 계약의 스킬 넷 중 **하나만 든다**
 *
 * | 스킬 | 되는가 | 왜 |
 * |---|---|---|
 * | `navigate_to` | **YES** | 계약의 `location` 을 **저작된 미션의 이름**으로 읽어 그 미션을 파견한다 |
 * | `inspect` | NO | 점검은 미션 **안의** 액션이고, 플릿에는 그것을 따로 지목하는 문이 없다 |
 * | `pick_place` | NO | 조작 프리미티브가 없다 |
 * | `move_relative` | NO | 속도 명령이 없다 — 플릿은 저작물을 돌리지 관절을 돌리지 않는다 |
 *
 * **이것이 이 어댑터의 측정 결과다.** ADR 36 이 층 넷을 가르고 §15.77 이 *"못 옮기면 그 층으로는 계약을 못
 * 만족한다고 적는다"* 고 정한 그 자리이며, 여기서 플릿 층은 계약의 넷 중 하나만 만족한다. 억지로 옮기지 않는다 —
 * `pick_place` 를 미션 이름으로 흉내내면 상류가 조작을 시켰다고 믿는데 실제로는 누가 저작해 둔 무엇이 돈다.
 *
 * ## 종착은 `endTime` 이 정하고 성패는 `error` 가 정한다
 *
 * **`Run.missionStatus` 로 판정하지 않는다.** 그것은 값 집합 없는 자유 문자열이라(게시 스펙에 `enum` 이 없다)
 * 어떤 문자열이 성공인지 우리가 **짐작해야** 하고, 짐작한 매핑은 벤더가 문구를 바꾸는 날 조용히 틀린다. 대신
 * 스펙이 뜻을 적어 둔 둘만 쓴다 — `Run.endTime`(*"When this run completed"*)이 종착을, `RunEvent.error`
 * (*"The error code for an error which occured"*)가 성패를 정한다. `missionStatus` 는 진단에 원문으로 실린다.
 *
 * ## 못 하는 것을 못 한다고 한다
 *
 * 취소·일시정지·결함 관측·잔여 파지가 전부 없다. 게시 스펙 35 경로와 클라이언트 경로 40 개를 다 세어도 도는
 * 미션을 멈추는 문이 없고(그래서 조사의 3값은 `UNKNOWN` 이다 — 게시본이 불완전함이 증명돼 있으므로 부재의
 * 단정까지는 못 간다), 기체의 결함은 플릿이 아예 안 나른다.
 */
class OrbitAdapter(
    private val link: OrbitLink,
    private val identity: AdapterIdentity,
    /** 이 어댑터가 맡은 기체의 Orbit 별명. **주소가 아니라 별명이다** — 파견이 그것으로 지목한다. */
    private val nickname: String,
    /** 파견이 요구하는 운전자 식별자. 배타 제어의 주체이며 배포자가 정한다. */
    private val driverId: String,
) : RobotAdapter {

    private class RunningTask(
        val taskId: String,
        val missionUuid: String,
        var state: TaskState,
        var runUuid: String? = null,
        var failure: Fault? = null,
        /** 벤더의 자유 문자열. 판정에 안 쓰고 진단에만 싣는다. */
        var vendorStatus: String? = null,
        /** 마지막으로 본 실행의 액션 개수 둘. 실행을 아직 못 봤으면 널 — **0 이 아니다.** */
        var actionCount: Int? = null,
        var pendingActionCount: Int? = null,
    )

    private var task: RunningTask? = null

    override val state: TaskState
        get() = task?.state ?: TaskState.TASK_STATE_UNSPECIFIED

    // ── 접수

    override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
        task?.takeIf { !it.state.isTerminal }?.let {
            return Acceptance.Refused(Refusal.ALREADY_RUNNING, "이미 도는 태스크가 있다: ${it.taskId}")
        }
        if (skillType != NAVIGATE) {
            return Acceptance.Refused(
                Refusal.UNSUPPORTED_SKILL,
                "플릿 층에는 이 스킬의 프리미티브가 없다: $skillType — 파견할 수 있는 것은 저작된 미션뿐이다",
            )
        }

        val location = parameters[P_LOCATION] as? String
        if (location.isNullOrBlank()) {
            return Acceptance.Refused(Refusal.PARAMETER_MISSING, "$P_LOCATION 이 없다")
        }
        val dispatch = link.dispatch
            ?: return Acceptance.Refused(
                Refusal.VENDOR_SURFACE_ABSENT,
                "이 Orbit 에 파견 경로가 없다 — 게시 스펙에 없는 경로라 배포본마다 다르다",
            )
        val missions = link.missions
            ?: return Acceptance.Refused(Refusal.VENDOR_SURFACE_ABSENT, "이 Orbit 이 미션 목록을 안 준다")

        val known = missions.missions().getOrElse {
            return Acceptance.Refused(Refusal.LINK_ERROR, "미션 목록을 못 읽었다: ${it.message}")
        }
        // **이름이 여럿이면 고르지 않는다.** 하나를 고르면 상류가 지목한 것과 다른 것이 돌 수 있고,
        // 그것은 이름의 주인이 사이트라는 ADR 35 를 어댑터가 대신 판단하는 것이다.
        val matched = known.filter { it.name == location }
        when (matched.size) {
            0 -> return Acceptance.Refused(
                Refusal.SITE_NAME_UNKNOWN,
                "이 플릿에 그 이름의 미션이 없다: '$location' (아는 것 ${known.size} 개)",
            )
            1 -> Unit
            else -> return Acceptance.Refused(
                Refusal.SITE_NAME_AMBIGUOUS,
                "같은 이름의 미션이 ${matched.size} 개다: '$location'",
            )
        }

        return when (val outcome = dispatch.dispatch(nickname, matched.single().uuid, driverId)) {
            is DispatchResult.Accepted -> {
                task = RunningTask(taskId, matched.single().uuid, TaskState.TASK_STATE_RUNNING, outcome.runUuid)
                Acceptance.Accepted(taskId)
            }
            // **플릿의 거절은 정준 분류로 못 옮긴다** — 이유가 문자열 하나로 오고 값 집합이 없다.
            is DispatchResult.Refused -> Acceptance.Refused(
                Refusal.VENDOR_REJECTED,
                "플릿이 파견을 거절했다: ${outcome.detail}",
                FailureClass.FAILURE_CLASS_UNCLASSIFIED,
                outcome.detail,
            )
            is DispatchResult.Unreachable -> Acceptance.Refused(Refusal.LINK_ERROR, "플릿에 못 닿았다: ${outcome.detail}")
        }
    }

    // ── 관측

    override fun poll(now: Instant): TaskState {
        val current = task ?: return TaskState.TASK_STATE_UNSPECIFIED
        if (current.state.isTerminal) return current.state

        val runs = link.runs ?: return current.state
        val run = runs.latestRun(nickname).getOrNull() ?: return current.state

        // 우리가 시킨 것이 맞는지 — 파견이 uuid 를 안 돌려줬으면 별명의 최근 실행을 그것으로 삼는다.
        // **그 가정을 여기 적어 둔다**: 다른 사람이 같은 기체에 일을 시키면 그 실행을 우리 것으로 읽는다.
        current.runUuid = current.runUuid ?: run.uuid
        current.vendorStatus = run.missionStatus
        current.actionCount = run.actionCount
        current.pendingActionCount = run.pendingActionCount

        // **종착의 판정은 endTime 하나다.** 자유 문자열을 안 믿는다.
        if (run.endTime.isNullOrBlank()) return current.state

        val errors = runs.events(current.runUuid ?: run.uuid).getOrElse {
            // 사건을 못 읽으면 성패를 모른다. **성공으로 접지 않는다** — 틀리는 방향을 막는 쪽으로 둔다.
            current.state = TaskState.TASK_STATE_NEEDS_INTERVENTION
            current.failure = classifiedFault(
                "ORBIT_RUN_EVENTS_UNREADABLE",
                FailureClass.FAILURE_CLASS_UNCLASSIFIED,
                it.message ?: "",
                "실행은 끝났는데 사건을 못 읽어 성패를 모른다",
                canAcceptNewTask = true,
            )
            return current.state
        }

        val failed = errors.filter { (it.error ?: 0) != 0 }
        current.state = if (failed.isEmpty()) TaskState.TASK_STATE_SUCCEEDED else TaskState.TASK_STATE_FAILED
        if (failed.isNotEmpty()) {
            current.failure = classifiedFault(
                ORBIT_ACTION_ERROR,
                // **정수 하나로는 분류가 안 선다.** 값 집합도 뜻도 벤더가 안 적었고, 지어내면 상류가 그 분류로
                // 분기한다(§15.91 이 정준 분류를 만든 이유가 그것이다).
                FailureClass.FAILURE_CLASS_UNCLASSIFIED,
                failed.joinToString(", ") { "${it.actionName ?: "?"}#${it.error}" },
                "플릿이 오류 코드만 준다 — 뜻은 벤더 문서에 없다",
                canAcceptNewTask = true,
            )
        }
        return current.state
    }

    /**
     * 진행률 — **플릿이 개수를 세어 준다.**
     *
     * `Run.actionCount` 와 `Run.pendingActionCount` 가 벤더의 자원에 있는 값이므로, 끝난 액션 수를 세는 것은
     * 유도이지 발명이 아니다. 같은 층에서 국면(예: Spot 취득의 열한 상태)을 분수로 바꾸는 것은 다른 종류이고
     * 그것은 안 한다 — 국면 사이의 거리를 우리가 정하는 순간 숫자에 근거가 없어진다.
     *
     * 셋을 못 잰다로 둔다: 실행을 아직 못 봤을 때, 개수가 0 일 때(나눌 수 없다), 그리고 남은 것이 전체보다 많을 때
     * (벤더의 두 값이 어긋난 것이므로 짐작하지 않는다).
     */
    override fun progress(): ProgressObservation {
        val current = task ?: return ProgressObservation.NotObservable("도는 태스크가 없다")
        val total = current.actionCount ?: return ProgressObservation.NotObservable("아직 이 태스크의 실행을 못 봤다")
        val pending = current.pendingActionCount ?: return ProgressObservation.NotObservable("플릿이 남은 액션 수를 안 줬다")
        if (total <= 0) return ProgressObservation.NotObservable("플릿이 액션 개수를 $total 로 준다 — 나눌 수 없다")
        if (pending > total) {
            return ProgressObservation.NotObservable("플릿의 개수가 어긋난다: 남은 $pending / 전체 $total")
        }
        val done = total - pending
        return ProgressObservation.Fraction(done.toDouble() / total, "행동 $done/$total")
    }

    override fun failure(): Fault? = task?.failure

    /**
     * **결과 참조를 안 낸다.** `RunEvent.actionRunArchiveFileUrl` 이 있지만 그것은 점검 결과의 자리이고, 이
     * 어댑터는 점검을 안 든다 — 낼 수 있는 스킬이 없으므로 낼 것도 없다.
     */
    override fun result(): String? = null

    /** 플릿은 기체의 결함을 안 나른다. **없다가 아니라 못 본다** — 접으면 결함 없는 로봇으로 보인다. */
    override fun faults(): FaultObservation =
        FaultObservation.NotObservable("플릿 API 에 기체 결함 표면이 없다(`Anomaly` 는 점검 이상이지 기체 결함이 아니다)")

    /** 그리퍼를 볼 문이 없다. */
    override fun hold(): HoldObservation = HoldObservation.NotObservable("플릿 API 가 잔여 파지를 안 나른다")

    /**
     * ADR 35 의 확인 질의 — 여기서는 **플릿이 아는 이름**이다.
     *
     * 다른 셋은 로봇에 물었다(Spot 은 그래프 주석, Digit 은 물체 목록, G1 은 자리가 없다). 플릿에서는 저작된
     * 미션의 이름이 그것이고, 계약의 `location` 이 가리키는 것도 그것이다.
     */
    override fun knownSiteNames(): SiteNames {
        val missions = link.missions ?: return SiteNames.Unsupported
        return missions.missions().fold(
            onSuccess = { SiteNames.Known(it.map { mission -> mission.name }.sorted()) },
            onFailure = { SiteNames.Unavailable("미션 목록을 못 읽었다: ${it.message}") },
        )
    }

    /**
     * **못 읽는다.** 플릿이 기체의 소프트웨어를 아예 모르는 것은 아니다 — `Run.robotSoftwareMajorVersion` 등이
     * 있다. 다만 그것은 **실행이 하나라도 있어야** 나오고, 기체 자원(`Robot`)에는 없다. 실행에서 긁어 오면
     * *"지금 이 기체의 펌웨어"* 가 아니라 *"마지막 실행 때의 펌웨어"* 가 되므로 안 한다.
     */
    override fun robotSoftware(): String? = null

    // ── 조작 — 셋 다 없다

    override fun pause(): Applied =
        Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "플릿에 도는 미션을 멈추는 문이 없다(`calendar/disable-enable` 은 일정이다)")

    override fun cancel(): Applied =
        Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "플릿에 도는 미션을 취소하는 문이 없다 — 게시 스펙과 클라이언트 경로를 다 세어도 없다")

    /**
     * 갱신 — **없다. 멈출 수 없으니 다시 시킬 수도 없다.**
     *
     * §4.4 의 갱신은 `Halt` → `Reset` → `Start` 의 합성이고, 그 첫 칸이 여기 없다([cancel] 과 같은 이유다).
     * 파견만 다시 하면 앞 미션이 계속 도는 채로 새 미션이 붙거나 플릿이 거절하는데, **어느 쪽인지 알 방법이
     * 없다.** 그래서 시도하지 않는다 — 호스트가 이것을 계약의 `UPDATE_UNSUPPORTED` 로 옮긴다(§15.109).
     */
    override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied =
        Applied.Refused(
            Refusal.NO_VENDOR_PRIMITIVE,
            "플릿에 도는 미션을 멈추는 문이 없어 갱신의 첫 칸이 없다 — 취소한 뒤(그것도 없다) 새 태스크로 가야 한다",
        )

    /** 진단이 읽는 벤더 원문. 판정에 안 쓴다. */
    fun vendorStatus(): String? = task?.vendorStatus

    private companion object {
        const val NAVIGATE = "navigate_to"
        const val P_LOCATION = "location"
        const val ORBIT_ACTION_ERROR = "ORBIT_ACTION_ERROR"
    }
}
