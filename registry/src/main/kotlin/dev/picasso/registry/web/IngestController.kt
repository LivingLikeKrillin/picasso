package dev.picasso.registry.web

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.registry.ingest.HandshakeIngestOutcome
import dev.picasso.registry.ingest.HandshakeIngestService
import dev.picasso.registry.ingest.LivenessOutcome
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.ingest.SiteNameReport
import dev.picasso.registry.ingest.TaskIngestOutcome
import dev.picasso.registry.ingest.TaskIngestService
import dev.picasso.registry.ledger.ConsumerKind
import dev.picasso.registry.ledger.LedgerService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** §9.2의 `POST /requirements` 본문. **계약 타입이 아니다** — 소비자가 스스로 적는다. */
data class DeclareRequest(
    val consumer_id: String,
    val kind: String,
    val site: String,
    val display_name: String?,
    val requires: List<String>,
)

/**
 * §5.4·§9.2의 적재 표면. **`DiagController`와 다른 클래스인 것이 요점이다.**
 *
 * 진단 컨트롤러는 스스로 *"여기 `POST`를 하나 더하는 순간 그 경계가 조용히
 * 새는 문이 된다"*고 적어 두었다. 그 말을 지키려면 쓰기는 다른 문으로
 * 들어와야 하고, 그 문에는 관문이 있다([IngestTokenInterceptor]).
 *
 * ## 계약 메시지는 protobuf JSON으로 받는다
 *
 * Jackson으로 받으면 `oneof`·enum·`uint64`의 표현이 protobuf 규약과 갈라지고,
 * 그러면 브로커가 붙는 날 같은 메시지가 다르게 읽힌다. [JsonFormat]이 그
 * 규약의 주인이다.
 *
 * **모르는 필드에서 던지게 둔다**(`ignoringUnknownFields`를 안 켠다). 계약이
 * 앞선 발신자가 새 필드를 실어 보내면 적재가 조용히 그것을 버리는 대신
 * 400으로 알린다 — 조용히 버리면 원장이 반쪽인 채로 초록이다.
 */
