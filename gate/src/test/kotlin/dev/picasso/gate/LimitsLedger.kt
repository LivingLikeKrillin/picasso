package dev.picasso.gate

/** `docs/limits.md` 의 표를 읽는다. **열림 판정의 근거가 실재하는지** 보려는 것뿐이다. */
object LimitsLedger {

    private val BRANCH = Regex("""^## (\d)\.""")

    /**
     * 표의 **첫 칸 전부**를 갈래와 함께. 원문 순서를 지키므로 **중복도 보인다.**
     *
     * ★**칸을 뽑는 규칙은 여기 한 벌뿐이다.** `parse` 와 유일성 시험이 나눠 쓴다 — 두 벌로 두면
     * 어느 날 한쪽만 는다(§15.115). 펜스 규칙도 `ClaimSurface` 의 것을 그대로 쓴다.
     */
    fun rows(content: String): List<Pair<Int, String>> {
        val lines = content.replace("\r\n", "\n").lines()
        val outside = ClaimSurface.outsideFence(lines)
        val out = mutableListOf<Pair<Int, String>>()
        var branch = 0
        lines.forEachIndexed { i, line ->
            if (!outside[i]) return@forEachIndexed
            val head = BRANCH.find(line.trimStart())
            if (head != null) {
                branch = head.groupValues[1].toInt()
                return@forEachIndexed
            }
            if (branch == 0 || !line.trimStart().startsWith("|")) return@forEachIndexed
            val cell = line.split("|").getOrNull(1)?.replace("*", "")?.trim() ?: return@forEachIndexed
            if (cell.isEmpty() || cell.trim(':', '-').isEmpty() || cell == "출처" || cell == "갈래") return@forEachIndexed
            out += branch to cell
        }
        return out
    }

    fun parse(content: String): Map<Int, Set<String>> {
        val out = mutableMapOf<Int, MutableSet<String>>()
        for ((branch, cell) in rows(content)) {
            val bucket = out.getOrPut(branch) { mutableSetOf() }
            bucket += cell
            // ★한 칸에 id 를 `·` 로 이어 적은 행이 셋 있다. 조각도 댈 수 있어야 한다.
            if ("·" in cell) cell.split("·").map(String::trim).filterTo(bucket, String::isNotEmpty)
        }
        return out
    }

    fun of(): Map<Int, Set<String>> = parse(Repo.read("docs/limits.md"))

    fun allIds(): Set<String> = of().values.flatten().toSet()
}
