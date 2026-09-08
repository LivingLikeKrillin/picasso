package dev.picasso.registry.web

import dev.picasso.registry.binding.RecordOutcome
import dev.picasso.registry.binding.SiteNameRegistration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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
class OperationsController(private val siteNames: SiteNameRegistration) {

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
        is RecordOutcome.Recorded -> ResponseEntity.ok(
            mapOf("robot" to robot, "status" to "REGISTERED", "keys" to outcome.keys),
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
