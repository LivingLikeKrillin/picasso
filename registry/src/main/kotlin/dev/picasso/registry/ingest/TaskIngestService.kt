package dev.picasso.registry.ingest

import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.isTerminal
import dev.picasso.registry.store.Db
import java.sql.Connection

/**
 * @param recorded 적재된 태스크 행 수.
 * @param skipped 적재하지 못한 것과 사유. **개수가 아니라 사유를 남긴다** —
 *   개수만 남기면 "왜 드레인이 안 줄지"에 답할 수 없다.
 */
data class TaskIngestOutcome(val recorded: Int, val skipped: List<String>)

/**
 * §3.2가 `registry ⇠ 브로커` **구독**으로 규정한 자리 — `task` 표를 채운다.
 *
 * 이것이 없으면 §9.3의 조회 2(드레인)가 언제나 `NotObservable`이고, 검사
 * 6번은 소비자가 0이어도 축소를 승인하지 못한다.
 *
 * ## 브로커가 없으므로 부르는 쪽이 아직 어댑터다
 *
 * 입력을 [StateMessage] — **계약 타입** — 로 못박은 것이 그래서다. 브로커가
 * 붙는 날 구독기가 같은 메시지를 그대로 넘기면 되고, `registry`는 자기가
 * 어댑터에게서 받았는지 브로커에게서 받았는지 몰라도 된다.
 *
 * ## 종착은 되돌리지 않는다
 *
 * 늦게 도착한 옛 스냅샷이 종착을 비종착으로 되돌리면 **드레인이 영영 안
 * 끝나고 축소가 영원히 막힌다.** §4.5의 래치와 같은 이유이며, 여기서는
 * 발행 순서가 뒤집힐 수 있다는 사실(§10.4의 `REORDER`)이 그것을 실제
 * 위험으로 만든다.
 *
 * 그러면서도 **`updated_at`은 올린다.** 그것이 §9.3의 관측선이 살아 있다는
 * 증거이기 때문이다 — 종착한 태스크만 계속 발행되는 조용한 라인에서
 * 워터마크가 늙으면 원장이 스스로를 못 보게 된다.
 */
class TaskIngestService(private val db: Db) {

    /**
     * 주기 스냅샷(§7.2의 `publish_interval`)으로 적재한다.
     *
     * **놓친 전이를 메우는 쪽이다.** 스냅샷은 "지금 전부"라 이벤트가 유실돼도
     * 다음 주기에 상태가 맞춰진다. 대신 **주기보다 짧은 태스크는 여기 안
     * 잡힌다** — 그것을 [record] 의 이벤트 오버로드가 맡는다.
     */
    fun record(message: StateMessage): TaskIngestOutcome =
        ingest(message.header) { c, revisionId, skipped ->
            var recorded = 0
            message.tasksList.forEach { snapshot ->
                if (one(
                        c, message.header.robotId, revisionId, skipped,
                        snapshot.taskId, snapshot.skillType, snapshot.state,
                        snapshot.revision, snapshot.attempt,
                    )
                ) {
                    recorded++
                }
            }
            recorded
        }

    /**
     * 전이 이벤트로 적재한다(§4.7). **드레인의 해상도가 여기서 정해진다.**
     *
     * 스냅샷만 받으면 발행 주기보다 짧은 태스크가 비종착으로 한 번도 안
     * 실리고, 그러면 §9.3의 드레인이 도는 태스크를 0으로 관측한다 — 틀리는
     * 방향이 **축소를 여는** 쪽이라 위험하다.
     *
     * 계약이 이 경로를 예정해 두었다: `TaskTransition`이 `skill_type`을
     * 싣는 이유가 *"registry가 이 이벤트를 구독해 task 테이블을 적재하며
     * 드레인 판정이 스킬 단위로 서려면"*이라고 `event.proto`에 적혀 있다.
     *
     * **태스크 전이가 아닌 이벤트는 조용히 넘긴다.** 스킬 전이·결함·능력
     * 변경도 같은 스트림으로 오며, 그것을 건너뜀으로 세면 사유 목록이
     * 정상 트래픽으로 가득 차 진짜 문제가 묻힌다.
     */
    fun record(event: Event): TaskIngestOutcome {
        if (!event.hasTaskTransition()) return TaskIngestOutcome(0, emptyList())
        val transition = event.taskTransition
        return ingest(event.header) { c, revisionId, skipped ->
            val written = one(
                c, event.header.robotId, revisionId, skipped,
                transition.taskId, transition.skillType, transition.to,
                transition.revision, transition.attempt,
            )
            if (written) 1 else 0
        }
    }

