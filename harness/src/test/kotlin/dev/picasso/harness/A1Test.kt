package dev.picasso.harness

import com.google.protobuf.Message
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.OptionalFieldSupport
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.TaskState
import dev.picasso.profile.RequirementSet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **완료 기준 1 (A-1)** — 능력 집합이 다른 두 로봇을 같은 클라이언트 코드로.
 *
 * 이 주장은 증명하기 쉬운 척하기가 아주 쉽다. 넷을 막는다.
 *
 * 1. **아무것도 안 하는 클라이언트는 두 로봇 모두에서 "성공"한다** →
 *    전이 목록과 진행률을 단언한다.
 * 2. **두 프로파일이 사실상 같으면 "두 로봇"이 하나다** → 축마다 다름을
 *    먼저 단언한다.
 * 3. **기종을 보고 분기해도 양쪽이 다 동작하면 시험은 통과한다** →
 *    **같은 `robot_id` 뒤에 다른 프로파일을 놓고 두 번 돌려** 오간 요청이
 *    바이트 동일임을 단언한다. 소스를 훑는 것으로는 능력 기반 분기를
 *    못 본다.
 * 4. **파라미터 없는 태스크는 "같은 호출"이 자명하게 참이다** → 필수
 *    파라미터를 같은 값으로 실어 보낸다.
 */
class A1Test {

    private val profiles = Path.of("..", "profile", "profiles").normalize()

    /**
     * **요구 집합은 한 장이다.** 기체마다 손으로 쓴 파일을 주면 기종 분기가
     * 기종을 아는 사람이 쓴 설정으로 옮겨 간 것뿐이다(§12.2의 기제는
     * "요구 집합이 설정 파일" 하나다).
     */
    private val requirements: RequirementSet = RequirementSet.parse(
        "common",
        Files.readString(Path.of("..", "profile", "requirements", "common.json").normalize())
            .replace("\r\n", "\n"),
    )

