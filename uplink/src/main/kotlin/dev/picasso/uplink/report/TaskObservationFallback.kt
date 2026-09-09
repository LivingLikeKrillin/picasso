package dev.picasso.uplink.report

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.StateMessage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 적재에 **실패한** 관측을 받는 쪽.
 *
 * `TaskObservations`와 나눈 것이 요점이다 — 성공 경로와 실패 경로는 다른
 * 것을 알아야 한다. 실패 경로만이 **왜 실패했는지**를 알고, 그것을 안 남기면
 * §15.45가 적은 대로 *"왜 막히는지 알기 어렵다"*가 된다.
 */
fun interface FailedObservations {

    /**
     * @param kind `state` 또는 `event`. 밀어 넣을 때 어느 경로로 보낼지 정한다.
     * @param json protobuf JSON. **적재가 읽는 것과 같은 규약이어야** 나중에
     *   그대로 밀어 넣을 수 있다.
     * @param reason 1차가 실패한 사유. 없으면 null.
     */
    fun onFailure(kind: String, json: String, reason: String?)
}

/**
 * 1차가 실패하면 2차로 넘긴다. `FallbackHandshakeReporter`와 같은 형태다.
 *
 * ## 삼키는 것과 잃는 것은 다르다
 *
 * 적재 실패가 발행을 막으면 안 되므로(§5.4와 같은 규칙) 삼켜야 하는데,
 * **삼킨 것을 버리면 그 사이의 태스크 전이가 통째로 사라진다.** 그러면
 * `task` 표는 그 태스크를 못 본 채로 남고, 뒤늦게 스냅샷이 와도 이미 종착한
 * 태스크는 다시 안 실린다 — 드레인이 조용히 틀린다.
 *
 * ## 왜 막히는지도 남긴다
 *
 * 적재가 멈추면 워터마크가 늙어 §9.3의 조회가 `NotObservable`이 되고 축소가
 * 막힌다(§15.41의 안전한 실패). 그런데 **막힌 이유가 어디에도 없으면**
 * 운영자는 원장이 아니라 엉뚱한 것을 뒤진다. 폴백 파일이 그 답이다.
 */
class FallbackTaskObservations(
    private val primary: TaskObservations,
    private val fallback: FailedObservations,
) : TaskObservations {

    override fun onState(message: StateMessage) {
        runCatching { primary.onState(message) }
            .onFailure { fallback.onFailure("state", print(message), it.message) }
    }

    override fun onEvent(event: Event) {
        runCatching { primary.onEvent(event) }
            .onFailure { fallback.onFailure("event", print(event), it.message) }
    }

    private fun print(message: Message): String = printer.print(message)

    private companion object {
        val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    }
}

/**
 * §3.2의 "없을 때: **로컬 파일에 기록**"의 태스크 쪽.
 *
 * `FileHandshakeReporter`와 같은 JSONL이다 — 하나의 JSON 배열로 적으면
 * 프로세스가 죽은 시점이 곧 파일이 깨진 시점이고, 그때 잃는 것은 마지막 한
 * 줄이 아니라 전부다.
 *
 * **파일이 존재한다는 사실 자체가 신호다.** 적재가 잘 되면 이 파일은 안
 * 생긴다.
 */
class FileTaskObservations(private val path: Path) : FailedObservations {

    override fun onFailure(kind: String, json: String, reason: String?) {
        val line = """{"kind":${quote(kind)},"reason":${reason?.let { quote(it) } ?: "null"},""" +
            """"message":$json}"""
        path.parent?.let { Files.createDirectories(it) }
        Files.write(
            path,
            (line + "\n").toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (it.code < 0x20) append("\\u%04x".format(it.code)) else append(it)
            }
        }
        append('"')
    }
}
