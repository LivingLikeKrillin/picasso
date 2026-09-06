package dev.picasso.harness

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.profile.RequirementSet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **완료 기준 11(C-2)이 공허해지지 않게 하는 것이 이 파일의 존재 이유다.**
 *
 * 세 번째 기종을 더하고 스위트를 돌리면 **당연히** 통과한다 — `A1Test`가 두
 * 기종을 이름으로 집으므로 새 프로파일은 아무 시험도 건드리지 않는다.
 * "소스 변경 0으로 통과"가 "아무것도 안 했으니 통과"와 구별되지 않는다.
 *
 * 그래서 여기는 **디렉터리를 훑는다.** 프로파일 한 장을 더하면 시험이 하나
 * 늘고, 소스는 정말로 0줄 바뀐다.
 *
 * ## `profile/profiles/`에 들어오려면 만족해야 하는 것
 *
 * 훑기의 대가다. **다섯이며 하나가 아니다** — "`navigate_to`만 선언하면
 * 된다"로 읽고 필수 파라미터를 하나 더하면 여기서 빨갛게 난다.
 *
 * 1. `navigate_to@^1.0`을 선언한다(공통 요구 집합).
 * 2. `protocol_limits`가 `common.json`의 `limits_needed` 이상이다
 *    (문자열 64, 배열 8). `quadruped-b`가 정확히 그 값이라 여유가 없다.
 * 3. `REQUIRED` 선택 필드가 있다면 `task.parameters.verify_grasp` 하나뿐이다.
 *    그 밖의 것을 요구하면 `REQUIRED_OPTIONAL_MISSING`으로 협상에서 막힌다.
 * 4. `navigate_to`의 **필수** 파라미터가 `location` 하나뿐이다. 시나리오가
 *    그것만 보내고 `ParameterCheck`는 누락을 거절한다.
 * 5. `location.max_length`가 보내는 값보다 크고, `navigate_to`의 소요시간이
 *    하네스의 전진 폭 안에 든다.
 *
 * 이 우리가 좁아 보이면 그것이 맞다 — A-1의 주장이 그만큼 좁다.
 */
class AllModelsTest {

    private val profiles = Path.of("..", "profile", "profiles").normalize()

    private val requirements: RequirementSet = RequirementSet.parse(
        "common",
        Files.readString(Path.of("..", "profile", "requirements", "common.json").normalize())
            .replace("\r\n", "\n"),
    )

    private val parameters = listOf(
        ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
    )

    private fun models(): List<Path> = Files.list(profiles).use { stream ->
        stream.asSequence()
            .filter { it.isRegularFile() && it.extension == "json" }
            .sortedBy { it.name }
            .toList()
    }

    @Test
    fun `훑기가 실제 기종을 전부 찾는다`() {
        // 훑기가 조용히 빈 목록이나 일부만 내면 아래 시험이 그만큼 적게 돌면서
        // 초록으로 남는다. **바닥과 내용을 함께 못박는다.**
        val found = models().map { it.name }
        assertTrue(found.size >= MINIMUM, "훑을 프로파일이 $MINIMUM 장 미만이다: $found")
        assertTrue(
            found.containsAll(listOf("humanoid-a.json", "quadruped-b.json")),
            "훑기가 알려진 기종을 놓쳤다: $found",
        )
        assertEquals(found.distinct(), found)
    }

    @Test
    fun `모든 기종이 공통 태스크를 완주한다`() {
        // **이름으로 집지 않는다.** 프로파일 한 장을 더하면 여기가 한 번 더 돈다.
        val models = models()
        assertTrue(models.size >= MINIMUM)

        models.forEach { path ->
            Harness(mapOf(ROBOT to path)).use { harness ->
                assertCompleted(
                    path.name,
                    ContractSuite.runCommonTask(
                        harness, harness.client(), ROBOT, requirements, parameters,
                    ),
                )
            }
        }
    }

    @Test
    fun `모든 기종이 공통 요구 집합을 협상에서 받아들인다`() {
        // 위 시험의 전제를 따로 못박는다 — 거절이면 완주 단언까지 못 간다.
        models().forEach { path ->
            Harness(mapOf(ROBOT to path)).use { harness ->
                val response = harness.client().negotiate(ROBOT, requirements)
                assertTrue(
                    response.accepted,
                    "${path.name} 이 공통 요구 집합을 거절했다: " +
                        response.rejectionsList.joinToString { "${it.code}: ${it.detail}" },
                )
            }
        }
    }

    private companion object {
        const val ROBOT = "r1"

        /**
         * 훑기가 조용히 빈 목록을 내는 것을 막는 **바닥**이지 census가 아니다.
         *
         * **기종을 더할 때 올리지 않는다.** 올려야 한다면 프로파일 한 장을
         * 더하는 데 시험 파일이 함께 바뀌고, 그것은 검사 8번이 막는 바로
         * 그것이다 — C-2의 "소스 변경 0"이 자기 시험 때문에 깨진다.
         *
         * 기종이 **지워지는** 것은 여기가 아니라 검사 6번이 본다(스킬 제거는
         * 축소이고 §9.3의 두 조회를 요구한다).
         */
        const val MINIMUM = 2
    }
}
