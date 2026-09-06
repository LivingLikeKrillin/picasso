package dev.picasso.gate

import dev.picasso.gate.checks.Check01BufLint
import dev.picasso.gate.checks.Check02BufBreaking
import dev.picasso.gate.checks.Check03ProfileSchema
import dev.picasso.gate.checks.Check04CrossRef
import dev.picasso.gate.checks.Check05ContractDeps
import dev.picasso.gate.checks.Check06Vocabulary
import dev.picasso.gate.checks.Check07ModelBranching
import dev.picasso.gate.checks.Check08ProfileOnly

/**
 * 검사 목록의 단일 출처.
 *
 * CLI 안에 두면 `registry`가 같은 목록을 못 쓴다 — §11.1의 "구현은 하나이고
 * 호출 지점이 둘"이 깨진다. 9번은 검사가 아니라 음성 하네스라 목록에 없다.
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
        Check08ProfileOnly(),
    )

    /**
     * CI 호출 지점이 반드시 갖춰야 하는 자원.
     *
     * 없는 것들의 이유:
     *  - `BASELINE`·`CONTRACT_BASELINE` — 계약이나 프로파일이 신규면 없는 것이
     *    정상이다(§11.1).
     *  - `REGISTRY` — CI에는 DB가 없다. §11.1이 인정하는 유일한 상시 건너뜀이다.
     *  - `CHANGED_FILES` — push 빌드에는 기준선이 없어 diff를 못 만든다.
     *    **PR 빌드에서는 CI가 이것을 요구 목록에 더한다** — 안 그러면 diff
     *    생성이 어느 날 조용히 깨져도 검사 8이 SKIP으로 넘어가고 완료 기준
     *    11의 기제가 아무 소리 없이 사라진다.
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
