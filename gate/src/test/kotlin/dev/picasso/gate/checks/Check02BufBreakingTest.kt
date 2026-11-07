package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.buf.BufResult
import dev.picasso.gate.buf.BufRunner
import dev.picasso.gate.input.GateInput
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check02BufBreakingTest {

    private class FakeBuf(
        private val result: BufResult,
        private val byDir: Map<Path, BufResult> = emptyMap(),
    ) : BufRunner {
        var seenArgs: List<String>? = null
        val calls = mutableListOf<Pair<List<String>, Path>>()
        override fun run(args: List<String>, workingDir: Path): BufResult {
            if (seenArgs == null) seenArgs = args
            calls += args to workingDir
            // 기본 출력은 계약 모듈의 것이다. 제어 채널은 따로 주지 않으면 통과한다 —
            // 앞의 시험들이 계약 모듈 하나의 출력을 다루기 때문이다.
            return byDir[workingDir] ?: if (workingDir == CONTROL_DIR) BufResult(0, "") else result
        }
    }

    private companion object {
        val CONTROL_DIR: Path = Path.of("/repo", "mimic")
    }

    private val gitRef = "../.git#ref=abc1234,subdir=contracts"

    private val controlRef = "../.git#ref=abc1234,subdir=mimic"

    private fun runWith(fake: FakeBuf, against: String = gitRef, control: String? = controlRef) =
        Check02BufBreaking().run(
            GateInput(
                repoRoot = Path.of("/repo"),
                buf = fake,
                contractBaseline = against,
                controlBaseline = control,
            ),
        )

    @Test
    fun `저장소와 buf와 계약 기준선을 요구한다`() {
        assertEquals(
            setOf(Resource.REPO, Resource.BUF, Resource.CONTRACT_BASELINE),
            Check02BufBreaking().requires,
        )
    }

    @Test
    fun `기준선을 그대로 넘긴다`() {
        // 게이트가 git을 해석하면 CI와 registry 두 호출이 같은 코드를 못 쓴다.
        // 경로가 contracts/ 기준인 것도 여기서 굳는다 — .git#... 은 실패한다(실측).
        val fake = FakeBuf(BufResult(0, ""))
        runWith(fake)
        assertEquals(listOf("breaking", "--against", gitRef), fake.seenArgs)
    }

    @Test
    fun `제어 채널 기준선이 있으면 mimic 모듈에서도 breaking을 부른다`() {
        // §15.287 — 제어 채널의 필드 번호 재사용도 계약과 같은 규칙으로 잡는다.
        val fake = FakeBuf(BufResult(0, ""))
        val r = runWith(fake)
        assertEquals(
            listOf(
                listOf("breaking", "--against", gitRef) to Path.of("/repo", "contracts"),
                listOf("breaking", "--against", controlRef) to Path.of("/repo", "mimic"),
            ),
            fake.calls,
        )
        assertTrue(r.skippedParts.isEmpty(), "기준선이 다 있는데 건너뜀을 냈다: ${r.skippedParts}")
    }

    @Test
    fun `제어 채널 기준선이 없으면 그쪽만 부분 건너뜀이다`() {
        // 베이스에 mimic/buf.yaml 이 없는 PR. 계약은 비교하고, 비교하지 않은 것을
        // 통과로 숨기지 않는다.
        val fake = FakeBuf(BufResult(0, ""))
        val r = runWith(fake, control = null)
        assertEquals(listOf(Path.of("/repo", "contracts")), fake.calls.map { it.second })
        assertEquals(setOf(Resource.CONTROL_BASELINE), r.skippedParts)
    }

    @Test
    fun `제어 채널의 파괴를 그 모듈 자리로 짚는다`() {
        val breaking =
            "src/main/proto/picasso/control/v1/control.proto:34:3:Field \"1\" with name " +
                "\"duration_millis\" on message \"AdvanceClockRequest\" changed type from \"int64\" to \"string\"."
        val r = runWith(
            FakeBuf(BufResult(0, ""), byDir = mapOf(Path.of("/repo", "mimic") to BufResult(100, breaking))),
        )

        r as CheckResult.Failed
        assertEquals(
            "mimic/src/main/proto/picasso/control/v1/control.proto:34",
            r.findings.single().location,
        )
    }

    @Test
    fun `종료코드 0이면 통과한다`() {
        assertTrue(runWith(FakeBuf(BufResult(0, ""))) is CheckResult.Passed)
    }

    @Test
    fun `파괴를 소견으로 낸다`() {
        val breaking =
            "proto/picasso/v1/task.proto:64:3:Field \"1\" with name \"task_id\" on message " +
                "\"TaskHandle\" changed type from \"string\" to \"int64\"."
        val r = runWith(FakeBuf(BufResult(100, breaking)))

        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("changed type") })
        assertEquals("contracts/proto/picasso/v1/task.proto:64", r.findings.single().location)
    }

    @Test
    fun `git 참조가 없는 기준선을 경고한다`() {
        // --against . 이나 ../contracts 는 사실상 자기 자신과 비교라 실패할 수
        // 없다. 통과가 아니라 "검사가 아무것도 하지 않았다"이고, 그것이
        // 초록불로 보이면 안 된다.
        listOf(".", "./", "../contracts").forEach { against ->
            val r = runWith(FakeBuf(BufResult(0, "")), against = against)
            r as CheckResult.Passed
            assertTrue(
                r.findings.any { it.severity == Severity.WARNING },
                "무의미한 기준선 '$against' 을 조용히 통과시켰다",
            )
        }
    }

    @Test
    fun `git 참조가 있으면 경고하지 않는다`() {
        val r = runWith(FakeBuf(BufResult(0, "")))
        r as CheckResult.Passed
        assertTrue(r.findings.isEmpty(), "정상 기준선에 경고를 냈다: ${r.findings.map { it.message }}")
    }
}
