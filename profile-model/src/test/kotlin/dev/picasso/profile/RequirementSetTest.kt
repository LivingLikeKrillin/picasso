package dev.picasso.profile

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequirementTest {

    @Test
    fun `문법에 맞는 것을 파싱한다`() {
        assertEquals(Requirement("pick_place", 1, 2), Requirement.parse("pick_place@^1.2"))
        assertEquals(Requirement("navigate_to", 0, 0), Requirement.parse("navigate_to@^0.0"))
        assertEquals(Requirement("weld", 12, 34), Requirement.parse("weld@^12.34"))
    }

    @Test
    fun `문법을 벗어난 것을 통째로 거절한다`() {
        // 하나만 보면 나머지가 침묵한다. 관대하게 파싱하면 캐럿 없는 요구가
        // 있는 것처럼 되고, 그것이 무슨 뜻인지 계약이 정한 바가 없다.
        listOf(
            "pick_place",           // 버전 없음
            "pick_place@1.2",       // 캐럿 없음
            "pick_place@^1",        // minor 없음
            "pick_place@^1.2.3",    // patch는 문법에 없다
            "@^1.2",                // 스킬 없음
            "pick_place@^-1.2",
            "pick_place@^1.2 ",
            "pick place@^1.2",
            "pick_place@^1.2@^1.3",
            "",
        ).forEach {
            assertFailsWith<IllegalArgumentException>("'$it' 를 받아들였다") { Requirement.parse(it) }
        }
    }

    @Test
    fun `만족 판정이 §5-2의 방향을 지킨다`() {
        val requirement = Requirement("pick_place", 1, 2)

        // 로봇이 더 높은 minor를 갖는 것은 정상이다 — minor 증가는 선택
        // 파라미터 추가뿐이고 클라이언트가 몰라도 동작한다.
        assertTrue(requirement.satisfiedBy(1, 2))
        assertTrue(requirement.satisfiedBy(1, 9))

        // 반대 방향은 불만족이다.
        assertTrue(!requirement.satisfiedBy(1, 1))
        // major는 동일성이다. 양방향 모두 불만족.
        assertTrue(!requirement.satisfiedBy(2, 9))
        assertTrue(!requirement.satisfiedBy(0, 9))
    }
}

class RequirementSetTest {

    private val good = Files.readString(Path.of("..", "profile", "requirements", "minimal.json").normalize())

    @Test
    fun `픽스처 요구 집합을 읽는다`() {
        val set = RequirementSet.parse("minimal", good)
        assertEquals("line-controller", set.clientId)
        assertEquals(
            listOf(Requirement("pick_place", 1, 2), Requirement("navigate_to", 1, 0)),
            set.requirements,
        )
        assertEquals(listOf("task.parameters.verify_grasp"), set.optionalFieldsUsed)
        assertEquals(LimitsNeeded(64, 8), set.limitsNeeded)
    }

    @Test
    fun `틀린 요구 집합 픽스처가 실제로 major를 어긴다`() {
        // 픽스처가 무해하면 완료 기준 13이 공허해진다. 픽스처 프로파일의
        // pick_place는 1.2인데 요구는 ^2.0이다.
        val raw = Files.readString(Path.of("..", "profile", "requirements", "wrong-major.json").normalize())
        val requirement = RequirementSet.parse("wrong-major", raw)
            .requirements.single { it.skillType == "pick_place" }
        assertEquals(2, requirement.major)
        assertTrue(!requirement.satisfiedBy(1, 2), "픽스처가 major를 어기지 않는다")
    }

    @Test
    fun `틀린 것이 여럿이면 전부 열거한다`() {
        // 첫 실패에서 끊으면 Negotiate가 거절 다섯을 한 번에 돌려주는 규율과
        // 모순이다. §5.4가 요구 집합을 설정 파일이라 한 이상 오타는 왕복
        // 한 번에 다 알려줘야 한다.
        val message = assertFailsWith<IllegalArgumentException> {
            RequirementSet.parse(
                "broken",
                """
                {
                  "schema_version": "0.9.0",
                  "client_id": "",
                  "requirements": ["pick_place", "navigate_to@1.0"],
                  "typo_key": 1
                }
                """.trimIndent(),
            )
        }.message!!

        listOf("schema_version", "client_id", "pick_place", "navigate_to@1.0", "typo_key")
            .forEach { assertTrue(it in message, "'$it' 가 소견에 없다:\n$message") }
    }

    @Test
    fun `모르는 키를 조용히 무시하지 않는다`() {
        // 오타 난 requirement가 "요구 없음"이 되면 협상이 무조건 통과한다.
        assertFailsWith<IllegalArgumentException> {
            RequirementSet.parse("typo", good.replace("\"requirements\"", "\"requirement\""))
        }
    }

    @Test
    fun `요구가 비면 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            RequirementSet.parse("empty", good.replace(
                """["pick_place@^1.2", "navigate_to@^1.0"]""", "[]",
            ))
        }
    }

    @Test
    fun `같은 스킬을 두 번 요구하면 거절한다`() {
        // 조용히 하나를 고르면 어느 쪽이 판정됐는지 알 수 없다.
        assertFailsWith<IllegalArgumentException> {
            RequirementSet.parse("dup", good.replace(
                """["pick_place@^1.2", "navigate_to@^1.0"]""",
                """["pick_place@^1.2", "pick_place@^1.5"]""",
            ))
        }
    }

    @Test
    fun `중복 멤버를 마지막 값으로 접지 않는다`() {
        // {"requirements":[...],"requirements":[]} 가 통과하면 위 "요구가
        // 비면 거절한다"를 통째로 우회할 수 있다(프로파일에서 실측한 구멍).
        assertFailsWith<IllegalArgumentException> {
            RequirementSet.parse(
                "dup-member",
                good.replace("\"optional_fields_used\"", "\"requirements\": [], \"optional_fields_used\""),
            )
        }
    }

    @Test
    fun `루트가 객체가 아니면 거절한다`() {
        listOf("", "[]", "123", "null", "\"x\"").forEach {
            assertFailsWith<IllegalArgumentException>("'$it' 를 받아들였다") {
                RequirementSet.parse("scalar", it)
            }
        }
    }

    @Test
    fun `limits_needed를 안 쓰면 0이다`() {
        // 0은 "말하지 않았다"이고 LIMIT_EXCEEDED를 유발하지 않는다.
        val set = RequirementSet.parse(
            "no-limits",
            """
            {
              "schema_version": "1.0.0",
              "client_id": "c",
              "requirements": ["pick_place@^1.2"]
            }
            """.trimIndent(),
        )
        assertEquals(LimitsNeeded(0, 0), set.limitsNeeded)
        assertEquals(emptyList(), set.optionalFieldsUsed)
    }
}
