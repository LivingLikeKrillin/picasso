package dev.picasso.adapter.orbit

import dev.picasso.adapter.core.VendorSurface

/**
 * 남쪽 경계 — Boston Dynamics **Orbit**(플릿 매니저)이 실제로 말하는 것.
 *
 * ## 이 포트가 다른 셋과 다른 점
 *
 * Spot·Digit·G1 의 남쪽은 **기체 하나**다. 여기는 **기체 여럿을 아는 서버**이고, 그래서 이 저장소에서 처음으로
 * *"어댑터가 로봇 목록을 발견한다"* 가 성립한다(ADR 37). 계약은 여전히 기체 단위이므로 어댑터 인스턴스는 기체마다
 * 하나이고, 이 링크만 여럿이 공유한다.
 *
 * ## 층이 셋이고 **셋 다 널일 수 있다**
 *
 * | 층 | 무엇 | 널이면 |
 * |---|---|---|
 * | [fleet] | 기체 목록 — `GET /robots` | 발견을 못 한다 → 선언으로 내려앉는다(ADR 37 결정 5) |
 * | [missions] | 저작된 미션의 **이름과 uuid** | 이름으로 일을 못 시킨다 |
 * | [dispatch] | 지금 미션을 시킨다 | **일을 못 시킨다.** 게시 스펙에 없는 경로다 — 아래를 볼 것 |
 * | [runs] | 무엇이 돌았고 어떻게 끝났는가 | 종착을 못 읽는다 |
 *
 * **널이 진짜 있을 수 있는 자리다.** Orbit 은 인스턴스마다 자기 `/api/v0` 를 서빙하므로 배포본이 게시본과 다를 수
 * 있고(§15.83 ③), 특히 [dispatch] 는 **게시 스펙 35 개 경로 어디에도 없다** — 벤더의 파이썬 클라이언트에만 있다.
 * 널로 두지 않고 있는 척하면, 못 붙는 배포본에서 어댑터가 아무것도 안 하면서 조용히 초록으로 보인다(G1 의 `sport`
 * 가 시뮬레이터에서 널인 것과 같은 자리).
 *
 * ## 근거 등급이 이름 공간으로 갈려 있다
 *
 * 매니페스트에서 `GET /robots` 처럼 생긴 것은 **게시 스펙**의 것이고, `bosdyn-orbit:` 이 붙은 것은 **클라이언트
 * 소스에만** 있는 것이다. 후자는 벤더가 공개 API 로 약속한 적이 없으므로 언제 사라져도 벤더 잘못이 아니다.
 * 그 차이를 이름에 박아 두지 않으면 다음 조사에서 둘을 구별할 수 없다.
 *
 * ## 측정하면서 드러난 것 셋
 *
 * 1. **`Robot` 에 일련번호가 없다.** 발견으로 얻는 것은 `hostname`·`nickname`·`robotIndex`·`username` 뿐이다.
 *    Orbit 이 일련번호를 모르는 것은 아니다 — `Run.robotSerial` 에 있다. 다만 **기체 자원에는 안 실린다.**
 *    우리 원장의 `robot.serial_number` 가 필수라 이 경로와 안 맞고, 그 충돌은 등록 쪽에서 다룬다.
 * 2. **`Run.missionStatus` 가 값 집합 없는 문자열이다.** 게시 스펙에 `enum` 이 없다. 결과 어휘의 거리가 여기서
 *    가장 멀다 — Spot 직결에서는 `MissionStatus` 열거를 받는데, 같은 로봇을 Orbit 뒤에서 보면 자유 문자열이다.
 * 3. **`RunEvent.error` 가 정수 하나다.** 실패의 이유가 코드 하나로 접힌다(§15.83).
 */
interface OrbitLink {

    /** 기체 목록. **널이면 이 배포본이 목록을 안 준다** — 그때 등록은 사람이 적는다(ADR 37 결정 5의 비싼 사분면). */
    @get:VendorSurface("GET /robots", "bosdyn-orbit:robots")
    val fleet: FleetLayer?

