package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.buf.BufResult
import dev.picasso.gate.input.GateInput

/**
 * 검사 1 — `buf lint`(설계 §11.2).
 *
 * **위임이지 판정이 아니다.** 규칙은 `contracts/buf.yaml`의 `lint.use`가
 * 정하고 이 검사는 실패를 게이트의 소견으로 옮기기만 한다.
 */
class Check01BufLint : GateCheck {

    override val id = "1"
    override val name = "buf lint"
    override val requires = setOf(Resource.REPO, Resource.BUF)

    override fun run(input: GateInput): CheckResult {
        val result = input.buf!!.run(listOf("lint"), input.repoRoot!!.resolve(CONTRACTS))
        return if (result.exitCode == 0) CheckResult.Passed(id)
        else CheckResult.Failed(id, findingsOf(id, result, "buf lint"))
    }

    internal companion object {
        const val CONTRACTS = "contracts"

        /** buf는 `파일:줄:열:메시지` 형태로 낸다. */
        private val LOCATED = Regex("""^([^:]+):(\d+):(\d+):(.*)$""")

        /**
         * buf의 출력을 소견으로 옮긴다.
         *
         * **위치가 있는 줄만 지적으로 센다.** `tools/buf`는 Docker 래퍼이고
         * 출력을 합치므로 `Unable to find image ... locally` 같은 pull 로그가
         * 섞이는데(실측), 그것을 세면 "지적이 몇 개인지 센다"가 첫 pull 때
         * 무너진다.
         *
         * 위치 있는 줄이 하나도 없으면 출력을 통째로 낸다 — `Failure: could
         * not clone ...` 같은 것을 버리면 사람이 원인을 못 읽는다. 그리고
         * 소견 없는 `Failed`는 `CheckResult`의 `init`이 거부하므로, 출력이
         * 비어도 하나는 남긴다.
         */
        fun findingsOf(checkId: String, result: BufResult, what: String): List<Finding> {
            val lines = result.output.lines().filter { it.isNotBlank() }
            val located = lines.mapNotNull { LOCATED.find(it) }

            if (located.isNotEmpty()) {
                return located.map { m ->
                    Finding(
                        checkId, Severity.ERROR,
                        m.groupValues[4].trim(),
                        "$CONTRACTS/${m.groupValues[1]}:${m.groupValues[2]}",
                    )
                }
            }

            return listOf(
                Finding(
                    checkId, Severity.ERROR,
                    if (lines.isEmpty()) {
                        "$what 이 종료코드 ${result.exitCode}로 실패했는데 출력이 없다"
                    } else {
                        "$what 이 종료코드 ${result.exitCode}로 실패했다:\n${lines.joinToString("\n")}"
                    },
                ),
            )
        }
    }
}
