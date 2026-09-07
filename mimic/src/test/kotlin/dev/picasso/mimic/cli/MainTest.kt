package dev.picasso.mimic.cli

import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.wire.RequestHeaders
import io.grpc.ManagedChannelBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §10.2의 기동 순서. **기동 거부 메시지가 유일한 산출물이므로** 그것이
 * 실제로 나오는지를 본다.
 */
class MimicCliTest {

    private val schema = Path.of("..", "profile", "schema", "capability-profile.schema.json")
        .normalize().toString()
    private val minimal = Path.of("..", "profile", "fixtures", "minimal.json")
        .normalize().toString()

    private class Run(val code: Int, val out: String, val err: String)

    /** 기동한 서버를 반드시 닫는다 — 안 닫으면 시험이 포트를 흘린다. */
    private fun run(vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val cli = MimicCli()
        try {
            val code = cli.run(arrayOf(*args), out, err)
            return Run(code, out.toString(), err.toString())
        } finally {
            cli.started?.server?.shutdown()
        }
    }

    @Test
    fun `기체 하나로 기동한다`() {
        val result = run("--robot", "r1=$minimal", "--schema", schema, "--port", "0")
        assertEquals(0, result.code, result.err)
        assertTrue("r1" in result.out, result.out)
    }

    @Test
    fun `--port 0이면 실제 포트를 알려준다`() {
        // 0을 그대로 찍으면 호출자가 붙을 수 없다.
        val result = run("--robot", "r1=$minimal", "--schema", schema, "--port", "0")
        val port = Regex("""포트 (\d+)""").find(result.out)?.groupValues?.get(1)?.toInt()
        assertTrue(port != null && port > 0, "실제 포트를 안 알려준다: ${result.out}")
    }

    @Test
    fun `스키마를 통과하지 못하는 프로파일이면 기동하지 않는다`() {
        // §10.2 — 능력을 모르는 채 표면을 열면 소비자가 없는 능력을 믿는다.
        val broken = Files.createTempFile("broken", ".json")
        Files.writeString(broken, """{"schema_version": "1.0.0", "vendor": "x"}""")
        try {
            val result = run("--robot", "r1=$broken", "--schema", schema)
            assertEquals(2, result.code)
            assertTrue("기동 거부" in result.err, result.err)
        } finally {
            Files.deleteIfExists(broken)
        }
    }

    @Test
    fun `기체 하나가 거부되면 전부 기동하지 않는다`() {
        // 부분 기동은 소비자에게 거짓말이다 — 없는 기체를 물으면 NOT_FOUND가
        // 나오는데 "설정에 없다"인지 "프로파일이 깨졌다"인지 구별할 수 없다.
        val result = run(
            "--robot", "r1=$minimal",
            "--robot", "r2=/nowhere/missing.json",
            "--schema", schema,
        )
        assertEquals(2, result.code)
        assertTrue(result.out.isBlank(), "일부라도 기동했다: ${result.out}")
    }

    // ── 레지스트리 연계 (§15.40의 "운영에서 누가 드는가")

    @Test
    fun `레지스트리 없이도 기동한다`() {
        // §3.2가 이 방향을 런타임 접근으로 뒀다 — 상대가 없어도 모듈이 돈다.
        val result = run("--robot", "r1=$minimal", "--schema", schema, "--port", "0")

        assertEquals(0, result.code, result.err)
        assertTrue("레지스트리 연계" !in result.out, result.out)
    }

    @Test
    fun `토큰 없이 레지스트리를 붙이면 기동을 거부한다`() {
        // 적재 표면이 전부 401을 낼 것이고(§15.38), 그러면 관측이 통째로
        // 폴백으로 가면서 **"붙었다"고 보고된다.** 기동에서 막는 편이 정직하다.
        val result = run(
            "--robot", "r1=$minimal", "--schema", schema, "--port", "0",
            "--registry", "http://127.0.0.1:1",
        )

        assertEquals(2, result.code)
        assertTrue("--ingest-token" in result.err, result.err)
    }

    @Test
    fun `레지스트리를 붙이면 연계와 폴백 자리를 알린다`() {
        // **폴백 파일이 어디 쌓이는지 운영자가 알아야 한다.** 모르면 적재가
        // 멈춘 것을 원장이 비는 것으로만 알게 된다.
        val dir = Files.createTempDirectory("picasso-cli")

        val result = run(
            "--robot", "r1=$minimal", "--schema", schema, "--port", "0",
            "--registry", "http://127.0.0.1:1", "--ingest-token", "t",
            "--site", "line-a", "--fallback-dir", dir.toString(),
        )

        assertEquals(0, result.code, result.err)
        assertTrue("레지스트리 연계" in result.out, result.out)
        assertTrue("site=line-a" in result.out, result.out)
        assertTrue("handshake-fallback.jsonl" in result.out, result.out)
        assertTrue("task-fallback.jsonl" in result.out, result.out)
    }

    @Test
    fun `기동할 때 폴백을 다시 민다`() {
        // §15.46이 물은 "언제 부르는가"의 답. 레지스트리가 닿지 않으므로
        // 재적재는 실패하지만, **불렀다는 것**과 그 결과가 보여야 한다.
        val dir = Files.createTempDirectory("picasso-cli")
        Files.write(
            dir.resolve("task-fallback.jsonl"),
            ("""{"kind":"state","reason":null,"message":{}}""" + "\n").toByteArray(),
        )

        val result = run(
            "--robot", "r1=$minimal", "--schema", schema, "--port", "0",
            "--registry", "http://127.0.0.1:1", "--ingest-token", "t",
            "--fallback-dir", dir.toString(),
        )

        assertEquals(0, result.code, result.err)
        assertTrue("폴백 재적재" in result.out, result.out)
        assertTrue("1건 실패" in result.out, "닿지 못한 줄이 실패로 안 세어졌다: ${result.out}")
    }

