package dev.picasso.harness

import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.mimic.control.v1.SetConnectionRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 완료 기준 5 — `OFFLINE`·`HIBERNATING`·`CONNECTION_BROKEN`을 강제하면
 * **소비자가 침묵의 세 원인을 다르게 판정한다.**
 *
 * §4.7이 `HIBERNATING`을 둔 이유가 이것이다 — 침묵의 상한만 두면 **절전 중인
 * 로봇이 고장으로 오판된다.** 그러므로 이 파일의 중심은 "셋이 서로 다른
 * 침묵을 만든다"가 아니라 그 반대다: **침묵은 셋 다 같고, 연결 스트림이
 * 그것을 가른다.**
 */
class SilenceTest {

    private val profile = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    private fun harness() = Harness(mapOf(ROBOT to profile))

    private fun Harness.setConnection(state: ConnectionState) = oracle.setConnection(
        SetConnectionRequest.newBuilder().setRobotId(ROBOT).setState(state.name).build(),
    )

    private fun Harness.states() =
        publisher.publications.count { it.message is StateMessage }

    private fun Harness.connections() =
        publisher.publications.mapNotNull { it.message as? ConnectionMessage }

    /** 소비자가 [seconds]초 동안 **상태 스트림에서** 본 것의 개수. */
    private fun Harness.observeSilence(seconds: Long): Int {
        val before = states()
        advance(Duration.ofSeconds(seconds))
        return states() - before
    }

    // ── 정상

    @Test
    fun `기동하면 ONLINE이 retain으로 나가 있다`() {
        harness().use { harness ->
            val message = harness.publisher.publications.first { it.message is ConnectionMessage }
            assertEquals(
                ConnectionState.CONNECTION_STATE_ONLINE,
                (message.message as ConnectionMessage).state,
            )
            assertTrue(message.retained, "retain이 아니면 새 구독자가 생사를 모른다")
        }
    }

    @Test
    fun `ONLINE이면 침묵하지 않는다`() {
        // 아래 시험들의 전제다. 애초에 아무것도 안 나가면 "멈췄다"가 자명하다.
        harness().use { harness ->
            assertTrue(harness.observeSilence(600) > 0, "정상인데 상태가 안 나간다")
        }
    }

    // ── 침묵의 세 원인

    @Test
    fun `침묵만으로는 셋을 구분할 수 없다`() {
        // **이것이 없으면 완료 기준 5가 공허하다.** 셋이 서로 다른 침묵을
        // 만든다면 연결 스트림이 왜 필요한지 설명되지 않는다.
        val observed = SILENT.map { state ->
            harness().use { harness ->
                harness.setConnection(state)
                state to harness.observeSilence(600)
            }
        }
        assertEquals(3, observed.size, "표가 셋이 아니다")
        assertEquals(
            1, observed.map { it.second }.distinct().size,
            "셋이 서로 다른 침묵을 만든다: $observed",
        )
        assertEquals(0, observed.first().second, "침묵이 아니다")
    }

    @Test
    fun `연결 스트림이 셋을 구분한다`() {
        // 위 시험의 짝. 같은 침묵을 만들지만 소비자는 셋을 다르게 판정한다.
        val seen = SILENT.map { state ->
            harness().use { harness ->
                harness.setConnection(state)
                harness.advance(Duration.ofSeconds(600))
                harness.connections().last().state
            }
        }
        assertEquals(SILENT, seen, "연결 스트림이 셋을 못 가른다: $seen")
        assertEquals(3, seen.distinct().size)
    }

    @Test
    fun `깨어나면 다시 나간다`() {
        // 멈추기만 하고 안 돌아오면 "의도된 정지"가 영구 고장과 같아진다.
        harness().use { harness ->
            harness.setConnection(ConnectionState.CONNECTION_STATE_HIBERNATING)
            assertEquals(0, harness.observeSilence(600))

            harness.setConnection(ConnectionState.CONNECTION_STATE_ONLINE)
            assertTrue(harness.observeSilence(600) > 0, "깨어났는데 안 나간다")
        }
    }

    // ── 거절

    @Test
    fun `모르는 상태는 거절한다`() {
        // 조용히 UNSPECIFIED로 접으면 시험이 침묵을 만든 줄 알고 넘어간다.
        harness().use { harness ->
            listOf("SLEEPING", "", "CONNECTION_STATE_UNSPECIFIED").forEach { bad ->
                val error = assertFailsWith<StatusRuntimeException>(message = bad) {
                    harness.oracle.setConnection(
                        SetConnectionRequest.newBuilder().setRobotId(ROBOT).setState(bad).build(),
                    )
                }
                assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code, bad)
            }
            // 거절해 놓고 침묵시키지 않는다.
            assertTrue(harness.observeSilence(600) > 0)
        }
    }

    @Test
    fun `같은 상태를 두 번 넣으면 한 번만 나간다`() {
        harness().use { harness ->
            val before = harness.connections().size
            assertTrue(harness.setConnection(ConnectionState.CONNECTION_STATE_OFFLINE).changed)
            assertTrue(!harness.setConnection(ConnectionState.CONNECTION_STATE_OFFLINE).changed)
            assertEquals(before + 1, harness.connections().size, "유령 전이가 나갔다")
        }
    }

    @Test
    fun `모르는 기체는 NOT_FOUND다`() {
        harness().use { harness ->
            val error = assertFailsWith<StatusRuntimeException> {
                harness.oracle.setConnection(
                    SetConnectionRequest.newBuilder().setRobotId("r9")
                        .setState(ConnectionState.CONNECTION_STATE_OFFLINE.name).build(),
                )
            }
            assertEquals(Status.Code.NOT_FOUND, error.status.code)
        }
    }

    private companion object {
        const val ROBOT = "r1"

        /** §4.7의 침묵을 만드는 셋. `ONLINE`이 아닌 전부다. */
        val SILENT = listOf(
            ConnectionState.CONNECTION_STATE_OFFLINE,
            ConnectionState.CONNECTION_STATE_HIBERNATING,
            ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
        )
    }
}
