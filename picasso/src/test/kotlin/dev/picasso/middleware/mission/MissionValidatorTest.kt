package dev.picasso.middleware.mission

import dev.picasso.middleware.FloorOwner
import dev.picasso.middleware.FloorOwnership
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 검증기 — 다섯 검사(기한·신호·자원·스킬·안전)와 노드 id 중복이 **각각** 잘못된 정의를 거부하고, 거부가 종류와
 * 후속 행동으로 보인다.
 *
 * 검사마다 그 검사만 걸리는 정의를 하나씩 든다. 한 정의가 둘에 걸리면 한 검사를 빼도 다른 검사가 거부해 그 결함이
 * 안 보인다 — 그래서 «그 종류 하나뿐» 을 댄다.
 */
class MissionValidatorTest {

    private val at: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun floors(vararg unowned: String) = object : FloorOwnership {
        override fun ownerOf(location: String): FloorOwner =
            if (location in unowned) FloorOwner.Unowned else FloorOwner.Declared("line-a")
    }

    private fun refusals(
        text: String,
        signals: List<SignalSpec> = MissionFixtures.SIGNALS,
        floors: FloorOwnership = FloorOwnership.None,
        siteSkills: Set<String> = MissionFixtures.SITE_SKILLS,
    ): List<MissionRefusal> = MissionValidator.validate(MissionFixtures.parsed(text), signals, floors, siteSkills, at)

    private fun only(kind: MissionRefusalKind, found: List<MissionRefusal>): MissionRefusal {
        assertEquals(listOf(kind), found.map { it.kind }, "그 종류 하나만 걸려야 한다: $found")
        return found.single()
    }