    /**
     * 저작된 미션 목록.
     *
     * **ADR 35 가 플릿에서 한 겹 옮겨진 자리다.** 그 ADR 은 *"사이트 이름은 로봇 안에 산다"* 였고, 여기서는
     * **플릿 안에 산다** — 사람이 저작해 Orbit 에 저장한 미션의 `name` 이 곧 계약이 나르는 사이트 이름이다.
     * 주인이 사이트라는 것은 그대로이고 사는 곳만 바뀐다.
     */
    @get:VendorSurface("GET /missions", "Mission.name")
    val missions: MissionLayer?

    /** 지금 시킨다. **널이 기본값에 가깝다** — 게시 스펙에 없는 경로라 배포본에 있으리라는 보장이 없다. */
    @get:VendorSurface("bosdyn-orbit:calendar/mission/dispatch/{robot_nickname}?currentDriverId={driver_id}")
    val dispatch: DispatchLayer?

    /** 실행 관측. 널이면 종착을 못 읽는다. */
    @get:VendorSurface("GET /runs/", "GET /run_events/")
    val runs: RunLayer?
}

/**
 * 기체 목록(`GET /robots`).
 *
 * **이것이 ADR 37 의 *발견* 이다.** 어댑터가 여기서 읽은 것을 적재 문으로 올리면 그 기체는 사람의 말이 아니라
 * 관측으로 원장에 앉는다.
 */
interface FleetLayer {

    @VendorSurface("GET /robots")
    fun robots(): Result<List<OrbitRobot>>
}

/**
 * Orbit 이 아는 기체 하나.
 *
 * **일련번호가 없다.** 벤더가 이 자원에 안 싣는다 — [OrbitLink] 의 측정 노트 1번을 볼 것. 그래서 이 기체를 원장에
 * 들일 때 무엇을 일련번호 자리에 놓을지는 **어댑터가 정할 일이 아니라** 등록 절차가 정할 일이다.
 */
data class OrbitRobot(
    /** 로봇에 닿는 주소. 계약의 `robot_id` 와 다른 것이며, 무엇을 `robot_id` 로 삼을지는 등록이 정한다. */
    @field:VendorSurface("Robot.hostname") val hostname: String,
    /** 사람이 붙인 이름. **파견이 이것으로 기체를 지목한다** — 주소가 아니라 별명이다. */
    @field:VendorSurface("Robot.nickname") val nickname: String,
    /** Orbit 서버의 슬롯 번호(0..최대, 보통 32). 기체의 신원이 아니라 **이 서버에서의 자리**다. */
    @field:VendorSurface("Robot.robotIndex") val robotIndex: Int,
    @field:VendorSurface("Robot.username") val username: String,
)

/**
 * 저작된 미션의 이름과 uuid(`GET /missions`).
 *
 * **`Mission` 은 스펙에서 `deprecated: true` 다.** 그런데 `Schedule.task.missionId` 는 여전히 그것을 참조하고,
 * 파견도 그 id 를 받는다 — 벤더 안에서 어긋나 있다. 대체제로 보이는 `SiteWalk` 는 파견 경로가 받는 자리가 없다
 * (클라이언트가 `dispatchTarget.walk` 로 인라인하는 길이 있으나 그것은 스펙 밖의 스펙 밖이다).
 */
interface MissionLayer {

    @VendorSurface("GET /missions")
    fun missions(): Result<List<OrbitMission>>
}

/** 저작된 미션 하나. **이름이 사람의 것이고 uuid 가 기계의 것이다** — 계약은 앞의 것을 나른다(ADR 35). */
data class OrbitMission(
    @field:VendorSurface("Mission.uuid") val uuid: String,
    @field:VendorSurface("Mission.name") val name: String,
)

/**
 * 지금 미션을 시킨다 — **게시 스펙 밖의 경로**다.
 *
 * 스펙 안에서 일을 넣는 문은 캘린더 항목(`POST /calendar/schedule`)뿐이고, 그것은 *언제* 를 정하는 것이지 *지금* 이
 * 아니다. 벤더의 클라이언트는 `schedule.timeMs` 를 **1** 로 두어 지금이 되게 하는 별도 경로를 친다.
 */
