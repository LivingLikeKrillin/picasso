package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.input.ChangedFiles
import dev.picasso.gate.input.GateInput
import dev.picasso.profile.ProfileDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Check08ProfileOnlyTest {

    private val check = Check08ProfileOnly()

    private fun document(path: String): ProfileDocument =
        ProfileDocument.parse(
            path,
            """{"schema_version":"1.0.0","vendor":"acme-corp","model":"m","revision":1}""",
        ).getOrThrow()

    private fun run(
        all: List<String>,
        added: List<String>,
        loaded: List<String> = added,
    ) = check.run(
        GateInput(
            changed = ChangedFiles(all, added),
            profiles = loaded.map(::document),
        ),
    )

    // ── 통과

    @Test
    fun `프로파일 한 장만 더하면 통과한다`() {
        val added = listOf("profile/profiles/quadruped-c.json")
        assertTrue(run(added, added) is CheckResult.Passed)
    }

    @Test
    fun `추가가 없으면 통과한다`() {
        // **평범한 PR을 막지 않는다.** §11.2를 문자 그대로 구현하면
        // 소스를 고치는 모든 변경이 실패한다.
        val all = listOf("mimic/src/main/kotlin/A.kt", "docs/x.md")
        assertTrue(run(all, added = emptyList(), loaded = emptyList()) is CheckResult.Passed)
    }

    @Test
    fun `기존 프로파일 수정은 소스와 함께 가도 된다`() {
        // 개정판을 올리는 일이 코드 변경을 동반할 수 있다.
        val all = listOf("profile/profiles/humanoid-a.json", "mimic/src/main/kotlin/A.kt")
        assertTrue(run(all, added = emptyList(), loaded = emptyList()) is CheckResult.Passed)
    }

    @Test
    fun `픽스처 추가는 이 검사의 대상이 아니다`() {
        // 검사 8은 §7.4의 실제 기종에 관한 것이다.
        val all = listOf("profile/fixtures/new.json", "mimic/src/main/kotlin/A.kt")
        assertTrue(
            run(all, added = listOf("profile/fixtures/new.json"), loaded = emptyList())
                is CheckResult.Passed,
        )
    }

    // ── 실패

    @Test
    fun `소스가 섞이면 실패한다`() {
        val added = listOf("profile/profiles/quadruped-c.json")
        val result = run(added + "mimic/src/main/kotlin/A.kt", added)
        result as CheckResult.Failed
        assertTrue(result.findings.any { "A.kt" in it.message }, "${result.findings}")
    }

    @Test
    fun `문서 변경이 섞여도 실패한다`() {
        // "소스 변경 0"이지 "코드 변경 0"이 아니다 — 전용 커밋이라는
        // 주장은 무엇이 섞이든 깨진다.
        val added = listOf("profile/profiles/quadruped-c.json")
        assertTrue(run(added + "README.md", added) is CheckResult.Failed)
    }

    @Test
    fun `두 장을 한꺼번에 더하면 실패한다`() {
        // C-2의 주장은 "프로파일 한 장"이다.
        val added = listOf("profile/profiles/c.json", "profile/profiles/d.json")
        val result = run(added, added)
        result as CheckResult.Failed
        assertTrue(result.findings.any { "한 장" in it.message }, "${result.findings}")
    }

    @Test
    fun `하위 디렉터리에 더하면 실패한다`() {
        // InputCollector가 재귀하지 않으므로 게이트가 읽지 않는다 —
        // "프로파일만 바꿨다"로 통과하면서 검사 3·4·6을 통째로 비껴간다.
        val added = listOf("profile/profiles/vendor/evil.json")
        val result = run(added, added, loaded = emptyList())
        result as CheckResult.Failed
        assertTrue(result.findings.any { "바로 아래" in it.message }, "${result.findings}")
    }

    @Test
    fun `더했는데 게이트가 안 읽었으면 실패한다`() {
        // 경로 모양이 맞아도 실제로 안 읽혔으면 검사 밖이다.
        val added = listOf("profile/profiles/quadruped-c.json")
        val result = run(added, added, loaded = emptyList())
        result as CheckResult.Failed
        assertTrue(result.findings.any { "읽지 않았다" in it.message }, "${result.findings}")
    }

    // ── 입력의 무결성

    @Test
    fun `추가 목록이 전체 목록의 부분집합이어야 한다`() {
        // 둘을 따로 주다 보면 어긋난 상태가 만들어진다. 그러면 extras 계산이
        // 음수 방향으로 어긋나 소스가 섞였는데도 통과할 수 있다.
        assertFailsWith<IllegalArgumentException> {
            ChangedFiles(all = listOf("a"), added = listOf("b"))
        }
    }

    @Test
    fun `요구 자원을 선언한다`() {
        // 문서를 안 요구하면 "게이트가 읽었는가" 대조를 못 한다.
        assertEquals(
            setOf(Resource.CHANGED_FILES, Resource.PROFILE_DOCUMENT),
            check.requires,
        )
    }

    @Test
    fun `diff가 없으면 아예 돌지 않는다`() {
        // 자원이 없으면 GateRunner가 건너뛴다 — 조용히 통과가 아니다.
        assertTrue(Resource.CHANGED_FILES !in GateInput(profiles = listOf(document("p"))).available())
    }
}
