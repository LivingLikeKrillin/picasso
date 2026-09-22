package dev.picasso.middleware.host

import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.LedgerExport
import dev.picasso.middleware.RemedySearchRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * 창구가 도는 동안의 **살아 있는 한 벌** — 이 창구에 되돌려 댈 수 있는 유일한 한 벌이다.
 *
 * ## 왜 인계본이 아닌가
 *
 * ★인계본(`handoff/narrator/run-*`)은 **다른 시나리오의 산물**이다. `run-1` 의 `search-1` 은
 * `hum-02` / `PATROL-1` 인데 창구에 서 있는 자리는 `hum-02` / `PATROL-APPROVES` 라, 인계본을
 * 그대로 창구에 대면 첫 줄부터 `NO_PROPOSAL` 이 온다. **거짓이 아니다** — 창구에 그 제안이 없는
 * 것이 사실이다. 그러나 「알려진 조치로 자동 회복」을 실물로 보이지는 못한다.
 *
 * 한 벌과 창구가 같은 제안을 들려면 **같은 미들웨어가 둘 다 냈어야 한다.** 그 자리가 여기다.
 *
 * ## 구동 식별자는 한 바퀴에 하나다
 *
 * ★★앞 판은 내보낼 때마다 새로 찍었다. 30분 돌린 한 판에서 식별자가 `-10930` 까지 갔다(실측).
 * 읽는 쪽은 「같은 구동의 같은 줄에는 한 번만 부른다」로 승인을 거르는데, 식별자가 매번 바뀌면
 * **그 거름망이 통째로 죽는다** — 같은 한 벌을 다시 훑을 때마다 승인을 또 시도한다. 승인은
 * 제안을 소모하므로 두 번째는 거절로 오고, 그러면 대장에 **일어나지 않은 거절**이 쌓인다.
 *
 * ## 안 늘면 안 쓴다
 *
 * 두 대장은 append-only 라 **줄 수가 곧 변경 신호**다. 안 늘었는데 다시 쓰면 읽는 쪽이 세 파일을
 * 한 벌로 집을 때 파일마다 다른 순간을 볼 수 있다. 쓰는 순서는 대장 먼저, 안내가 마지막이다 —
 * 안내가 나타나는 것이 «다 나왔다» 는 신호이고, 그때 그 수만큼의 줄이 이미 자리에 있다.
 */
class LiveExport(private val dir: Path, private val runId: String) {

    /** 마지막으로 쓴 (사건 수, 탐색 수). 안 썼으면 널이다 — **0,0 과 «아직 안 썼다» 는 다르다.** */
    private var written: Pair<Int, Int>? = null

    /** 썼으면 true, 대장이 안 늘어 건너뛰었으면 false. */
    fun snapshot(
        incidents: List<IncidentBundle>,
        searches: List<RemedySearchRecord>,
        virtualNow: Instant,
    ): Boolean {
        val size = incidents.size to searches.size
        if (size == written) return false

        Files.createDirectories(dir)
        atomically(LedgerExport.INCIDENTS, LedgerExport.incidents(incidents))
        atomically(LedgerExport.REMEDY_SEARCHES, LedgerExport.remedySearches(searches))
        val wall = Instant.now()
        atomically(
            LedgerExport.MANIFEST,
            LedgerExport.manifest(runId, wall, virtualNow, incidents.size, searches.size),
        )
        written = size
        return true
    }

    /**
     * 임시 이름으로 쓰고 이름만 바꾼다. **반쯤 쓰인 파일을 집어 가면 그것은 한 벌이 아니라
     * 파편이고, 파편과 빈 한 벌은 같은 모양이다.**
     */
    private fun atomically(name: String, body: String) {
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, body)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
