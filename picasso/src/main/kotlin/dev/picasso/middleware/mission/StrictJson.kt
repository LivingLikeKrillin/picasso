package dev.picasso.middleware.mission

/**
 * JSON 의 **문법과 중복 키만** 본다 — 값은 읽지 않는다. 값은 `JsonFormat` 이 `Struct` 로 읽는다.
 *
 * 따로 두는 이유는 `JsonFormat` 이 너그럽기 때문이다. 같은 키가 둘이면 말없이 뒤엣것을 쓰고, 끝에 붙은 글자와 따옴표 없는
 * 키를 받아 준다. 임무 정의에서 같은 키가 둘이면 쓴 사람이 어느 쪽을 뜻했는지 모른다 — 하나를 고르면 고르지 않은 쪽이
 * 조용히 사라진다. 그래서 **중복 키는 거부한다.** 새 의존을 들이지 않으려고(결정 (차)) 손으로 쓴다.
 *
 * 문법이 틀린 자리를 만나면 거기서 멈추고 그 하나만 알린다(그 뒤는 읽을 수 없다). 중복 키는 문법이 맞는 동안 전부 모은다.
 */
internal object StrictJson {

    /**
     * @param problems 경로는 `$.steps[0].id` 모양이다. 비었으면 문법이 맞고 중복 키가 없다.
     * @param broken 문법이 틀렸는가. 참이면 **값을 읽지 않는다** — 너그러운 파서가 고쳐 읽은 값으로 칸을 대면 쓴 사람이
     *   적지 않은 문서를 판정하게 된다.
     */
    class Scanned(val problems: List<String>, val broken: Boolean)

    fun scan(text: String): Scanned {
        val scan = Scan(text)
        try {
            scan.ws()
            scan.value("$")
            scan.ws()
            if (scan.i < text.length) scan.fail("$", "JSON 값 뒤에 글자가 더 있다")
        } catch (stop: Stop) {
            return Scanned(scan.found + stop.problem, broken = true)
        }
        return Scanned(scan.found, broken = false)
    }

    private class Stop(val problem: String) : RuntimeException(problem, null, false, false)

    private class Scan(val s: String) {
        var i = 0
        val found = mutableListOf<String>()

        fun fail(path: String, what: String): Nothing = throw Stop("$path: $what (${i + 1}번째 글자)")

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(path: String) {
            if (i >= s.length) fail(path, "값이 없다")
            when (val c = s[i]) {
                '{' -> obj(path)
                '[' -> arr(path)
                '"' -> string(path)
                't' -> word(path, "true")
                'f' -> word(path, "false")
                'n' -> word(path, "null")
                else -> if (c == '-' || c.isDigit()) number(path) else fail(path, "JSON 값이 아니다 '$c'")
            }
        }

        fun obj(path: String) {
            i++ // {
            val keys = mutableSetOf<String>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail(path, "키는 큰따옴표 문자열이어야 한다")
                val key = string(path)
                if (!keys.add(key)) found += "$path: 키 '$key' 가 두 번 나온다"
                ws()
                if (i >= s.length || s[i] != ':') fail(path, "키 '$key' 뒤에 ':' 가 없다")
                i++
                ws()
                value("$path.$key")
                ws()
                if (i >= s.length) fail(path, "객체가 닫히지 않았다")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return
                    }
                    else -> fail(path, "',' 나 '}' 가 와야 한다")
                }
            }
        }

        fun arr(path: String) {
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return
            }
            var index = 0
            while (true) {
                ws()
                value("$path[$index]")
                index++
                ws()
                if (i >= s.length) fail(path, "배열이 닫히지 않았다")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return
                    }
                    else -> fail(path, "',' 나 ']' 가 와야 한다")
                }
            }
        }

        /** 문자열 하나를 읽고 풀어 쓴 값을 돌려준다 — 키 비교가 `"a"` 와 `"a"` 를 같게 보도록. */
        fun string(path: String): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail(path, "문자열이 닫히지 않았다")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail(path, "문자열이 닫히지 않았다")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail(path, "\\u 뒤에 16진 넷이 없다")
                                val hex = s.substring(i, i + 4)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail(path, "\\u 뒤에 16진 넷이 없다"))
                                i += 4
                            }
                            else -> fail(path, "모르는 탈출 문자 '\\$e'")
                        }
                    }
                    c < ' ' -> fail(path, "문자열 안에 제어 문자가 있다")
                    else -> out.append(c)
                }
            }
        }

        fun word(path: String, w: String) {
            if (!s.startsWith(w, i)) fail(path, "JSON 값이 아니다")
            i += w.length
        }

        fun number(path: String) {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) fail(path, "수가 아니다")
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isDigit()) fail(path, "소수점 뒤에 숫자가 없다")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) fail(path, "지수에 숫자가 없다")
                while (i < s.length && s[i].isDigit()) i++
            }
            check(i > start)
        }
    }
}
