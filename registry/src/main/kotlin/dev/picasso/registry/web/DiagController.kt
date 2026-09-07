package dev.picasso.registry.web

import dev.picasso.registry.diag.BindingsAnswer
import dev.picasso.registry.diag.DiagAnswerLimits
import dev.picasso.registry.diag.DiagnosticsService
import dev.picasso.registry.diag.DiffAnswer
import dev.picasso.registry.diag.EpochRow
import dev.picasso.registry.ledger.DependentsAnswer
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.diag.RejectionRow
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §8.5의 진단 1~5. **read-only이며 `GET`뿐이다.** 6번은 변경 계획이
 * 서는 3b-2다.
 *
 * 진단이 무언가를 바꿀 수 있으면 그것은 진단이 아니라 조작이고, 조작은
 * 감사 로그와 승인 경계를 지나야 한다(§8.5). 여기 `POST`를 하나 더하는
 * 순간 그 경계가 조용히 새는 문이 된다.
 *
 * **모든 질의 파라미터에 이름을 적는다.** 안 적으면 스프링이 리플렉션으로
 * 이름을 읽으려 하고, Kotlin은 `-java-parameters` 없이 그 정보를 안 넣는다 —
 * 컴파일러 플래그 하나에 네 엔드포인트가 전부 500이 되는 배선이 된다.
 * 이름을 적으면 **와이어 이름이 읽는 자리에 그대로 보인다**는 이득이 덤이다.
 *
 * **답은 여기서 만들지 않는다.** [DiagnosticsService]가 정하고 이 클래스는
 * 경로와 질의 파라미터만 안다 — 그래야 진단의 거동 시험이 서버 없이 돈다.
 */
@RestController
class DiagController(
    private val diagnostics: DiagnosticsService,
    private val ledger: LedgerService,
) {

    @GetMapping("/diag/bindings")
    fun bindings(
        @RequestParam(name = "site", required = false) site: String?,
        @RequestParam(name = "history", required = false, defaultValue = "false") history: Boolean,
    ): BindingsAnswer = diagnostics.bindings(site, history)

    @GetMapping("/diag/diff")
    fun diff(
        @RequestParam(name = "from") from: Long,
        @RequestParam(name = "to") to: Long,
    ): DiffAnswer = diagnostics.diff(from, to)

    @GetMapping("/diag/epochs")
    fun epochs(
        @RequestParam(name = "robot_id") robotId: String,
        @RequestParam(
            name = "limit",
            required = false,
            defaultValue = DiagAnswerLimits.DEFAULT_TEXT,
        ) limit: Int,
    ): List<EpochRow> = diagnostics.epochs(robotId, limit)

    /**
     * 진단 5번 — **이 능력을 지금 누가 쓰는가**(§9.2).
     *
     * 답이 `active`와 `dormant`로 갈려 나오는 것이 요점이다. 0이 된 이유가
     * "아무도 안 쓴다"인지 "다들 조용하다"인지 운영자가 갈라 봐야 하고,
     * 후자는 계절성 소비자가 돌아올 수 있다는 뜻이다.
     */
    @GetMapping("/diag/dependents")
    fun dependents(@RequestParam(name = "skill") skill: String): DependentsAnswer =
        ledger.dependents(skill)

    @GetMapping("/diag/rejections")
    fun rejections(
        @RequestParam(name = "reason_code", required = false) reasonCode: String?,
        @RequestParam(
            name = "limit",
            required = false,
            defaultValue = DiagAnswerLimits.DEFAULT_TEXT,
        ) limit: Int,
    ): List<RejectionRow> = diagnostics.rejections(reasonCode, limit)
}
