package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.Support
import dev.picasso.contracts.v1.TaskState
import dev.picasso.profile.LimitsNeeded
import dev.picasso.profile.Requirement
import dev.picasso.profile.RequirementSet
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §7.4의 일곱 차이를 **소비자 관점에서** 관측한다.
 *
 * 프로파일에 차이가 있다는 것은 `ModelProfilesTest`가 문서에서 확인한다.
 * 여기서는 그 차이가 **wire 너머로 다른 결과를 만드는지**를 본다 — 문서에만
 * 있고 거동이 같으면 "차이가 데이터로 표현됐다"가 절반만 참이다.
 */
class CapabilityDifferenceTest {

    private val profiles = Path.of("..", "profile", "profiles").normalize()

    private val harness = Harness(
        mapOf(
            HUMANOID to profiles.resolve("humanoid-a.json"),
            QUADRUPED to profiles.resolve("quadruped-b.json"),
        ),
    )

    @AfterTest fun close() = harness.close()

    private val client = harness.client()

    private fun string(key: String, value: String): ParameterValue =
        ParameterValue.newBuilder().setKey(key).setStringValue(value).build()

    private fun requirements(
        vararg specs: String,
        optionalFields: List<String> = listOf("task.parameters.verify_grasp"),
        limits: LimitsNeeded = LimitsNeeded(64, 8),
    ) = RequirementSet(
        clientId = "line-controller",
        requirements = specs.map(Requirement::parse),
        optionalFieldsUsed = optionalFields,
        limitsNeeded = limits,
    )

    private fun codes(robotId: String, set: RequirementSet): List<RejectionCode> =
        client.negotiate(robotId, set).rejectionsList.map { it.code }

    // ── 협상에서 갈리는 셋 (§5.4)

    @Test
    fun `한쪽에만 있는 스킬은 SKILL_ABSENT다`() {
        val set = requirements("pick_place@^1.2")
        assertEquals(emptyList(), codes(HUMANOID, set), "있는 쪽에서 거절됐다")
        assertEquals(listOf(RejectionCode.REJECTION_CODE_SKILL_ABSENT), codes(QUADRUPED, set))
    }

    @Test
    fun `프로토콜 한계 차이가 LIMIT_EXCEEDED를 만든다`() {
        // 이것은 **핸드셰이크**의 거절이며 판정 입력은 limits_needed다(§5.4).
        // 아래 `같은 파라미터가…`는 StartTask의 PARAMETER_INVALID이고 서로
        // 다른 코드·다른 시점이다 — 둘을 하나로 보면 한쪽만 시험하고
        // 다른 쪽이 증명됐다고 착각한다.
        val set = requirements("navigate_to@^1.0", limits = LimitsNeeded(200, 8))
        assertEquals(emptyList(), codes(HUMANOID, set), "한계가 넓은 쪽에서 거절됐다")
        assertEquals(listOf(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED), codes(QUADRUPED, set))
    }

