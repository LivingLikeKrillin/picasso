package dev.picasso.gate.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `같은 이름과 major가 둘이면 실패한다`() {
        // 설계 §8.3이 UNIQUE(name, major)를 규정한다. firstOrNull로 조용히
        // 하나만 보면 검사 4번의 무결성이 무너진다.
        assertTrue(index.duplicates().isEmpty())
    }
}
