package dev.picasso.harness.revision

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 집은 요청 하나. [claimedAt] 은 registry 가 준 문자열 그대로다 — 보고가 그대로 돌려 싣는다. */
data class ClaimedTest(val requestId: Long, val profileRevisionId: Long, val claimedAt: String, val document: String)

/** 보고의 답(picasso-ops P2·S1d 스펙 §5.1·§5.2). */
sealed interface ReportReply {
    data class Recorded(val status: String) : ReportReply

    /** 이미 끝난 요청이다. 다시 보낸 보고라면 앞 보고가 반영된 것이다. */
    data object Completed : ReportReply

    /** 이 실행기가 집은 요청이 아니다. */
    data object NotClaimer : ReportReply

    /** 그 밖의 거절(400·404). 다시 보내도 달라지지 않는다. */
    data class Refused(val status: Int, val body: String) : ReportReply
}

/** 응답을 받지 못했거나 5xx·401 이다. [status] 가 0 이면 응답이 없었다. */
class DeskUnavailable(val status: Int, message: String) : IOException(message)

/**
 * registry 의 시험 요청 문(`/ingest/test-requests`). 실행기는 이것으로만 registry 에 닿는다 — 설계 문서 §3.2 가
 * `harness ⇢ registry` 를 런타임 접근으로 두었다.
 */
interface TestDesk {
    /** 집을 것이 없으면 널. */
    fun claim(worker: String): ClaimedTest?

    fun report(requestId: Long, worker: String, claimedAt: String, outcomes: List<SuiteOutcome>): ReportReply
}

/** 적재 토큰을 쥔 HTTP 창구. JDK `HttpClient` 를 쓴다(`uplink` 와 같은 선택). */
class HttpTestDesk(
    private val baseUrl: String,
    private val ingestToken: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : TestDesk {

    override fun claim(worker: String): ClaimedTest? {
        val response = post("/ingest/test-requests/claim", MAPPER.writeValueAsString(mapOf("worker" to worker)))
        return when (response.statusCode()) {
            204 -> null
            200 -> MAPPER.readTree(response.body()).let {
                ClaimedTest(it["request_id"].asLong(), it["profile_revision_id"].asLong(), it["claimed_at"].asText(), it["document"].asText())
            }
            else -> throw DeskUnavailable(response.statusCode(), "집기 실패: ${response.statusCode()} ${response.body()}")
        }
    }

    override fun report(requestId: Long, worker: String, claimedAt: String, outcomes: List<SuiteOutcome>): ReportReply {
        val body = mapOf(
            "worker" to worker,
            "claimed_at" to claimedAt,
            "results" to outcomes.map {
                mapOf("suite" to it.suite.name, "result" to if (it.passed) "PASS" else "FAIL", "detail" to MAPPER.readTree(it.detailJson()))
            },
        )
        val response = post("/ingest/test-requests/$requestId/results", MAPPER.writeValueAsString(body))
        val status = response.statusCode()
        return when {
            status == 200 -> ReportReply.Recorded(MAPPER.readTree(response.body())["status"].asText())
            status == 409 && MAPPER.readTree(response.body())["reason"]?.asText() == "COMPLETED" -> ReportReply.Completed
            status == 409 -> ReportReply.NotClaimer
            status == 400 || status == 404 -> ReportReply.Refused(status, response.body())
            else -> throw DeskUnavailable(status, "보고 실패: $status ${response.body()}")
        }
    }

    private fun post(path: String, json: String): HttpResponse<String> = try {
        http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $ingestToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    } catch (e: IOException) {
        throw DeskUnavailable(0, "registry 에 닿지 않는다: ${e.message}")
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}

/**
 * 개정판 시험 실행기(picasso-ops P2·S1d 스펙 §5). **요청을 집어 후보 문서로 3종을 돌리고 결과를 보고한다.**
 *
 * registry 를 부르는 쪽이 이것이고 그 반대는 없다(설계 문서 §3.2 순환 방지 규칙 1). picasso 안에는 이것을 상주시키는
 * `main` 이 없다 — 첫 소비자(picasso-ops 의 가짜 현장)가 프로세스 안에서 [start] 로 띄운다(ADR 9).
 *
 * 오류 처리는 스펙 §5.2 의 표 그대로다. 집기가 실패하면 다음 폴링에 다시 집는다. 보고의 응답을 못 받으면 같은 보고를
 * 최대 [REPORT_RETRIES] 번 다시 보내고, 다시 보낸 보고가 «이미 끝남» 이면 앞 보고가 반영된 것으로 본다.
 */
class RevisionTestRunner(
    private val desk: TestDesk,
    private val suites: RevisionSuites,
    private val worker: String,
    private val log: (String) -> Unit = { System.err.println("[$it]") },
) {

    /** 하나를 집어 처리한다. 집은 것이 있었으면 참. */
    fun pollOnce(): Boolean {
        val claimed = try {
            desk.claim(worker)
        } catch (e: DeskUnavailable) {
            log(if (e.status == 401) "적재 토큰이 registry 와 맞지 않는다: ${e.message}" else "집기 실패, 다음 폴링에 다시: ${e.message}")
            return false
        } ?: return false

        val file = Files.createTempFile("revision-${claimed.profileRevisionId}-", ".json")
        val outcomes = try {
            Files.writeString(file, claimed.document)
            suites.run(file)
        } finally {
            Files.deleteIfExists(file)
        }
        report(claimed, outcomes)
        return true
    }

    private fun report(claimed: ClaimedTest, outcomes: List<SuiteOutcome>) {
        repeat(REPORT_RETRIES + 1) { attempt ->
            val reply = try {
                desk.report(claimed.requestId, worker, claimed.claimedAt, outcomes)
            } catch (e: DeskUnavailable) {
                log("보고 실패(${attempt + 1}번째): ${e.message}")
                return@repeat
            }
            when (reply) {
                is ReportReply.Recorded -> log("요청 ${claimed.requestId} 보고: ${reply.status}")
                ReportReply.Completed -> log(
                    if (attempt == 0) "요청 ${claimed.requestId} 는 이미 끝났다" else "요청 ${claimed.requestId} 는 앞 보고가 반영됐다",
                )
                ReportReply.NotClaimer -> log("요청 ${claimed.requestId} 는 이 실행기가 집은 것이 아니다 — 버린다")
                is ReportReply.Refused -> log("요청 ${claimed.requestId} 보고 거절 ${reply.status}: ${reply.body}")
            }
            return
        }
        log("요청 ${claimed.requestId} 보고를 포기한다 — 만료 뒤 다시 집힌다")
    }

    /** [interval] 마다 [pollOnce] 를 부른다. 닫으면 멈춘다. */
    fun start(interval: Duration): AutoCloseable {
        val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "revision-test-runner").apply { isDaemon = true } }
        executor.scheduleWithFixedDelay(
            {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    log("실행기 오류: ${e::class.simpleName}: ${e.message}")
                }
            },
            0, interval.toMillis(), TimeUnit.MILLISECONDS,
        )
        return AutoCloseable {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    companion object {
        /** 보고의 응답을 못 받았을 때 다시 보내는 횟수(스펙 §5.2). */
        const val REPORT_RETRIES = 3
    }
}
