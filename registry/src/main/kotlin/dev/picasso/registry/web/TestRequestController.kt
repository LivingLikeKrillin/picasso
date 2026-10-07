package dev.picasso.registry.web

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.registry.testing.ReportOutcome
import dev.picasso.registry.testing.SuiteRun
import dev.picasso.registry.testing.TestRequestService
import dev.picasso.registry.testing.TestRequested
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * §8.4 ②의 시험 요청을 문으로 낸다(picasso-ops P2·S1d 스펙 §5.1). **문이 둘로 갈린다.**
 *
 * 요청은 조작 문이다 — 사람이 *"이 개정판을 시험하라"* 고 적는다. 집기와 보고는 적재 문이다 — 기계가 관측한 것을
 * 올린다. 운영자 토큰만 쥔 쪽(운영 화면)은 결과를 적을 길이 없고, 그것을 토큰 경계가 지킨다. 관문은
 * [OperatorToken] 과 [IngestToken] 이 경로로 건다.
 *
 * 생긴 이유는 첫 바깥 소비자다(ADR 9). picasso-ops 의 가짜 현장이 실행기를 띄우고 운영 화면이 시험을 요청한다.
 */
@RestController
class TestRequestController(private val requests: TestRequestService) {

    @PostMapping("/operations/profile-revisions/{profileRevisionId}/test-requests")
    fun request(
        @PathVariable profileRevisionId: Long,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (val outcome = requests.requestTest(profileRevisionId, actor)) {
        is TestRequested.Created -> ResponseEntity.status(HttpStatus.CREATED).body(mapOf("request_id" to outcome.requestId))
        is TestRequested.Existing -> ResponseEntity.ok(mapOf("request_id" to outcome.requestId))
        TestRequested.UnknownRevision ->
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "없는 개정판이다: $profileRevisionId"))
        is TestRequested.NotTestable -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "시험할 수 없는 상태다", "status" to outcome.status),
        )
    }

    /**
     * 하나를 집는다. 집을 것이 없으면 204 다.
     *
     * 문서는 **문자열로** 준다. 실행기가 그것을 파일로 써서 스키마 검사를 거치게 하므로, 다시 직렬화한 JSON 이 아니라
     * 제출된 문서 그대로여야 한다.
     */
    @PostMapping("/ingest/test-requests/claim")
    fun claim(@RequestBody body: ClaimBody): ResponseEntity<Map<String, Any>> {
        if (body.worker.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "worker 가 비었다"))
        val claimed = requests.claim(body.worker) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(
            mapOf(
                "request_id" to claimed.requestId,
                "profile_revision_id" to claimed.profileRevisionId,
                "claimed_at" to claimed.claimedAt.toString(),
                "document" to claimed.documentJson,
            ),
        )
    }

    /**
     * 결과 셋을 보고한다. 409 는 본문 `reason` 으로 가른다 — `COMPLETED` 면 앞 보고가 이미 반영된 것이고,
     * `NOT_CLAIMER` 면 이 실행기의 보고가 아니다. 실행기가 둘을 다르게 다룬다(스펙 §5.2).
     */
    @PostMapping("/ingest/test-requests/{requestId}/results")
    fun report(
        @PathVariable requestId: Long,
        @RequestBody body: ReportBody,
    ): ResponseEntity<Map<String, Any>> {
        if (body.worker.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "worker 가 비었다"))
        val claimedAt = try {
            Instant.parse(body.claimed_at)
        } catch (e: DateTimeParseException) {
            return ResponseEntity.badRequest().body(mapOf("error" to "claimed_at 이 ISO 시각이 아니다: ${body.claimed_at}"))
        }
        val runs = body.results.map { SuiteRun(it.suite, it.result, it.detail?.toString()) }
        return when (val outcome = requests.report(requestId, body.worker, claimedAt, runs)) {
            is ReportOutcome.Recorded -> ResponseEntity.ok(mapOf("status" to outcome.status))
            ReportOutcome.UnknownRequest ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "없는 요청이다: $requestId"))
            ReportOutcome.AlreadyCompleted ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "이미 끝난 요청이다", "reason" to "COMPLETED"))
            ReportOutcome.NotClaimer -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("error" to "집은 실행기나 집은 시각이 다르다", "reason" to "NOT_CLAIMER"),
            )
            is ReportOutcome.BadResults -> ResponseEntity.badRequest().body(mapOf("error" to outcome.detail))
        }
    }
}

/** `POST /ingest/test-requests/claim` 의 본문. */
data class ClaimBody(val worker: String = "")

/** `POST /ingest/test-requests/{requestId}/results` 의 본문. */
data class ReportBody(
    val worker: String = "",
    val claimed_at: String = "",
    val results: List<SuiteResultBody> = emptyList(),
)

/** 스위트 하나. [detail] 은 실행기가 정한 JSON 이며 그대로 `revision_test_run.detail` 에 들어간다. */
data class SuiteResultBody(val suite: String = "", val result: String = "", val detail: JsonNode? = null)
