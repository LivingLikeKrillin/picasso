package dev.picasso.mimic.engine

import dev.picasso.contracts.v1.FailureClass
import dev.picasso.profile.ProfileDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 프로파일의 실패 모드 → 계약의 정준 분류(`Fault.failure_class`, 미들웨어 중앙 설계 §1.4).
 *
 * 미믹에는 벤더가 없다. 그래서 규칙이 셋뿐이고 그 셋을 여기서 붙든다 — 선언이 이긴다 ·
 * 이름이 같은 코어만 유도한다 · 나머지는 `UNCLASSIFIED` 이며 **스킬 이름으로 추측하지 않는다.**
 */
class FailureClassTest {

    private fun mode(errorType: String, failureClass: String? = null, skillType: String? = "pick_place") =
        ProfileDocument.FailureModeEntry(
            errorType = errorType,
            skillType = skillType,
            resolution = "SELF_RETRIABLE",
            rate = 0.1,
            canContinueCurrentTask = false,
            canAcceptNewTask = true,
            errorHint = "",
            activeUntil = null,
            failureClass = failureClass,
        )

    @Test
    fun `프로파일이 선언한 분류가 이긴다`() {
        val fault = FailureDraw.faultOf(mode("SKILL_EXECUTION_FAILED", failureClass = "GRASP_FAILED"), "pick_place", "t-1")
        assertEquals(FailureClass.FAILURE_CLASS_GRASP_FAILED, fault.failureClass)
    }

    @Test
    fun `이름이 같은 코어 셋은 유도한다`() {
        assertEquals(FailureClass.FAILURE_CLASS_LOCALIZATION_LOST, FailureDraw.failureClassOf(mode("LOCALIZATION_LOST", skillType = null)))
        assertEquals(FailureClass.FAILURE_CLASS_PAYLOAD_LOST, FailureDraw.failureClassOf(mode("PAYLOAD_LOST")))
        assertEquals(FailureClass.FAILURE_CLASS_CONTROL_AUTHORITY_LOST, FailureDraw.failureClassOf(mode("CONTROL_AUTHORITY_LOST", skillType = null)))
    }

    @Test
    fun `그 밖은 UNCLASSIFIED 다 — pick_place 의 SKILL_EXECUTION_FAILED 를 잡기 실패라 추측하지 않는다`() {
        assertEquals(FailureClass.FAILURE_CLASS_UNCLASSIFIED, FailureDraw.failureClassOf(mode("SKILL_EXECUTION_FAILED")))
        assertEquals(FailureClass.FAILURE_CLASS_UNCLASSIFIED, FailureDraw.failureClassOf(mode("X_FIXTURE_SIMULATED_HARDWARE_FAULT", skillType = null)))
    }

    @Test
    fun `벤더 이름공간의 모드만 원문 자리를 채운다`() {
        assertEquals("X_FIXTURE_SIMULATED_HARDWARE_FAULT", FailureDraw.faultOf(mode("X_FIXTURE_SIMULATED_HARDWARE_FAULT", skillType = null), "", "t-1").vendorDetail)
        assertEquals("", FailureDraw.faultOf(mode("SKILL_EXECUTION_FAILED"), "pick_place", "t-1").vendorDetail)
    }
}
