package dev.picasso.gate

import dev.picasso.gate.input.GateInput

/**
 * 검사 결과 모음.
 *
 * allClean과 exitCode가 건너뜀에 대해 다르게 답하는 것이 의도다.
 *  - allClean : 실패도 건너뜀도 없을 때만 참. 사람이 "게이트가 다 돌았나"를
 *               판단할 때 쓴다.
 *  - exitCode : 실패가 있거나 **있어야 할 자원이 없었을 때** 1. CI가 쓴다.
 *
 * 설계 §11.1대로 CI에는 registry가 없어 검사 6번의 축소 조회가 상시
 * 건너뜀이다. 그것으로 CI를 빨갛게 만들면 사람이 게이트를 꺼버린다.
 * 대신 **건너뜀은 언제나 출력에 남는다** — 조용히 통과시키지 않는다.
 *
 * 다만 **모든 건너뜀이 정당한 것은 아니다.** §11.1이 상시 건너뜀으로
 * 인정하는 것은 검사 6번의 `REGISTRY` 하나뿐이다. 문서나 디스크립터가
 * 없어서 건너뛰는 것은 배선이 깨진 것이므로 [absentRequired]로 들어와
 * 빨간불이 된다.
 */
data class GateReport(
    val results: List<CheckResult>,
    /**
     * 이 호출 지점에서 반드시 있어야 했는데 없던 자원.
     * 건너뜀이 아니라 실패다 — 글롭 오타나 빈 디렉터리로 검사가 전부
     * 건너뛰고 종료코드 0이 나던 구멍을 이것이 막는다.
     */
    val absentRequired: Set<Resource> = emptySet(),
) {
    val passed: List<CheckResult.Passed> = results.filterIsInstance<CheckResult.Passed>()
    val failed: List<CheckResult.Failed> = results.filterIsInstance<CheckResult.Failed>()
    val skipped: List<CheckResult.Skipped> = results.filterIsInstance<CheckResult.Skipped>()

    /**
     * 하나라도 못 돈 것이 있는가.
     *
     * `skipped`를 따로 세는 이유: `Skipped.missing`이 비어 있으면
     * `skippedParts`도 비어 건너뜀이 집계에서 통째로 사라진다.
     */
    val anySkipped: Boolean =
        skipped.isNotEmpty() || results.any { it.skippedParts.isNotEmpty() }

    val allClean: Boolean = failed.isEmpty() && absentRequired.isEmpty() && !anySkipped

    val exitCode: Int = if (failed.isEmpty() && absentRequired.isEmpty()) 0 else 1

    fun render(): String = buildString {
        if (absentRequired.isNotEmpty()) {
            appendLine("FAIL  게이트 입력이 갖춰지지 않았다")
            appendLine("      반드시 있어야 하는 자원: ${absentRequired.joinToString(", ")}")
            appendLine("      배선이 깨졌거나 대상이 비었다. 건너뜀으로 넘기지 않는다.")
        }

        results.forEach { r ->
            when (r) {
                is CheckResult.Passed -> {
                    append("PASS  ${r.checkId}")
                    if (r.skippedParts.isNotEmpty()) {
                        append("  (부분 건너뜀: ${r.skippedParts.joinToString(", ")})")
                    }
                    appendLine()
                    appendAll(r.findings)
                }

                is CheckResult.Skipped -> {
                    appendLine("SKIP  ${r.checkId}  ${r.reason}")
                    appendLine("      없는 자원: ${r.missing.joinToString(", ").ifEmpty { "(없음)" }}")
                }

                is CheckResult.Failed -> {
                    appendLine("FAIL  ${r.checkId}")
                    appendAll(r.findings)
                }
            }
        }

        if (anySkipped) {
            appendLine()
            appendLine("건너뛴 검사가 있다 — 통과가 아니다.")
        }
    }

    /** 통과의 경고도 위치를 찍는다. 어느 스킬인지 없으면 사람이 못 고친다. */
    private fun StringBuilder.appendAll(findings: List<Finding>) {
        findings.forEach { f ->
            appendLine("      ${f.severity} ${f.message}")
            f.location?.let { appendLine("        at $it") }
        }
    }
}

/**
 * @param required 이 호출 지점에서 반드시 있어야 하는 자원(설계 §11.1).
 *   CI는 `REGISTRY`를 뺀 나머지를, `registry`는 문서·스키마·디스크립터를 준다.
 *   비워 두면 자원 결손이 전부 건너뜀이 되어 **검사가 하나도 안 돌아도
 *   종료코드 0**이 나므로, 진짜 호출 지점은 반드시 채운다.
 */
class GateRunner(
    private val checks: List<GateCheck>,
    private val required: Set<Resource> = emptySet(),
) {
    init {
        require(checks.isNotEmpty()) { "검사가 하나도 없는 게이트는 게이트가 아니다" }
        require(checks.map { it.id }.toSet().size == checks.size) {
            "검사 id가 중복이다: ${checks.map { it.id }}"
        }
    }

    fun run(input: GateInput): GateReport {
        val available = input.available()

        val results = checks.map { check ->
            val missing = check.requires - available
            if (missing.isNotEmpty()) {
                CheckResult.Skipped(
                    checkId = check.id,
                    missing = missing,
                    reason = "요구 자원이 없다",
                )
            } else {
                runCatching { verify(check, check.run(input)) }.getOrElse { t ->
                    // 검사가 던지면 통과로 새지 않게 실패로 접는다.
                    failure(check.id, "검사가 예외로 중단됐다: ${t.message ?: t::class.simpleName}")
                }
            }
        }

        return GateReport(results, absentRequired = required - available)
    }

    /** 검사가 딴 id의 결과를 내면 출력이 거짓말을 한다. */
    private fun verify(check: GateCheck, result: CheckResult): CheckResult =
        if (result.checkId == check.id) {
            result
        } else {
            failure(
                check.id,
                "검사 ${check.id}(${check.name})이 checkId='${result.checkId}'인 결과를 냈다",
            )
        }

    private fun failure(checkId: String, message: String) =
        CheckResult.Failed(checkId, listOf(Finding(checkId, Severity.ERROR, message)))
}
