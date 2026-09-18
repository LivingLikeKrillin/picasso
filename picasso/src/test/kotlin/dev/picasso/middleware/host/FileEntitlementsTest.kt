package dev.picasso.middleware.host

import dev.picasso.middleware.DeclaredAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 자격 선언 목록을 파일에서 읽는다 — ADR 43 이 「배치의 결정」이라 한 자리의 첫 구현체.
 *
 * **못 읽으면 던진다.** 빈 목록으로 접으면 오타 난 선언 파일이 «아무것도 선언 안 했다» 와 같은 모양이
 * 되고, 그 배치는 자동 승인이 전부 거절되는 것을 정상으로 읽는다.
 */
class FileEntitlementsTest {

    private fun file(body: String): Path {
        val dir = createTempDirectory("entitlements")
        val path = dir.resolve("entitlements.json")
        Files.writeString(path, body)
        return path
    }

    @Test
    fun `인계본에 실린 선언 파일이 실제로 읽힌다`() {
        // ★받는 쪽이 이 파일로 무엇이 승인될지를 예측한다. 여기서 안 읽히면 그 예측이 서지 않는다.
        val declared = FileEntitlements.read(SHIPPED)
        val one = assertNotNull(declared.declaredFor("narrator-1"), "선언이 없다: ${declared.approvers()}")

        assertEquals(setOf("hum-02", "hum-04"), one.robotIds)
        assertEquals(listOf(DeclaredAction("pick_place", mapOf("destination" to "DROP-01"))), one.actions)
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), one.expiresAt)
        assertNull(declared.declaredFor("아무나"), "선언 안 한 승인자에게 자격이 나왔다")
    }

    @Test
    fun `빈 목록은 정상이고 아무에게도 자격을 안 준다`() {
        // 빈 것은 오류가 아니다 — «아무것도 안 덮는다» 는 유효한 선언이고 사람은 여전히 누른다.
        val declared = FileEntitlements.read(file("""{"entitlements":[]}"""))
        assertEquals(emptySet(), declared.approvers())
    }

    @Test
    fun `못 읽는 선언은 빈 목록이 아니라 예외다`() {
        // ★★조용히 비면 그 배치는 «선언이 안 읽혔다» 와 «아무것도 선언 안 했다» 를 구별할 방법이 없다.
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file("{")) }
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file("""{"목록":[]}""")) }
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file("""{"entitlements":{}}""")) }
        assertFailsWith<IllegalArgumentException> {
            FileEntitlements.read(file("""{"entitlements":[{"approverId":"a","robotIds":[],"actions":[]}]}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            FileEntitlements.read(Path.of("없는", "파일.json"))
        }
    }

    @Test
    fun `같은 승인자를 두 번 선언하면 파일이 안 선다`() {
        // 어느 쪽이 유효한지 파일이 답하지 않는다. 하나를 고르는 규칙을 만들면 그 규칙이 선언보다 세진다.
        val twice = """{"entitlements":[$ONE,$ONE]}"""
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file(twice)) }
    }

    private companion object {
        val SHIPPED: Path = Path.of("..", "handoff", "narrator", "entitlements.json").normalize()

        const val ONE = """{"approverId":"narrator-1","robotIds":["hum-02"],""" +
            """"expiresAt":"2099-01-01T00:00:00Z","actions":[{"skillType":"pick_place","parameters":{}}]}"""
    }
}
