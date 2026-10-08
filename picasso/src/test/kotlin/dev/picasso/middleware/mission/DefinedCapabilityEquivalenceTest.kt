package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.Route
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.WaitSpec
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **데이터로 옮긴 `PrepareSequencedRack` 이 코드와 같은 계획을 내는가** — 작업 지시 모양마다, 단위마다, 칸마다.
 *
 * 기존 `SequencingRackTest` 의 작업 지시 도우미는 제시 자리를 material 로 묶은 맵으로 만들어 «같은 material 이 둘» 모양이
 * 없다. 그래서 여기서 모양을 새로 짓는다 — 짝 규칙(뒤엣것이 이긴다)이 갈리는 자리가 바로 그 모양이다.
 */
class DefinedCapabilityEquivalenceTest {

    private val code = PrepareSequencedRack()
    private val data = DefinedCapability(MissionFixtures.parsed(MissionFixtures.PREPARE_SEQUENCED_RACK))

    private fun slot(id: String, material: String?) =
        EquipmentRequirement(id, EquipmentUse.DESTINATION, material?.let { mapOf(EquipmentUse.PROP_MATERIAL to it) }.orEmpty())

    private fun bin(id: String, material: String?) =
        EquipmentRequirement(id, EquipmentUse.SOURCE, material?.let { mapOf(EquipmentUse.PROP_MATERIAL to it) }.orEmpty())

    private fun order(vararg equipment: EquipmentRequirement) =
        JobOrder("SEQ-9", PrepareSequencedRack.WORK_MASTER, version = 1, equipmentRequirements = equipment.toList())

