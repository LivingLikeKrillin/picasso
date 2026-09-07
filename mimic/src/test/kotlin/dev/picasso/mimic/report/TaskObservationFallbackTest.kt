package dev.picasso.mimic.report

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.TaskTransition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §15.45 — **삼키되 잃지 않고, 왜 막혔는지 남긴다.**
 *
 * 적재 실패가 발행을 막으면 안 되므로 삼켜야 하는데(§5.4와 같은 규칙),
 * **삼킨 것을 버리면 그 사이의 전이가 통째로 사라진다.** 그러면 `task` 표는
 * 그 태스크를 못 본 채로 남고, 뒤늦게 스냅샷이 와도 이미 종착한 태스크는
 * 다시 안 실린다 — 드레인이 조용히 틀린다.
 */
class TaskObservationFallbackTest {

    private val failures = mutableListOf<Triple<String, String, String?>>()
    private val sink = FailedObservations { kind, json, reason ->
        failures += Triple(kind, json, reason)
    }

    @Test
    fun `1차가 되면 2차는 안 부른다`() {
        // 언제나 둘 다 쓰면 적재가 멀쩡한데도 폴백 파일이 무한히 자라고,
        // **파일이 있다는 사실이 신호이기를 그친다.**
        val primary = RecordingObservations()

        FallbackTaskObservations(primary, sink).onState(state())

        assertEquals(1, primary.states.size)
        assertEquals(0, failures.size)
    }

    @Test
    fun `1차가 실패하면 2차가 받는다`() {
        FallbackTaskObservations(exploding("DB가 없다"), sink).onState(state())

        assertEquals(1, failures.size)
        assertEquals("state", failures.single().first)
    }

    @Test
    fun `전이도 폴백으로 간다`() {
        FallbackTaskObservations(exploding("DB가 없다"), sink).onEvent(event())

        assertEquals("event", failures.single().first, "종류를 안 남기면 밀어 넣을 때 경로를 모른다")
    }

    @Test
    fun `사유가 함께 남는다`() {
        // **§15.45의 절반이 이것이다.** 적재가 멈추면 워터마크가 늙어 축소가
        // 막히는데, 막힌 이유가 어디에도 없으면 운영자는 원장이 아니라
        // 엉뚱한 것을 뒤진다.
        FallbackTaskObservations(exploding("연결이 끊겼다"), sink).onState(state())

        assertEquals("연결이 끊겼다", failures.single().third)
    }

    @Test
    fun `남긴 것이 적재가 읽는 규약과 같다`() {
        // 다른 규약으로 적으면 밀어 넣는 날 `oneof`·enum이 다르게 읽힌다.
        FallbackTaskObservations(exploding("x"), sink).onEvent(event())

        val json = ObjectMapper().readTree(failures.single().second)
        assertEquals("t1", json.get("taskTransition").get("taskId").asText())
        assertEquals("TASK_STATE_RUNNING", json.get("taskTransition").get("to").asText())
    }

    // ── 파일

    @Test
    fun `파일이 한 줄에 하나씩 쌓인다`() {
        val path = Files.createTempDirectory("picasso-task-fallback").resolve("nested/failed.jsonl")
        val file = FileTaskObservations(path)
        val chain = FallbackTaskObservations(exploding("끊겼다"), file)

        chain.onState(state())
        chain.onEvent(event())

        val lines = Files.readAllLines(path)
        assertEquals(2, lines.size)
        val first = ObjectMapper().readTree(lines[0])
        assertEquals("state", first.get("kind").asText())
        assertEquals("끊겼다", first.get("reason").asText())
        // **내용까지 본다.** 빈 메시지를 적으면 줄은 멀쩡해 보이는데 밀어
        // 넣을 때 아무것도 복구되지 않는다 — 존재만 보는 단언은 그것을
        // 통과시킨다(실측으로 주입이 빠져나갔다).
        assertEquals(
            "t1",
            first.get("message").get("tasks").get(0).get("taskId").asText(),
            lines[0],
        )
        assertEquals("event", ObjectMapper().readTree(lines[1]).get("kind").asText())
    }

    @Test
    fun `적재가 멀쩡하면 파일이 안 생긴다`() {
        // **파일이 존재한다는 사실 자체가 신호다.**
        val path = Files.createTempDirectory("picasso-task-fallback").resolve("failed.jsonl")

        FallbackTaskObservations(RecordingObservations(), FileTaskObservations(path)).onState(state())

        assertTrue(!Files.exists(path), "적재가 됐는데 폴백 파일이 생겼다")
    }

    @Test
    fun `사유가 없어도 줄이 깨지지 않는다`() {
        val path = Files.createTempDirectory("picasso-task-fallback").resolve("failed.jsonl")

        FileTaskObservations(path).onFailure("state", "{}", null)

        val node = ObjectMapper().readTree(Files.readAllLines(path).single())
        assertTrue(node.get("reason").isNull, "사유 없음이 줄을 깨면 안 된다")
    }

    // ── 씨앗

    private class RecordingObservations : TaskObservations {
        val states = mutableListOf<StateMessage>()
        override fun onState(message: StateMessage) { states += message }
        override fun onEvent(event: Event) = Unit
    }

    private fun exploding(reason: String) = object : TaskObservations {
        override fun onState(message: StateMessage) = throw IllegalStateException(reason)
        override fun onEvent(event: Event) = throw IllegalStateException(reason)
    }

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
