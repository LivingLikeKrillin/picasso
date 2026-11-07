package dev.picasso.middleware

import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 잠금 밖 플릿 읽기(§15.237) — [PrefetchingFleetPort] 가 운반 상태와 찾기를 읽을 것마다 하나씩 자기 실행기에서 읽고, 다 읽은 값을 한 번만,
 * 실제로 보낸·받은 시각과 함께 주는가. 답하지 않는 읽기와 낡은 값, 받아 가지 않은 자리를 어떻게 다루는가.
 *
 * 읽기는 손으로 돌리는 실행기([Manual])에서 돈다. 언제 읽고 언제 시계가 가는지를 시험이 정한다.
 */
class PrefetchingFleetPortTest {

    private var clock: Instant = Instant.parse("2026-11-07T00:00:00Z")

    /** 넘겨받은 읽기를 쌓아 두었다가 [run] 이 돌린다. */
    private class Manual : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queued += command
        }

        fun run() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** 상태와 찾기만 답하는 플릿. 읽은 횟수를 세고 [state] 를 답한다. [failure] 가 있으면 상태 읽기가 그것을 던진다. */
    private inner class Fleet : AmrFleetPort {
        val statusReads = mutableListOf<String>()
        val finds = mutableListOf<String>()
        var state = TransportState.ACCEPTED
        var failure: RuntimeException? = null

        override fun dispatch(order: TransportOrder): TransportHandle? = error("not used")
        override fun cancel(handle: TransportHandle): Boolean = error("not used")

        override fun find(reference: String): TransportHandle? {
            finds += reference
            return TransportHandle("tr-$reference")
        }

        override fun status(handle: TransportHandle): TransportStatus {
            statusReads += handle.id
            clock = clock.plusSeconds(1)
            failure?.let { throw it }
            return TransportStatus(state, null, holding = false)
        }
    }

    private val fleet = Fleet()
    private val manual = Manual()
    private val port = PrefetchingFleetPort(fleet, now = { clock }, executor = manual)

    @Test
    fun `운반 상태는 잠금 밖에서 읽고 다 읽은 값을 한 번만 준 뒤 다음 읽기를 건다`() {
        assertEquals(FleetRead.Pending, port.prefetchedStatus(A), "읽은 적이 없는데 값을 줬다")
        assertEquals(emptyList(), fleet.statusReads, "부른 쪽 스레드에서 읽었다")

        val sentAt = clock
        manual.run()
        clock = clock.plusSeconds(1)
        val read = assertIs<FleetRead.Read<TransportStatus>>(port.prefetchedStatus(A))
        assertEquals(TransportState.ACCEPTED, read.value.state)
        assertEquals(sentAt, read.sentAt, "보낸 시각이 아니라 건 시각이나 받아 간 시각을 줬다")
        assertEquals(sentAt.plusSeconds(1), read.receivedAt, "받은 시각이 아니라 받아 간 시각을 줬다")

        assertEquals(1, manual.queued.size, "값을 준 뒤 다음 읽기를 걸지 않았다")
        assertEquals(FleetRead.Pending, port.prefetchedStatus(A), "받아 간 값을 다시 줬다")
        fleet.state = TransportState.DELIVERED
        manual.run()
        assertEquals(TransportState.DELIVERED, assertIs<FleetRead.Read<TransportStatus>>(port.prefetchedStatus(A)).value.state)
        assertEquals(listOf(A.id, A.id), fleet.statusReads, "값을 준 뒤 다음 읽기를 걸지 않았거나 읽는 중에 또 걸었다")
    }

    @Test
    fun `찾기와 상태는 따로 읽고 한 운반의 읽기가 다른 운반을 막지 않는다`() {
        assertEquals(FleetRead.Pending, port.prefetchedStatus(A))
        assertEquals(FleetRead.Pending, port.prefetchedStatus(B), "한 운반의 읽기가 다른 운반의 읽기를 막았다")
        assertEquals(FleetRead.Pending, port.prefetchedFind(REFERENCE), "상태 읽기가 찾기를 막았다")
        manual.run()

        assertEquals(TransportHandle("tr-$REFERENCE"), assertIs<FleetRead.Read<TransportHandle?>>(port.prefetchedFind(REFERENCE)).value)
        assertIs<FleetRead.Read<TransportStatus>>(port.prefetchedStatus(B))
        assertEquals(listOf(A.id, B.id), fleet.statusReads)
        assertEquals(listOf(REFERENCE), fleet.finds)
    }

    @Test
    fun `답하지 않는 읽기는 기한이 지나면 답 없음으로 주고 받아 가지 않아 낡은 값은 버린다`() {
        port.prefetchedStatus(A)
        clock = clock.plus(port.maxAge).plusSeconds(1)
        assertEquals(FleetRead.Overdue(clock.minus(port.maxAge).minusSeconds(1)), port.prefetchedStatus(A), "기한 넘게 답 없는 읽기를 읽는 중으로 줬다")

        manual.run()
        clock = clock.plus(port.maxAge).plusSeconds(1)
        assertEquals(FleetRead.Pending, port.prefetchedStatus(A), "받은 지 기한이 지난 값을 줬다")
        manual.run()
        assertIs<FleetRead.Read<TransportStatus>>(port.prefetchedStatus(A), "낡은 값을 버린 뒤 다시 읽지 않았다")

        fleet.failure = IllegalStateException("시험: 답 없음")
        manual.run()
        assertIs<FleetRead.Failed>(port.prefetchedStatus(A), "던진 읽기를 실패로 주지 않았다")
    }

    @Test
    fun `읽기가 돌지 않고 받아 가지 않은 자리는 기한이 지나면 다음에 물을 때 지운다`() {
        port.prefetchedStatus(A)
        port.prefetchedStatus(B)
        manual.run()
        assertEquals(2, port.slotCount())

        clock = clock.plus(port.maxAge).plusSeconds(1)
        port.prefetchedStatus(B)
        assertEquals(1, port.slotCount(), "끝난 운반의 자리를 지우지 않았다")
        port.prefetchedStatus(A)
        assertEquals(2, port.slotCount(), "지운 자리를 다시 물었는데 읽기를 걸지 않았다")
    }

    private companion object {
        val A = TransportHandle("tr-a")
        val B = TransportHandle("tr-b")
        const val REFERENCE = "WT-781#HU-1042"
    }
}
