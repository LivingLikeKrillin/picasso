package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput

/**
 * 검사 2 — `buf breaking`(설계 §11.2).
 *
 * 기준선은 **호출 지점이 만든 것을 그대로 넘긴다.** 게이트가 git을 해석하면
 * CI와 `registry` 두 호출이 같은 코드를 못 쓴다(§11.1).
 *
 * 6번과의 역할 분담(§9.4): **2번은 계약 축의 구조, 6번은 프로파일 축의
 * 어휘와 파급.** proto만 바뀐 PR은 6번에 잡히지 않으므로 여기서 막는다.
 */
class Check02BufBreaking : GateCheck {

    override val id = "2"
    override val name = "buf breaking"
    override val requires = setOf(Resource.REPO, Resource.BUF, Resource.CONTRACT_BASELINE)

    override fun run(input: GateInput): CheckResult {
        val failures = mutableListOf<Finding>()
        val warnings = mutableListOf<Finding>()

        // 계약이 먼저고, 제어 채널(§10.5)은 기준선이 있을 때만 본다. 베이스에
        // `mimic/buf.yaml` 이 없으면 호출 지점이 넘기지 않으므로 부분 건너뜀이다(§15.287).
        val modules = buildList {
            add(Check01BufLint.CONTRACTS to input.contractBaseline!!)
            input.controlBaseline?.let { add(Check01BufLint.CONTROL to it) }
        }

        modules.forEach { (module, against) ->
            val result = input.buf!!.run(
                listOf("breaking", "--against", against),
                input.repoRoot!!.resolve(module),
            )
            if (result.exitCode != 0) {
                failures += Check01BufLint.findingsOf(id, result, "buf breaking", module)
            } else if (!GIT_REF.containsMatchIn(against)) {
                // git 참조가 아닌 기준선은 사실상 자기 자신(또는 작업 트리)과 비교라
                // 실패할 수 없다. 통과가 아니라 "검사가 아무것도 하지 않았다"이고,
                // 그것이 초록불로 보이면 안 된다.
                //
                // 열거가 아니라 반대로 판정한다 — {".", "./"}만 막으면 ../contracts가
                // 조용히 통과한다(실측).
                warnings += Finding(
                    id, Severity.WARNING,
                    "기준선 '$against' 에 git 참조(#branch=·#ref=)가 없다 — 자기 자신이나 " +
                        "작업 트리와 비교했을 가능성이 높다. 회귀를 막을 수 없다",
                    module,
                )
            }
        }

        val skipped = if (input.controlBaseline == null) setOf(Resource.CONTROL_BASELINE) else emptySet()
        return if (failures.isNotEmpty()) CheckResult.Failed(id, failures + warnings, skippedParts = skipped)
        else CheckResult.Passed(id, warnings, skippedParts = skipped)
    }

    private companion object {
        val GIT_REF = Regex("""#(branch|ref|tag|commit)=""")
    }
}
