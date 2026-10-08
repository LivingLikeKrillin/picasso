package dev.picasso.middleware

import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ClientRobotPort] 가 기한으로 닫힌 `WatchTask` 스트림을 이어 붙인다.
 *
 * 스트림에는 `PicassoClient` 의 기한이 걸린다(기본 10초). 운영 호스트는 실제 시간으로 돌고 스킬은 그보다
 * 길다(`pick_place` 45초). 이어 붙이지 않으면 종료가 오기 전에 스트림이 닫혀 미들웨어가 종료를 영영 못 본다.
 * 시험은 기한을 1초로 주고 실제로 1초 넘게 기다려 스트림을 닫은 뒤 가상 시계로 태스크를 끝낸다.
 */
class StreamResumeTest {

    private val profile = Path.of("..", "profile", "fixtures", "no-pause.json").normalize()

    private val parameters = mapOf("object_id" to "box-7", "destination" to "dock-3")

    /** 태스크를 출발시키고 첫 갱신을 받은 뒤, 기한이 지나 스트림이 닫힐 때까지 기다린다. */
    private fun Harness.startAndOutliveDeadline(port: ClientRobotPort): TaskHandle {
        val started = port.start(ROBOT, TASK, 1, "pick_place", parameters)
        assertTrue(started.hasHandle(), "태스크 시작이 거부됐다: ${started.rejection}")
        advance(Duration.ofSeconds(1))
        assertTrue(port.watch(ROBOT, started.handle).isNotEmpty(), "첫 스트림이 아무것도 못 받았다")
        Thread.sleep(DEADLINE_MS + 700)
        return started.handle
    }

    private fun Harness.watchRequests() =
        recorder.requests.count { (_, message) -> message is WatchTaskRequest }

    @Test
    fun `기한으로 닫힌 스트림 뒤의 종료를 다음 watch 가 돌려준다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            val port = ClientRobotPort(harness.client(deadlineSeconds = DEADLINE_MS / 1000))
            val handle = harness.startAndOutliveDeadline(port)

            harness.advance(Duration.ofSeconds(60))
            val updates = port.watch(ROBOT, handle)

            assertEquals(TaskState.TASK_STATE_SUCCEEDED, updates.last().state, "종료를 못 봤다: ${updates.map { it.state }}")
        }
    }

    @Test
    fun `이어 붙인 목록의 갱신 번호는 늘기만 한다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            val port = ClientRobotPort(harness.client(deadlineSeconds = DEADLINE_MS / 1000))
            val handle = harness.startAndOutliveDeadline(port)

            harness.advance(Duration.ofSeconds(60))
            val indices = port.watch(ROBOT, handle).map { it.header.updateIndex }

            assertTrue(indices.size >= 2, "이어 붙인 것이 없다: $indices")
            assertEquals(indices.sorted().distinct(), indices, "갱신 번호가 겹치거나 뒤로 갔다: $indices")
        }
    }

    @Test
    fun `종료를 본 뒤에는 다시 붙지 않는다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            val port = ClientRobotPort(harness.client(deadlineSeconds = DEADLINE_MS / 1000))
            val handle = harness.startAndOutliveDeadline(port)
            harness.advance(Duration.ofSeconds(60))
            assertEquals(TaskState.TASK_STATE_SUCCEEDED, port.watch(ROBOT, handle).last().state)
            val before = harness.watchRequests()

            Thread.sleep(DEADLINE_MS + 700)
            repeat(3) { port.watch(ROBOT, handle) }

            assertEquals(before, harness.watchRequests(), "종료 뒤에 스트림을 또 열었다")
        }
    }

    @Test
    fun `여러 스레드가 동시에 넣어도 팔로워는 하나도 잃지 않는다`() {
        val follower = TaskFollower()
        val threads = 8
        val each = 5_000
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        repeat(threads) {
            pool.execute {
                go.await()
                repeat(each) { i -> follower.onNext(update(i.toLong())) }
            }
        }
        go.countDown()
        while (!pool.awaitTermination(1, TimeUnit.MILLISECONDS)) {
            follower.updates
            if (pool.isShutdown.not()) pool.shutdown()
        }

        assertEquals(threads * each, follower.updates.size, "동시에 넣은 갱신을 잃었다")
    }

    private fun update(index: Long): WatchTaskResponse =
        WatchTaskResponse.newBuilder().setHeader(MessageHeader.newBuilder().setUpdateIndex(index)).build()

    private companion object {
        const val ROBOT = "robot-1"
        const val TASK = "task-1"
        const val DEADLINE_MS = 1000L
    }
}
