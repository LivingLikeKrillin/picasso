package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.ProfileDirectories
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput

/**
 * 검사 8 — 프로파일 전용 변경 확인(설계 §11.2).
 *
 * C-2의 "세 번째 기종은 프로파일 한 장"을 **CI 실패 조건**으로 바꾼다.
 * §12.2가 "11번의 소스 변경 0 강제가 이 프로젝트의 주장을 CI 실패 조건으로
 * 바꾼다"고 한 것이 이 검사다.
 *
 * **§11.2의 문장을 그대로 구현하면 안 된다.** "변경 파일이 `profile/profiles/`
 * 아래뿐인지"를 문자 그대로 두면 소스를 고치는 **모든** 변경이 실패한다.
 * 판정을 좁힌다.
 *
 * > `profile/profiles/`에 파일을 **추가**하는 변경은 그것만 담아야 한다.
 *
 * - **추가**만 본다. 기존 프로파일 수정은 소스와 함께 가도 된다 — 개정판을
 *   올리는 일이 코드 변경을 동반할 수 있다.
 * - 추가가 없으면 **통과**다. 건너뜀이 아니다 — 판정을 했고 위반이 없다.
 * - 두 장을 한꺼번에 더하는 것도 막는다. C-2의 주장은 "한 장"이다.
 *
 * **스위트를 실행하지 않는다**(§11.2) — 계약 스위트는 `harness`가 소유하므로
 * `gate`가 부르면 §3.2의 그래프가 깨진다. CI가 둘을 순서대로 부른다.
 */
class Check08ProfileOnly : GateCheck {

    override val id = "8"
    override val name = "프로파일 전용 변경"

    /**
     * 문서도 요구한다. **추가된 프로파일을 게이트가 실제로 읽었는지** 대조해야
     * 하는데, 안 그러면 `profile/profiles/vendor/evil.json` 같은 하위 경로가
     * "프로파일만 바꿨다"로 통과하면서 검사 3·4·6에는 안 잡힌다
     * (`InputCollector`가 재귀하지 않는다).
     */
    override val requires = setOf(Resource.CHANGED_FILES, Resource.PROFILE_DOCUMENT)

    override fun run(input: GateInput): CheckResult {
        val changed = input.changed!!

        val addedModels = changed.added.filter { it.startsWith("${ProfileDirectories.MODELS}/") }
        if (addedModels.isEmpty()) {
            // 평범한 변경이다. 판정을 했고 위반이 없다.
            return CheckResult.Passed(id)
        }

        val findings = mutableListOf<Finding>()

        addedModels.filterNot { FLAT_JSON.matches(it) }.forEach {
            findings += Finding(
                id, Severity.ERROR,
                "기종 프로파일은 ${ProfileDirectories.MODELS}/ 바로 아래 .json 이어야 한다: $it " +
                    "— 게이트가 하위 디렉터리를 읽지 않으므로 검사 3·4·6을 통째로 비껴간다",
                it,
            )
        }

        // 게이트가 실제로 읽은 문서와 대조한다. 더해 놓고 안 읽히면
        // "프로파일만 바꿨다"는 판정이 아무것도 보장하지 않는다.
        val loaded = input.profiles.map { normalize(it.path) }.toSet()
        addedModels.filterNot { added -> loaded.any { it.endsWith(added) } }.forEach {
            findings += Finding(
                id, Severity.ERROR,
                "더한 프로파일을 게이트가 읽지 않았다: $it — 검사 밖에 놓인다",
                it,
            )
        }

        if (addedModels.size > 1) {
            findings += Finding(
                id, Severity.ERROR,
                "기종 프로파일을 한 번에 여럿 더했다: $addedModels " +
                    "— C-2의 주장은 '프로파일 한 장'이다(설계 §12.2의 11번)",
            )
        }

        val extras = changed.all - addedModels.toSet()
        if (extras.isNotEmpty()) {
            findings += Finding(
                id, Severity.ERROR,
                "기종 프로파일을 더하면서 다른 것을 함께 바꿨다: ${extras.sorted()} " +
                    "— C-2의 '소스 변경 0'이 깨진다. 전용 커밋으로 나눠라",
            )
        }

        return if (findings.isEmpty()) CheckResult.Passed(id) else CheckResult.Failed(id, findings)
    }

    /** Windows에서 `ProfileDocument.path`가 역슬래시를 담는다. */
    private fun normalize(path: String) = path.replace('\\', '/')

    private companion object {
        /** `profile/profiles/` **바로 아래**의 `.json` 하나. 하위 경로는 게이트가 안 읽는다. */
        val FLAT_JSON = Regex(
            "^" + Regex.escape(ProfileDirectories.MODELS) + "/[^/]+[.]json" + "$",
        )
    }
}
