package dev.picasso.registry

/**
 * 계약 semver 비교. **바인딩 합법성의 한쪽 입력이다**(§9.1).
 *
 * 어댑터 버전이 따르는 계약이 개정판이 요구하는 스킬의 `introduced_in_semver`
 * 보다 낮으면 그 조합은 **거부된다** — 어댑터가 모르는 스킬을 프로파일이
 * 선언한 것이고, 그 로봇에 그 스킬로 태스크를 걸면 어댑터가 이해하지 못한다.
 *
 * **프리릴리스와 빌드 메타데이터를 다루지 않는다.** 계약은 `MAJOR.MINOR.PATCH`
 * 셋만 쓴다(§5.2). 파싱할 수 없는 값은 조용히 0으로 접지 않고 던진다 —
 * 접으면 `x.y.z`가 아닌 값이 언제나 가장 낮은 것으로 취급되어 **아무 조합이나
 * 통과한다.**
 */
@JvmInline
value class Semver private constructor(private val parts: Triple<Int, Int, Int>) :
    Comparable<Semver> {

    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, { it.parts.first }, { it.parts.second }, { it.parts.third })

    override fun toString(): String = "${parts.first}.${parts.second}.${parts.third}"

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

        fun parse(text: String): Semver {
            val m = PATTERN.matchEntire(text.trim())
                ?: error("계약 semver 형식이 아니다: '$text' (MAJOR.MINOR.PATCH만 쓴다)")
            return Semver(
                Triple(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                ),
            )
        }
    }
}
