package dev.picasso.registry.revision

import dev.picasso.gate.GateChecks
import dev.picasso.gate.GateRunner
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import dev.picasso.gate.input.MalformedProfile
import dev.picasso.profile.ProfileDocument

/** 검증 결과. 실패해도 문서는 남는다 — 편집 후 재제출이 §8.4 ①의 경로다. */
sealed interface ValidationOutcome {
    data object Valid : ValidationOutcome

    /** @param reasons 사람이 읽을 사유. `validation_detail`에 그대로 실린다. */
    data class Invalid(val reasons: List<String>) : ValidationOutcome

    /**
     * 검증 자체를 못 했다. **실패와 구별한다** — 스키마 파일이 없어서 못 본
     * 것을 "프로파일이 틀렸다"로 알리면 운영자가 엉뚱한 것을 고친다.
     */
    data class Incomplete(val detail: String) : ValidationOutcome
}

/**
 * §8.4 ①의 개정판 검증. **게이트를 부른다. 다시 구현하지 않는다.**
 *
 * §11.1이 이유다 — *"입력은 언제나 프로파일 문서다. CI에는 DB가 없으므로
 * 문서로 통일해야 CI와 `registry` 두 호출이 같은 답을 낸다."* 여기서 따로
 * 만들면 두 번째 진실이 생기고, 그때 **CI가 통과시킨 프로파일을 레지스트리가
 * 거부하는 날**이 온다. 그 날 누구도 어느 쪽이 옳은지 말할 수 없다.
 *
 * ## 자원이 CI와 다르다
 *
 * CI는 저장소 트리와 `buf`를 갖고 여기는 안 갖는다. 그래서 검사 1·2(buf),
 * 5(저장소 의존), 7·8(소스 트리)은 여기서 **건너뛴다.** 그것이 §11.1이
 * 인정한 설계다.
 *
 * **그러나 건너뜀을 통과로 읽지 않는다.** 3·4·6이 실제로 돌았는지 확인하고,
 * 하나라도 안 돌았으면 [ValidationOutcome.Incomplete]다 — 자원이 빠져
 * 아무것도 안 본 검증이 "유효함"으로 기록되면 그 개정판은 검증된 적 없이
 * 활성화될 수 있다.
 */
class RevisionValidator(
    /**
     * **널을 허용한다.** 빈 문자열은 게이트에게 "있음"으로 보이므로
     * (`available()`이 `!= null`로 판정한다) 자원 부재를 표현할 수 없다 —
     * 실측으로 그 때문에 `absentRequired` 가드가 한 번도 발화하지 않았고,
     * 그것을 없애는 주입이 안 잡혔다.
     */
    private val schemaJson: String?,
    private val descriptor: ByteArray?,
    /**
     * 돌릴 검사들. **시험이 갈아 끼울 수 있어야 한다** — 그러지 않으면
     * "§8.4 ①의 셋이 실제로 돌았는가" 가드를 발화시킬 방법이 없고,
     * 그 가드를 없애는 주입이 조용히 통과한다.
     */
    private val checks: List<dev.picasso.gate.GateCheck> = GateChecks.all(),
) {
    fun validate(
        documentJson: String,
        source: String,
        /** 직전 활성 개정판의 문서. 없으면 신규라 파괴 검사가 통과한다(§11.1). */
        baseline: String? = null,
    ): ValidationOutcome {
        val parsed = ProfileDocument.parse(source, documentJson)
        val document = parsed.getOrNull()
            ?: return ValidationOutcome.Invalid(
                listOf("프로파일을 읽을 수 없다: ${parsed.exceptionOrNull()?.message}"),
            )

        val input = GateInput(
            profiles = listOf(document),
            malformed = emptyList<MalformedProfile>(),
            schemaJson = schemaJson,
            descriptor = descriptor,
            baseline = baseline?.let {
                mapOf(dev.picasso.profile.ProfileKey(document.vendor, document.model) to it)
            } ?: emptyMap(),
        )

        // **`required`를 채운다.** 비워 두면 자원 결손이 전부 건너뜀이 되어
        // 검사가 하나도 안 돌아도 깨끗하다고 답한다.
        val report = GateRunner(checks, required = REQUIRED).run(input)

        if (report.absentRequired.isNotEmpty()) {
            return ValidationOutcome.Incomplete(
                "검증 입력이 갖춰지지 않았다: ${report.absentRequired}",
            )
        }

        val didNotRun = MUST_RUN - report.results
            .filterNot { it is dev.picasso.gate.CheckResult.Skipped }
            .map { it.checkId }
            .toSet()
        if (didNotRun.isNotEmpty()) {
            return ValidationOutcome.Incomplete("검사 $didNotRun 이 돌지 않았다")
        }

        val errors = report.failed.flatMap { it.findings }
            .filter { it.severity == Severity.ERROR }
            .map { "검사 ${it.checkId}: ${it.message}" }

        return if (errors.isEmpty()) ValidationOutcome.Valid else ValidationOutcome.Invalid(errors)
    }

    private companion object {
        /** 없으면 검증이 아무것도 안 본 것이 되는 자원. */
        val REQUIRED = setOf(
            Resource.PROFILE_DOCUMENT,
            Resource.PROFILE_SCHEMA,
            Resource.CONTRACT_DESCRIPTOR,
        )

        /**
         * §8.4 ①이 요구하는 셋 — JSON Schema 검증(3), proto 교차검증(4),
         * 능력 어휘 파괴 검사(6). **이 셋이 안 돌면 검증이 아니다.**
         */
        val MUST_RUN = setOf("3", "4", "6")
    }
}
