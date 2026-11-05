package dev.picasso.uplink.report

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StateMessage
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 적재를 발행한 스레드 밖에서 민다(§15.210).
 *
 * ## 왜 큐인가
 *
 * 발행은 발신자의 잠금 아래에서 온다 — `mimic` 은 엔진 잠금, `adapter-host` 는 호스트 잠금이다. 적재가 그 자리에서
 * 레지스트리로 동기 HTTP 를 보내면 레지스트리가 느리거나 죽은 동안 같은 잠금을 기다리는 RPC 와 시간 흘리기가 그 제한
 * (연결 2초, 요청 3초)만큼 선다. 여기서는 발행한 스레드가 큐에 넣기만 하고, 보내는 것은 작업 스레드 하나다.
 *
 * **작업 스레드는 하나다.** 태스크 관측의 순서가 레지스트리에 도착하는 순서이고, 둘이 나눠 보내면 전이 이벤트가
 * 뒤바뀐다.
 *
 * ## 큐가 차면
 *
 * 태스크 관측은 **사실**이라 늦게 가도 참이다. 큐가 차면 [overflow] 로 넘기고(폴백 파일, `FileTaskObservations`),
 * 그것이 없으면 버린다 — 폴백 없는 모드가 실패를 삼키는 것과 같다. 넘기는 것은 발행한 스레드에서 하며 로컬 파일
 * 쓰기다.
 *
 * 생존은 **시점이 곧 내용**이고 레지스트리는 받은 시각으로 적는다(`HttpLiveness` 의 KDoc). 큐에서 오래 머문 생존을
 * 보내면 그만큼 늦은 시각의 생존으로 읽혀 죽은 기체가 살아 있는 것처럼 보인다. 그래서 꺼낼 때 [livenessMaxAge] 보다
 * 오래 머물렀으면 보내지 않고, 큐가 차도 넘기지 않고 버린다. 안 보낸 생존은 워터마크를 늙게 하고 §9.3 의 조회가
 * 축소를 막는 쪽으로 틀린다(§15.41).
 *
 * ## 끝낼 때
 *
 * [close] 가 큐가 빌 때까지 기다린다(최대 [CLOSE_TIMEOUT]). 그 안에 못 비우거나 프로세스가 강제로 죽으면 큐에 남은
 * 관측은 레지스트리에도 폴백에도 없다 — 동기로 보낼 때는 없던 손실이다.
 */
class QueuedIngest(
    private val tasks: TaskObservations,
    private val liveness: LivenessObservations,
    /** 큐가 찼을 때 태스크 관측을 넘길 곳. 없으면 버린다. */
    private val overflow: FailedObservations? = null,
    capacity: Int = CAPACITY,
    private val livenessMaxAge: Duration = LIVENESS_MAX_AGE,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {

    private sealed interface Item {
        data class State(val message: StateMessage) : Item
        data class Transition(val event: Event) : Item
        data class Live(
            val header: MessageHeader,
            val state: ConnectionState,
            val software: String?,
            val siteNames: SiteNameSummary?,
            val queuedAt: Long,
        ) : Item
    }

    private val queue = ArrayBlockingQueue<Item>(capacity)

    /** 넣었으나 아직 다 보내지 않은 것의 수. 꺼낸 뒤 보내는 중인 것도 든다 — [drain] 이 이것을 본다. */
    private val pending = AtomicInteger()

    @Volatile private var closed = false

    private val staleLiveness = AtomicLong()
    private val droppedLiveness = AtomicLong()
    private val overflowedTasks = AtomicLong()

    private val worker = Thread(::loop, "picasso-ingest").apply {
        isDaemon = true
        start()
    }

    /** 발행한 스레드에서 부르는 태스크 적재. 넣기만 한다. */
    val taskSink: TaskObservations = object : TaskObservations {
        override fun onState(message: StateMessage) = offerTask(Item.State(message), "state", message)
        override fun onEvent(event: Event) = offerTask(Item.Transition(event), "event", event)
    }

    /** 발행한 스레드에서 부르는 생존 보고. 넣기만 하며, 차 있으면 버린다. */
    val livenessSink: LivenessObservations = object : LivenessObservations {
        override fun onConnection(
            header: MessageHeader,
            state: ConnectionState,
            software: String?,
            siteNames: SiteNameSummary?,
        ) {
            if (!offer(Item.Live(header, state, software, siteNames, nanoTime()))) droppedLiveness.incrementAndGet()
        }
    }

    /** 꺼낼 때 너무 늦어 안 보낸 생존의 수. */
    val staleLivenessCount: Long get() = staleLiveness.get()

    /** 큐가 차서 버린 생존의 수. */
    val droppedLivenessCount: Long get() = droppedLiveness.get()

    /** 큐가 차서(또는 닫힌 뒤라서) [overflow] 로 넘긴(없으면 버린) 태스크 관측의 수. */
    val overflowedTaskCount: Long get() = overflowedTasks.get()

    /** 닫힌 뒤에는 넣지 않는다 — 꺼낼 스레드가 없다. */
    private fun offer(item: Item): Boolean {
        if (closed) return false
        pending.incrementAndGet()
        if (queue.offer(item)) return true
        pending.decrementAndGet()
        return false
    }

    private fun offerTask(item: Item, kind: String, message: Message) {
        if (offer(item)) return
        overflowedTasks.incrementAndGet()
        overflow?.let { sink -> runCatching { sink.onFailure(kind, printer.print(message), QUEUE_FULL) } }
    }

    private fun loop() {
        while (true) {
            val item = try {
                queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            if (item == null) {
                if (closed) return
                continue
            }
            try {
                send(item)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun send(item: Item) {
        when (item) {
            is Item.State -> runCatching { tasks.onState(item.message) }
            is Item.Transition -> runCatching { tasks.onEvent(item.event) }
            is Item.Live -> {
                if (nanoTime() - item.queuedAt > livenessMaxAge.toNanos()) {
                    staleLiveness.incrementAndGet()
                    return
                }
                runCatching { liveness.onConnection(item.header, item.state, item.software, item.siteNames) }
            }
        }
    }

    /**
     * 큐가 비고 보내는 중인 것이 없을 때까지 기다린다.
     *
     * @return 그 안에 비웠으면 참.
     */
    fun drain(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(DRAIN_STEP_MILLIS)
        }
        return true
    }

    /** 큐를 비우고([CLOSE_TIMEOUT]) 작업 스레드를 세운다. 닫힌 뒤 넣는 것은 큐가 찬 것과 같이 다룬다. 두 번 불러도 된다. */
    override fun close() {
        if (closed) return
        drain(CLOSE_TIMEOUT)
        closed = true
        worker.join(POLL_MILLIS * 4)
    }

    companion object {
        /** 큐에 둘 수 있는 관측의 수. */
        const val CAPACITY = 4096

        /**
         * 생존이 큐에 머물러도 되는 시간. 적재 요청 하나가 걸릴 수 있는 최대(연결 2초 + 요청 3초)다 — 레지스트리가 제때
         * 답하면 이보다 오래 머물 일이 없다.
         */
        val LIVENESS_MAX_AGE: Duration = Duration.ofSeconds(5)

        /** [close] 가 큐를 비우며 기다리는 최대 시간. */
        val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** 큐가 차서 폴백으로 넘길 때의 사유. */
        const val QUEUE_FULL = "적재 큐가 찼다"

        private const val POLL_MILLIS = 50L
        private const val DRAIN_STEP_MILLIS = 5L
        private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    }
}
