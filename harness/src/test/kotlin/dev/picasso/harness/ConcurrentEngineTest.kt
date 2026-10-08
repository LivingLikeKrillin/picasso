package dev.picasso.harness

import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.cli.MimicCli
import io.grpc.ManagedChannelBuilder
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 네트워크 채널에서 시간을 흘리는 스레드와 RPC 를 받는 스레드가 엔진을 함께 만져도 깨지지 않는다.
 *
 * 다른 시험은 in-process 채널의 `directExecutor` 로 한 스레드에서 돈다. 운영 배치(별도 프로세스의 호스트가
 * Netty 로 붙고, 현장이 다른 스레드에서 시계를 민다)는 그렇지 않다. 엔진에는 잠금이 없으므로 서버가 RPC 와
 * 시간 흘리기를 한 잠금으로 줄 세워야 한다. 이 시험은 열린 스트림이 많은 태스크 하나에 시간을 잘게 밀면서
 * 다른 스레드가 같은 태스크에 스트림을 계속 여는 경우를 만든다. 시간을 미는 쪽은 열린 스트림 목록을 훑고
 * RPC 쪽은 그 목록에 넣는다.
 */
class ConcurrentEngineTest {

    private val profile = Path.of("..", "profile", "fixtures", "no-pause.json").normalize()
    private val schema = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()

    @Test
    fun `시간을 흘리는 동안 다른 스레드가 스트림을 열어도 엔진이 깨지지 않는다`() {
        val err = StringBuilder()
        val started = assertNotNull(
            MimicCli().start(mapOf(ROBOT to profile), schema, port = 0, virtual = true, seed = 0L, err = err),
            "mimic 기동 거부: $err",
        )
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", started.server.port).usePlaintext().build()
        try {
            val client = PicassoClient(channel, "concurrency", deadlineSeconds = 30)
            val parameters = listOf(
                ParameterValue.newBuilder().setKey("object_id").setStringValue("box-7").build(),
                ParameterValue.newBuilder().setKey("destination").setStringValue("dock-3").build(),
            )
            val handle = client.start(ROBOT, TASK, 1, "pick_place", parameters).handle

            var failure: Throwable? = null
            val followers = mutableListOf<TaskFollower>()
            val stop = AtomicBoolean(false)
            val opener = thread {
                while (!stop.get() && followers.size < MAX_STREAMS) followers += client.follow(ROBOT, handle)
            }
            try {
                repeat(STEPS) { started.server.advance(Duration.ofMillis(10)) }
            } catch (e: Throwable) {
                failure = e
            } finally {
                stop.set(true)
                opener.join(TimeUnit.SECONDS.toMillis(30))
            }

            assertEquals(null, failure, "시간을 흘리다 엔진이 깨졌다: $failure")
            assertTrue(followers.size > 10, "스트림을 거의 못 열었다(${followers.size}) — 경합이 안 생겼다")
            val broken = followers.mapNotNull { it.error }.filter { it.message?.contains("DEADLINE_EXCEEDED") != true }
            assertEquals(emptyList(), broken.map { it.toString() }, "스트림이 서버 오류로 닫혔다")
        } finally {
            channel.shutdownNow()
            started.server.shutdown()
        }
    }

    private companion object {
        const val ROBOT = "robot-1"
        const val TASK = "task-1"
        const val STEPS = 3_000

        /** 서버의 실행기가 잠금을 기다리는 호출로 스레드를 끝없이 늘리지 않게 한다. */
        const val MAX_STREAMS = 2_000
    }
}