    /** 두 경로가 공유하는 껍데기 — 기체·개정판을 풀고 트랜잭션을 연다. */
    private fun ingest(
        header: MessageHeader,
        body: (java.sql.Connection, Long, MutableList<String>) -> Int,
    ): TaskIngestOutcome {
        val robotId = header.robotId
        if (robotId.isBlank()) {
            return TaskIngestOutcome(0, listOf("헤더에 robot_id가 없다"))
        }

        return db.transaction { c ->
            val revisionId = profileRevisionId(c, header.profileRef.profileId, header.profileRef.revision)
                ?: return@transaction TaskIngestOutcome(
                    0,
                    listOf(
                        "모르는 개정판이다: ${header.profileRef.profileId}" +
                            "#${header.profileRef.revision}",
                    ),
                )

            val skipped = mutableListOf<String>()
            val recorded = body(c, revisionId, skipped)
            TaskIngestOutcome(recorded, skipped)
        }
    }

    /** 태스크 한 줄. @return 적재했으면 참. */
    private fun one(
        c: java.sql.Connection,
        robotId: String,
        revisionId: Long,
        skipped: MutableList<String>,
        taskId: String,
        skillType: String,
        state: TaskState,
        revision: Int,
        attempt: Int,
    ): Boolean {
        val skillTypeId = skillTypeId(c, revisionId, skillType)
        if (skillTypeId == null) {
            // **그 행만 건너뛴다.** 메시지 전체를 버리면 스킬 하나가 낯설다는
            // 이유로 같은 로봇의 다른 태스크가 드레인에서 통째로 사라진다.
            skipped += "개정판 $revisionId 이 선언하지 않은 스킬이다: $skillType"
            return false
        }
        if (taskId.isBlank()) {
            skipped += "task_id가 없다"
            return false
        }
        upsert(c, robotId, revisionId, skillTypeId, taskId, state, revision, attempt)
        return true
    }

    /**
     * `profile_ref`는 `"vendor/model"`과 개정판 번호다(§7.2의 기종 좌표).
     * **`profile_revision_id`를 직접 싣지 않는 것이 의도다** — 그것은
     * 레지스트리의 내부 채번이고, 로봇이 그것을 알면 좌표가 둘이 된다.
     */
    private fun profileRevisionId(c: Connection, profileId: String, revision: Int): Long? {
        val slash = profileId.indexOf('/')
        if (slash <= 0 || slash == profileId.length - 1) return null
        return c.prepareStatement(
            """
            SELECT pr.profile_revision_id
            FROM profile_revision pr
            JOIN capability_profile cp ON cp.profile_id = pr.profile_id
            WHERE cp.vendor = ? AND cp.model = ? AND pr.revision = ?
            """.trimIndent(),
        ).use { s ->
            s.setString(1, profileId.substring(0, slash))
            s.setString(2, profileId.substring(slash + 1))
            s.setInt(3, revision)
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    /**
     * **그 개정판이 선언한 스킬에서 찾는다.** `TaskSnapshot`에는 major가 없고
     * `skill_type`은 `(name, major)`가 유일 키이므로, 이름만으로 고르면 major가
     * 둘일 때 아무거나 집게 된다 — 그러면 §9.3의 드레인이 다른 major의 축소를
     * 막거나 못 막는다. 개정판을 거쳐 가면 pinning된 좌표와 같은 답이 나온다.
     */
    private fun skillTypeId(c: Connection, revisionId: Long, skillType: String): Long? =
        c.prepareStatement(
            """
            SELECT s.skill_type_id
            FROM profile_skill ps
            JOIN skill_type s ON s.skill_type_id = ps.skill_type_id
            WHERE ps.profile_revision_id = ? AND s.name = ?
            """.trimIndent(),
        ).use { s ->
            s.setLong(1, revisionId)
            s.setString(2, skillType)
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun upsert(
        c: Connection,
        robotId: String,
        revisionId: Long,
        skillTypeId: Long,
        taskId: String,
        state: TaskState,
        revision: Int,
        attempt: Int,
    ) {
        c.prepareStatement(
            """
            INSERT INTO task (task_id, robot_id, profile_revision_id, skill_type_id,
                              revision, attempt, state, terminal)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (robot_id, task_id) DO UPDATE SET
                -- 종착은 래치다. 되돌리면 드레인이 영영 안 끝난다.
                state    = CASE WHEN task.terminal THEN task.state ELSE EXCLUDED.state END,
                terminal = task.terminal OR EXCLUDED.terminal,
                revision = CASE WHEN task.terminal THEN task.revision ELSE EXCLUDED.revision END,
                attempt  = CASE WHEN task.terminal THEN task.attempt ELSE EXCLUDED.attempt END,
                -- 종착한 뒤에도 올린다. 관측선이 살아 있다는 증거다.
                updated_at = now()
            """.trimIndent(),
        ).use { s ->
            s.setString(1, taskId)
            s.setString(2, robotId)
            s.setLong(3, revisionId)
            s.setLong(4, skillTypeId)
            s.setInt(5, revision)
            s.setInt(6, attempt)
            s.setString(7, state.name)
            s.setBoolean(8, state.isTerminal)
            s.executeUpdate()
        }
    }
}
