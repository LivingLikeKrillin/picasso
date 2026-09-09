package dev.picasso.registry.web

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.registry.binding.DiscoveredRobot
import dev.picasso.registry.binding.RobotRegistration
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
 * `POST /ingest/robots` 의 본문 한 줄(ADR 37 의 **발견**).
 *
 * **`origin` 이 없다.** 출처는 이 요청이 어느 문으로 들어왔는가이지 본문이 정하는 것이 아니다 — 본문이 정하면
 * 적재 토큰을 든 현장의 기체가 스스로 *"사람이 선언했다"* 고 적을 수 있다.
 *
 * [endpoint] 는 **받아서 거절하기 위해** 있다. 발견된 기체의 접속 정보는 플릿이 갖고 우리는 안 갖는다
 * (ADR 37 결정 4). 필드를 아예 두지 않으면 보낸 쪽은 우리가 그것을 저장했다고 믿는다.
 */
data class DiscoveredRobotRequest(
    val robot_id: String,
    val serial_number: String,
    val display_name: String? = null,
    val endpoint: String? = null,
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
    private val robots: RobotRegistration,
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

    /**
     * ADR 37 의 **발견** — 어댑터가 플릿에 물어 얻은 기체 목록을 올린다.
     *
     * **여기가 적재 문인 것이 이 엔드포인트의 절반이다**(결정 3). 같은 사실을 조작 문(`POST /operations/robots`)으로
     * 보내면 그것은 *선언* 이고, 둘의 차이는 페이로드가 아니라 **어느 문으로 들어왔는가**다.
     *
     * `site` 는 **어댑터가 배포된 사이트**다 — 플릿은 우리 `site_id` 를 모른다. `/ingest/handshake` 의 `site` 와 같은
     * 자리이며 같은 성질(어댑터의 신고)이다.
     *
     * **부분 성공을 그대로 낸다.** 하나가 거절돼도 나머지는 들이고, 거절된 것은 사유와 함께 돌려준다 — 목록 하나가
     * 통째로 실패하면 플릿에 기체를 더한 날 발견 전체가 멈추고, 그 멈춤은 *"플릿에서 사라졌다"* 와 구별되지 않는다.
     */
    @PostMapping("/ingest/robots")
    fun robots(
        @RequestParam(name = "site") site: String,
        @RequestBody body: List<DiscoveredRobotRequest>,
    ): ResponseEntity<Map<String, Any>> {
        if (site.isBlank()) return badRequest("site가 없다")
        if (body.isEmpty()) return badRequest("기체 목록이 비었다")

        val outcome = robots.discover(
            site,
            body.map { DiscoveredRobot(it.robot_id, it.serial_number, it.display_name, it.endpoint) },
        )
        // **거절이 있어도 200 이다.** 부분 성공이고, 무엇이 들어가고 무엇이 안 들어갔는지는 본문이 말한다.
        return ResponseEntity.ok(mapOf("recorded" to outcome.recorded, "refused" to outcome.refused))
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
