package dev.picasso.uplink.report

import com.sun.net.httpserver.HttpServer
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.uplink.Publication
import dev.picasso.uplink.RecordingPublisher
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 조립이 **실제로 됐는가.**
 *
 * ## 출력 문자열은 조립을 증명하지 않는다
 *
 * CLI 시험은 종료 코드와 안내 문구를 본다. 그래서 **보고자를 안 넘겨도,
 * 발행을 안 감싸도, 폴백을 빼도 통과한다** — 실측으로 넷이 그렇게 빠져나갔다.
 *
 * 여기서는 닿지 않는 주소로 링크를 만들어 **실패가 파일에 쌓이는지**로
 * 조립을 관측한다. 파일에 줄이 생겼다는 것은 그 경로가 실제로 붙어 있고
 * 폴백까지 물려 있다는 뜻이다.
 */
class RegistryLinkTest {

    private val unreachable = "http://127.0.0.1:1"

    // ── 연계 없음

    @Test
    fun `연계가 없으면 보고가 아무 데도 안 간다`() {
        val link = RegistryLink.none()

        // 던지지 않아야 한다 — 레지스트리 없이도 mimic이 돈다(§3.2).
        link.reporter.report(handshake())

        assertEquals(emptyList(), link.fallbackPaths())
        assertEquals(null, link.replayFallbacks())
    }

    @Test
    fun `연계가 없으면 발행을 안 감싼다`() {
        val downstream = RecordingPublisher()

        val wrapped = RegistryLink.none().wrap(downstream)

        assertTrue(wrapped === downstream, "감쌌다 — 연계가 없으면 그대로여야 한다")
    }

    // ── 연계 있음

    @Test
    fun `보고자가 실제로 붙고 실패는 파일로 간다`() {
        val dir = Files.createTempDirectory("picasso-link")
        val link = RegistryLink.http(unreachable, "t", "line-a", dir)

        link.reporter.report(handshake())

        val file = dir.resolve("handshake-fallback.jsonl")
        assertEquals(1, Files.readAllLines(file).size, "보고자가 안 붙었거나 폴백이 없다")
    }

    @Test
    fun `발행이 감싸여 태스크 관측이 적재로 간다`() {
        val dir = Files.createTempDirectory("picasso-link")
        val downstream = RecordingPublisher()
        val link = RegistryLink.http(unreachable, "t", "line-a", dir)

        link.wrap(downstream).publish(publication())
        assertTrue(link.drain(Duration.ofSeconds(10)), "적재 큐가 안 비었다")

        assertEquals(1, downstream.publications.size, "감싸면서 발행을 삼켰다")
        val file = dir.resolve("task-fallback.jsonl")
        assertEquals(1, Files.readAllLines(file).size, "적재가 안 붙었거나 폴백이 없다")
    }

    @Test
    fun `폴백 자리를 둘 다 알린다`() {
        val dir = Files.createTempDirectory("picasso-link")

        val paths = RegistryLink.http(unreachable, "t", "line-a", dir).fallbackPaths()

        assertEquals(2, paths.size, "$paths")
        assertTrue(paths.any { it.fileName.toString().startsWith("handshake") }, "$paths")
        assertTrue(paths.any { it.fileName.toString().startsWith("task") }, "$paths")
    }

    // ── 폴백 없는 모드

    @Test
    fun `폴백 자리가 없으면 보고자가 던진다`() {
        // **삼키는 책임은 부르는 쪽에 있다** — 핸드셰이크는
        // `SkillServiceImpl`이(§5.4), 태스크는 `IngestBridge`가 삼킨다.
        // 여기서 삼키면 폴백이 붙어 있을 때도 파일로 넘길 기회를 잃는다.
        val link = RegistryLink.http(unreachable, "t", "line-a", fallbackDir = null)

        assertFailsWith<Exception> { link.reporter.report(handshake()) }
        assertEquals(emptyList(), link.fallbackPaths())
        assertEquals(null, link.replayFallbacks())
    }

    @Test
    fun `폴백 자리가 없어도 발행은 지나간다`() {
        // 적재가 죽어도 로봇은 조용해지면 안 된다 — `IngestBridge`가 삼킨다.
        val link = RegistryLink.http(unreachable, "t", "line-a", fallbackDir = null)
        val downstream = RecordingPublisher()

        link.wrap(downstream).publish(publication())

        assertEquals(1, downstream.publications.size, "적재 실패가 발행을 막았다")
    }

    @Test
    fun `레지스트리가 답하지 않는 동안에도 발행은 그 답을 기다리지 않는다`() {
        // 발행은 발신자의 잠금 아래에서 온다(§15.210). 여기서 레지스트리를 기다리면 그 잠금을 기다리는 RPC 가 같이 선다.
        val release = CountDownLatch(1)
        val arrived = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ingest") { exchange ->
                arrived.incrementAndGet()
                exchange.requestBody.readBytes()
                release.await(10, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("{}".toByteArray()) }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        val link = RegistryLink.http("http://127.0.0.1:${server.address.port}", "t", "line-a", fallbackDir = null)
        try {
            val publisher = link.wrap(RecordingPublisher())
            publisher.publish(publication())
            // 첫 적재가 레지스트리에 닿아 서 있는 동안 다음 발행을 잰다.
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (arrived.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(arrived.get() > 0, "적재가 레지스트리에 안 닿았다")

            val started = System.nanoTime()
            publisher.publish(publication())
            val waited = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(waited < Duration.ofMillis(500), "발행이 레지스트리의 답을 기다렸다: $waited")
        } finally {
            release.countDown()
            link.close()
            server.stop(0)
        }
    }

    // ── 재적재

    @Test
    fun `재적재가 두 파일을 다 훑는다`() {
        // 한쪽만 밀면 나머지는 영영 안 밀린다.
        val dir = Files.createTempDirectory("picasso-link")
        val link = RegistryLink.http(unreachable, "t", "line-a", dir)
        link.reporter.report(handshake())
        link.wrap(RecordingPublisher()).publish(publication())
        assertTrue(link.drain(Duration.ofSeconds(10)), "적재 큐가 안 비었다")

        val outcome = requireNotNull(link.replayFallbacks())

        // 레지스트리가 닿지 않으므로 둘 다 다시 실패한다 — 중요한 것은
        // **둘 다 시도됐다**는 것이다.
        assertEquals(2, outcome.failed.size, "두 파일 중 하나만 훑었다: $outcome")
    }

    @Test
    fun `폴백 파일이 없으면 재적재가 조용하다`() {
        val dir = Files.createTempDirectory("picasso-link")

        val outcome = requireNotNull(RegistryLink.http(unreachable, "t", "line-a", dir).replayFallbacks())

        assertEquals(ReplayOutcome(0, emptyList(), 0), outcome)
    }

    // ── 씨앗

    private fun header() = MessageHeader.newBuilder()
        .setClientId("line-controller").setRobotId("r1").build()

    private fun handshake() = HandshakeReport(
        site = "line-a",
        request = NegotiateRequest.newBuilder()
            .setHeader(header())
            .setRequirement(
                CapabilityRequirement.newBuilder()
                    .setClientId("line-controller").setRobotId("r1")
                    .addRequirements("pick_place@^1.2"),
            ).build(),
        response = NegotiateResponse.newBuilder().setAccepted(true).build(),
    )

    private fun publication() = Publication(
        "picasso/1/line-a/robot/r1/state",
        StateMessage.newBuilder()
            .setHeader(header())
            .addTasks(TaskSnapshot.newBuilder().setTaskId("t1").setSkillType("navigate_to"))
            .build(),
        1,
    )
}
