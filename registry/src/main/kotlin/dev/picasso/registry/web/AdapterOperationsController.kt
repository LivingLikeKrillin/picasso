package dev.picasso.registry.web

import dev.picasso.registry.adapter.AdapterDeclared
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.VersionDeclared
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * P1 — 어댑터 제품·빌드의 조작 문(시운전 Step 2). **조작 문이다** — 사람이 *"이 빌드를 들였다"* 고 적는다.
 * 관문은 [OperatorToken] 이 경로로 건다.
 *
 * 생긴 이유는 첫 바깥 소비자다(ADR 9). picasso-ops 의 운영 화면이 인스턴스를 등록하려면 고를 빌드가
 * 있어야 하는데, 빌드를 넣는 문이 없어 시험만 서비스를 직접 불렀다.
 *
 * 응답 코드는 결과마다 하나다. **400 과 409 를 접지 않는다** — 앞은 «고쳐서 다시», 뒤는 «다른 버전 번호로» 다.
 */
@RestController
class AdapterOperationsController(private val adapters: AdapterService) {

    @PostMapping("/operations/adapters")
    fun declareAdapter(
        @RequestBody request: DeclareAdapterRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (val outcome = adapters.declareAdapter(request.vendor, request.name, actor)) {
        is AdapterDeclared.Created -> ResponseEntity.status(HttpStatus.CREATED).body(mapOf("adapter_id" to outcome.adapterId))
        is AdapterDeclared.Existing -> ResponseEntity.ok(mapOf("adapter_id" to outcome.adapterId))
        is AdapterDeclared.Rejected -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
    }

    @PostMapping("/operations/adapters/{adapterId}/versions")
    fun declareVersion(
        @PathVariable adapterId: Long,
        @RequestBody request: DeclareVersionRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (
        val outcome = adapters.declareVersion(adapterId, request.version, request.contract_semver, actor)
    ) {
        is VersionDeclared.Created ->
            ResponseEntity.status(HttpStatus.CREATED).body(mapOf("adapter_version_id" to outcome.adapterVersionId))
        is VersionDeclared.Existing -> ResponseEntity.ok(mapOf("adapter_version_id" to outcome.adapterVersionId))
        is VersionDeclared.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "같은 버전이 다른 계약 semver 로 이미 있다", "existing_contract_semver" to outcome.existingContractSemver),
        )
        is VersionDeclared.BadSemver -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
        is VersionDeclared.UnknownAdapter ->
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "모르는 어댑터다: ${outcome.adapterId}"))
        is VersionDeclared.Rejected -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
    }

    /** 조작 문 뒤의 읽기다 — 등록하러 온 사람이 **조작 직전에** 고를 빌드를 보는 흐름이라 같은 문에 둔다. */
    @GetMapping("/operations/adapters")
    fun list(): List<AdapterResponse> = adapters.list().map { row ->
        AdapterResponse(
            adapter_id = row.adapterId,
            vendor = row.vendor,
            name = row.name,
            versions = row.versions.map { v ->
                AdapterVersionResponse(
                    adapter_version_id = v.adapterVersionId,
                    version = v.version,
                    contract_semver = v.contractSemver,
                    conformance = v.conformance,
                    registered_at = v.registeredAt,
                    registered_by = v.registeredBy,
                )
            },
        )
    }
}

/**
 * `GET /operations/adapters` 의 행. **응답 모양(snake_case)은 이 문이 정한다** — 서비스의 `AdapterRow` 는 HTTP 를
 * 모르는 camelCase 다(같은 패키지의 `AdapterInstanceRow` 와 같은 배치). 키는 picasso-ops S1 스펙 §5 의 응답 모양 그대로다.
 */
data class AdapterResponse(
    val adapter_id: Long,
    val vendor: String,
    val name: String,
    val versions: List<AdapterVersionResponse>,
)

data class AdapterVersionResponse(
    val adapter_version_id: Long,
    val version: String,
    val contract_semver: String,
    val conformance: String,
    val registered_at: String,
    val registered_by: String,
)

/** `POST /operations/adapters` 의 본문. */
data class DeclareAdapterRequest(val vendor: String = "", val name: String = "")

/** `POST /operations/adapters/{adapterId}/versions` 의 본문. */
data class DeclareVersionRequest(val version: String = "", val contract_semver: String = "")
