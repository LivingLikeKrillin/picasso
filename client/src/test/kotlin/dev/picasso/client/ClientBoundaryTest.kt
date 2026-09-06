package dev.picasso.client

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `client`가 넘지 않아야 하는 선.
 *
 * §3.2가 `client → mimic`을 금지하는 것은 빌드가 강제한다. 강제되지 않는
 * 것은 **`profile-model`을 통해 기종 저작 형식이 새어 들어오는 것**이다 —
 * `RequirementSet`(소비자 선언)을 쓰려고 연 문으로 `ProfileDocument`(기종
 * 선언)가 함께 들어온다. 얇은 소비자가 그것을 읽기 시작하면 게이트 7번이
 * 막으려는 바로 그것이 된다.
 */
class ClientBoundaryTest {

    private val sources: List<Path> = Files.walk(Path.of("src", "main", "kotlin")).use { stream ->
        stream.asSequence().filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    @Test
    fun `볼 소스가 있다`() {
        // 목록이 비면 아래 시험이 아무것도 확인하지 않고 통과한다.
        assertTrue(sources.size >= 2, "client 소스를 못 찾았다: $sources")
    }

    @Test
    fun `기종 저작 형식을 읽지 않는다`() {
        val forbidden = listOf("ProfileDocument", "ProfileKey", "CapabilityProjection")
        sources.forEach { path ->
            val text = Files.readString(path)
            forbidden.forEach {
                assertTrue(
                    it !in text,
                    "$path 가 $it 를 쓴다 — client는 기종 저작 형식을 읽지 않는다(§3.3)",
                )
            }
        }
    }

    @Test
    fun `헤더를 손으로 만들지 않는다`() {
        // §5.5의 요청 열은 contracts의 RequestHeaders가 안다. 여기서 다시
        // 조립하면 표가 두 벌이 되고, 한쪽만 고치면 조용히 어긋난다.
        val builders = sources.count { "MessageHeader.newBuilder" in Files.readString(it) }
        assertEquals(0, builders, "client가 MessageHeader를 직접 조립한다")
    }
}
