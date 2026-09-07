package dev.picasso.registry

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * 시험용 PostgreSQL. **컨테이너는 JVM 하나에 하나뿐이다.**
 *
 * 시험 클래스마다 띄우면 기동이 클래스 수만큼 붙는다. 그것이 문제인 이유는
 * 성능이 아니라 **결함 주입**이다 — 이 저장소는 청크당 스위트를 10~17회
 * 돌린다. 라운드가 5~10배 느려지면 주입을 줄이게 되고, 그러면 지금까지
 * 지켜 온 것을 잃는다.
 *
 * **H2로 대체할 수 없다.** 스키마가 JSONB를 쓴다(§3.4). 대체하면 시험이
 * 지나는 DB와 운영이 도는 DB가 달라지고, 그 차이는 언제나 늦게 드러난다.
 */
object PostgresSupport {

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("picasso")
            .withUsername("picasso")
            .withPassword("picasso")
            .also { it.start() }
    }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password

    fun connection(): Connection = DriverManager.getConnection(jdbcUrl, username, password)

    /**
     * 스키마를 지우고 다시 세운다.
     *
     * **시험마다 롤백하지 않는다.** 롤백은 `registry`가 자기 트랜잭션
     * 경계를 갖는 코드(§8.5의 "API 한 번 = 트랜잭션 한 번")를 시험할 수
     * 없게 만든다 — 바깥 트랜잭션이 안쪽 커밋을 삼킨다.
     *
     * @return 적용된 마이그레이션 수. 0이면 무언가 잘못된 것이다.
     */
    fun reset(): Int {
        val flyway = Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .cleanDisabled(false)
            .load()
        flyway.clean()
        return flyway.migrate().migrationsExecuted
    }

    /** 한 줄 질의. 시험이 스키마를 직접 들여다볼 때 쓴다. */
    fun <T> queryOne(sql: String, read: (java.sql.ResultSet) -> T): T =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    check(rs.next()) { "행이 없다: $sql" }
                    read(rs)
                }
            }
        }

    /**
     * 여러 줄 질의. **개수가 아니라 값을 단언하려고 둔다** — 개수만 보면
     * 이름이 바뀐 것도, 순서가 뒤집힌 것도 통과한다(1단계 실측).
     */
    fun <T> queryAll(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(read(rs)) }
                }
            }
        }

    fun execute(sql: String) = connection().use { c ->
        c.createStatement().use { it.execute(sql) }
    }
}
