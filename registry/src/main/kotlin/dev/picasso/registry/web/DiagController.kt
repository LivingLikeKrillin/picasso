package dev.picasso.registry.web

import dev.picasso.registry.diag.BindingsAnswer
import dev.picasso.registry.diag.DiagAnswerLimits
import dev.picasso.registry.diag.DiagnosticsService
import dev.picasso.registry.diag.DiffAnswer
import dev.picasso.registry.diag.EpochRow
import dev.picasso.registry.ledger.DependentsAnswer
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.plan.ChangePlanService
import dev.picasso.registry.plan.PlanView
import dev.picasso.registry.diag.RejectionRow
import dev.picasso.registry.diag.SoftwareRow
import dev.picasso.registry.diag.StalledRow
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §8.5의 진단 여덟. **read-only이며 `GET`뿐이다.**
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
    /** ADR 37 의 등록 상태·배포 목록. 진단 서비스가 아니라 그 서비스들이 답한다 — 상태의 정의가 거기 있다. */
    private val robots: dev.picasso.registry.binding.RobotRegistration,
    private val instances: dev.picasso.registry.adapter.AdapterInstanceService,
    private val diagnostics: DiagnosticsService,
    private val ledger: LedgerService,
    private val changePlans: ChangePlanService,
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

    /**
     * 진단 7번 — 프로파일이 전제한 펌웨어와 기체가 보고한 것.
     *
     * 여기 나오는 불일치는 **프로파일이 더 이상 그 개체를 설명하지 않는다**는
     * 뜻이다. §15.1이 *"선언을 그대로 두고 거동만 바꾸면 게이트가 못 본다"*고
     * 인정한 자리의 런타임 쪽 대응이다.
     */
    @GetMapping("/diag/software")
    fun software(
        @RequestParam(name = "site", required = false) site: String?,
    ): List<SoftwareRow> = diagnostics.software(site)

    /** 진단 8번 — 오래 비종착으로 남아 축소를 막고 있는 태스크. */
    @GetMapping("/diag/stalled")
    fun stalled(): List<StalledRow> = diagnostics.stalled()

    /**
     * 진단 9번 — **이 기체가 왜 여기 있는가**(ADR 37).
     *
     * 사람이 선언했는데 기체가 한 번도 답하지 않은 줄(`CLAIMED`)이 여기 남는다. 오타 난 `robot_id` 로 선언한
     * 기체가 그 상태이며, **그것이 보이지 않으면 화면은 초록인데 기체가 안 붙는다**(ADR 37 의 대가 마지막 줄).
     */
    @GetMapping("/diag/robots")
    fun robots(
        @RequestParam(name = "site", required = false) site: String?,
    ): List<dev.picasso.registry.binding.RegisteredRobot> = robots.list(site)

    /**
     * 진단 10번 — **무엇이 어디에 떠 있는가**(ADR 37 결정 2).
     *
     * 적합성 상태를 함께 낸다. `UNTESTED` 인 빌드가 **실제로 배포됐다**는 것은 운영자가 알아야 할 사실이고,
     * 그것을 보여 주는 자리가 여기가 처음이다(§9.7 ④).
     */
    @GetMapping("/diag/adapter-instances")
    fun adapterInstances(
        @RequestParam(name = "site", required = false) site: String?,
    ): List<dev.picasso.registry.adapter.AdapterInstanceRow> = instances.list(site)

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

    /**
     * 진단 6번 — 진행 중 변경 계획과 각 단계의 충족 여부(§8.5).
     *
     * **`satisfied`는 DB의 캐시가 아니라 지금 재평가한 값이다**(§9.5).
     * 캐시를 내면 화면이 "열렸다"고 하는데 실행은 거부되는 상태가 생기고,
     * 그때 운영자는 화면을 믿는다.
     */
    @GetMapping("/diag/plans")
    fun plans(
        @RequestParam(name = "finished", required = false, defaultValue = "false")
        finished: Boolean,
    ): List<PlanView> = changePlans.plans(finished)

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
