package dev.picasso.middleware

import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 잠금 밖 플릿 명령(§15.237) — [IssuingFleetPort] 가 맡기기와 취소를 운반 참조마다 한 차선에서 보내고, 결과를 한 번만, 실제로 보낸 시각과
 * 함께 주는가.
 *
 * 송신은 손으로 돌리는 실행기([Manual])에서 돈다. 언제 보내고 언제 시계가 가는지를 시험이 정한다.
 */
class IssuingFleetPortTest {

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

    /** 맡기기와 취소만 답하는 플릿. [failure] 가 있으면 맡기기가 그것을 던진다. */
    private class Fleet : AmrFleetPort {
        val dispatches = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        var failure: RuntimeException? = null

        override fun dispatch(order: TransportOrder): TransportHandle? {
            dispatches += order.reference
            failure?.let { throw it }
            return TransportHandle("tr-${order.reference}")
        }

        override fun cancel(handle: TransportHandle): Boolean {
            cancels += handle.id
            return true
        }

        override fun find(reference: String): TransportHandle? = error("not used")
        override fun status(handle: TransportHandle): TransportStatus = error("not used")
    }

    private val fleet = Fleet()
    private val manual = Manual()
    private val port = IssuingFleetPort(fleet, now = { clock }, executor = manual)

    private fun dispatch(reference: String = A) = port.issueDispatch(TransportOrder(reference, "HU-1", "OUT-1", "IN-1"))
    private fun cancel(reference: String = A) = port.issueCancel(reference, TransportHandle("tr-$reference"))

    @Test
    fun `맡기기와 취소는 운반마다 한 차선을 쓰고 다른 운반은 막지 않는다`() {
        assertEquals(DispatchIssue.Pending, dispatch())
        assertIs<FleetCancelIssue.Failed>(cancel(), "맡기는 중에 같은 운반에 취소를 함께 냈다")
        assertEquals(DispatchIssue.Pending, dispatch(B), "한 운반의 송신이 다른 운반의 송신을 막았다")
        assertEquals(emptyList(), fleet.dispatches, "부른 쪽 스레드에서 보냈다")

        manual.run()
        assertEquals(TransportHandle("tr-$A"), assertIs<DispatchIssue.Answered>(dispatch()).handle)
        assertEquals(FleetCancelIssue.Pending, cancel(), "결과를 준 뒤에도 차선을 놓지 않았다")
        assertIs<DispatchIssue.NotSent>(dispatch(), "취소가 차선을 쥔 동안 같은 운반을 다시 맡겼다")
        manual.run()
        assertEquals(FleetCancelIssue.Answered(true), cancel())
        assertEquals(FleetCancelIssue.Pending, cancel(), "받아 간 결과를 다시 줬다")
        assertEquals(listOf(A, B), fleet.dispatches)
    }

    @Test
    fun `보내지 않았다는 실패와 답 없는 실패를 가르고 보낸 시각을 준다`() {
        fleet.failure = CommandNotSent("시험: 차단 창")
        dispatch()
        manual.run()
        assertIs<DispatchIssue.NotSent>(dispatch(), "보내지 않은 맡기기를 나갔는지 모르는 것으로 줬다")

        fleet.failure = IllegalStateException("response lost")
        dispatch()
        clock = clock.plusSeconds(7)
        val sentAt = clock
        manual.run()
        clock = clock.plusSeconds(5)
        val doubt = assertIs<DispatchIssue.InDoubt>(dispatch())
        assertEquals(sentAt, doubt.sentAt, "보낸 시각이 아니라 건 시각이나 받아 간 시각을 줬다")
    }

    private companion object {
        const val A = "WT-781#HU-1042"
        const val B = "WT-782#HU-1043"
    }
}
