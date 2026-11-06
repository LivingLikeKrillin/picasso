package dev.picasso.middleware

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 출발(`StartTask`)을 **소비자의 잠금 밖에서** 보내는 [RobotPort](§15.237).
 *
 * 미들웨어는 자체 스레드가 없고 소비자의 잠금 아래에서 돈다. 그 아래에서 [start] 를 동기로 부르면 답하지 않는 기체 하나가 라운드를
 * 기한만큼 늘리고, 그동안 소비자의 다른 일(HTTP 엔드포인트 등)도 같은 잠금을 기다린다. 이 데코레이터는 [issue] 로 받은 출발을 자기
 * 스레드에서 위임 포트의 [start] 로 보내고, 미들웨어는 다음 라운드에 같은 키로 다시 물어 결과를 받는다. 잠금 아래에서는 기다리지 않는다.
 *
 * - **기체마다 차선은 하나다.** 한 출발이 차선을 쥐면 결과를 줄 때까지 같은 기체의 다른 출발은 걸지 않고 [Issue.NotSent] 로 답한다 —
 *   보내지 않았으니 미들웨어가 다음 라운드에 게이트를 다시 지나 낸다. 같은 기체에 명령 둘이 함께 나가 순서가 뒤바뀌지 않게 한다.
 *   답하지 않는 기체는 제 차선만 붙들고 다른 기체의 송신을 늦추지 않는다.
 * - **결과는 한 번만 주고, 줄 때까지 든다.** 받아 간 뒤에 차선을 놓는다. 같은 키로 다시 물으면 새 송신이다.
 * - **보낸 시각은 실제로 보낸 시각이다.** 미들웨어는 그것으로 요청 시각을 적는다 — 미확정 유예와 근거 창이 송신 대기만큼 밀리지 않는다.
 * - **송신의 실패를 가른다.** 위임 포트가 [CommandNotSent] 를 던지면 [Issue.NotSent], 그 밖의 예외는 [Issue.InDoubt] 다.
 * - **취소와 읽기는 그대로 위임한다.** [cancel]·[watch]·[snapshot] 등은 위임 포트로 바로 간다. 취소를 잠금 밖으로 옮기는 것은 다음 조각이다.
 *
 * 위임 포트는 두 스레드에서 불린다 — 이 데코레이터의 송신과 잠금 아래의 나머지 호출. [ClientRobotPort] 는 그것을 견딘다.
 *
 * @param now 보낸 시각을 재는 시계. 미들웨어와 같은 시계여야 한다.
 * @param executor 송신을 돌릴 실행기. 없으면 송신마다 가상 스레드를 쓰고 [close] 가 그것을 내린다.
 */
class IssuingRobotPort(
    private val delegate: RobotPort,
    private val now: () -> Instant = Instant::now,
    executor: Executor? = null,
) : RobotPort by delegate, AutoCloseable {

    private val owned: ExecutorService? = if (executor == null) Executors.newVirtualThreadPerTaskExecutor() else null
    private val executor: Executor = executor ?: checkNotNull(owned)

    private data class Key(val taskId: String, val revision: Int)

    /** 기체 하나의 송신 차선. 모든 칸은 이 객체의 모니터 아래에서 읽고 쓴다. */
    private class Lane {
        /** 차선을 쥔 송신 — 보내는 중이거나 결과를 아직 주지 않았다. 널이면 비었다. */
        var key: Key? = null

        /** 끝났고 아직 주지 않은 결과. */
        var result: Issue? = null
    }

    private val lanes = ConcurrentHashMap<String, Lane>()

    /** 이 기체의 송신이 도는 중인가. 시험이 송신이 끝나기를 기다리는 문이다. */
    internal fun sending(robotId: String): Boolean =
        lanes[robotId]?.let { synchronized(it) { it.key != null && it.result == null } } ?: false

    override fun issue(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): Issue {
        val lane = lanes.computeIfAbsent(robotId) { Lane() }
        val key = Key(taskId, revision)
        synchronized(lane) {
            val held = lane.key
            if (held != null && held != key) {
                return Issue.NotSent("robot=$robotId 의 차선에 다른 출발(${held.taskId} 리비전 ${held.revision})이 있다")
            }
            if (held == key) {
                val done = lane.result ?: return Issue.Pending
                lane.key = null
                lane.result = null
                return done
            }
            lane.key = key
        }
        return try {
            executor.execute { send(robotId, lane, taskId, revision, skillType, parameters) }
            Issue.Pending
        } catch (_: RejectedExecutionException) {
            synchronized(lane) { lane.key = null }
            Issue.NotSent("실행기가 robot=$robotId 의 송신을 받지 않았다")
        }
    }

    /** 송신 한 번. 잠금 밖에서 돈다. 무엇으로 끝나든 결과를 남긴다 — 안 남기면 그 기체의 차선이 영영 비지 않는다. */
    private fun send(robotId: String, lane: Lane, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) {
        var result: Issue? = null
        val sentAt = now()
        try {
            result = try {
                Issue.Answered(delegate.start(robotId, taskId, revision, skillType, parameters), sentAt)
            } catch (e: CommandNotSent) {
                Issue.NotSent(e.message ?: "robot=$robotId 에 보내지 않았다")
            } catch (e: RuntimeException) {
                Issue.InDoubt(e, sentAt)
            }
        } finally {
            val done = result ?: Issue.InDoubt(IllegalStateException("robot=$robotId 의 송신이 결과 없이 끝났다"), sentAt)
            synchronized(lane) { lane.result = done }
        }
    }

    /** 이 데코레이터가 만든 실행기를 내린다. 넘겨받은 실행기는 부른 쪽의 것이라 건드리지 않는다. */
    override fun close() {
        owned?.shutdownNow()
    }
}
