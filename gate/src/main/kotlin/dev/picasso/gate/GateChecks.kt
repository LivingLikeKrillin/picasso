package dev.picasso.gate

import dev.picasso.gate.checks.Check01BufLint
import dev.picasso.gate.checks.Check02BufBreaking
import dev.picasso.gate.checks.Check03ProfileSchema
import dev.picasso.gate.checks.Check04CrossRef
import dev.picasso.gate.checks.Check05ContractDeps
import dev.picasso.gate.checks.Check06Vocabulary
import dev.picasso.gate.checks.Check07ModelBranching

/**
 * 검사 목록의 단일 출처.
 *
 * CLI 안에 두면 `registry`가 같은 목록을 못 쓴다 — §11.1의 "구현은 하나이고
 * 호출 지점이 둘"이 깨진다. 8번은 아직 없다(Chunk 5).
 */
object GateChecks {

    fun all(): List<GateCheck> = listOf(
        Check01BufLint(),
        Check02BufBreaking(),
        Check03ProfileSchema(),
        Check04CrossRef(),
        Check05ContractDeps(),
        Check06Vocabulary(),
        Check07ModelBranching(),
    )

    /**
     * CI 호출 지점이 반드시 갖춰야 하는 자원.
     *
     * 없는 것들의 이유:
     *  - `BASELINE`·`CONTRACT_BASELINE` — 계약이나 프로파일이 신규면 없는 것이
     *    정상이다(§11.1).
     *  - `REGISTRY` — CI에는 DB가 없다. §11.1이 인정하는 유일한 상시 건너뜀이다.
     *  - `CHANGED_FILES` — 검사 8번이 2단계다.
     *
     * `BUF`는 넣는다. CI에는 Docker가 있으므로 없으면 배선이 깨진 것이고,
     * 검사 1·2번이 조용히 건너뛰면 계약 파괴가 통째로 지나간다.
     */
    val REQUIRED_IN_CI: Set<Resource> = setOf(
        Resource.REPO,
        Resource.PROFILE_DOCUMENT,
        Resource.PROFILE_SCHEMA,
        Resource.CONTRACT_DESCRIPTOR,
        Resource.BUF,
    )
}
