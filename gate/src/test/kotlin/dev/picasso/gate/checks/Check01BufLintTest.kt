package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.buf.BufResult
import dev.picasso.gate.buf.BufRunner
import dev.picasso.gate.input.GateInput
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check01BufLintTest {

    private class FakeBuf(private val result: BufResult) : BufRunner {
        var seenArgs: List<String>? = null
        var seenDir: Path? = null
        override fun run(args: List<String>, workingDir: Path): BufResult {
            seenArgs = args
            seenDir = workingDir
            return result
        }
    }

    private fun runWith(fake: FakeBuf) =
        Check01BufLint().run(GateInput(repoRoot = Path.of("/repo"), buf = fake))

    @Test
    fun `저장소와 buf를 요구한다`() {
        assertEquals(setOf(Resource.REPO, Resource.BUF), Check01BufLint().requires)
    }

    @Test
    fun `contracts 디렉터리에서 lint를 부른다`() {
        val fake = FakeBuf(BufResult(0, ""))
        runWith(fake)
        assertEquals(listOf("lint"), fake.seenArgs)
        assertEquals(Path.of("/repo", "contracts"), fake.seenDir)
    }

    @Test
    fun `종료코드 0이면 통과한다`() {
        assertTrue(runWith(FakeBuf(BufResult(0, ""))) is CheckResult.Passed)
    }

    @Test
    fun `buf가 실패하면 그 출력을 소견으로 낸다`() {
        val violation =
            "proto/picasso/v1/skill.proto:12:1:Message name \"skill_declaration\" should be PascalCase"
        val r = runWith(FakeBuf(BufResult(100, violation)))

        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("PascalCase") })
        assertEquals("contracts/proto/picasso/v1/skill.proto:12", r.findings.single().location)
    }

    @Test
    fun `위반 줄마다 소견을 낸다`() {
        // 여러 줄을 한 덩어리로 내면 사람이 몇 개인지 못 센다.
        val r = runWith(FakeBuf(BufResult(100, "proto/a.proto:1:1:첫째\nproto/b.proto:2:1:둘째\n")))
        r as CheckResult.Failed
        assertEquals(2, r.findings.size)
    }

    @Test
    fun `Docker 잡음을 지적으로 세지 않는다`() {
        // tools/buf는 Docker 래퍼이고 출력을 합치므로 pull 로그가 섞인다(실측).
        // 그것을 소견으로 세면 "몇 개인지 센다"가 첫 pull 때 무너진다.
        val r = runWith(
            FakeBuf(
                BufResult(
                    100,
                    "Unable to find image 'bufbuild/buf:1.47.2' locally\n" +
                        "1.47.2: Pulling from bufbuild/buf\n" +
                        "proto/a.proto:1:1:진짜 위반\n",
                ),
            ),
        )
        r as CheckResult.Failed
        assertEquals(1, r.findings.size, "Docker 잡음이 지적으로 셌다: ${r.findings.map { it.message }}")
    }

    @Test
    fun `위치 있는 줄이 하나도 없으면 출력을 통째로 낸다`() {
        // Failure: could not clone ... 같은 것. 버리면 사람이 원인을 못 읽는다.
        val r = runWith(FakeBuf(BufResult(1, "Failure: could not clone file:///workspace/.git")))
        r as CheckResult.Failed
        assertTrue(r.findings.single().message.contains("could not clone"))
    }

    @Test
    fun `실패했는데 출력이 비어도 소견을 낸다`() {
        // 소견 없는 Failed는 CheckResult의 init이 거부한다 — 그러면 게이트가
        // 예외로 죽고 사람은 buf가 왜 실패했는지 못 읽는다.
        val r = runWith(FakeBuf(BufResult(1, "   ")))
        r as CheckResult.Failed
        assertTrue(r.findings.isNotEmpty())
    }
}
