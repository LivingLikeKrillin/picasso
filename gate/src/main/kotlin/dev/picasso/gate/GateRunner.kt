package dev.picasso.gate

import dev.picasso.gate.input.GateInput

/**
 * 검사 결과 모음.
 *
 * allClean과 exitCode가 건너뜀에 대해 다르게 답하는 것이 의도다.
 *  - allClean : 실패도 건너뜀도 없을 때만 참. 사람이 "게이트가 다 돌았나"를
 *               판단할 때 쓴다.
 *  - exitCode : 실패가 있을 때만 1. CI가 쓴다.
 *
 * 설계 §11.1대로 CI에는 registry가 없어 검사 6번의 축소 조회가 상시
 * 건너뜀이다. 그것으로 CI를 빨갛게 만들면 사람이 게이트를 꺼버린다.
 * 대신 **건너뜀은 언제나 출력에 남는다** — 조용히 통과시키지 않는다.
 */
data class GateReport(val results: List<CheckResult>) {

    val passed: List<CheckResult.Passed> = results.filterIsInstance<CheckResult.Passed>()
    val failed: List<CheckResult.Failed> = results.filterIsInstance<CheckResult.Failed>()
    val skipped: List<CheckResult.Skipped> = results.filterIsInstance<CheckResult.Skipped>()

    /** 부분 건너뜀까지 포함해 하나라도 못 돈 것이 있는가 */
    val anySkipped: Boolean = results.any { it.skippedParts.isNotEmpty() }

    val allClean: Boolean = failed.isEmpty() && !anySkipped

    val exitCode: Int = if (failed.isEmpty()) 0 else 1

    fun render(): String = buildString {
        results.forEach { r ->
            when (r) {
                is CheckResult.Passed -> {
                    append("PASS  ${r.checkId}")
                    if (r.skippedParts.isNotEmpty()) {
                        append("  (부분 건너뜀: ${r.skippedParts.joinToString(", ")})")
                    }
                    appendLine()
                    r.findings.forEach { f -> appendLine("      ${f.severity} ${f.message}") }
                }

                is CheckResult.Skipped -> {
                    appendLine("SKIP  ${r.checkId}  ${r.reason}")
                    appendLine("      없는 자원: ${r.missing.joinToString(", ")}")
                }

                is CheckResult.Failed -> {
                    appendLine("FAIL  ${r.checkId}")
                    r.findings.forEach { f ->
                        appendLine("      ${f.severity} ${f.message}")
                        f.location?.let { appendLine("        at $it") }
                    }
                }
            }
        }

        if (anySkipped) {
            appendLine()
            appendLine("건너뛴 검사가 있다 — 통과가 아니다.")
        }
    }
}

class GateRunner(private val checks: List<GateCheck>) {

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
                runCatching { check.run(input) }.getOrElse { t ->
                    // 검사가 던지면 통과로 새지 않게 실패로 접는다.
                    CheckResult.Failed(
                        checkId = check.id,
                        findings = listOf(
                            Finding(
                                checkId = check.id,
                                severity = Severity.ERROR,
                                message = "검사가 예외로 중단됐다: ${t.message ?: t::class.simpleName}",
                            ),
                        ),
                    )
                }
            }
        }

        return GateReport(results)
    }
}
