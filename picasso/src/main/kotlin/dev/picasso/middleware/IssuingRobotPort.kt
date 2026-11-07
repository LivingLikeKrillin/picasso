package dev.picasso.middleware

import dev.picasso.contracts.v1.TaskHandle
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 명령(`StartTask`·`CancelTask`)을 **소비자의 잠금 밖에서** 보내는 [RobotPort](§15.237).
 *
 * 미들웨어는 자체 스레드가 없고 소비자의 잠금 아래에서 돈다. 그 아래에서 [start]·[cancel] 을 동기로 부르면 답하지 않는 기체 하나가
 * 라운드를 기한만큼 늘리고, 그동안 소비자의 다른 일(HTTP 엔드포인트 등)도 같은 잠금을 기다린다. 이 데코레이터는 [issue]·[issueCancel] 로
 * 받은 명령을 자기 스레드에서 위임 포트의 [start]·[cancel] 로 보내고, 미들웨어는 다음 라운드에 같은 키로 다시 물어 결과를 받는다.
 * 잠금 아래에서는 기다리지 않는다.
 *
 * - **기체마다 차선은 하나다.** 출발과 취소가 같은 차선을 쓴다. 한 명령이 차선을 쥐면 결과를 줄 때까지 같은 기체의 다른 명령은 걸지
 *   않는다 — 출발은 [Issue.NotSent], 취소는 [CancelIssue.Failed] 로 답한다. 보내지 않았으니 미들웨어가 다음 라운드에 다시 낸다. 같은
 *   기체에 명령 둘이 함께 나가 순서가 뒤바뀌지 않게 한다. 답하지 않는 기체는 제 차선만 붙들고 다른 기체의 송신을 늦추지 않는다.
 * - **결과는 한 번만 주고, 줄 때까지 든다.** 받아 간 뒤에 차선을 놓는다. 같은 키로 다시 물으면 새 송신이다. 받아 가지 않으면 차선이
 *   풀리지 않는다 — 미들웨어 인스턴스를 버리고 이 포트를 계속 쓰면 그 기체의 차선이 막힌다. 미들웨어를 새로 만들면 포트도 새로 만든다.
 * - **보낸 시각은 실제로 보낸 시각이다.** 미들웨어는 그것으로 요청 시각을 적는다 — 미확정 유예와 근거 창이 송신 대기만큼 밀리지 않는다.
 * - **출발의 실패를 가른다.** 위임 포트가 [CommandNotSent] 를 던지면 [Issue.NotSent], 그 밖의 예외는 [Issue.InDoubt] 다. 취소는 가르지
 *   않는다 — 두 번 나가도 물리 작업이 둘이 되지 않아 모두 [CancelIssue.Failed] 다.
 * - **읽기는 그대로 위임한다.** [watch]·[snapshot] 등은 위임 포트로 바로 간다.
 *
 * 위임 포트는 두 스레드에서 불린다 — 이 데코레이터의 송신과 잠금 아래의 나머지 호출. [ClientRobotPort] 는 그것을 견딘다.
 *
 * @param now 보낸 시각을 재는 시계. 미들웨어와 같은 시계여야 한다.
 * @param executor 송신을 돌릴 실행기. 없으면 송신마다 가상 스레드를 쓰고 [close] 가 그것을 내린다.
 */
class IssuingRobotPort(
    private val delegate: RobotPort,
    now: () -> Instant = Instant::now,
    executor: Executor? = null,
) : RobotPort by delegate, AutoCloseable {

    private val owned: ExecutorService? = if (executor == null) Executors.newVirtualThreadPerTaskExecutor() else null
    private val lanes = CommandLanes(now, executor ?: checkNotNull(owned))

    private enum class Kind { START, CANCEL }

    private data class Key(val kind: Kind, val taskId: String, val revision: Int) {
        override fun toString() = "$kind $taskId 리비전 $revision"
    }

    /** 이 기체의 송신이 도는 중인가. 시험이 송신이 끝나기를 기다리는 문이다. */
    internal fun sending(robotId: String): Boolean = lanes.sending(robotId)

    override fun issue(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): Issue = lanes.claim(
        robotId, Key(Kind.START, taskId, revision), Issue.Pending,
        busy = { Issue.NotSent("robot=$robotId 의 차선에 다른 명령($it)이 있다") },
        rejected = { Issue.NotSent("실행기가 robot=$robotId 의 송신을 받지 않았다") },
        lost = { Issue.InDoubt(IllegalStateException("robot=$robotId 의 송신이 결과 없이 끝났다"), it) },
    ) { sentAt ->
        try {
            Issue.Answered(delegate.start(robotId, taskId, revision, skillType, parameters), sentAt)
        } catch (e: CommandNotSent) {
            Issue.NotSent(e.message ?: "robot=$robotId 에 보내지 않았다")
        } catch (e: RuntimeException) {
            Issue.InDoubt(e, sentAt)
        }
    }

    override fun issueCancel(robotId: String, handle: TaskHandle): CancelIssue = lanes.claim(
        robotId, Key(Kind.CANCEL, handle.taskId, handle.revision), CancelIssue.Pending,
        busy = { CancelIssue.Failed(CommandNotSent("robot=$robotId 의 차선에 다른 명령($it)이 있다")) },
        rejected = { CancelIssue.Failed(CommandNotSent("실행기가 robot=$robotId 의 송신을 받지 않았다")) },
        lost = { CancelIssue.Failed(IllegalStateException("robot=$robotId 의 취소 송신이 결과 없이 끝났다")) },
    ) {
        try {
            CancelIssue.Answered(delegate.cancel(robotId, handle))
        } catch (e: RuntimeException) {
            CancelIssue.Failed(e)
        }
    }

    /** 이 데코레이터가 만든 실행기를 내린다. 넘겨받은 실행기는 부른 쪽의 것이라 건드리지 않는다. */
    override fun close() {
        owned?.shutdownNow()
    }
}
