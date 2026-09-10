package dev.picasso.registry.web

import dev.picasso.registry.adapter.AdapterInstanceService
import dev.picasso.registry.adapter.InstanceOutcome
import dev.picasso.registry.binding.RecordOutcome
import dev.picasso.registry.binding.RetirementOutcome
import dev.picasso.registry.binding.RobotRegistration
import dev.picasso.registry.binding.RobotRegistrationOutcome
import dev.picasso.registry.binding.RobotStatus
import dev.picasso.registry.binding.SiteNameRegistration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §8.5의 조작 표면. **[DiagController]·[IngestController]와 다른 문이다.**
 *
 * 문이 셋인 것이 신뢰 경계가 셋이기 때문이다.
 *
 * | 문 | 누가 | 관문 |
 * |---|---|---|
 * | 진단(`diag` 이하) | 운영자가 본다 | 없다(read-only, §15.38) |
 * | 적재(`ingest` 이하, `requirements`) | 기체·소비자가 쓴다 | [IngestToken] |
 * | 조작(`operations` 이하) | **운영자가 판단을 기록한다** | [OperatorToken] |
 *
 * 경로 패턴을 와일드카드로 적지 않는 것은 **Kotlin 블록 주석이 중첩되기**
 * 때문이다 — 슬래시 뒤에 별 둘이 붙으면 그 자리에서 새 주석이 열리고 파일
 * 끝까지 주석이 된다. [IngestTokenInterceptor]가 같은 이유로 그렇게 적었고,
 * 이 파일이 그것을 잊어 한 번 물렸다.
 *
 * 뒤의 둘을 한 토큰으로 두면 **현장의 기체가 운영자 조작을 할 수 있다** —
 * 적재 토큰은 어댑터마다 배포되기 때문이다. 그 승격은 코드 어디에도 안
 * 적히므로 안 보인다.
 *
 * ## 행위자는 자기 신고다
 *
 * `X-Actor`가 위조 가능하다(§15.3). **그래도 요구한다** — 비워 두면 감사
 * 로그에 `unknown`이 쌓이고, 그러면 조사 단서로도 못 쓴다. 위조 가능한
 * 이름이 없는 것보다 낫다.
 */
