package dev.picasso.harness

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.StateMessage
import java.nio.file.Files
import java.nio.file.Path

/**
 * @param replayed 적재로 다시 보낸 줄 수.
 * @param failed 다시 실패한 줄. **원문 그대로** 담는다 — 호출자가 새 폴백
 *   파일로 쓸 수 있어야 하고, 그러려면 형식이 보존돼야 한다.
 * @param malformed 읽을 수 없던 줄 수. **조용히 버리지 않는다** — 프로세스가
 *   죽으며 잘린 마지막 줄이 여기 걸리고, 그 수가 1을 넘으면 파일이 다른
 *   이유로 상한 것이다.
 */
data class ReplayOutcome(
    val replayed: Int,
    val failed: List<String>,
    val malformed: Int,
)

/**
 * §15.46 — 폴백 파일을 **다시 밀어 넣는다.**
 *
 * ## 왜 이것이 있어야 하는가
 *
 * 폴백은 잃지 않게 할 뿐 원장을 채우지 않는다. 레지스트리가 돌아와도
 * **그 구간의 소비자·태스크는 관측되지 않은 채로 남고**, 축소 판정은 그
 * 구간을 못 본 원장 위에서 이뤄진다. 막는 쪽이라 사고는 안 나지만, 관측이
 * 빈 것은 사실이다.
 *
 * ## 전부 다시 민다 — 멱등이기 때문이다
 *
 * "어디까지 밀었는가"를 따로 기록하지 않는다. 커서를 두면 그 커서가 세 번째
 * 진실이 되고, 어긋난 날 **밀지 않은 구간이 조용히 생긴다.**
 *
 * 대신 적재가 멱등이라는 성질에 기댄다 — 소비자 요구는 upsert이고(§9.2의
 * `last_seen` 갱신), 태스크는 upsert에 종착 래치가 걸려 있다(§4.5). 같은
 * 줄을 두 번 밀어도 원장은 같다. **그 성질이 이 클래스의 전제이므로
 * 시험이 그것을 붙든다.**
 *
 * ## 파일은 안 지운다
 *
 * 성공했는지는 [ReplayOutcome]으로 알리고 파일을 어떻게 할지는 호출자가
 * 정한다. 데이터를 지우는 판단을 도구가 대신하면, 적재가 실은 실패했는데
 * 파일이 사라진 날 되돌릴 것이 없다.
 */
class FallbackReplay(
    /** 핸드셰이크 한 건을 적재로 보낸다. 던지면 실패로 센다. */
    private val handshakes: (site: String, NegotiateRequest, NegotiateResponse) -> Unit,
    /** 태스크 관측 하나를 적재로 보낸다. 던지면 실패로 센다. */
    private val tasks: TaskObservations,
) {

    /** `FileHandshakeReporter`가 적은 줄들. */
    fun replayHandshakes(path: Path): ReplayOutcome = replay(path) { node ->
        val site = node.get("site")?.asText()
            ?: throw IllegalArgumentException("site가 없다")
        val request = NegotiateRequest.newBuilder()
        val response = NegotiateResponse.newBuilder()
        parse(node.get("request"), request, "request")
        parse(node.get("response"), response, "response")
        handshakes(site, request.build(), response.build())
    }

    /** `FileTaskObservations`가 적은 줄들. */
    fun replayTasks(path: Path): ReplayOutcome = replay(path) { node ->
        val message = node.get("message")
            ?: throw IllegalArgumentException("message가 없다")
        // **`kind`로 경로를 가른다.** 안 가르면 전이가 스냅샷으로 들어가
        // `tasks` 배열이 비어 아무것도 안 실린다.
        when (val kind = node.get("kind")?.asText()) {
            "state" -> {
                val builder = StateMessage.newBuilder()
                parse(message, builder, "message")
                tasks.onState(builder.build())
            }

            "event" -> {
                val builder = Event.newBuilder()
                parse(message, builder, "message")
                tasks.onEvent(builder.build())
            }

            else -> throw IllegalArgumentException("모르는 kind다: $kind")
        }
    }

    /**
     * 줄 단위로 읽어 하나씩 보낸다.
     *
     * **한 줄이 깨져도 나머지를 민다.** 파일 전체를 버리면 잘린 마지막 줄
     * 하나 때문에 그 앞의 멀쩡한 관측이 전부 사라진다 — JSONL로 적은 이유가
     * 그것이다.
     */
    private fun replay(path: Path, send: (JsonNode) -> Unit): ReplayOutcome {
        if (!Files.exists(path)) return ReplayOutcome(0, emptyList(), 0)

        var replayed = 0
        var malformed = 0
        val failed = mutableListOf<String>()

        Files.readAllLines(path).forEach { line ->
            if (line.isBlank()) return@forEach
            val node = runCatching { mapper.readTree(line) }.getOrNull()
            if (node == null || !node.isObject) {
                malformed++
                return@forEach
            }
            runCatching { send(node) }
                .onSuccess { replayed++ }
                // **원문을 담는다.** 다시 실패한 줄은 그대로 새 폴백 파일이
                // 되어야 하고, 다시 직렬화하면 규약이 한 번 더 갈릴 자리가 생긴다.
                .onFailure { failed += line }
        }

        return ReplayOutcome(replayed, failed, malformed)
    }

    private fun parse(node: JsonNode?, builder: Message.Builder, what: String) {
        if (node == null) throw IllegalArgumentException("$what 가 없다")
        JsonFormat.parser().merge(node.toString(), builder)
    }

    private companion object {
        val mapper = ObjectMapper()
    }
}
