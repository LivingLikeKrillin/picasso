package dev.picasso.contracts

import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.SkillDeclaration
import kotlin.test.Test
import dev.picasso.contracts.v1.SkillCatalog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedTypesTest {

    @Test
    fun `Capability를 만들 수 있다`() {
        // 생성만 확인하고 넘어가면 다음 태스크에서 무너진다.
        val capability = Capability.newBuilder()
            .setVendor("acme")
            .setModel("r1")
            .setProfileRevision(1)
            .addSkills(SkillDeclaration.newBuilder().setSkillType("navigate_to").setMajor(1))
            .build()

        assertEquals("acme", capability.vendor)
        assertEquals(1, capability.skillsCount)
    }

    @Test
    fun `제약의 부재와 0을 구분한다`() {
        // ParameterDeclaration의 제약 넷이 명시적 존재인 이유가 이것이다.
        // 암묵 존재로 두면 "하한을 선언하지 않았다"와 "하한이 0이다"가 같아지고,
        // 투영이 없는 제약을 있다고 거짓말한다.
        val undeclared = ParameterDeclaration.newBuilder().setKey("verify_grasp").build()
        assertFalse(undeclared.hasMinValue())
        assertFalse(undeclared.hasMaxValue())
        assertFalse(undeclared.hasMaxLength())
        assertFalse(undeclared.hasUnit())

        val zeroLowerBound = ParameterDeclaration.newBuilder()
            .setKey("grip_force")
            .setMinValue(0.0)
            .build()
        assertTrue(zeroLowerBound.hasMinValue())
        assertEquals(0.0, zeroLowerBound.minValue)
    }

    @Test
    fun `카탈로그가 스킬의 hold 효과를 말한다`() {
        // 설계안 §1.1 — 쥐고(grasps) 놓으면(releases) 끝난 뒤 빈손이다. 참조만 하는 스킬은 둘 다 아니다.
        fun options(name: String) = SkillCatalog.getDescriptor().findMessageTypeByName(name)!!.options
        assertTrue(options("PickPlaceV1").getExtension(SkillCatalog.graspsObject))
        assertTrue(options("PickPlaceV1").getExtension(SkillCatalog.releasesObject))
        assertFalse(options("InspectV1").getExtension(SkillCatalog.graspsObject))
        assertFalse(options("InspectV1").getExtension(SkillCatalog.releasesObject))
    }
}
