package dev.picasso.middleware

import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 플릿 명령(맡기기·취소)을 **소비자의 잠금 밖에서** 보내는 [AmrFleetPort](§15.237). [IssuingRobotPort] 의 플릿 쪽이다.
 *
 * 받은 명령을 자기 스레드에서 위임 포트의 [dispatch]·[cancel] 로 보내고, 미들웨어는 다음 라운드에 같은 키로 다시 물어 결과를 받는다.
 * 차선 규칙은 [CommandLanes] 를 따른다.
 *
 * - **운반 참조마다 차선은 하나다.** 같은 운반의 맡기기와 취소가 같은 차선을 쓴다. 한 명령이 차선을 쥐면 결과를 줄 때까지 같은 운반의
 *   다른 명령은 걸지 않는다 — 맡기기는 [DispatchIssue.NotSent], 취소는 [FleetCancelIssue.Failed] 로 답한다. 답하지 않는 운반 하나가 다른
 *   운반의 명령을 늦추지 않는다.
 * - **맡기기의 실패를 가른다.** 위임 포트가 [CommandNotSent] 를 던지면 [DispatchIssue.NotSent], 그 밖의 예외는 [DispatchIssue.InDoubt]
 *   다. 취소는 가르지 않는다 — 모두 [FleetCancelIssue.Failed] 다.
 * - **읽기는 그대로 위임한다.** [status]·[find] 는 위임 포트로 바로 간다.
 * - 결과를 받아 가지 않으면 그 운반의 차선이 풀리지 않는다 — 미들웨어를 새로 만들면 포트도 새로 만든다.
 *
 * @param now 보낸 시각을 재는 시계. 미들웨어와 같은 시계여야 한다.
 * @param executor 송신을 돌릴 실행기. 없으면 송신마다 가상 스레드를 쓰고 [close] 가 그것을 내린다.
 */
class IssuingFleetPort(
    private val delegate: AmrFleetPort,
    now: () -> Instant = Instant::now,
    executor: Executor? = null,
) : AmrFleetPort by delegate, AutoCloseable {

    private val owned: ExecutorService? = if (executor == null) Executors.newVirtualThreadPerTaskExecutor() else null
    private val lanes = CommandLanes(now, executor ?: checkNotNull(owned))

    private data class Dispatch(val reference: String) {
        override fun toString() = "DISPATCH $reference"
    }

    private data class Cancel(val reference: String, val handle: TransportHandle) {
        override fun toString() = "CANCEL $reference(${handle.id})"
    }

    /** 이 운반의 송신이 도는 중인가. 시험이 송신이 끝나기를 기다리는 문이다. */
    internal fun sending(reference: String): Boolean = lanes.sending(reference)

    override fun issueDispatch(order: TransportOrder): DispatchIssue = lanes.claim(
        order.reference, Dispatch(order.reference), DispatchIssue.Pending,
        busy = { DispatchIssue.NotSent("운반 ${order.reference} 의 차선에 다른 명령($it)이 있다") },
        rejected = { DispatchIssue.NotSent("실행기가 운반 ${order.reference} 의 송신을 받지 않았다") },
        lost = { DispatchIssue.InDoubt(IllegalStateException("운반 ${order.reference} 의 송신이 결과 없이 끝났다"), it) },
    ) { sentAt ->
        try {
            DispatchIssue.Answered(delegate.dispatch(order), sentAt)
        } catch (e: CommandNotSent) {
            DispatchIssue.NotSent(e.message ?: "운반 ${order.reference} 을 보내지 않았다")
        } catch (e: RuntimeException) {
            DispatchIssue.InDoubt(e, sentAt)
        }
    }

    override fun issueCancel(reference: String, handle: TransportHandle): FleetCancelIssue = lanes.claim(
        reference, Cancel(reference, handle), FleetCancelIssue.Pending,
        busy = { FleetCancelIssue.Failed(CommandNotSent("운반 $reference 의 차선에 다른 명령($it)이 있다")) },
        rejected = { FleetCancelIssue.Failed(CommandNotSent("실행기가 운반 $reference 의 송신을 받지 않았다")) },
        lost = { FleetCancelIssue.Failed(IllegalStateException("운반 $reference 의 취소 송신이 결과 없이 끝났다")) },
    ) {
        try {
            FleetCancelIssue.Answered(delegate.cancel(handle))
        } catch (e: RuntimeException) {
            FleetCancelIssue.Failed(e)
        }
    }

    /** 이 데코레이터가 만든 실행기를 내린다. 넘겨받은 실행기는 부른 쪽의 것이라 건드리지 않는다. */
    override fun close() {
        owned?.shutdownNow()
    }
}
