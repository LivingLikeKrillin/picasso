package dev.picasso.registry.web

import dev.picasso.registry.binding.Binding
import dev.picasso.registry.binding.BindingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 바인딩의 조작 문(§9.1, picasso-ops P2·S1d 스펙 §6.2). 관문은 [OperatorToken] 이 경로로 건다.
 *
 * 404 와 409 는 본문 `reason` 으로 가른다. 화면이 할 일이 거절마다 다르다 — 없는 기체는 등록부터, 퇴역 기체는 복귀부터,
 * 활성 아닌 개정판은 활성화부터, 낮은 계약은 다른 빌드다.
 */
@RestController
class BindingOperationsController(private val bindings: BindingService) {

    @PostMapping("/operations/robots/{robotId}/binding")
    fun bind(
        @PathVariable robotId: String,
        @RequestBody request: BindRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any?>> {
        val build = request.adapter_version_id
        val revision = request.profile_revision_id
        if (build == null || revision == null) {
            return ResponseEntity.badRequest().body(mapOf("error" to "adapter_version_id 와 profile_revision_id 가 필요하다"))
        }
        return when (val outcome = bindings.bindRobot(robotId, build, revision, actor, request.reason)) {
            is Binding.Bound -> ResponseEntity.status(HttpStatus.CREATED)
                .body(mapOf("robot_binding_id" to outcome.bindingId, "unbound_binding_id" to outcome.unbound))
            is Binding.AlreadyBound -> ResponseEntity.ok(mapOf("robot_binding_id" to outcome.bindingId, "already" to true))
            Binding.UnknownRobot -> notFound("UNKNOWN_ROBOT", "모르는 기체다: $robotId")
            Binding.UnknownRevision -> notFound("UNKNOWN_REVISION", "없는 개정판이다: $revision")
            Binding.UnknownBuild -> notFound("UNKNOWN_BUILD", "없는 어댑터 빌드다: $build")
            Binding.RobotRetired -> conflict(mapOf("reason" to "ROBOT_RETIRED", "error" to "퇴역한 기체다 — 묶으려면 복귀시킨다"))
            is Binding.RevisionNotActive -> conflict(
                mapOf("reason" to "REVISION_NOT_ACTIVE", "error" to "활성 개정판이 아니다", "status" to outcome.status.name),
            )
            is Binding.ContractTooOld -> conflict(
                mapOf(
                    "reason" to "CONTRACT_TOO_OLD",
                    "error" to "빌드의 계약 semver 가 개정판의 스킬보다 낮다",
                    "contract_semver" to outcome.contractSemver,
                    "too_new" to outcome.tooNew.map { (skill, since) -> mapOf("skill_type" to skill, "introduced_in_semver" to since) },
                ),
            )
        }
    }

    private fun notFound(reason: String, error: String): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("reason" to reason, "error" to error))

    private fun conflict(body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(body)
}

/** `POST /operations/robots/{robotId}/binding` 의 본문. 숫자 칸이 빠지면 0 이 아니라 널로 받아 400 으로 가른다. */
data class BindRequest(
    val adapter_version_id: Long? = null,
    val profile_revision_id: Long? = null,
    val reason: String? = null,
)
