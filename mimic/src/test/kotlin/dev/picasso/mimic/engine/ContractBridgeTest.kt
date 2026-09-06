package dev.picasso.mimic.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.picasso.contracts.v1.Resolution as ProtoResolution
import dev.picasso.contracts.v1.SkillState as ProtoSkillState
import dev.picasso.contracts.v1.TaskState as ProtoTaskState

/**
 * 엔진의 자체 enum과 계약 enum이 어긋나지 않는지 본다.
 *
 * **엔진 쪽이 늘면 컴파일이 깨진다**(`when`에 `else`가 없다). **계약 쪽이
 * 늘면 여기가 잡는다** — 컴파일은 멀쩡하기 때문이다.
 *
 * 이것이 없으면 두 벌을 두는 것이 Chunk 1 결정 1("두 벌로 쓰면 이 프로젝트가
 * 막으려는 드리프트를 우리가 낸다")과 정면으로 모순된다.
 */
class ContractBridgeTest {

    /** proto3가 강제로 넣는 둘. 엔진에는 이것들의 거동이 정의되지 않는다. */
    private val protoOnly = setOf("UNSPECIFIED", "UNRECOGNIZED")

    @Test
    fun `TaskState는 계약의 열값과 일대일이다`() {
        val contractValues = ProtoTaskState.entries
            .filterNot { v -> protoOnly.any { v.name.endsWith(it) } }
            .toSet()

        assertEquals(
            contractValues,
            TaskState.entries.map { it.toProto() }.toSet(),
            "엔진과 계약의 TaskState가 어긋났다",
        )
        // 단사여야 한다 — 둘이 같은 값으로 접히면 위 단언이 통과한다.
        assertEquals(TaskState.entries.size, TaskState.entries.map { it.toProto() }.toSet().size)
    }

    @Test
    fun `SkillState는 계약의 열값과 일대일이다`() {
        val contractValues = ProtoSkillState.entries
            .filterNot { v -> protoOnly.any { v.name.endsWith(it) } }
            .toSet()

        assertEquals(contractValues, SkillState.entries.map { it.toProto() }.toSet())
        assertEquals(SkillState.entries.size, SkillState.entries.map { it.toProto() }.toSet().size)
    }

    @Test
    fun `Resolution은 계약의 열값과 일대일이다`() {
        val contractValues = ProtoResolution.entries
            .filterNot { v -> protoOnly.any { v.name.endsWith(it) } }
            .toSet()

        assertEquals(contractValues, Resolution.entries.map { it.toProto() }.toSet())
        assertEquals(Resolution.entries.size, Resolution.entries.map { it.toProto() }.toSet().size)
    }

    @Test
    fun `Resolution은 왕복한다`() {
        Resolution.entries.forEach { assertEquals(it, it.toProto().toEngine()) }
    }

    @Test
    fun `계약이 강제로 넣는 둘은 엔진으로 못 넘어온다`() {
        // 조용히 기본값으로 접으면 태스크 종착 판정이 미정의가 된다(§10.5).
        listOf(ProtoResolution.RESOLUTION_UNSPECIFIED, ProtoResolution.UNRECOGNIZED).forEach {
            kotlin.test.assertFailsWith<IllegalStateException> { it.toEngine() }
        }
    }
}
