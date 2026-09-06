package dev.picasso.client

import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 완료 기준 3의 소비자 쪽 — **결손은 감지, 중복은 무시, 역전은 재정렬.**
 *
 * 전송 없이 순수하게 본다. 실제 발행 축 위의 증명은 `TransportFaultTest`가
 * 하고, 여기서는 **규칙 자체**를 본다.
 */
class EventConsumerTest {

    private val start: Instant = Instant.parse("2026-09-06T00:00:00Z")

    private fun item(
        sequence: Long,
        session: String = SESSION,
        eventId: String = "e$sequence",
        payload: Boolean = true,
        at: Instant = start,
    ) = Received(
        sessionId = session,
        sequence = sequence,
        eventId = eventId,
        event = if (!payload) {
            null
        } else {
            Event.newBuilder()
                .setHeader(
                    MessageHeader.newBuilder()
                        .setSessionId(session).setSequence(sequence).setEventId(eventId),
                )
                .build()
        },
        at = at,
    )

    private fun EventConsumer.feed(vararg items: Received) = items.forEach { accept(it) }

    private fun EventConsumer.sequences() = events.map { it.header.sequence }

    // ── 정상

    @Test
    fun `순서대로 오면 그대로 접는다`() {
        val consumer = EventConsumer()
        consumer.feed(item(0), item(1), item(2))

        assertEquals(listOf(0L, 1L, 2L), consumer.sequences())
        assertEquals(emptyList(), consumer.missing)
        assertEquals(0, consumer.duplicatesIgnored)
    }

    @Test
    fun `payload 없는 번호도 자리를 차지한다`() {
        // **상태·연결 메시지가 같은 `sequence` 축을 쓴다**(§5.5의 발행 열).
        // 그것을 빼면 소비자가 그 자리를 결손으로 오탐한다.
        val consumer = EventConsumer()
        consumer.feed(item(0), item(1, payload = false), item(2))

        assertEquals(listOf(0L, 2L), consumer.sequences(), "접힌 것은 이벤트 둘뿐이다")
        assertEquals(emptyList(), consumer.missing, "상태 메시지를 결손으로 봤다")
    }

    // ── 중복

    @Test
    fun `중복을 무시한다`() {
        val consumer = EventConsumer()
        consumer.feed(item(0), item(1), item(1), item(1), item(2))

        assertEquals(listOf(0L, 1L, 2L), consumer.sequences())
        assertEquals(2, consumer.duplicatesIgnored, "중복이 안 걸러졌다")
        assertEquals(emptyList(), consumer.missing)
    }

    @Test
    fun `중복 판정은 event_id로 한다`() {
        // 번호로만 거르면 재생(`ReplayEvents`)이 준 것을 새것으로 오해하거나
        // 그 반대가 된다 — §4.8이 `event_id`를 멱등 키로 둔 이유다.
        val consumer = EventConsumer()
        consumer.feed(item(0, eventId = "a"), item(1, eventId = "b"))
        // 같은 번호를 다른 event_id 로 다시 준다 — 이것은 중복이 아니라
        // 이미 지나간 번호이므로 조용히 버려지되 중복으로 세지 않는다.
        consumer.feed(item(1, eventId = "c"))

        assertEquals(0, consumer.duplicatesIgnored, "다른 event_id 를 중복으로 셌다")
        assertEquals(listOf(0L, 1L), consumer.sequences())
    }

    // ── 역전

    @Test
    fun `창 안의 역전을 재정렬한다`() {
        val consumer = EventConsumer()
        consumer.feed(item(0), item(2), item(1), item(3))

        assertEquals(listOf(0L, 1L, 2L, 3L), consumer.sequences(), "재정렬을 안 했다")
        assertEquals(emptyList(), consumer.missing, "역전을 결손으로 봤다")
    }

    @Test
    fun `멀리 뒤집힌 것도 창 안이면 흡수한다`() {
        val consumer = EventConsumer(windowEvents = 8)
        consumer.feed(item(0), item(5), item(4), item(3), item(2), item(1))

        assertEquals((0L..5L).toList(), consumer.sequences())
        assertEquals(emptyList(), consumer.missing)
    }

    @Test
    fun `재정렬 뒤 최종 상태가 정상 순서와 같다`() {
        // **이것이 완료 기준 3이다.** 순서만 맞추고 내용이 달라지면 뜻이 없다.
        val ordered = EventConsumer()
        ordered.feed(*(0L..7L).map { item(it) }.toTypedArray())

        val shuffled = EventConsumer()
        shuffled.feed(item(0), item(3), item(1), item(2), item(5), item(4), item(7), item(6))

        assertEquals(ordered.events, shuffled.events, "역전을 흡수한 결과가 다르다")
        assertEquals(emptyList(), shuffled.missing)
    }