@RestController
class IngestController(
    private val handshakes: HandshakeIngestService,
    private val tasks: TaskIngestService,
    private val liveness: LivenessService,
    private val ledger: LedgerService,
    private val json: com.fasterxml.jackson.databind.ObjectMapper,
) {

    /**
     * 핸드셰이크 결과 보고(§5.4). 오간 두 메시지를 **그대로** 받는다.
     *
     * 보고자가 요약해 보내면 그 요약이 두 번째 진실이 되고, 보고자가 여럿이
     * 되는 날 서로 다른 원장이 쌓인다.
     */
    @PostMapping("/ingest/handshake")
    fun handshake(
        @RequestParam(name = "site") site: String,
        @RequestBody body: String,
    ): ResponseEntity<Map<String, Any>> {
        val root: JsonNode = json.readTree(body)
        val request = NegotiateRequest.newBuilder()
        val response = NegotiateResponse.newBuilder()

        val requestNode = root.get("request")
            ?: return badRequest("본문에 request가 없다")
        val responseNode = root.get("response")
            ?: return badRequest("본문에 response가 없다")

        runCatching {
            JsonFormat.parser().merge(requestNode.toString(), request)
            JsonFormat.parser().merge(responseNode.toString(), response)
        }.onFailure { return badRequest("계약 메시지를 읽을 수 없다: ${it.message}") }

        return when (val outcome = handshakes.record(request.build(), response.build(), site)) {
            is HandshakeIngestOutcome.Recorded -> ResponseEntity.ok(
                mapOf("accepted" to outcome.accepted, "written" to outcome.written),
            )

            // **거부는 400이다.** 202로 삼키면 보고자가 자기 보고가 버려진
            // 것을 모르고, 원장은 조용히 빈다.
            is HandshakeIngestOutcome.Refused -> badRequest(outcome.detail)
        }
    }

    /** 태스크 전이 적재(§8.3). 브로커가 붙는 날 구독기가 같은 것을 넘긴다. */
    @PostMapping("/ingest/task")
    fun task(@RequestBody body: String): ResponseEntity<Map<String, Any>> {
        val message = StateMessage.newBuilder()
        runCatching { JsonFormat.parser().merge(body, message) }
            .onFailure { return badRequest("StateMessage를 읽을 수 없다: ${it.message}") }

        val outcome: TaskIngestOutcome = tasks.record(message.build())
        // **건너뛴 것을 본문에 싣는다.** 개수만 200으로 돌려주면 "왜 드레인이
        // 안 줄지"에 답할 수 없다.
        return ResponseEntity.ok(
            mapOf("recorded" to outcome.recorded, "skipped" to outcome.skipped),
        )
    }

    /**
     * 기체 생존 보고(§4.7의 `connection` 스트림).
     *
     * **`/ingest/task`와 따로 있는 이유**는 태스크가 없는 기체도 살아 있기
     * 때문이다. 태스크 적재를 관측선으로 쓰면 일이 없던 기체와 죽은 기체가
     * 같아지고, 그 둘을 못 가리면 §9.3의 축소 판정이 조용해진 발신자를
     * "쓰는 사람 0명"으로 읽는다.
     *
     * `software`는 계약 메시지에 없으므로 쿼리로 받는다 —
     * `/ingest/handshake`의 `site`와 같은 자리다. **브로커가 붙는 날
     * 구독기는 `ConnectionMessage`만 갖고 이것을 비운 채 부른다.**
     * `COALESCE`가 이미 읽은 값을 지키므로 그때 원장이 퇴행하지 않는다.
     */
    @PostMapping("/ingest/liveness")
    fun liveness(
        @RequestParam(name = "software", required = false) software: String?,
        @RequestParam(name = "site_names_unsupported", required = false) siteNamesUnsupported: Boolean?,
        @RequestParam(name = "site_names_count", required = false) siteNamesCount: Int?,
        @RequestBody body: String,
    ): ResponseEntity<Map<String, Any>> {
        val message = ConnectionMessage.newBuilder()
        runCatching { JsonFormat.parser().merge(body, message) }
            .onFailure { return badRequest("ConnectionMessage를 읽을 수 없다: ${it.message}") }

        val built = message.build()
        // **빈 문자열을 null 로 접는다.** 쿼리 파라미터는 `?software=` 만으로도
        // 빈 문자열이 되는데, 그것을 그대로 실으면 "못 읽는 기종"이 "버전이
        // 비어 있다"로 원장에 앉는다.
        val reported = software?.takeIf { it.isNotBlank() }

        // **둘이 함께 와야 답으로 친다.** 하나만 오면 그것은 잘린 보고이고,
        // 반쪽으로 상태를 올리면 "기체가 답했다"가 거짓이 된다(ADR 35).
        val siteNames = if (siteNamesUnsupported != null && siteNamesCount != null) {
            SiteNameReport(siteNamesUnsupported, siteNamesCount)
        } else {
            null
        }

        return when (val outcome = liveness.record(built.header, built.state, reported, siteNames)) {
            is LivenessOutcome.Recorded -> ResponseEntity.ok(mapOf("recorded" to true))
            is LivenessOutcome.Rejected -> badRequest(outcome.reason)
        }
    }

    /** §9.2의 요구 등록. `source=DECLARED`. */
    @PostMapping("/requirements")
    fun requirements(@RequestBody request: DeclareRequest): ResponseEntity<Map<String, Any>> {
        val kind = runCatching { ConsumerKind.valueOf(request.kind) }.getOrNull()
            ?: return badRequest("모르는 kind다: ${request.kind} (${ConsumerKind.entries})")
        if (request.consumer_id.isBlank()) return badRequest("consumer_id가 없다")
        if (request.site.isBlank()) return badRequest("site가 없다")
        if (request.requires.isEmpty()) return badRequest("requires가 비었다")

        val written = ledger.declare(
            request.consumer_id,
            kind,
            request.site,
            request.display_name ?: request.consumer_id,
            request.requires,
        )
        return ResponseEntity.ok(mapOf("written" to written))
    }

    private fun badRequest(detail: String): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to detail))
}