    @Test
    fun `REQUIRED 선택 필드가 한쪽에서만 걸린다`() {
        val set = requirements("navigate_to@^1.0", optionalFields = emptyList())
        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING),
            codes(HUMANOID, set),
        )
        assertEquals(emptyList(), codes(QUADRUPED, set), "선언하지 않은 쪽이 요구했다")
    }

    // ── 태스크 RPC에서 갈리는 둘 (완료 기준 7)

    @Test
    fun `cancel_support 차이가 CANCEL_UNSUPPORTED를 만든다`() {
        // **공통 스킬에서 갈린다.** 한쪽에만 있는 스킬로 시험하면 다른 쪽은
        // SKILL_ABSENT가 나오고, 두 축이 뭉쳐 어느 것이 걸렸는지 알 수 없다.
        val support = mapOf(HUMANOID to Support.SUPPORT_YES, QUADRUPED to Support.SUPPORT_NO)
        support.forEach { (robot, expected) ->
            assertEquals(
                expected,
                client.capabilities(robot).skillsList
                    .single { it.skillType == ContractSuite.COMMON_SKILL }.cancelSupport,
                "$robot 의 선언이 전제와 다르다",
            )
        }

        val handles = support.keys.associateWith { robot ->
            val started = client.start(
                robot, "cancel-$robot", 1, ContractSuite.COMMON_SKILL,
                listOf(string("location", "dock-3")),
            )
            assertTrue(started.hasHandle(), "$robot: ${started.rejection}")
            started.handle
        }

        val yes = client.cancel(HUMANOID, handles.getValue(HUMANOID))
        assertEquals(TaskState.TASK_STATE_CANCELLING, yes.state, "지원하는데 거절됐다")

        val no = client.cancel(QUADRUPED, handles.getValue(QUADRUPED))
        assertTrue(no.hasRejection(), "지원하지 않는데 통과했다: ${no.state}")
        assertEquals(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, no.rejection.code)
    }

    @Test
    fun `pause_support가 UNKNOWN이어도 시도가 허용된다`() {
        // §7.4의 축은 "시도가 허용되고 **로봇이 거절할 수 있다**"이다.
        // 뒷절반은 로봇 쪽 거절이 필요하고 그것은 실패 주입(Chunk 6)이다.
        // 여기서는 앞절반만 증명한다 — 절반을 전부인 척하지 않는다.
        assertEquals(
            Support.SUPPORT_UNKNOWN,
            client.capabilities(QUADRUPED).skillsList
                .single { it.skillType == ContractSuite.COMMON_SKILL }.pauseSupport,
        )
        val started = client.start(
            QUADRUPED, "pause-unknown", 1, ContractSuite.COMMON_SKILL,
            listOf(string("location", "dock-3")),
        )
        val paused = client.pause(QUADRUPED, started.handle)
        assertEquals(TaskState.TASK_STATE_PAUSED, paused.state, "UNKNOWN인데 미리 막았다")
    }

    // ── minor 차이 (§5.2)

    @Test
    fun `minor가 낮은 쪽도 같은 태스크를 완주한다`() {
        // §5.2 — minor 증가는 선택 파라미터 추가뿐이고 **클라이언트가 몰라도**
        // 동작한다. 그 방향이지 "로봇이 모르는 키를 무시한다"가 아니다.
        // 클라이언트는 minor 0의 파라미터만 싣는다.
        listOf(HUMANOID, QUADRUPED).forEach { robot ->
            val outcome = ContractSuite.runTask(
                harness, client, robot, "inspect", listOf(string("target", "panel-7")),
                "inspect-$robot",
            )
            assertTrue(outcome.accepted, "$robot: ${outcome.rejection}")
            assertEquals(
                TaskState.TASK_STATE_SUCCEEDED,
                outcome.follower!!.updates.last().state,
                "$robot 에서 완주하지 않았다",
            )
        }
    }

    @Test
    fun `높은 minor의 키는 낮은 쪽에서 거절된다`() {
        // 위 시험만 있으면 "로봇이 모르는 키를 무시하게 고치면 되겠다"는
        // 잘못된 결론으로 간다. **코어 키는 fail-closed다**(§5.3) — 그것을
        // 열면 게이트 음성 케이스의 보장이 통째로 무너진다.
        val withMode = listOf(string("target", "panel-7"), string("mode", "VISUAL"))

        val high = client.start(HUMANOID, "mode-high", 1, "inspect", withMode)
        assertTrue(high.hasHandle(), "minor 3인데 거절했다: ${high.rejection}")

        val low = client.start(QUADRUPED, "mode-low", 1, "inspect", withMode)
        assertTrue(low.hasRejection(), "minor 0인데 모르는 키를 받았다")
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, low.rejection.code)
        assertTrue("mode" in low.rejection.detail, low.rejection.detail)
    }

    @Test
    fun `허용 값 밖의 ENUM은 거절된다`() {
        // 계약은 열거의 자리만 만들고 값 집합은 프로파일이 정한다.
        // 그 집행이 없으면 ENUM 선언이 장식이다.
        val bad = listOf(string("target", "panel-7"), string("mode", "XRAY"))
        val response = client.start(HUMANOID, "mode-bad", 1, "inspect", bad)
        assertTrue(response.hasRejection(), "허용 값 밖인데 통과했다")
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code)
    }

    // ── §10.4 ③ 한계 집행

    @Test
    fun `같은 파라미터가 한쪽에서 통과하고 한쪽에서 거절된다`() {
        // **이것이 §10.4 ③의 요점이다** — 기종 차이가 코드가 아니라
        // 데이터에서 온다. 걸리는 것은 프로토콜 한계이지 파라미터 선언의
        // 최대 길이가 아니다(둘 다 256이다). 그래야 축이 뭉치지 않는다.
        val narrow = client.capabilities(QUADRUPED).protocolLimits.maxStringLength
        val wide = client.capabilities(HUMANOID).protocolLimits.maxStringLength
        assertTrue(wide > narrow, "전제가 무너졌다: $narrow, $wide")

        val declared = client.capabilities(QUADRUPED).skillsList
            .single { it.skillType == ContractSuite.COMMON_SKILL }
            .parametersList.single { it.key == "location" }.maxLength
        assertTrue(declared > narrow, "선언 길이가 먼저 걸린다 — 축이 뭉친다: $declared")

        val value = "x".repeat(narrow + 10)
        val parameters = listOf(string("location", value))

        val ok = client.start(HUMANOID, "limit-wide", 1, ContractSuite.COMMON_SKILL, parameters)
        assertTrue(ok.hasHandle(), "넓은 쪽에서 거절됐다: ${ok.rejection}")

        val rejected = client.start(QUADRUPED, "limit-narrow", 1, ContractSuite.COMMON_SKILL, parameters)
        assertTrue(rejected.hasRejection(), "좁은 쪽에서 통과했다")
        assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, rejected.rejection.code)
        assertTrue("프로토콜" in rejected.rejection.detail, rejected.rejection.detail)
    }

    private companion object {
        const val HUMANOID = "r1"
        const val QUADRUPED = "r2"
    }
}
