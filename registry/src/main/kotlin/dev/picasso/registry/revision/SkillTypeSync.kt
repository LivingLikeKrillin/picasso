package dev.picasso.registry.revision

import dev.picasso.gate.model.ContractIndex
import dev.picasso.registry.store.Db

/**
 * `skill_type`을 계약에서 채운다.
 *
 * **이 표는 읽기 전용이다**(§8.1) — 계약이 소유하고 여기서 편집하지 않는다.
 * 레지스트리에서 손으로 넣게 두면 계약과 두 번째 진실이 생기고, 그때
 * 어댑터가 아는 스킬과 레지스트리가 아는 스킬이 갈린다.
 *
 * **디스크립터를 다시 파싱하지 않고 `ContractIndex`를 쓴다.** 게이트가 이미
 * 그것을 하고, 검사 4번의 교차검증이 그 결과 위에 선다 — 여기서 따로 읽으면
 * 같은 디스크립터를 두 코드가 다르게 읽는 날이 온다.
 *
 * ## `introduced_in_semver`는 **처음 본 때**의 계약 semver다
 *
 * 나중 동기화가 덮어쓰면 안 된다 — 덮으면 옛 어댑터가 이미 아는 스킬이
 * 갑자기 "더 새로운 계약이 필요한" 것으로 보여 멀쩡한 바인딩이 거부된다.
 */
class SkillTypeSync(private val db: Db) {

    /**
     * @return 새로 들어온 스킬 타입 수. 0이면 계약에 새것이 없다는 뜻이다.
     */
    fun sync(descriptor: ByteArray, contractSemver: String, actor: String): Int =
        db.transaction { c ->
            val index = ContractIndex.from(descriptor)
            var inserted = 0

            index.skillTypes().forEach { name ->
                // major는 카탈로그가 이름당 여럿을 가질 수 있다(§5.2 — major가
                // 오르면 새 메시지다). 전부 넣는다.
                (1..MAX_MAJOR).forEach { major ->
                    val def = index.find(name, major) ?: return@forEach
                    val rows = c.prepareStatement(
                        """
                        INSERT INTO skill_type
                            (name, major, introduced_in_semver, contract_revision, synced_at)
                        VALUES (?, ?, ?, ?, now())
                        ON CONFLICT (name, major) DO UPDATE
                            SET contract_revision = EXCLUDED.contract_revision,
                                synced_at = now()
                        """.trimIndent(),
                    ).use { s ->
                        s.setString(1, def.name)
                        s.setInt(2, def.major)
                        s.setString(3, contractSemver)
                        s.setString(4, contractSemver)
                        s.executeUpdate()
                    }
                    // ON CONFLICT DO UPDATE 는 갱신도 1을 낸다. 새것인지는
                    // 따로 본다 — 그러지 않으면 "새로 들어온 수"가 거짓말한다.
                    if (rows > 0 && wasInserted(c, def.name, def.major, contractSemver)) {
                        inserted += 1
                    }
                }
            }

            audit(c, actor, inserted)
            inserted
        }

    private fun wasInserted(
        c: java.sql.Connection,
        name: String,
        major: Int,
        semver: String,
    ): Boolean = c.prepareStatement(
        "SELECT introduced_in_semver = ? AND contract_revision = ? FROM skill_type " +
            "WHERE name = ? AND major = ?",
    ).use { s ->
        s.setString(1, semver); s.setString(2, semver)
        s.setString(3, name); s.setInt(4, major)
        s.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
    }

    private fun audit(c: java.sql.Connection, actor: String, inserted: Int) = c.prepareStatement(
        "INSERT INTO audit_log (operation, actor, subject, after) VALUES (?, ?, ?, ?::jsonb)",
    ).use {
        it.setString(1, "SKILL_TYPE_SYNC")
        it.setString(2, actor)
        it.setString(3, "contract")
        it.setString(4, """{"inserted":$inserted}""")
        it.executeUpdate()
    }

    private companion object {
        /**
         * 훑는 major의 상한. `ContractIndex`가 이름별 major 목록을 안 주므로
         * 위에서 훑는다. **작아서 문제가 되면 시험이 먼저 빨개진다** —
         * 프로파일이 선언한 스킬이 `skill_type`에 없으면 바인딩이 막힌다.
         */
        const val MAX_MAJOR = 8
    }
}
