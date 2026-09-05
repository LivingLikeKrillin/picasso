package dev.picasso.gate

enum class Severity { ERROR, WARNING }

/**
 * 하나의 지적. location은 사람이 파일을 열 수 있는 형태여야 한다 —
 * 게이트가 무엇을 막았는지 알려주지 못하면 사람이 게이트를 끄게 된다.
 */
data class Finding(
    val checkId: String,
    val severity: Severity,
    val message: String,
    val location: String? = null,
)

sealed interface CheckResult {
    val checkId: String

    /** 이 검사가 돌긴 했으나 일부를 건너뛴 자원. 출력에 남는다. */
    val skippedParts: Set<Resource>

    data class Passed(
        override val checkId: String,
        val findings: List<Finding> = emptyList(),
        override val skippedParts: Set<Resource> = emptySet(),
    ) : CheckResult {
        init {
            require(findings.none { it.severity == Severity.ERROR }) {
                "통과가 오류 소견을 담을 수 없다"
            }
        }
    }

    data class Failed(
        override val checkId: String,
        val findings: List<Finding>,
        override val skippedParts: Set<Resource> = emptySet(),
    ) : CheckResult {
        init {
            require(findings.any { it.severity == Severity.ERROR }) {
                "실패에는 오류 소견이 하나 이상 있어야 한다"
            }
        }
    }

    /**
     * 자원이 없어 아예 돌지 못했다. 통과가 아니다.
     * 출력에 반드시 남으며, CLI는 이것을 눈에 띄게 표시한다.
     */
    data class Skipped(
        override val checkId: String,
        val missing: Set<Resource>,
        val reason: String,
    ) : CheckResult {
        override val skippedParts: Set<Resource> get() = missing
    }
}