    // ── 결손

    @Test
    fun `창을 넘긴 구멍을 결손으로 확정한다`() {
        val consumer = EventConsumer(windowEvents = 3)
        consumer.feed(item(0), item(2), item(3), item(4))

        assertEquals(listOf(1L), consumer.missing, "결손을 확정 안 했다")
        assertEquals(listOf(0L, 2L, 3L, 4L), consumer.sequences())
    }

    @Test
    fun `창 밖의 역전은 결손으로 본다`() {
        // 창이 무한이면 메모리가 새고 결손을 영영 확정하지 못한다 —
        // 소비자는 조용히 멈춘 것처럼 보인다.
        val consumer = EventConsumer(windowEvents = 2)
        consumer.feed(item(0), item(3), item(4))
        assertEquals(listOf(1L, 2L), consumer.missing)

        // 늦게 온 것은 이미 지나간 번호다. 다시 접지 않는다.
        consumer.feed(item(1))
        assertEquals(listOf(0L, 3L, 4L), consumer.sequences(), "확정한 뒤에 다시 접었다")
    }

    @Test
    fun `시간만으로도 결손이 확정된다`() {
        // 조용한 구간에서 개수 창이 안 차면 영영 확정이 안 된다.
        val consumer = EventConsumer(windowEvents = 100, windowDuration = Duration.ofSeconds(2))
        consumer.feed(item(0), item(2, at = start))
        assertEquals(emptyList(), consumer.missing, "아직 창 안인데 확정했다")

        consumer.tick(start.plusSeconds(3))
        assertEquals(listOf(1L), consumer.missing, "시간이 지나도 확정 안 했다")
        assertEquals(listOf(0L, 2L), consumer.sequences())
    }

    @Test
    fun `구멍을 조용히 메우지 않는다`() {
        // **감지가 곧 복구인 척하면 안 된다.** 잃은 것은 잃은 것이고,
        // 소비자가 할 수 있는 것은 그 사실을 아는 것뿐이다.
        // 창이 2이므로 뒤따르는 것이 둘은 와야 확정된다 — 하나만 주고
        // 확정을 기대하면 시험이 구현보다 성급한 것이다(실측).
        val consumer = EventConsumer(windowEvents = 2)
        consumer.feed(item(0), item(3), item(4))

        assertTrue(consumer.missing.isNotEmpty(), "구멍을 못 봤다")
        assertEquals(
            listOf(0L, 3L, 4L), consumer.sequences(),
            "없던 이벤트를 만들어 냈다 — 소비자에게 없던 사실을 있다고 말한다",
        )
    }

    // ── 세션

    @Test
    fun `세션이 바뀌면 처음부터 다시 센다`() {
        // §4.8 — 재기동하면 번호가 0부터 다시 시작한다. 이전 기대값을 들고
        // 있으면 전부 결손으로 오탐한다.
        val consumer = EventConsumer()
        consumer.feed(item(0), item(1), item(2))

        consumer.feed(item(0, session = "s2"), item(1, session = "s2"))

        assertEquals(1, consumer.resets)
        assertEquals(emptyList(), consumer.missing, "재기동을 결손으로 봤다")
        assertEquals(listOf(0L, 1L, 2L, 0L, 1L), consumer.sequences())
    }

    @Test
    fun `세션이 바뀌면 중복 키도 잊는다`() {
        // 안 잊으면 새 세션의 첫 이벤트들이 옛 키와 부딪혀 통째로 버려진다.
        val consumer = EventConsumer()
        consumer.feed(item(0, eventId = "x"))
        consumer.feed(item(0, session = "s2", eventId = "x"))

        assertEquals(0, consumer.duplicatesIgnored, "세션이 다른데 중복으로 봤다")
        assertEquals(2, consumer.events.size)
    }

    // ── 설정

    @Test
    fun `창이 0이면 만들 수 없다`() {
        // 창이 0이면 역전을 절대 못 흡수하고 전부 결손이 된다.
        assertFailsWith<IllegalArgumentException> { EventConsumer(windowEvents = 0) }
        assertFailsWith<IllegalArgumentException> { EventConsumer(windowDuration = Duration.ZERO) }
    }

    @Test
    fun `창 크기가 실제로 판정을 바꾼다`() {
        // 리터럴 8을 쓰면서 인자를 무시하는 구현을 잡는다.
        val narrow = EventConsumer(windowEvents = 1)
        narrow.feed(item(0), item(2), item(1))
        assertEquals(listOf(1L), narrow.missing, "창 1인데 흡수했다")

        val wide = EventConsumer(windowEvents = 8)
        wide.feed(item(0), item(2), item(1))
        assertEquals(emptyList(), wide.missing, "창 8인데 결손으로 봤다")
    }

    private companion object {
        const val SESSION = "s1"
    }
}
