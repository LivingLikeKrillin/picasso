package dev.picasso.harness

import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.profile.RequirementSet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **완료 기준 13** — 핸드셰이크가 §4.3의 `Negotiate` 거절 **다섯**을 각각
 * 사유와 함께 반환한다.
 *
 * §12.2가 픽스처 셋을 지정했다.
 *
 * | 픽스처 | 겨냥 |
 * |---|---|
 * | 프로파일 차이 | `SKILL_ABSENT` · `LIMIT_EXCEEDED` · `REQUIRED_OPTIONAL_MISSING` |
 * | `profile/requirements/wrong-major.json` | `MAJOR_MISMATCH` |
 * | `client --identity-override` | `IDENTITY_MISMATCH` |
 *
 * **요구 집합을 손으로 짜지 않는다.** `CapabilityDifferenceTest`는 판정
 * 자체를 보느라 인메모리로 만들었지만, 완료 기준 13이 요구하는 것은
 * **파일로 존재하는 픽스처가 실제로 그 거절을 만든다**는 것이다. 손으로
 * 짜면 §5.4의 "요구 집합은 코드가 아니라 설정"이 시험에서 다시 코드가 된다.
 *
 * 그리고 같은 파일이 **기체에 따라 다른 거절**을 낸다 — 차이가 전부 데이터에
 * 있다는 것의 가장 짧은 증거다.
 *
 * **§5.4의 나머지 절반은 여기 없다.** 협상 결과를 `registry`에 보고하는 것은
 * 3a단계다(§13을 그렇게 고쳤다).
 */
class HandshakeTest {

    private val profiles = Path.of("..", "profile", "profiles").normalize()
    private val requirements = Path.of("..", "profile", "requirements").normalize()

    private val harness = Harness(
        mapOf(
            HUMANOID to profiles.resolve("humanoid-a.json"),
            QUADRUPED to profiles.resolve("quadruped-b.json"),
        ),
    )

    @AfterTest fun close() = harness.close()

    /** **파일에서 읽는다.** 손으로 짜면 픽스처가 존재한다는 것이 증명되지 않는다. */
    private fun fixture(name: String): RequirementSet = RequirementSet.parse(
        name,
        Files.readString(requirements.resolve("$name.json")).replace("\r\n", "\n"),
    )

    private fun codes(
        robotId: String,
        set: RequirementSet,
        identityOverride: String? = null,
    ): List<RejectionCode> =
        harness.client(set.clientId, identityOverride)
            .negotiate(robotId, set).rejectionsList.map { it.code }

    // ── 다섯을 한자리에서

    @Test
    fun `거절 다섯이 세 픽스처에서 각각 나온다`() {
        val produced: Map<RejectionCode, () -> List<RejectionCode>> = mapOf(
            // 프로파일 차이 — 같은 파일, 다른 기체.
            RejectionCode.REJECTION_CODE_SKILL_ABSENT to
                { codes(QUADRUPED, fixture("wrong-major")) },
            RejectionCode.REJECTION_CODE_MAJOR_MISMATCH to
                { codes(HUMANOID, fixture("wrong-major")) },
            RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED to
                { codes(QUADRUPED, fixture("wide")) },
            RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING to
                { codes(HUMANOID, fixture("wide")) },
            // 신원은 파일이 아니라 클라이언트의 기동 인자다.
            RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH to
                { codes(HUMANOID, fixture("common"), identityOverride = "someone-else") },
        )
        // 표가 비면 아무것도 확인하지 않고 통과한다.
        assertEquals(5, produced.size)

        produced.forEach { (expected, produce) ->
            assertTrue(expected in produce(), "$expected 가 안 나왔다: ${produce()}")
        }
    }

    // ── 같은 파일이 기체에 따라 다르게 걸린다

    @Test
    fun `틀린 요구 집합이 기체에 따라 다른 거절을 낸다`() {
        // humanoid-a는 pick_place를 1.2로 갖고 quadruped-b는 아예 없다.
        // 파일은 하나다 — 차이는 전부 프로파일에 있다.
        val wrongMajor = fixture("wrong-major")
        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_MAJOR_MISMATCH), codes(HUMANOID, wrongMajor),
        )
        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_SKILL_ABSENT), codes(QUADRUPED, wrongMajor),
        )
    }

    @Test
    fun `넓은 한계 요구가 기체에 따라 다른 거절을 낸다`() {
        // humanoid-a는 한계가 넉넉하지만 REQUIRED 선택 필드를 요구하고,
        // quadruped-b는 그 반대다.
        val wide = fixture("wide")
        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING), codes(HUMANOID, wide),
        )
        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED), codes(QUADRUPED, wide),
        )
    }

    // ── 픽스처가 무해하지 않은지

    @Test
    fun `공통 요구 집합은 두 기체 모두에서 통과한다`() {
        // 이것이 없으면 "언제나 전부 거절"하는 구현이 위 시험을 다 통과한다.
        val common = fixture("common")
        listOf(HUMANOID, QUADRUPED).forEach {
            assertEquals(emptyList(), codes(it, common), "$it 이 공통 요구를 거절했다")
        }
    }

    @Test
    fun `identity-override 없이는 신원이 어긋나지 않는다`() {
        // 오버라이드가 실제로 원인인지 본다 — 아니면 그 픽스처가 장식이다.
        assertTrue(
            RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH !in codes(HUMANOID, fixture("common")),
        )
    }

    // ── 거절의 품질

    @Test
    fun `모든 거절에 사유가 붙는다`() {
        // §12.2가 "각각 **사유와 함께**"라고 적었다. openTCS
        // ExplainedBoolean의 요점이며 거절만 하고 이유를 안 주면 운영에서
        // 원인을 못 찾는다.
        val rejections = listOf(
            harness.client("line-controller").negotiate(QUADRUPED, fixture("wrong-major")),
            harness.client("line-controller").negotiate(HUMANOID, fixture("wide")),
            harness.client("line-controller", "someone-else")
                .negotiate(HUMANOID, fixture("common")),
        ).flatMap { it.rejectionsList }

        assertTrue(rejections.size >= 3, "${rejections.size}")
        rejections.forEach {
            assertTrue(it.detail.isNotBlank(), "${it.code} 에 이유가 없다")
        }
    }

    @Test
    fun `accepted가 거절 유무와 일치한다`() {
        // 어긋나면 클라이언트가 통과했다고 믿는다.
        listOf(
            HUMANOID to fixture("common"),
            HUMANOID to fixture("wide"),
            QUADRUPED to fixture("wrong-major"),
        ).forEach { (robot, set) ->
            val response = harness.client(set.clientId).negotiate(robot, set)
            assertEquals(
                response.rejectionsList.isEmpty(), response.accepted,
                "$robot / ${set.clientId}: accepted와 거절이 어긋난다",
            )
        }
    }

    private companion object {
        const val HUMANOID = "r1"
        const val QUADRUPED = "r2"
    }
}