@RestController
class OperationsController(
    private val siteNames: SiteNameRegistration,
    private val robots: RobotRegistration,
    private val instances: AdapterInstanceService,
) {

    /**
     * 배포된 어댑터를 기록한다(ADR 37 결정 2).
     *
     * **조작 문이다** — 사람이 *"이 빌드를 여기에 띄웠고 플릿은 저 주소다"* 라고 적는다. 적재 문이 아닌 이유는
     * 그것이 판단이기 때문이며, 적재 토큰으로 이것이 되면 현장의 기체가 자기 배포를 스스로 선언할 수 있다.
     */
    @PostMapping("/operations/adapter-instances")
    fun registerInstance(
        @RequestBody request: RegisterInstanceRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (
        val outcome = instances.register(
            instanceId = request.instance_id,
            adapterVersionId = request.adapter_version_id,
            siteId = request.site,
            fleetEndpoint = request.fleet_endpoint,
            actor = actor,
        )
    ) {
        InstanceOutcome.Registered -> ResponseEntity.status(HttpStatus.CREATED).body(mapOf("instance" to request.instance_id))
        InstanceOutcome.Updated -> ResponseEntity.ok(mapOf("instance" to request.instance_id))
        is InstanceOutcome.Rejected -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
    }

    /**
     * ADR 37 의 **선언** — 사람이 기체를 원장에 들인다.
     *
     * 설계 §8.5의 조작 목록에 *기체 등록* 이 처음부터 있었는데 엔드포인트가 없었다. 그래서 기체 행은 시험이 SQL 로
     * 직접 넣었고, 그 상태가 ADR 37 이 적어 둔 *"우리 하네스가 스스로 결정 5의 비싼 사분면에 앉아 있다"* 였다.
     *
     * **여기로 들어오면 `DECLARED` 다.** 본문이 출처를 정하지 않는다(결정 3) — `origin` 을 실어 보내도 아무 일도
     * 일어나지 않는다.
     *
     * 답이 넷으로 갈린다. 등록됨(201)·갱신됨(200)·다른 문으로 이미 들어옴(409)·본문이 틀림(400). **`WrongDoor` 를
     * 400 과 접으면 안 된다** — 앞은 운영자가 고칠 것이 없고(이미 발견된 기체다) 뒤는 고쳐서 다시 보내야 한다.
     */
    @PostMapping("/operations/robots")
    fun declareRobot(
        @RequestBody request: DeclareRobotRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (
        val outcome = robots.declare(
            robotId = request.robot_id,
            siteId = request.site,
            serialNumber = request.serial_number,
            displayName = request.display_name,
            endpoint = request.endpoint,
            actor = actor,
        )
    ) {
        // **등록한 뒤의 실제 상태를 낸다.** "REGISTERED" 를 박아 두면 기체가 아직 답한 적 없는 것(CLAIMED)이
        // 성공으로만 보이고, 오타 난 robot_id 로 선언한 화면이 초록이다(ADR 35와 같은 규율).
        is RobotRegistrationOutcome.Registered ->
            ResponseEntity.status(HttpStatus.CREATED).body(mapOf("robot" to request.robot_id, "status" to outcome.status.name))
        is RobotRegistrationOutcome.Updated ->
            ResponseEntity.ok(mapOf("robot" to request.robot_id, "status" to outcome.status.name))
        is RobotRegistrationOutcome.WrongDoor ->
            ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to outcome.detail, "origin" to outcome.origin.name))
        // **퇴역도 409 다.** 400 과 접으면 안 된다 — 본문은 멀쩡하고, 운영자가 할 일은 고쳐서 다시 보내는 것이
        // 아니라 **복귀를 누르는 것**이다.
        is RobotRegistrationOutcome.RetiredAlready ->
            ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to outcome.detail, "status" to RobotStatus.RETIRED.name))
        is RobotRegistrationOutcome.Rejected ->
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
    }

    /**
     * 기체를 **퇴역시킨다** — ADR 37 이 안 세운 나가는 문.
     *
     * 원장은 지금까지 *한 번 들어온 기체가 영원히 있는 곳* 이었다. 팔린 기체도, 폐기된 기체도, 다른 사이트로
     * 옮긴 기체도 목록에 남았다. **현장에 처음 적용할 때는 안 보이고 두 번째 해부터 보이는 구멍이다.**
     *
     * ★**조작 문에만 있다.** 어댑터가 *"플릿에서 안 보인다"* 고 해서 퇴역이 되면 **네트워크 단절이 퇴역이 된다.**
     * 안 보이는 것은 관측이고 떠난 것은 판단이다.
     *
     * 답이 넷 — 퇴역함(200)·이미 퇴역(200, 첫 사유를 안 덮었다)·그런 기체 없음(404)·본문 틀림(400).
     */
    @PostMapping("/operations/robots/{robotId}/retirement")
    fun retireRobot(
        @PathVariable robotId: String,
        @RequestBody request: RetireRobotRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = answer(robotId, robots.retire(robotId, request.reason, actor))

    /**
     * 퇴역을 **되돌린다.**
     *
     * 되돌아온 기체는 실제로 있다. 다만 그것을 **발견이 정하게 두지 않는다** — 자동 복귀면 운영자의 판단을
     * 현장 프로세스가 매번 덮는다.
     */
    @DeleteMapping("/operations/robots/{robotId}/retirement")
    fun reinstateRobot(
        @PathVariable robotId: String,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = answer(robotId, robots.reinstate(robotId, actor))

    private fun answer(robotId: String, outcome: RetirementOutcome): ResponseEntity<Map<String, Any>> = when (outcome) {
        is RetirementOutcome.Retired ->
            ResponseEntity.ok(mapOf("robot" to robotId, "status" to RobotStatus.RETIRED.name, "already" to outcome.alreadyWas))
        // **복귀 뒤의 상태를 박아 두지 않는다.** 복귀하면 CLAIMED 일 수도 CONFIRMED 일 수도 있고, 그것은
        // 이 기체가 답한 적이 있는가에 달렸다 — 원장에 물어서 낸다(`declareRobot` 과 같은 규율).
        is RetirementOutcome.Reinstated ->
            ResponseEntity.ok(
                mapOf("robot" to robotId, "status" to (robots.statusOf(robotId)?.name ?: "UNKNOWN"), "was_retired" to outcome.wasRetired),
            )
        is RetirementOutcome.Unknown ->
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "모르는 기체다: ${'$'}{outcome.robotId}"))
        is RetirementOutcome.Rejected ->
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
    }

    /**
     * 사이트 이름을 이 기체에 등록했다고 **기록한다**(ADR 35의 결정 3).
     *
     * **레지스트리가 등록하는 것이 아니다.** 등록은 계약 밖의 사이트
     * 작업이고(Spot은 지도 녹화 시의 웨이포인트 명명, Digit은 `add-object`)
     * 여기는 그 사실만 적는다. 레지스트리가 로봇에 무언가 밀면 §3.2가 깨진다.
     *
     * 답이 셋으로 갈린다 — 기록됨(200), 등록할 것이 없음(409), 활성 바인딩
     * 없음(404). **불리언 하나로 접으면 운영자가 왜 안 됐는지 모른 채
     * 재시도한다.**
     */
    @PostMapping("/operations/site-names")
    fun recordSiteNames(
        @RequestParam robot: String,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (val outcome = siteNames.record(robot, actor)) {
        // **기록한 뒤의 실제 상태를 낸다.** "REGISTERED" 를 박아 두면 기체가
        // 아직 답한 적 없는 것(CLAIMED)과 답이 어긋난 것(CONTRADICTED)이
        // 성공으로 보인다 — 그것이 이 확장이 막으려는 것이다(ADR 35).
        is RecordOutcome.Recorded -> ResponseEntity.ok(
            mapOf(
                "robot" to robot,
                "status" to siteNames.statusOf(robot).name,
                "keys" to outcome.keys,
            ),
        )

        // **409다.** 요청이 잘못된 것이 아니라 이 기체의 상태에서 뜻이 없는
        // 조작이다 — 400으로 내면 운영자가 요청을 고치려 든다.
        is RecordOutcome.NothingToRegister -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "robot" to robot,
                "status" to "NOT_REQUIRED",
                "error" to "이 기체가 드는 스킬 중 사이트 이름을 쓰는 것이 없다",
            ),
        )

        is RecordOutcome.NoActiveBinding -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf("robot" to robot, "error" to "활성 바인딩이 없다"),
        )
    }

    /**
     * 무엇을 등록해야 하는지 **미리 본다.**
     *
     * 조작 문 뒤에 있지만 읽기다. 진단(`/diag/bindings`)이 같은 것을 이미
     * 내는데도 여기 두는 것은, 등록하러 온 사람이 **조작 직전에** 대상을
     * 확인하는 흐름이기 때문이다 — 다른 문으로 갔다 오게 하면 그 사이에
     * 바인딩이 바뀐 것을 못 본다.
     */
    @GetMapping("/operations/site-names")
    fun siteNamesOf(@RequestParam robot: String): Map<String, Any> = mapOf(
        "robot" to robot,
        "status" to siteNames.statusOf(robot).name,
        "keys" to siteNames.required(robot).sorted(),
    )
}

