package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.mimic.engine.TaskState as EngineState
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 완료 기준 8 — 프로파일의 `resolution` 셋이 각각 다른 종착을 만들고,
 * **앞의 둘만** `RetryTask`를 받아 `attempt`가 오른다.
 *
 * **Chunk 3b가 이미 표면에서 표를 돌았다.** 거기서는 상태를 문으로 심고
 * `RetryTask`가 열 행에서 무엇을 하는지 봤다. 여기서 보는 것은 다른 것이다 —
 * **결함이 그 상태를 만드는 사슬**이다. 프로파일이 `resolution`을 선언하고,
 * 그것이 §4.5 전파 규칙 1을 타고 태스크 상태가 되고, 그 상태가 재시도를
 * 받거나 안 받는다. 사슬의 어느 마디가 끊겨도 여기서 걸린다.
 *
 * **픽스처를 쓴다.** 실기종 셋 중 어느 것도 `TERMINAL`을 선언하지 않아
 * 그것으로는 표의 셋째 행을 만들 수 없다.
 */
class RetryTest {

    private val profile = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("object_id").setStringValue("b-1").build(),
        ParameterValue.newBuilder().setKey("destination").setStringValue("dock-3").build(),
    )

    /** 결함 하나를 세우고 그것이 태스크를 보낸 곳을 돌려준다. */
    private fun Harness.forceFault(errorType: String, taskId: String = TASK): String =
        oracle.forceFault(
            ForceFaultRequest.newBuilder()
                .setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

    private fun Harness.dump() = oracle.dumpInternalState(
        DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
    )

    private fun Harness.internal(taskId: String = TASK) =
        dump().tasksList.single { it.taskId == taskId }

    /** `pick_place`를 접수하고 `RUNNING`까지 민다. */
    private fun Harness.running(): TaskHandle {
        val response = client().start(ROBOT, TASK, 1, "pick_place", parameters)
        assertTrue(response.hasHandle(), "접수가 거절됐다: ${response.rejection}")
        advance(Duration.ofSeconds(1))
        assertEquals("RUNNING", internal().taskState, "RUNNING까지 못 밀었다")
        return response.handle
    }

    private fun harness() = Harness(mapOf(ROBOT to profile))

    // ── 사슬: resolution → 종착

    @Test
    fun `resolution 셋이 각각 다른 종착을 만든다`() {
        // §4.5 전파 규칙 1. 하나만 보면 나머지 둘이 침묵한다.
        assertEquals(3, TABLE.size, "표가 셋이 아니다")
        assertEquals(3, TABLE.values.distinct().size, "종착이 안 갈리는 표다")

        TABLE.forEach { (errorType, expected) ->
            harness().use { harness ->
                harness.running()
                assertEquals(expected.name, harness.forceFault(errorType), errorType)
                assertEquals(expected.name, harness.internal().taskState, errorType)
            }
        }
    }

    @Test
    fun `표의 결함이 전부 프로파일에서 온다`() {
        // 위 표가 리터럴이면 프로파일이 선언하는 뜻이 없다. 세 `error_type`이
        // 실제로 그 프로파일에 있고 **각각 다른 resolution을 선언하는지** 본다.
        val declared = Regex("\"error_type\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,400}?\"resolution\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(java.nio.file.Files.readString(profile))
            .associate { it.groupValues[1] to it.groupValues[2] }

        assertEquals(
            mapOf(
                "SKILL_EXECUTION_FAILED" to "SELF_RETRIABLE",
                "LOCALIZATION_LOST" to "NEEDS_INTERVENTION",
                "X_FIXTURE_SIMULATED_HARDWARE_FAULT" to "TERMINAL",
            ),
            declared.filterKeys { it in TABLE.keys },
            "표가 프로파일과 어긋난다",
        )
    }

    // ── 재시도를 받는가

    @Test
    fun `앞의 둘만 RetryTask를 받는다`() {
        // **셋을 표로 돈다.** 하나만 보면 "언제나 받는다"와 "절대 안 받는다"가
        // 둘 다 통과한다.
        val accepts = mapOf(
            "SKILL_EXECUTION_FAILED" to true,
            "LOCALIZATION_LOST" to true,
            "X_FIXTURE_SIMULATED_HARDWARE_FAULT" to false,
        )
        assertEquals(2, accepts.values.distinct().size, "받는 것과 못 받는 것이 다 있어야 한다")

        accepts.forEach { (errorType, accepted) ->
            harness().use { harness ->
                val handle = harness.running()
                harness.forceFault(errorType)

                val response = harness.client().retry(ROBOT, handle)
                if (accepted) {
                    assertEquals(TaskState.TASK_STATE_RUNNING, response.state, errorType)
                } else {
                    assertEquals(
                        RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                        response.rejection.code,
                        "$errorType 에서 재시도가 받아들여졌다 — 종착이 래치되지 않았다",
                    )
                    assertEquals(
                        EngineState.FAILED.name, harness.internal().taskState,
                        "$errorType: 거절해 놓고 상태를 바꿨다",
                    )
                }
            }
        }
    }

    @Test
    fun `재시도가 attempt를 올린다`() {
        // §12.2의 8번이 요구하는 값이다. 안 오르면 재시도가 **관측 불가능**하다.
        harness().use { harness ->
            val handle = harness.running()
            assertEquals(0, harness.internal().attempt, "시작부터 0이 아니다")

            harness.forceFault("SKILL_EXECUTION_FAILED")
            assertEquals(0, harness.internal().attempt, "실패만으로 attempt가 올랐다")

            harness.client().retry(ROBOT, handle)
            assertEquals(1, harness.internal().attempt)

            // 두 번째도 오른다 — 한 번만 올리고 마는 구현을 잡는다.
            harness.forceFault("SKILL_EXECUTION_FAILED")
            harness.client().retry(ROBOT, handle)
            assertEquals(2, harness.internal().attempt)
        }
    }

    @Test
    fun `거절된 재시도는 attempt를 안 올린다`() {
        // 안 일어난 일을 세면 소비자가 시도 횟수를 잘못 읽는다.
        harness().use { harness ->
            val handle = harness.running()
            harness.forceFault("X_FIXTURE_SIMULATED_HARDWARE_FAULT")

            repeat(3) { harness.client().retry(ROBOT, handle) }
            assertEquals(0, harness.internal().attempt)
        }
    }

    @Test
    fun `재시도한 태스크가 다시 완주한다`() {
        // `RUNNING`으로 돌려놓기만 하고 진행률을 안 되돌리면, 소요시간을 이미
        // 넘긴 시각이라 다음 tick이 곧바로 완주시킨다 — 재시도가 **다시
        // 수행하는 것**이 아니라 그냥 종착 도장이 된다.
        harness().use { harness ->
            val handle = harness.running()

            // 소요시간(45초 ±10%)의 절반쯤까지만 민다 — 완주하면 결함을
            // 받는 상태가 아니게 된다.
            harness.advance(Duration.ofSeconds(29))
            val burned = harness.internal().progress
            assertTrue(burned > 0.5, "전제가 무너졌다 — 진행이 거의 없다: $burned")

            harness.forceFault("SKILL_EXECUTION_FAILED")
            harness.client().retry(ROBOT, handle)

            assertTrue(
                harness.internal().progress < burned,
                "재시도인데 진행률이 그대로다: ${harness.internal().progress} (이전 $burned)",
            )
            harness.advance(Duration.ofSeconds(120))
            assertEquals(EngineState.SUCCEEDED.name, harness.internal().taskState)
        }
    }

    // ── 결함은 재시도가 지우지 않는다

    @Test
    fun `재시도가 결함을 지우지 않는다`() {
        // §4.3 — 결함이 언제 사라지는지는 **수명**이 정한다. 재시도가 지우면
        // 소비자는 "이 결함이 아직 유효한가"를 다시 추측하게 된다.
        // 픽스처의 LOCALIZATION_LOST 는 UNTIL_CLEARED 다.
        harness().use { harness ->
            val handle = harness.running()
            harness.forceFault("LOCALIZATION_LOST")
            assertEquals(listOf("LOCALIZATION_LOST"), harness.dump().faultsList.map { it.errorType })

            harness.client().retry(ROBOT, handle)
            assertEquals(
                listOf("LOCALIZATION_LOST"), harness.dump().faultsList.map { it.errorType },
                "재시도가 결함을 지웠다",
            )
        }
    }

    @Test
    fun `새 태스크 수명의 결함은 새 태스크로만 사라진다`() {
        // 같은 §4.3의 다른 행. 픽스처의 SKILL_EXECUTION_FAILED 는
        // UNTIL_NEW_TASK 이므로 **재시도로는 안 사라지고** 새 태스크로 사라진다.
        harness().use { harness ->
            val handle = harness.running()
            harness.forceFault("SKILL_EXECUTION_FAILED")
            assertEquals(
                listOf("UNTIL_NEW_TASK"),
                harness.dump().faultsList.map { it.lifetimeKind.removePrefix("KIND_") },
                "픽스처의 수명이 바뀌었다 — 이 시험이 다른 것을 말하게 된다",
            )

            harness.client().retry(ROBOT, handle)
            assertEquals(1, harness.dump().faultsList.size, "재시도가 지웠다")

            harness.client().start(ROBOT, "t2", 1, "pick_place", parameters)
            assertEquals(emptyList(), harness.dump().faultsList, "새 태스크인데 안 사라졌다")
        }
    }

    private companion object {
        const val ROBOT = "r1"
        const val TASK = "t1"

        /**
         * 픽스처가 선언한 `error_type` → §4.5 전파 규칙 1이 보내는 곳.
         *
         * **엔진 어휘다.** 오라클이 계약 enum이 아니라 엔진의 이름으로
         * 말하기 때문이며, 그것이 §12.2 A-2가 순환을 피하는 방법이다.
         */
        val TABLE = mapOf(
            "SKILL_EXECUTION_FAILED" to EngineState.RETRIABLE,
            "LOCALIZATION_LOST" to EngineState.NEEDS_INTERVENTION,
            "X_FIXTURE_SIMULATED_HARDWARE_FAULT" to EngineState.FAILED,
        )
    }
}
