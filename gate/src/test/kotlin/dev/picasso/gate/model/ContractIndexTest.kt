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
        // **집합을 못박는 것이 이 단언의 일이다.** 스킬을 하나 더하면 여기가
        // 빨개지고, 그 빨강이 "계약 어휘가 늘었다"를 사람 눈앞에 세운다.
        // 어휘가 조용히 느는 것은 이 저장소가 막으려는 것 중 하나다.
        assertEquals(
            setOf("pick_place", "navigate_to", "inspect", "move_relative"),
            index.skillTypes().toSet(),
        )
    }

    @Test
    fun `move_relative의 파라미터 넷이 전부 필수다`() {
        // **넷째 스킬은 실물이 데려왔다**(skill_catalog.proto 상단). G1의
        // `SetVelocity(vx, vy, omega, duration)`와 자리가 같아야 어댑터가
        // 지어내지 않고 옮길 수 있다. 하나라도 선택으로 새면 어댑터가 그
        // 값을 스스로 정하게 되고, 그것은 로봇이 아니라 우리가 정한 값이
        // 로봇의 선언인 척하는 것이다.
        val s = assertNotNull(index.find("move_relative", major = 1))
        assertEquals(0, s.maxMinor)
        assertTrue(s.maxMinorDeclared)

        assertEquals(
            listOf("forward_speed", "lateral_speed", "yaw_rate", "duration"),
            s.parameters.map { it.key },
        )
        s.parameters.forEach {
            assertEquals(ValueType.NUMBER, it.valueType, "${it.key} 의 값 타입")
            assertFalse(it.isOptional, "${it.key} 이 선택으로 선언됐다")
            assertEquals(0, it.sinceMinor)
        }
    }

    @Test
    fun `장소의 이름과 대상의 이름을 계약이 갈라 표시한다`() {
        // **ADR 35의 기계적 근거이고, 설계 §15.78의 기계적 근거이기도 하다.**
        // `registry` 가 바인딩마다 무엇을 등록해야 하는지를 장소 표시와
        // 프로파일이 선언한 스킬에서 유도한다 — 손으로 적는 목록이 없다.
        //
        // **넷을 한 덩어리로 두면 안 되는 이유가 여기 있다.** 앞 판은 불리언
        // 하나로 넷을 전부 사이트 이름이라 표시했고, 그래서 레지스트리가
        // `object_id` 까지 *"등록하라"* 고 요구했다. 대상은 등록하는 것이
        // 아니라 관측되는 것이다(§1.3 비목표).
        //
        // **값 타입으로 유도할 수 없다.** `move_relative` 의 넷이 전부 아니고,
        // 이름 넷이 `STRING` 이지만 `STRING` 이 곧 이름은 아니다.
        val places = mapOf(
            "navigate_to" to setOf("location"),
            "pick_place" to setOf("destination"),
            "inspect" to emptySet(),
            "move_relative" to emptySet(),
        )
        val objects = mapOf(
            "navigate_to" to emptySet(),
            "pick_place" to setOf("object_id"),
            "inspect" to setOf("target"),
            "move_relative" to emptySet(),
        )

        places.forEach { (skill, names) ->
            val def = assertNotNull(index.find(skill, major = 1), skill)
            assertEquals(
                names,
                def.parameters.filter { it.isSiteReference }.map { it.key }.toSet(),
                "$skill 의 장소 이름",
            )
            assertEquals(
                objects.getValue(skill),
                def.parameters.filter { it.isObjectReference }.map { it.key }.toSet(),
                "$skill 의 대상 이름",
            )
        }

        // **표시가 하나도 없으면 위 단언이 전부 빈 집합을 기대하는 것과
        // 구별되지 않는다.** 양쪽 바닥을 각각 못박는다.
        assertEquals(2, places.values.sumOf { it.size }, "장소 표시가 사라졌다")
        assertEquals(2, objects.values.sumOf { it.size }, "대상 표시가 사라졌다")
    }

    @Test
    fun `한 파라미터가 장소이면서 대상일 수는 없다`() {
        // **이 시험이 §15.78을 지킨다.** 둘 다 붙은 파라미터가 생기면 그
        // 순간 다시 한 덩어리가 되고, 등록 유도가 등록할 수 없는 것을
        // 집어 든다. 표시를 갈라 놓기만 하고 겹침을 막지 않으면 갈라 둔
        // 것이 조용히 원상 복구된다.
        val both = index.all().flatMap { def ->
            def.parameters.filter { it.isSiteReference && it.isObjectReference }
                .map { "${def.name}.${it.key}" }
        }
        assertEquals(emptyList(), both, "장소와 대상을 겸한 파라미터가 있다")
    }

    @Test
    fun `pick_place의 major와 max_minor를 복구한다`() {
        val s = assertNotNull(index.find("pick_place", major = 1))
        assertEquals(1, s.major)
        assertEquals(2, s.maxMinor)
        assertTrue(s.maxMinorDeclared)
    }

    @Test
    fun `ENUM 파라미터를 열값 타입으로 읽는다`() {
        // 계약은 열거의 **자리**만 만들고 값 집합은 프로파일이 정한다
        // (skill_catalog.proto의 매핑 표). 그 자리가 ValueType.ENUM으로
        // 읽히지 않으면 검사 4번이 프로파일의 ENUM 선언을 타입 불일치로 막는다.
        val inspect = assertNotNull(index.find("inspect", major = 1))
        assertEquals(3, inspect.maxMinor)
        val mode = assertNotNull(inspect.parameters.firstOrNull { it.key == "mode" })
        assertEquals(ValueType.ENUM, mode.valueType)
        assertEquals(3, mode.sinceMinor)
        assertTrue(mode.isOptional)
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