/**
 * `POST /operations/robots/{robotId}/retirement` 의 본문.
 *
 * **사유를 요구한다** — 판단은 이유가 있어야 나중에 되짚을 수 있고, 감사 로그에만 남기면 아무도 안 뒤진다.
 */
data class RetireRobotRequest(val reason: String = "")

/**
 * `POST /operations/robots` 의 본문(ADR 37 의 **선언**).
 *
 * **`origin` 이 없다** — 출처는 문이 정한다(결정 3). [endpoint] 는 직결에서만 우리가 갖는 로봇의 주소이며
 * **자격증명이 아니다**(§6.3 — 비밀을 어디 둘지는 정한 적 없다).
 */
data class DeclareRobotRequest(
    val robot_id: String,
    val site: String,
    val serial_number: String,
    val display_name: String? = null,
    val endpoint: String? = null,
)

/**
 * `POST /operations/adapter-instances` 의 본문.
 *
 * [fleet_endpoint] 는 **플릿 경유일 때만** 채운다(ADR 37 결정 4). 직결이면 로봇의 주소가 `robot.endpoint` 에 있다.
 * **자격증명은 안 받는다**(§6.3).
 */
data class RegisterInstanceRequest(
    val instance_id: String,
    val adapter_version_id: Long,
    val site: String,
    val fleet_endpoint: String? = null,
)
