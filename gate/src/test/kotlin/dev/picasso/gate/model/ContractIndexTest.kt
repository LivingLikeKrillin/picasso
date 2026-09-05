package dev.picasso.gate.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractIndexTest {

    private fun descriptorBytes(): ByteArray {
        val p = System.getProperty("picasso.descriptor")
            ?: error("picasso.descriptor 시스템 프로퍼티가 없다 — gate/build.gradle.kts 배선을 보라")
        val path = Path.of(p)
        check(Files.exists(path)) {
            "디스크립터가 없다: $p\n" +
                "먼저 만들어라:\n" +
                "  mkdir -p contracts/build && ( cd contracts && ../tools/buf build -o build/descriptor.binpb )"
        }
        return Files.readAllBytes(path)
    }

    private val index by lazy { ContractIndex.from(descriptorBytes()) }

    @Test
    fun `카탈로그의 스킬 타입을 전부 찾는다`() {
        assertEquals(setOf("pick_place", "navigate_to"), index.skillTypes().toSet())
    }

    @Test
    fun `pick_place의 major와 max_minor를 복구한다`() {
        val s = assertNotNull(index.find("pick_place", major = 1))
        assertEquals(1, s.major)
        assertEquals(2, s.maxMinor)
        assertTrue(s.maxMinorDeclared)
    }

    @Test
    fun `명시적으로 선언한 0과 미선언을 구분한다`() {
        // proto2 존재성 — navigate_to는 max_minor를 0으로 명시 선언했다.
        // 이 구분이 없으면 검사 4번의 최신 minor 초과 판정이 무너진다.
        val s = assertNotNull(index.find("navigate_to", major = 1))
        assertEquals(0, s.maxMinor)
        assertTrue(s.maxMinorDeclared, "명시 선언된 0을 미선언으로 읽었다")
    }

    @Test
    fun `파라미터의 키와 since_minor와 is_optional을 복구한다`() {
        val s = assertNotNull(index.find("pick_place", major = 1))

        assertEquals(
            listOf("object_id", "destination", "verify_grasp", "grip_force", "grasp_attempts"),
            s.parameters.map { it.key },
        )

        val byKey = s.parameters.associateBy { it.key }
        assertEquals(0, byKey.getValue("object_id").sinceMinor)
        assertEquals(false, byKey.getValue("object_id").isOptional)

        // 처음부터 선택이었던 파라미터 — since_minor > 0으로 유도할 수 없다는 근거
        assertEquals(0, byKey.getValue("verify_grasp").sinceMinor)
        assertEquals(true, byKey.getValue("verify_grasp").isOptional)

        assertEquals(2, byKey.getValue("grip_force").sinceMinor)
        assertEquals(true, byKey.getValue("grip_force").isOptional)
    }

    @Test
    fun `proto 타입을 프로파일의 ValueType으로 옮긴다`() {
        val byKey = assertNotNull(index.find("pick_place", major = 1))
            .parameters.associateBy { it.key }

        assertEquals(ValueType.STRING, byKey.getValue("object_id").valueType)
        assertEquals(ValueType.BOOL, byKey.getValue("verify_grasp").valueType)
        assertEquals(ValueType.NUMBER, byKey.getValue("grip_force").valueType)
        assertEquals(ValueType.INTEGER, byKey.getValue("grasp_attempts").valueType)
    }

    @Test
    fun `선언한 minor에 유효한 필수 파라미터를 추린다`() {
        // 검사 4번의 양방향 대조가 쓰는 질의다.
        val s = assertNotNull(index.find("pick_place", major = 1))

        assertEquals(
            setOf("object_id", "destination"),
            s.requiredParametersAt(minor = 0).map { it.key }.toSet(),
        )
        // minor 2에서도 필수는 늘지 않는다 — 추가된 둘은 선택이다.
        assertEquals(
            setOf("object_id", "destination"),
            s.requiredParametersAt(minor = 2).map { it.key }.toSet(),
        )
    }

    @Test
    fun `지금 계약에는 중복도 도달 불가능한 파라미터도 없다`() {
        assertTrue(index.duplicates().isEmpty())
        assertFalse(index.isEmpty())
        assertTrue(index.scannedFiles > 0)
        index.all().forEach {
            assertTrue(it.unreachableParameters().isEmpty(), "도달 불가능한 파라미터: $it")
        }
    }

    // ── 계약 저작 실수. 전부 조용히 삼키던 것들이라 실제 디스크립터를
    //    변형해 확인한다. 픽스처를 커밋하면 계약이 바뀔 때 낡는다.

    @Test
    fun `같은 이름과 major가 둘이면 duplicates가 잡고 find가 죽는다`() {
        // §8.3의 UNIQUE(name, major). duplicates()가 emptyList()를 돌려주도록
        // 잘못 고쳐도 초록이던 자리다.
        val mutated = ContractIndex.from(
            DescriptorMutation.duplicateSkill(descriptorBytes(), "PickPlaceV1"),
        )
        assertEquals(listOf("pick_place" to 1), mutated.duplicates())

        // find가 조용히 첫 번째만 보면 minor 판정이 어느 쪽 기준인지 미정의가 된다.
        assertFailsWith<IllegalStateException> { mutated.find("pick_place", 1) }
    }

    @Test
    fun `repeated 파라미터를 스칼라로 접지 않는다`() {
        // 접으면 프로파일이 그것을 평범한 STRING으로 선언해도 검사 4번이
        // 통과시키고 카디널리티 불일치가 런타임까지 간다.
        val bytes = DescriptorMutation.makeRepeated(descriptorBytes(), "PickPlaceV1", "object_id")
        val e = assertFailsWith<IllegalStateException> { ContractIndex.from(bytes) }
        assertTrue(e.message!!.contains("object_id"))
    }

    @Test
    fun `skill_type_major 미선언을 0으로 읽지 않는다`() {
        // proto2 기본값 0이 조용히 들어오면 프로파일 스키마의 major >= 1과
        // 어긋나 find가 못 찾고, 검사 4번은 "proto에 없는 스킬"이라며
        // 프로파일을 지목한다 — 진짜 버그는 proto에 있는데.
        val bytes = DescriptorMutation.dropMajor(descriptorBytes(), "NavigateToV1")
        val e = assertFailsWith<IllegalStateException> { ContractIndex.from(bytes) }
        assertTrue(e.message!!.contains("skill_type_major"))
    }

    @Test
    fun `max_minor를 넘는 since_minor는 도달 불가능하다`() {
        // pick_place의 max_minor는 2다. 필수 파라미터에 since_minor=7을 주면
        // 어떤 유효한 minor에서도 나타나지 않아 양방향 대조가 영영 못 본다.
        val bytes = DescriptorMutation.setSinceMinor(descriptorBytes(), "PickPlaceV1", "object_id", 7)
        val skill = assertNotNull(ContractIndex.from(bytes).find("pick_place", 1))

        assertTrue(skill.requiredParametersAt(2).none { it.key == "object_id" })
        assertEquals(listOf("object_id"), skill.unreachableParameters().map { it.key })
    }

    @Test
    fun `중첩된 스킬 메시지를 조용히 무시하지 않는다`() {
        val bytes = DescriptorMutation.nestSkill(descriptorBytes(), "NavigateToV1")
        val e = assertFailsWith<IllegalStateException> { ContractIndex.from(bytes) }
        assertTrue(e.message!!.contains("최상위"))
    }

    @Test
    fun `빈 디스크립터는 빈 색인이 된다`() {
        // 0바이트 디스크립터(buf가 중간에 죽어 빈 파일을 남긴 경우)는 예외
        // 없이 통과한다. 검사 4번이 isEmpty()를 보고 실패시켜야 한다.
        val empty = ContractIndex.from(ByteArray(0))
        assertTrue(empty.isEmpty())
        assertEquals(0, empty.scannedFiles)
    }
}