    /** 이름 붙인 작업 지시 모양들. 이름이 실패 메시지에 나온다. */
    private val shapes: Map<String, JobOrder> = mapOf(
        "제시 자리 있음" to order(slot("S01", "A"), slot("S02", "B"), bin("BIN-A", "A"), bin("BIN-B", "B")),
        "제시 자리 없음" to order(slot("S01", "A"), slot("S02", "C"), bin("BIN-A", "A")),
        "같은 material 둘 — 뒤엣것이 이긴다" to order(bin("BIN-A1", "A"), slot("S01", "A"), bin("BIN-A2", "A"), slot("S02", "A")),
        "material 없는 슬롯" to order(slot("S01", null), slot("S02", "A"), bin("BIN-A", "A")),
        "material 없는 제시 자리" to order(slot("S01", "A"), bin("BIN-X", null), bin("BIN-A", "A")),
        "목적지 0개" to order(bin("BIN-A", "A")),
        "무관한 쓰임" to order(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_MATERIAL to "A")),
            slot("S01", "A"),
            EquipmentRequirement("BIN-Z", "staging", mapOf(EquipmentUse.PROP_MATERIAL to "A")),
            bin("BIN-A", "A"),
        ),
        "제시 자리가 슬롯보다 앞" to order(bin("BIN-B", "B"), bin("BIN-A", "A"), slot("S01", "A"), slot("S02", "B"), slot("S03", "A")),
        "빈 작업 지시" to order(),
    )

    /** 대조하는 칸. 하나씩 이름을 붙여 어느 칸이 갈렸는지 보이게 한다. */
    private val fields: Map<String, (ExecutionUnit) -> Any?> = mapOf(
        "단위 id" to { it.unitId },
        "경로" to { it.route },
        "스킬" to { it.skillType },
        "파라미터" to { it.parameters },
        "기대 식별" to { it.expectedIdentity },
        "제시 자리" to { it.source },
        "목적지" to { it.destination },
        "상태" to { it.state },
        "실패 분류" to { it.failureClass },
        "대기 사양" to { it.wait },
    )

    @Test
    fun `작업 지시 모양마다 데이터 정의가 코드와 같은 계획을 칸마다 낸다`() {
        shapes.forEach { (shape, order) ->
            val expected = code.plan(order)
            val actual = data.plan(order)
            assertEquals(expected.size, actual.size, "$shape: 단위 수가 갈렸다")
            expected.zip(actual).forEachIndexed { at, (e, a) ->
                fields.forEach { (field, of) ->
                    assertEquals(of(e), of(a), "$shape: ${at + 1}번째 단위의 $field 이 갈렸다")
                }
                // 칸 목록이 낡으면(새 칸이 생기면) 위 대조가 빠뜨린다 — 통째로도 댄다.
                assertEquals(e, a, "$shape: ${at + 1}번째 단위가 갈렸다")
            }
        }
    }

    @Test
    fun `모양들이 짝 규칙의 갈래를 전부 밟는다`() {
        // ★대조가 무언가를 가리려면 갈래가 실제로 나와야 한다 — 전부 짝이 있으면 실패 분류 칸은 늘 널끼리 같다.
        val planned = shapes.values.flatMap { code.plan(it) }
        assertTrue(planned.any { it.state == UnitState.FAILED && it.failureClass == PrepareSequencedRack.NO_SOURCE })
        assertTrue(planned.any { it.state == UnitState.PENDING })
        assertTrue(planned.any { it.expectedIdentity == null }, "material 없는 슬롯이 안 나왔다")
        assertTrue(planned.any { it.source == "BIN-A2" }, "같은 material 의 뒤엣것이 안 나왔다")
        assertTrue(shapes.values.any { code.plan(it).isEmpty() }, "목적지 0개가 안 나왔다")
    }

    @Test
    fun `최고 근거 등급과 선택 파라미터와 WorkMaster 가 같다`() {
        assertEquals(code.workMasterId, data.workMasterId)
        assertEquals(code.maxEvidence, data.maxEvidence)
        assertEquals(code.preferredOptionals, data.preferredOptionals)
        assertEquals(code.evidenceWindow, data.evidenceWindow)
        assertEquals(code.inDoubtGrace, data.inDoubtGrace)
        assertEquals(code.stallWindow, data.stallWindow)
    }

    @Test
    fun `대기 노드는 신호 경로의 단위 하나가 되고 사양을 파라미터에도 싣는다`() {
        val capability = DefinedCapability(MissionFixtures.parsed(MissionFixtures.withArrivalWait(deadlineSeconds = 90, onDeadline = DeadlineOutcome.ABORTED)))
        val planned = capability.plan(shapes.getValue("제시 자리 있음"))

        val wait = planned.first()
        assertEquals(MissionFixtures.WAIT_NODE, wait.unitId)
        assertEquals(Route.SIGNAL, wait.route)
        assertEquals(WaitSpec.SKILL_TYPE, wait.skillType)
        assertEquals(UnitState.PENDING, wait.state)
        assertEquals(WaitSpec(MissionFixtures.RACK_PRESENT, "true", Duration.ofSeconds(90), DeadlineOutcome.ABORTED), wait.wait)
        assertEquals(
            mapOf("signal" to "rack_present", "expect" to "true", "deadlineSeconds" to "90", "onDeadline" to "ABORTED"),
            wait.parameters,
        )
        // 대기 뒤는 버전 1 과 같은 계획이다.
        assertEquals(code.plan(shapes.getValue("제시 자리 있음")), planned.drop(1))
    }

    @Test
    fun `대기 단위의 스킬 이름은 계약 카탈로그와 겹치지 않는다`() {
        // 겹치면 효과·사전 조건·선택 파라미터를 읽는 자리가 대기 단위를 그 스킬로 잘못 읽는다.
        assertTrue(MissionValidator.contractSkills.isNotEmpty())
        assertTrue(WaitSpec.SKILL_TYPE !in MissionValidator.contractSkills)
    }

    @Test
    fun `검증을 거치지 않은 기한은 해석기가 받지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(MissionFixtures.parsed(MissionFixtures.withArrivalWait(deadlineSeconds = 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(
                MissionFixtures.parsed(MissionFixtures.withArrivalWait().replace("\"OPERATOR_HOLD\"", "\"RETRY\"")),
            )
        }
    }
}
