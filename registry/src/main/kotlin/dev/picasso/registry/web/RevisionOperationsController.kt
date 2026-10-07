package dev.picasso.registry.web

import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.registry.binding.Activation
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.revision.RevisionListing
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeCatalog
import dev.picasso.registry.revision.Submitted
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 개정판의 조작 문(§8.4 ①③, picasso-ops P2·S1d 스펙 §6.2) — 스킬 종류 조회, 제출, 목록, 활성화. 관문은
 * [OperatorToken] 이 경로로 건다.
 *
 * 생긴 이유는 첫 바깥 소비자다(ADR 9). picasso-ops 의 운영 화면이 개정판을 제출하고 활성화하려 했으나 서비스가
 * 빈(bean)조차 없었다.
 */
@RestController
class RevisionOperationsController(
    private val revisions: RevisionService,
    private val listing: RevisionListing,
    private val skillTypes: SkillTypeCatalog,
    private val bindings: BindingService,
) {

    /** 계약이 아는 스킬 종류와 지금 계약 semver. 제출할 문서를 쓰는 사람이 무엇을 선언할 수 있는지 본다. */
    @GetMapping("/operations/skill-types")
    fun skillTypes(): Map<String, Any> = mapOf(
        "contract_semver" to ContractIdentity.semver,
        "skill_types" to skillTypes.list().map {
            mapOf(
                "name" to it.name,
                "major" to it.major,
                "introduced_in_semver" to it.introducedInSemver,
                "site_reference_keys" to it.siteReferenceKeys,
            )
        },
    )

    /**
     * 본문은 프로파일 문서 JSON **그대로**다. 문서 해시가 이 문자열로 매겨지므로 다시 직렬화하면 같은 문서의 재제출이
     * 다른 문서로 보인다.
     *
     * 검증에 실패해도 저장하므로 `DRAFT` 도 201 이다(§8.4 ①).
     */
    @PostMapping("/operations/profile-revisions")
    fun submit(
        @RequestBody document: String,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (val outcome = revisions.submitDocument(document, actor)) {
        is Submitted.Created -> ResponseEntity.status(HttpStatus.CREATED)
            .body(stored(outcome.profileRevisionId, outcome.revision, outcome.status.name, outcome.reasons))
        is Submitted.Existing ->
            ResponseEntity.ok(stored(outcome.profileRevisionId, outcome.revision, outcome.status.name, outcome.reasons))
        is Submitted.NotMonotonic -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "개정판 번호가 단조 증가하지 않는다(같은 번호에 다른 문서 포함)",
                "received" to outcome.received,
                "highest" to outcome.highest,
            ),
        )
        is Submitted.Unreadable -> ResponseEntity.badRequest().body(mapOf("error" to outcome.detail))
    }

    @GetMapping("/operations/profile-revisions")
    fun list(): List<Map<String, Any?>> = listing.list().map { row ->
        mapOf(
            "profile_revision_id" to row.profileRevisionId,
            "vendor" to row.vendor,
            "model" to row.model,
            "revision" to row.revision,
            "status" to row.status.name,
            "reasons" to row.reasons,
            "document_hash" to row.documentHash,
            "created_by" to row.createdBy,
            "created_at" to row.createdAt.toString(),
            "activated_by" to row.activatedBy,
            "activated_at" to row.activatedAt?.toString(),
            "suites" to row.suites.mapValues { (_, run) ->
                mapOf(
                    "result" to run.result,
                    "ran_at" to run.ranAt.toString(),
                    "ran_by" to run.ranBy,
                    "detail" to run.detail?.let { MAPPER.readTree(it) },
                )
            },
            "latest_test_request" to row.latestRequest?.let { r ->
                mapOf(
                    "request_id" to r.requestId,
                    "requested_by" to r.requestedBy,
                    "requested_at" to r.requestedAt.toString(),
                    "claimed_by" to r.claimedBy,
                    "claimed_at" to r.claimedAt?.toString(),
                    "claim_expires_at" to r.claimExpiresAt?.toString(),
                    "completed_at" to r.completedAt?.toString(),
                )
            },
        )
    }

    /** 409 본문에 상태와 스위트별 최신 결과를 싣는다 — 화면이 «왜 안 되는가» 를 다시 묻지 않게 한다. */
    @PostMapping("/operations/profile-revisions/{profileRevisionId}/activation")
    fun activate(
        @PathVariable profileRevisionId: Long,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any?>> = when (val outcome = bindings.activateRevision(profileRevisionId, actor)) {
        is Activation.Activated -> ResponseEntity.ok(mapOf("status" to "ACTIVE", "superseded" to outcome.superseded))
        Activation.AlreadyActive -> ResponseEntity.ok(mapOf("status" to "ACTIVE", "already" to true))
        is Activation.Refused -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "활성화할 수 없다 — 상태가 TESTED·SUPERSEDED 이고 세 스위트의 최신 결과가 모두 PASS 여야 한다",
                "status" to outcome.status.name,
                "suites" to outcome.latest,
            ),
        )
        Activation.Unknown ->
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "없는 개정판이다: $profileRevisionId"))
    }

    private fun stored(id: Long, revision: Int, status: String, reasons: List<String>): Map<String, Any> =
        mapOf("profile_revision_id" to id, "revision" to revision, "status" to status, "reasons" to reasons)

    private companion object {
        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
