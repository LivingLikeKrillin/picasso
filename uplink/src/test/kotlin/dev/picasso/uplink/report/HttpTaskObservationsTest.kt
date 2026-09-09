package dev.picasso.uplink.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.TaskTransition
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 태스크 적재의 와이어. **진짜 소켓으로 본다** — 경로·토큰·본문 형식 중
 * 어느 것도 타입으로 붙들려 있지 않다(`registry`는 `mimic`을 모르고 그
 * 역도 마찬가지다). 그 대가가 이 시험이다.
 */
class HttpTaskObservationsTest {

    private val received = mutableListOf<Captured>()
    private var status = 200

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/ingest") { exchange: HttpExchange ->
            received += Captured(
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization") ?: "",
                body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
            )
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val sink by lazy {
        HttpTaskObservations("http://127.0.0.1:${server.address.port}", "test-token")
    }

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `스냅샷이 적재 경로로 나간다`() {
        sink.onState(state())

        val sent = received.single()
        // 접두사 매칭이라 스텁이 다른 경로도 받아 준다 — 경로까지 단언한다.
        assertEquals("/ingest/task", sent.path)
        assertEquals("Bearer test-token", sent.authorization)
    }

    @Test
    fun `전이도 같은 경로로 나간다`() {
        // 경로를 둘로 나누면 어느 쪽으로 보낼지를 여기서 판정하게 되고,
        // 그 판정이 적재의 것과 갈릴 수 있다.
        sink.onEvent(event())

        assertEquals("/ingest/task", received.single().path)
    }

    @Test
    fun `본문이 계약 메시지 그대로다`() {
        sink.onEvent(event())

        val body = ObjectMapper().readTree(received.single().body)
        assertEquals("t1", body.get("taskTransition").get("taskId").asText())
        assertEquals("TASK_STATE_RUNNING", body.get("taskTransition").get("to").asText())
    }

    @Test
    fun `스냅샷과 전이가 구별되게 실린다`() {
        // 둘을 같은 모양으로 보내면 적재가 무엇인지 몰라 한쪽으로 접는다.
        sink.onState(state())
        sink.onEvent(event())

        val first = ObjectMapper().readTree(received[0].body)
        val second = ObjectMapper().readTree(received[1].body)
        assertTrue(first.has("tasks"), received[0].body)
        assertTrue(second.has("taskTransition"), received[1].body)
    }

    @Test
    fun `오류 상태면 던진다`() {
        // 삼키면 폴백이 파일로 붙들 기회를 잃는다.
        status = 401

        val thrown = assertFailsWith<IllegalStateException> { sink.onState(state()) }
        assertTrue("401" in (thrown.message ?: ""), thrown.message)
    }

    @Test
    fun `닿지 못하면 던진다`() {
        val dead = HttpTaskObservations("http://127.0.0.1:1", "t")

        assertFailsWith<Exception> { dead.onState(state()) }
    }

    private data class Captured(val path: String, val authorization: String, val body: String)

    private fun header() = MessageHeader.newBuilder().setRobotId("r1").build()

    private fun state() = StateMessage.newBuilder()
        .setHeader(header())
        .addTasks(TaskSnapshot.newBuilder().setTaskId("t1").setSkillType("navigate_to"))
        .build()

    private fun event() = Event.newBuilder()
        .setHeader(header())
        .setTaskTransition(
            TaskTransition.newBuilder()
                .setTaskId("t1").setSkillType("navigate_to")
                .setFrom(TaskState.TASK_STATE_ACCEPTED).setTo(TaskState.TASK_STATE_RUNNING),
        ).build()
}