    /** 두 기체가 똑같이 받는 값. 기본값이 아니어야 "같은 호출"이 뜻을 갖는다. */
    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
    )

    private fun harnessFor(model: String) =
        Harness(mapOf(ROBOT to profiles.resolve("$model.json")))

    private fun capability(model: String): Capability =
        harnessFor(model).use { it.client().capabilities(ROBOT) }

    // ── 전제 (공허함 2번)

    @Test
    fun `두 능력이 축마다 실제로 다르다`() {
        // vendor 하나만 달라도 assertNotEquals는 통과한다. 축마다 본다.
        val a = capability("humanoid-a")
        val b = capability("quadruped-b")

        val axes: Map<String, () -> Boolean> = mapOf(
            "스킬 집합" to {
                a.skillsList.map { it.skillType }.toSet() != b.skillsList.map { it.skillType }.toSet()
            },
            "공통 스킬은 major.minor가 같다" to {
                val x = a.skillsList.single { it.skillType == ContractSuite.COMMON_SKILL }
                val y = b.skillsList.single { it.skillType == ContractSuite.COMMON_SKILL }
                x.major == y.major && x.minor == y.minor
            },
            "같은 스킬의 minor" to {
                a.skillsList.single { it.skillType == "inspect" }.minor !=
                    b.skillsList.single { it.skillType == "inspect" }.minor
            },
            "cancel_support" to {
                a.skillsList.single { it.skillType == ContractSuite.COMMON_SKILL }.cancelSupport !=
                    b.skillsList.single { it.skillType == ContractSuite.COMMON_SKILL }.cancelSupport
            },
            "pause_support" to {
                a.skillsList.single { it.skillType == ContractSuite.COMMON_SKILL }.pauseSupport !=
                    b.skillsList.single { it.skillType == ContractSuite.COMMON_SKILL }.pauseSupport
            },
            "프로토콜 한계" to { a.protocolLimits != b.protocolLimits },
            "REQUIRED 선택 필드" to {
                val required = { c: Capability ->
                    c.optionalFieldsList
                        .filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }
                        .map { it.parameterPath }.toSet()
                }
                required(a) != required(b)
            },
            "제어 소유권" to { a.exclusiveControlRequired != b.exclusiveControlRequired },
        )
        // 표가 비면 아무것도 확인하지 않고 통과한다.
        assertEquals(8, axes.size)
        axes.forEach { (name, holds) -> assertTrue(holds(), "축이 실제로 다르지 않다: $name") }

        assertNotEquals(a, b)
    }

    // ── A-1 본체

    @Test
    fun `같은 코드가 두 기종에서 태스크를 완주시킨다`() {
        // 공허함 1번 — 실제로 종착까지 갔고 전이가 관측됐는가.
        listOf("humanoid-a", "quadruped-b").forEach { model ->
            harnessFor(model).use { harness ->
                // 단언은 AllModelsTest와 **같은 것**을 쓴다. 따로 쓰면
                // 한쪽이 약해지고, 약해지는 쪽은 언제나 나중에 쓴 것이다.
                assertCompleted(
                    model,
                    ContractSuite.runCommonTask(
                        harness, harness.client(), ROBOT, requirements, parameters,
                    ),
                )
            }
        }
    }

    @Test
    fun `같은 robot_id 뒤에 다른 기종을 놓아도 오간 요청이 바이트 동일하다`() {
        // 공허함 3번. **소스에 기종 이름이 없는지 훑는 것으로는 부족하다** —
        // `capability.skillsList.size == 3` 같은 능력 기반 분기는 기종 이름을
        // 한 글자도 안 쓴다. 두 기체에 나간 요청이 바이트 동일하면 그런
        // 분기가 있을 수 없다.
        //
        // robot_id까지 같게 두는 것이 요점이다. 다르면 "robot_id로 분기하지
        // 않았다"를 정규화로 지워 버리게 된다.
        val runs = listOf("humanoid-a", "quadruped-b").map { model ->
            harnessFor(model).use { harness ->
                val outcome = ContractSuite.runCommonTask(
                    harness, harness.client(), ROBOT, requirements, parameters,
                )
                assertTrue(outcome.accepted, "$model: ${outcome.rejection}")
                harness.recorder.requests
            }
        }

        // 시험이 비지 않았는지 — 요청이 없으면 아래가 자명하게 참이다.
        assertTrue(runs[0].size >= 3, "오간 요청이 너무 적다: ${runs[0].map { it.first }}")
        assertEquals(runs[0].size, runs[1].size, "두 기종에 보낸 요청 수가 다르다")

        runs[0].zip(runs[1]).forEachIndexed { i, (x, y) ->
            assertEquals(x.first, y.first, "$i 번째 RPC가 다르다")
            assertEquals(x.second, y.second, "$i 번째 요청 내용이 다르다 — 기종을 보고 분기했다")
        }
    }

    @Test
    fun `한 프로세스에 두 기종을 함께 올려도 같은 요청이 나간다`() {
        // 위 시험은 프로세스를 나눠 돈다. §10.2의 실제 모습은 한 프로세스가
        // 여럿을 호스팅하는 것이고, 거기서는 robot_id가 달라야 한다 —
        // 그 필드만 지우고 나머지를 대조한다.
        Harness(
            mapOf(
                "r1" to profiles.resolve("humanoid-a.json"),
                "r2" to profiles.resolve("quadruped-b.json"),
            ),
        ).use { harness ->
            val client = harness.client()

            ContractSuite.runCommonTask(harness, client, "r1", requirements, parameters, "t-a")
            val first = harness.recorder.requests
            harness.recorder.clear()

            ContractSuite.runCommonTask(harness, client, "r2", requirements, parameters, "t-a")
            val second = harness.recorder.requests

            assertTrue(first.isNotEmpty())
            assertEquals(first.size, second.size)
            first.zip(second).forEachIndexed { i, (x, y) ->
                assertEquals(x.first, y.first, "$i 번째 RPC가 다르다")
                assertEquals(
                    RequestRecorder.normalize(x.second),
                    RequestRecorder.normalize(y.second),
                    "$i 번째 요청이 robot_id 말고도 다르다",
                )
            }
        }
    }

    @Test
    fun `정규화가 robot_id만 지운다`() {
        // 정규화가 너무 많이 지우면 위 시험이 공허해진다.
        val a: Message = ContractSuite.let {
            dev.picasso.contracts.v1.StartTaskRequest.newBuilder()
                .setHeader(
                    dev.picasso.contracts.v1.MessageHeader.newBuilder()
                        .setRobotId("r1").setClientId("c").setSchemaId("s"),
                )
                .setRobotId("r1").setTaskId("t1").setSkillType("navigate_to").build()
        }
        val normalized = RequestRecorder.normalize(a) as dev.picasso.contracts.v1.StartTaskRequest

        assertEquals("", normalized.robotId)
        assertEquals("", normalized.header.robotId)
        // 나머지는 그대로여야 한다.
        assertEquals("t1", normalized.taskId)
        assertEquals("navigate_to", normalized.skillType)
        assertEquals("c", normalized.header.clientId)
        assertEquals("s", normalized.header.schemaId)
    }

    private companion object {
        /**
         * **기종 이름과 무관한 식별자다.** `robot_id`를 `humanoid-a`로 두면
         * 그것으로 분기하는 클라이언트가 "기종 이름을 안 쓴다"는 검사를
         * 통과한다.
         */
        const val ROBOT = "r1"
    }
}