interface DispatchLayer {

    /**
     * @param nickname [OrbitRobot.nickname] — **주소가 아니라 별명으로 지목한다.**
     * @param missionUuid 시킬 미션. `Mission` 은 스펙에서 `deprecated` 인데 `Schedule.task.missionId` 는 여전히
     *   그것을 참조한다 — 벤더 안에서 어긋나 있고, 우리가 고를 수 있는 것이 아니다.
     */
    @VendorSurface(
        "bosdyn-orbit:calendar/mission/dispatch/{robot_nickname}?currentDriverId={driver_id}",
        "Schedule.task.missionId",
        "Schedule.schedule.timeMs",
        "Schedule.agent.nickname",
    )
    fun dispatch(nickname: String, missionUuid: String, driverId: String): DispatchResult
}

/** 파견의 답. **우리 어휘다** — 벤더는 HTTP 상태와 본문으로 말한다. */
sealed interface DispatchResult {
    data class Accepted(val runUuid: String?) : DispatchResult

    /** 플릿이 거절했다. [detail] 은 벤더의 말 그대로다. */
    data class Refused(val detail: String) : DispatchResult

    /** 플릿에 못 닿았다. **거절과 다르다** — 앞은 답이고 뒤는 답이 없는 것이다. */
    data class Unreachable(val detail: String) : DispatchResult
}

/** 실행 관측(`GET /runs/`·`GET /run_events/`). */
interface RunLayer {

    /** 이 별명의 가장 최근 실행. 없으면 널 — **아직 안 시작한 것과 실패한 것은 다르다.** */
    @VendorSurface("GET /runs/", "Run.robotNickname")
    fun latestRun(nickname: String): Result<OrbitRun?>

    /** 그 실행에서 난 사건들. 실패의 이유가 여기 정수 하나로 온다. */
    @VendorSurface("GET /run_events/", "RunEvent.runUuid")
    fun events(runUuid: String): Result<List<OrbitRunEvent>>
}

/**
 * 실행 하나.
 *
 * **`missionStatus` 가 값 집합 없는 문자열이다** — 게시 스펙에 `enum` 이 없다. 그래서 어댑터가 이것을 계약의
 * `TaskState` 로 옮길 때 **문자열을 짐작해야 하고**, 그 짐작은 근거 등급이 낮다. 같은 로봇을 Spot 에 직결하면
 * `MissionStatus` 열거를 받는다는 것이 이 거리의 크기다.
 */
data class OrbitRun(
    @field:VendorSurface("Run.uuid") val uuid: String,
    @field:VendorSurface("Run.robotNickname") val robotNickname: String,
    /** **여기에는 일련번호가 있다.** 기체 자원(`Robot`)에는 없다 — 벤더 안의 비대칭이다. */
    @field:VendorSurface("Run.robotSerial") val robotSerial: String?,
    @field:VendorSurface("Run.missionName") val missionName: String?,
    /** 자유 문자열. 값 집합이 스펙에 없다. */
    @field:VendorSurface("Run.missionStatus") val missionStatus: String?,
    /** 없으면 아직 도는 중이다. */
    @field:VendorSurface("Run.endTime") val endTime: String?,
    @field:VendorSurface("Run.actionCount") val actionCount: Int,
    @field:VendorSurface("Run.pendingActionCount") val pendingActionCount: Int,
)

/** 실행 중에 난 사건 하나. */
data class OrbitRunEvent(
    @field:VendorSurface("RunEvent.uuid") val uuid: String,
    @field:VendorSurface("RunEvent.actionName") val actionName: String?,
    /**
     * **정수 하나다.** 벤더가 값 집합도, 뜻도 스펙에 안 적었다. 0 이나 널이 아니면 그 사건에서 오류가 났다는 것까지가
     * 우리가 아는 전부이고, 그래서 정준 분류는 `UNCLASSIFIED` 를 벗어날 수 없다.
     */
    @field:VendorSurface("RunEvent.error") val error: Int?,
    @field:VendorSurface("RunEvent.time") val time: String?,
)
