package dev.picasso.uplink.report

import com.google.protobuf.util.JsonFormat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * §3.2의 "없을 때: **로컬 파일에 기록**".
 *
 * ## 한 줄에 하나(JSONL)인 것이 요점이다
 *
 * 레지스트리가 돌아온 날 이 파일을 밀어 넣어야 하는데, 하나의 JSON 배열로
 * 적으면 **닫는 괄호가 없는 파일**이 된다 — 프로세스가 죽은 시점이 곧
 * 파일이 깨진 시점이고, 그때 잃는 것은 마지막 한 줄이 아니라 전부다.
 *
 * 줄 단위면 마지막 줄만 잘리고 나머지는 그대로 읽힌다.
 *
 * ## 계약 메시지는 protobuf JSON으로 적는다
 *
 * 적재 표면이 그 규약으로 읽는다. 여기서 다른 규약으로 적으면 밀어 넣는
 * 날 `oneof`·enum·`uint64`가 다르게 해석되고, 그 차이는 파일이 쌓인 뒤에야
 * 드러난다.
 */
class FileHandshakeReporter(private val path: Path) : HandshakeReporter {

    override fun report(report: HandshakeReport) {
        val line = buildString {
            append("""{"site":""")
            append(quote(report.site))
            append(""","request":""")
            append(printer.print(report.request))
            append(""","response":""")
            append(printer.print(report.response))
            append("}")
        }
        // **줄바꿈까지 한 번에 쓴다.** 본문과 개행을 나눠 쓰면 그 사이에
        // 죽었을 때 다음 보고가 같은 줄에 이어 붙어 두 줄이 한 줄이 된다.
        //
        // 한 줄임을 보장하는 것은 **`printer` 설정 하나**다. 예전에는 여기서
        // 개행을 한 번 더 걷어냈는데, `printer`가 이미 한 줄을 내므로 그것은
        // 죽은 코드였고 **`printer` 설정을 없애는 결함을 가렸다** — 주입이
        // 등가 변이가 되어 안 잡혔다(실측). 방어는 하나이고 그 하나를
        // 없애면 시험이 깨져야 한다.
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

    private companion object {
        /**
         * **`printer()`의 기본은 여러 줄이다.** 그대로 쓰면 한 보고가 여러
         * 줄이 되어 JSONL이 아니게 된다 — 그래서 위에서 개행을 걷어낸다.
         */
        val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    }
}
