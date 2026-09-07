package dev.picasso.registry.store

import java.sql.Connection
import java.sql.DriverManager

/**
 * 데이터베이스 접근의 유일한 문.
 *
 * **트랜잭션 경계가 조작의 경계다**(§8.5의 "API 한 번 = 트랜잭션 한 번 =
 * 감사 로그 한 줄"). 조작 하나가 여러 트랜잭션으로 쪼개지면 절반만 반영된
 * 상태가 남고, 그 상태를 설명하는 감사 로그는 없다.
 *
 * **프레임워크를 모른다**(§3.4의 "도메인 로직은 프레임워크를 모른다").
 * Spring이 붙어도 이 타입은 그대로여야 한다 — 붙는 것은 HTTP 표면이지
 * 트랜잭션의 뜻이 아니다.
 */
class Db(
    private val url: String,
    private val user: String,
    private val password: String,
) {
    fun open(): Connection = DriverManager.getConnection(url, user, password)

    /**
     * 하나의 트랜잭션으로 실행한다. 예외가 나면 통째로 되돌린다.
     *
     * **부분 커밋을 만들지 않는 것이 요점이다.** 개정판 행은 들어갔는데
     * 감사 로그가 없으면, 나중에 "누가 언제 이걸 올렸나"에 답할 수 없다.
     */
    fun <T> transaction(block: (Connection) -> T): T = open().use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        }
    }
}