    @Test
    fun `폴백이 없으면 재적재를 보고하지 않는다`() {
        // 언제나 보고하면 그 줄이 신호이기를 그친다.
        val result = run(
            "--robot", "r1=$minimal", "--schema", schema, "--port", "0",
            "--registry", "http://127.0.0.1:1", "--ingest-token", "t",
        )

        assertEquals(0, result.code, result.err)
        assertTrue("폴백 재적재" !in result.out, result.out)
    }

    @Test
    fun `연계를 붙이면 협상이 폴백에 쌓인다`() {
        // **CLI가 보고자를 실제로 서버에 넘겼는가.** 출력 문구만 보면 안
        // 넘겨도 통과한다 — 실측으로 그 주입이 빠져나갔다.
        val dir = Files.createTempDirectory("picasso-cli")

        withServer(dir) { port ->
            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext().build()
            try {
                SkillServiceGrpc.newBlockingStub(channel).negotiate(
                    NegotiateRequest.newBuilder()
                        .setHeader(RequestHeaders.build("picasso.v1.NegotiateRequest", "r1", "c1"))
                        .setRequirement(
                            CapabilityRequirement.newBuilder()
                                .setClientId("c1").setRobotId("r1")
                                .addRequirements("pick_place@^1.2"),
                        ).build(),
                )
            } finally {
                channel.shutdownNow()
            }
        }

        // 레지스트리가 닿지 않으므로 보고는 실패하고 폴백으로 간다.
        val file = dir.resolve("handshake-fallback.jsonl")
        assertTrue(Files.exists(file), "보고자가 서버에 안 붙었다")
        assertEquals(1, Files.readAllLines(file).size)
    }

    @Test
    fun `연계를 붙이면 태스크 전이가 폴백에 쌓인다`() {
        // **CLI가 발행을 실제로 감쌌는가.**
        val dir = Files.createTempDirectory("picasso-cli")

        withServer(dir) { port ->
            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext().build()
            try {
                TaskServiceGrpc.newBlockingStub(channel).startTask(
                    StartTaskRequest.newBuilder()
                        .setHeader(RequestHeaders.build("picasso.v1.StartTaskRequest", "r1", "c1"))
                        .setTaskId("t1").setRevision(1).setRobotId("r1")
                        .setSkillType("navigate_to")
                        .addParameters(
                            ParameterValue.newBuilder()
                                .setKey("location").setStringValue("dock-1"),
                        ).build(),
                )
            } finally {
                channel.shutdownNow()
            }
        }

        val file = dir.resolve("task-fallback.jsonl")
        assertTrue(Files.exists(file), "발행이 안 감싸였다")
    }

    @Test
    fun `기체마다 다른 브로커 클라이언트로 붙는다`() {
        // **MQTT clientId가 같으면 두 번째 접속이 첫 번째를 끊는다.**
        // 기체 둘이 같은 id로 붙으면 하나가 조용히 죽고, 그 기체의 발행이
        // 통째로 사라진다 — 로그에는 아무것도 안 남는다.
        //
        // 브로커 없이도 본다: 기동이 성공하고 두 기체가 다 살아 있으면
        // 적어도 id 충돌은 아니다. 실제 충돌은 브로커 시험이 본다.
        val result = run(
            "--robot", "r1=$minimal", "--robot", "r2=$minimal",
            "--schema", schema, "--port", "0",
        )

        assertEquals(0, result.code, result.err)
        assertTrue("r1" in result.out && "r2" in result.out, result.out)
    }

    /** 연계를 붙여 기동하고, 열린 포트로 무언가 한 뒤 닫는다. */
    private fun withServer(dir: Path, body: (Int) -> Unit) {
        val out = StringBuilder()
        val err = StringBuilder()
        val cli = MimicCli()
        try {
            val code = cli.run(
                arrayOf(
                    "--robot", "r1=$minimal", "--schema", schema, "--port", "0",
                    "--registry", "http://127.0.0.1:1", "--ingest-token", "t",
                    "--site", "line-a", "--fallback-dir", dir.toString(),
                ),
                out, err,
            )
            assertEquals(0, code, err.toString())
            body(cli.started!!.server.port)
        } finally {
            cli.started?.server?.shutdown()
        }
    }

    @Test
    fun `잘못된 인자를 통째로 확인한다`() {
        // 하나만 보면 나머지가 침묵한다.
        val cases = mapOf(
            "--robot 문법" to arrayOf("--robot", "r1", "--schema", schema),
            "--robot 빈 쪽" to arrayOf("--robot", "=$minimal", "--schema", schema),
            "--robot 중복" to arrayOf("--robot", "r1=$minimal", "--robot", "r1=$minimal", "--schema", schema),
            "--robot 없음" to arrayOf("--schema", schema),
            "--schema 없음" to arrayOf("--robot", "r1=$minimal"),
            "--port 비정수" to arrayOf("--robot", "r1=$minimal", "--schema", schema, "--port", "x"),
            "--clock 미지값" to arrayOf("--robot", "r1=$minimal", "--schema", schema, "--clock", "fast"),
            "모르는 인자" to arrayOf("--robot", "r1=$minimal", "--schema", schema, "--nope", "1"),
            "값 없는 인자" to arrayOf("--robot", "r1=$minimal", "--schema"),
        )
        assertEquals(9, cases.size)

        cases.forEach { (name, args) ->
            val result = run(*args)
            assertEquals(2, result.code, "$name 이 통과했다: ${result.out}")
            assertTrue("사용법" in result.err, "$name 에 사용법이 없다: ${result.err}")
        }
    }
}
