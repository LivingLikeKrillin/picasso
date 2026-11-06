package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 잠금 밖 출발(§15.237) — [IssuingRobotPort] 가 출발을 기체마다 한 차선에서 보내고, 결과를 한 번만, 실제로 보낸 시각과 함께 주는가.
 *
 * 송신은 손으로 돌리는 실행기([Manual])에서 돈다. 언제 보내고 언제 시계가 가는지를 시험이 정한다.
 */
class IssuingRobotPortTest {

    private var clock: Instant = Instant.parse("2026-11-06T00:00:00Z")

    /** 넘겨받은 송신을 쌓아 두었다가 [run] 이 돌린다. */
    private class Manual : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queued += command
        }

        fun run() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** `StartTask` 만 답하는 하위. [failure] 가 있으면 그것을 던진다. */
    private class Robot : RobotPort {
        val starts = mutableListOf<Triple<String, String, Int>>()
        var failure: RuntimeException? = null

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += Triple(robotId, taskId, revision)
            failure?.let { throw it }
            return StartTaskResponse.newBuilder().setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision)).build()
        }

        override fun snapshot(robotId: String): RobotSnapshot? =
            RobotSnapshot(sequence = 0, faults = emptyList(), connection = ConnectionState.CONNECTION_STATE_ONLINE, tasks = emptyMap())

        override fun replay(robotId: String, from: Long): Replay? = null
        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = error("not used")
        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = error("not used")
    }

    private val robot = Robot()
    private val manual = Manual()
    private val port = IssuingRobotPort(robot, now = { clock }, executor = manual)

    private fun issue(robotId: String = R, taskId: String = T, revision: Int = 1) = port.issue(robotId, taskId, revision, "INSPECT", emptyMap())

    @Test
    fun `결과는 한 번만 주고 받아 간 뒤 같은 키는 새 송신이다`() {
        assertEquals(Issue.Pending, issue(), "보내기 전에 결과를 줬다")
        assertEquals(Issue.Pending, issue(), "보내는 중에 결과를 줬다")
        assertEquals(emptyList(), robot.starts, "부른 쪽 스레드에서 보냈다")

        manual.run()
        val answered = assertIs<Issue.Answered>(issue())
        assertEquals(T, answered.response.handle.taskId)
        assertEquals(Issue.Pending, issue(), "받아 간 결과를 다시 줬다")
        manual.run()
        assertEquals(2, robot.starts.size, "받아 간 뒤 같은 키를 새 송신으로 보내지 않았다")
    }

    @Test
    fun `차선을 쥔 동안 같은 기체의 다른 출발은 보내지 않고 다른 기체는 막지 않는다`() {
        assertEquals(Issue.Pending, issue(revision = 1))
        assertIs<Issue.NotSent>(issue(revision = 2), "같은 기체에 명령 둘을 함께 냈다")
        assertEquals(Issue.Pending, issue(robotId = "quad-02"), "한 기체의 송신이 다른 기체의 송신을 막았다")

        manual.run()
        assertEquals(listOf(Triple(R, T, 1), Triple("quad-02", T, 1)), robot.starts)
        assertIs<Issue.Answered>(issue(revision = 1))
        assertEquals(Issue.Pending, issue(revision = 2), "결과를 준 뒤에도 차선을 놓지 않았다")
    }

    @Test
    fun `보내지 않았다는 실패와 답 없는 실패를 가르고 보낸 시각을 준다`() {
        robot.failure = CommandNotSent("시험: 차단 창")
        issue()
        manual.run()
        assertIs<Issue.NotSent>(issue(), "보내지 않은 출발을 나갔는지 모르는 것으로 줬다")

        robot.failure = IllegalStateException("response lost")
        issue()
        clock = clock.plusSeconds(7)
        val sentAt = clock
        manual.run()
        clock = clock.plusSeconds(5)
        val doubt = assertIs<Issue.InDoubt>(issue())
        assertEquals(sentAt, doubt.sentAt, "보낸 시각이 아니라 건 시각이나 받아 간 시각을 줬다")
    }

    private companion object {
        const val R = "quad-01"
        const val T = "PATROL-9#PUMP-01"
    }
}