    @Test
    fun `통과하는 정의는 거부가 없다`() {
        assertEquals(emptyList(), refusals(MissionFixtures.PREPARE_SEQUENCED_RACK))
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait()))
        // 텍스트 신호는 어떤 값이든 기다릴 수 있다.
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(signal = "lot_code", expect = "LOT-7")))
    }

    @Test
    fun `기한 검사 — 양의 기한이 없으면 거부한다`() {
        val zero = only(MissionRefusalKind.DEADLINE_INVALID, refusals(MissionFixtures.withArrivalWait(deadlineSeconds = 0)))
        assertEquals(MissionFixtures.WAIT_NODE, zero.nodeId)
        assertEquals("deadlineSeconds=0", zero.observed)

        val missing = MissionFixtures.withArrivalWait().replace(", \"deadlineSeconds\": 120", "")
        assertEquals("deadlineSeconds=없음", only(MissionRefusalKind.DEADLINE_INVALID, refusals(missing)).observed)
    }

    @Test
    fun `기한 검사 — 기한 뒤 상태가 허용 밖이거나 없으면 거부한다`() {
        val retry = MissionFixtures.withArrivalWait().replace("\"onDeadline\": \"OPERATOR_HOLD\"", "\"onDeadline\": \"RETRY\"")
        val refusal = only(MissionRefusalKind.DEADLINE_INVALID, refusals(retry))
        assertEquals("onDeadline=RETRY", refusal.observed)
        assertEquals("OPERATOR_HOLD 또는 ABORTED", refusal.expected)

        val missing = MissionFixtures.withArrivalWait().replace(", \"onDeadline\": \"OPERATOR_HOLD\"", "")
        assertEquals("onDeadline=없음", only(MissionRefusalKind.DEADLINE_INVALID, refusals(missing)).observed)
    }

    @Test
    fun `신호 검사 — 신호 사양에 없는 신호를 참조하면 거부한다`() {
        val refusal = only(MissionRefusalKind.SIGNAL_NOT_IN_SPEC, refusals(MissionFixtures.withArrivalWait(signal = "rack_ready")))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals("rack_ready", refusal.observed)
        assertTrue("rack_present" in refusal.expected, refusal.expected)
    }

    @Test
    fun `신호 검사 — 참거짓 신호에 그 밖의 값을 기다리면 거부한다`() {
        // 영영 안 끝나는 대기다 — 기한까지 서 있다가 운영자에게 간다.
        val refusal = only(MissionRefusalKind.SIGNAL_VALUE_INVALID, refusals(MissionFixtures.withArrivalWait(expect = "1")))
        assertEquals("1", refusal.observed)
    }

    @Test
    fun `자원 검사 — 대기 신호의 자리에 바닥 소유자가 없으면 거부하고 선언 없음은 통과한다`() {
        val refusal = only(
            MissionRefusalKind.FLOOR_UNOWNED,
            refusals(MissionFixtures.withArrivalWait(), floors = floors(MissionFixtures.DOCK)),
        )
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals(RefusalOwner.OUTSIDE_CONSOLE, refusal.owner)

        // ★관문과 같은 규칙 — 레지스터를 안 붙인 배치(NotDeclared)와 소유자가 있는 자리(Declared)는 통과한다.
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), floors = FloorOwnership.None))
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), floors = floors("OTHER")))
        // 자리가 없는 신호는 건너뛴다.
        val nowhere = MissionFixtures.SIGNALS.map { if (it.name == MissionFixtures.RACK_PRESENT) it.copy(location = null) else it }
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), signals = nowhere, floors = floors(MissionFixtures.DOCK)))
    }

    @Test
    fun `스킬 검사 — 계약에 없는 스킬을 거부한다`() {
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"skill\": \"pick_place\"", "\"skill\": \"pick_and_place\"")
        val refusal = only(MissionRefusalKind.SKILL_NOT_IN_CONTRACT, refusals(text))
        assertEquals("place", refusal.nodeId)
        assertEquals("pick_and_place", refusal.observed)
    }

    @Test
    fun `스킬 검사 — 현장 기체가 제공하지 않는 스킬을 거부한다`() {
        val refusal = only(
            MissionRefusalKind.SKILL_NOT_ON_SITE,
            refusals(MissionFixtures.PREPARE_SEQUENCED_RACK, siteSkills = setOf("navigate_to")),
        )
        assertEquals("pick_place", refusal.observed)
        assertEquals("그 스킬을 제공하는 기체를 현장에 둔다", refusal.nextAction)
    }

    @Test
    fun `안전 검사 — 안전 신호를 기다리는 대기를 거부한다`() {
        val refusal = only(MissionRefusalKind.SAFETY_SIGNAL_WAIT, refusals(MissionFixtures.withArrivalWait(signal = "guard_closed")))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
    }

    @Test
    fun `노드 id 가 겹치면 거부한다`() {
        val text = MissionFixtures.withArrivalWait().replace("\"id\": \"place\"", "\"id\": \"${MissionFixtures.WAIT_NODE}\"")
        val refusal = only(MissionRefusalKind.DUPLICATE_NODE_ID, refusals(text))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals("2 번 나온다", refusal.observed)
    }

    @Test
    fun `거부를 전부 모으고 각 거부가 여섯 칸과 노드를 든다`() {
        val text = MissionFixtures.withArrivalWait(deadlineSeconds = -1, signal = "rack_ready")
            .replace("\"skill\": \"pick_place\"", "\"skill\": \"teleport\"")
        val found = refusals(text)
        assertEquals(
            setOf(MissionRefusalKind.DEADLINE_INVALID, MissionRefusalKind.SIGNAL_NOT_IN_SPEC, MissionRefusalKind.SKILL_NOT_IN_CONTRACT),
            found.map { it.kind }.toSet(),
        )
        found.forEach {
            assertEquals(at, it.checkedAt, "마지막 확인 시각은 활성화를 시도한 시각이다")
            assertNull(it.basisVersion, "근거 버전은 해당 없음이다")
            assertTrue(it.nextAction.isNotBlank() && it.expected.isNotBlank() && it.observed.isNotBlank())
            assertTrue(it.nodeId != null)
        }
    }

    @Test
    fun `종류마다 후속 행동이 다르다`() {
        // 종류를 가르는 기준이 후속 행동이다 — 같은 행동을 가진 두 종류는 하나로 접혀야 한다.
        val actions = MissionRefusalKind.entries.map { it.nextAction }
        assertEquals(actions.size, actions.toSet().size, "후속 행동이 같은 종류가 있다: $actions")
    }
}
