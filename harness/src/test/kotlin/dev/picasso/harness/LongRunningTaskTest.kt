package dev.picasso.harness

import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 완료 기준 6 (A-4) — 30초+ 태스크의 진행률·중도취소·부분결과.
 *
 * 이 기준만 전용 시험이 없었다. 부품은 흩어져 있었다 — 가상 시계는
 * [ContractSuite]가 모든 시나리오에서 쓰고, 진행률의 시작값은
 * `ScenarioAssertions`가 보고, 취소는 `CancelRecoveryTest`가 본다. 그러나
 * **기준이 요구하는 문장 자체** — *"가상 시계로 압축한 30초+ 태스크에서 같은
 * `(revision, attempt)` 구간의 진행률이 단조 비감소이고 `CANCELLING`이 종착보다
 * 먼저 관측된다"* — 를 한자리에서 보는 시험은 없었다.
 *
 * ## 왜 `no-pause` 픽스처인가
 *
 * 기준이 요구하는 조합은 **30초 이상이면서 취소 가능한 스킬**이다. 프로파일
 * 여덟 장 중 그 조합은 `no-pause.json`의 `pick_place`(45초, `cancel_support:
 * YES`)뿐이다 — `minimal.json`의 같은 스킬은 45초지만 `cancel_support: NO`이고,
 * 실제 기종 쪽에서 30초를 넘는 것은 `quadruped-c`의 `navigate_to`(35초) 하나인데
 * 그것은 `profile/profiles/` 아래라 `AllModelsTest`의 훑기 대상이다. 시험이
 * 기종 프로파일을 붙들면 그쪽이 흔들릴 때 여기가 같이 빨개진다.
 *
 * ## 이 시험이 스스로 공허해지지 않게 하는 것
 *
 * 완주만 보면 "시간이 태스크를 지배한다"가 증명되지 않는다 — tick 세 번으로
 * 끝나는 1초짜리 스킬도 같은 단언을 통과한다. 그래서 **선언한 소요시간의 절반이
 * 지난 시점에 아직 `RUNNING`인지**를 함께 본다. 그 단언이 없으면 이 파일은
 * `A1Test`의 사본이 된다.
 */
class LongRunningTaskTest {

