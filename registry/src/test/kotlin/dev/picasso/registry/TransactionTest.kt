package dev.picasso.registry

import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §8.5의 트랜잭션 경계 — **API 한 번 = 트랜잭션 한 번 = 감사 로그 한 줄.**
 *
 * **되돌리기를 시험하는 것이 없었다**(실측). `rollback()`을 `commit()`으로
 * 바꾸는 주입이 조용히 통과했다 — 정상 경로에서는 예외가 안 나므로 차이가
 * 없기 때문이다. 예외가 나는 경로를 여기서 만든다.
 *
 * 이것이 없으면 조작 절반만 반영된 상태가 남고, **그 상태를 설명하는 감사
 * 로그는 없다.**
 */
class TransactionTest {

    private lateinit var db: Db

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    }

    private fun profiles(): Int =
        PostgresSupport.queryOne("SELECT count(*) FROM capability_profile") { it.getInt(1) }

    @Test
    fun `예외가 나면 통째로 되돌린다`() {
        val boom = runCatching {
            db.transaction { c ->
                c.createStatement().use {
                    it.execute("INSERT INTO capability_profile (vendor, model) VALUES ('a', 'b')")
                }
                error("조작 중간에 터진다")
            }
        }
        assertTrue(boom.isFailure, "예외가 안 나왔다 — 시험이 아무것도 확인 안 한다")
        assertEquals(0, profiles(), "되돌리지 않았다 — 절반만 반영된 상태가 남는다")
    }

    @Test
    fun `예외가 안 나면 커밋한다`() {
        // 위 시험의 짝. 언제나 되돌리는 구현이면 그것도 통과한다.
        db.transaction { c ->
            c.createStatement().use {
                it.execute("INSERT INTO capability_profile (vendor, model) VALUES ('a', 'b')")
            }
        }
        assertEquals(1, profiles(), "커밋을 안 했다")
    }

    @Test
    fun `여러 문장이 한 단위로 되돌아간다`() {
        // 조작 하나가 여러 행을 만든다(개정판 + 감사 로그). 뒤엣것이 터지면
        // 앞엣것도 남으면 안 된다 — 그것이 §8.5의 "한 줄"이 뜻하는 것이다.
        runCatching {
            db.transaction { c ->
                c.createStatement().use {
                    it.execute("INSERT INTO capability_profile (vendor, model) VALUES ('a', 'b')")
                    it.execute(
                        "INSERT INTO audit_log (operation, actor, subject) " +
                            "VALUES ('X', 'tester', 's')",
                    )
                    // 존재하지 않는 기체를 참조해 FK가 터진다.
                    it.execute(
                        "INSERT INTO task (task_id, robot_id, profile_revision_id, " +
                            "skill_type_id, revision, state) " +
                            "VALUES ('t1', 'nope', 1, 1, 1, 'ACCEPTED')",
                    )
                }
            }
        }
        assertEquals(0, profiles(), "앞선 문장이 남았다")
        assertEquals(
            0,
            PostgresSupport.queryOne("SELECT count(*) FROM audit_log") { it.getInt(1) },
            "일어나지 않은 조작이 감사 로그에 남았다",
        )
    }

    @Test
    fun `트랜잭션 밖에서는 안 되돌아간다`() {
        // 전제를 못박는다 — DB가 자동 커밋으로 도는 것이 아니라 이 타입이
        // 경계를 만든다는 것.
        runCatching {
            db.open().use { c ->
                c.createStatement().use {
                    it.execute("INSERT INTO capability_profile (vendor, model) VALUES ('a', 'b')")
                }
                error("여기서 터져도 앞 문장은 이미 커밋됐다")
            }
        }
        assertEquals(1, profiles(), "자동 커밋이 아니다 — 시험의 전제가 틀렸다")
    }
}