    private val profile = Path.of("..", "profile", "fixtures", "no-pause.json").normalize()

    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("object_id").setStringValue("box-7").build(),
        ParameterValue.newBuilder().setKey("destination").setStringValue("dock-3").build(),
    )

    private fun harness() = Harness(mapOf(ROBOT to profile))

    private fun Harness.internal(taskId: String = TASK) =
        oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build())
            .tasksList.single { it.taskId == taskId }

    private fun Harness.start(taskId: String = TASK): Pair<TaskHandle, TaskFollower> {
        val started = client().start(ROBOT, taskId, 1, SKILL, parameters)
        assertTrue(started.hasHandle(), "접수가 거절됐다: ${started.rejection}")
        return started.handle to client().follow(ROBOT, started.handle)
    }

    // ── 시간이 태스크를 지배한다

    @Test
    fun `선언한 소요시간의 절반에서는 아직 돌고 있다`() {
        harness().use { harness ->
            harness.start()
            harness.advance(Duration.ofSeconds(1))
            assertEquals("RUNNING", harness.internal().taskState, "RUNNING까지 못 밀었다")

            harness.advance(HALF)
            assertEquals(
                "RUNNING", harness.internal().taskState,
                "선언이 ${DECLARED.seconds}초인데 ${HALF.seconds}초에 끝났다 — " +
                    "소요시간이 관측 횟수에 달려 있다는 뜻이다",
            )
        }
    }

    @Test
    fun `가상 시계로만 완주한다`() {
        harness().use { harness ->
            val (_, follower) = harness.start()
            harness.push()

            assertNull(follower.error, "스트림이 깨졌다: ${follower.error}")
            assertEquals(
                listOf(
                    TaskState.TASK_STATE_ACCEPTED,
                    TaskState.TASK_STATE_RUNNING,
                    TaskState.TASK_STATE_SUCCEEDED,
                ),
                follower.updates.map { it.state },
                "45초 태스크가 완주하지 않았다",
            )
            assertEquals(1.0, follower.updates.last().progress, "완주인데 진행률이 1이 아니다")
            assertTrue(follower.completed, "종착인데 스트림을 안 닫았다")
        }
    }

    // ── 진행률

    @Test
    fun `같은 revision과 attempt 안에서 진행률이 단조 비감소다`() {
        harness().use { harness ->
            val (_, follower) = harness.start()
            harness.push()

            val updates = follower.updates
            assertTrue(updates.size >= 2, "관측이 ${updates.size}건이라 단조성을 말할 수 없다")

            updates.groupBy { it.revision to it.attempt }.forEach { (segment, inSegment) ->
                inSegment.zipWithNext { earlier, later ->
                    assertTrue(
                        later.progress >= earlier.progress,
                        "구간 $segment 에서 진행률이 되돌아갔다: " +
                            "${earlier.progress} → ${later.progress}",
                    )
                }
            }
        }
    }

    @Test
    fun `구간을 나누지 않으면 단조성이 성립하지 않는다`() {
        // **왜 `(revision, attempt)`로 나누는지를 붙들어 둔다.** 나누지 않아도
        // 통과하면 위 시험은 구간 없이도 참인 것을 확인하는 셈이고, 그러면
        // §4.4가 불변식에 단서를 단 이유가 시험에서 사라진다. 재시도가
        // 진행률을 0으로 되돌리는 것을 `RetryTest`가 이미 보고 있으므로,
        // 여기서는 그 사실이 **이 파일의 전제**임을 적어 둔다.
        harness().use { harness ->
            val (_, follower) = harness.start()
            harness.push()
            assertEquals(
                1, follower.updates.map { it.revision to it.attempt }.distinct().size,
                "이 시나리오는 구간이 하나여야 한다 — 구간이 갈리면 위 시험의 " +
                    "그룹핑이 실제로 값을 하는지 여기서 확인할 수 없다",
            )
        }
    }

    // ── 중도취소

    @Test
    fun `중도취소는 CANCELLING을 거쳐 종착한다`() {
        harness().use { harness ->
            val (handle, follower) = harness.start()
            harness.advance(Duration.ofSeconds(1))
            harness.advance(HALF)

            val midway = harness.internal().progress
            assertTrue(midway < 1.0, "취소 전에 이미 끝나 있었다(진행률 $midway) — 중도가 아니다")

            val cancelled = harness.client().cancel(ROBOT, handle)
            assertEquals(
                TaskState.TASK_STATE_CANCELLING, cancelled.state,
                "취소가 즉시 종착했다 — §4.4는 복구를 동반한다고 못박았다",
            )

            harness.advance(Duration.ofSeconds(1))
            assertEquals("CANCELLED", harness.internal().taskState, "복구가 안 끝났다")

            val observed = follower.updates.map { it.state }
            val cancelling = observed.indexOf(TaskState.TASK_STATE_CANCELLING)
            val terminal = observed.indexOf(TaskState.TASK_STATE_CANCELLED)
            assertTrue(cancelling >= 0, "CANCELLING이 스트림에 안 나왔다: $observed")
            assertTrue(terminal >= 0, "종착이 스트림에 안 나왔다: $observed")
            assertTrue(
                cancelling < terminal,
                "관측 순서가 CANCELLING → 종착이 아니다: $observed",
            )
            assertTrue(
                observed.none { it == TaskState.TASK_STATE_SUCCEEDED },
                "중도취소인데 완주가 함께 관측됐다: $observed",
            )
        }
    }

    // ── 부분결과 — 기준 6의 나머지 한 조각

    @Test
    fun `부분결과는 계약에만 있고 아직 아무도 채우지 않는다`() {
        // **이 시험은 통과하는 것이 좋은 상태가 아니다.** 기준 6은 "진행률·
        // 중도취소·**부분결과**"인데 `partial_result`(task.proto:112)를 비어
        // 있지 않게 만드는 곳이 엔진에 없다 — `TaskLog`가 기본값 ""로 들고
        // `TaskServiceImpl:347`이 그대로 실어 보낸다. 그래서 이 조각만은
        // 지금 증명할 수 없고, 없다는 사실을 여기 고정해 둔다.
        //
        // 채우는 쪽이 생기면 이 시험이 빨개진다. 그때 지우고 진짜 단언으로
        // 바꾸는 것이 이 시험의 용도다 — 조용히 빠지지 않게 하는 것.
        harness().use { harness ->
            val (_, follower) = harness.start()
            harness.push()
            assertTrue(
                follower.updates.all { it.partialResult.isEmpty() },
                "부분결과를 채우는 쪽이 생겼다 — 이 시험을 지우고 기준 6의 " +
                    "부분결과 단언을 세울 때다",
            )
        }
    }

    /** 종착까지 넉넉히 민다. 소요시간을 시험이 알면 지터가 붙는 날 빨개진다. */
    private fun Harness.push() = repeat(3) { advance(Duration.ofSeconds(30)) }

    private companion object {
        const val ROBOT = "r1"
        const val TASK = "t1"

        /** 30초를 넘으면서 취소 가능한 유일한 조합. */
        const val SKILL = "pick_place"

        val DECLARED: Duration = Duration.ofSeconds(45)
        val HALF: Duration = DECLARED.dividedBy(2)
    }
}
