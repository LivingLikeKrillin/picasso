package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Resource
import dev.picasso.gate.input.GateInput
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Check04CrossRefTest {

    private val descriptor: ByteArray by lazy {
        val p = System.getProperty("picasso.descriptor")
            ?: error("picasso.descriptor 시스템 프로퍼티가 없다")
        val path = Path.of(p)
        check(Files.exists(path)) {
            "디스크립터가 없다: $p\n" +
                "  mkdir -p contracts/build && ( cd contracts && ../tools/buf build -o build/descriptor.binpb )"
        }
        Files.readAllBytes(path)
    }

    // CRLF 체크아웃이면 \n을 담은 치환이 전부 빗나가고 음성 시험이 조용히 통과한다.
    private val fixtureJson: String by lazy {
        val p = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        check(Files.exists(p)) { "픽스처가 없다: ${p.toAbsolutePath()}" }
        Files.readString(p).replace("\r\n", "\n")
    }

    /** 치환이 빗나가면 시험이 조용히 통과한다. 그것을 여기서 막는다. */
    private fun mutate(from: String, to: String): String {
        val out = fixtureJson.replace(from, to)
        check(out != fixtureJson) { "치환이 아무것도 바꾸지 못했다: '$from'" }
        return out
    }

    private fun run(json: String, bytes: ByteArray = descriptor): CheckResult =
        Check04CrossRef().run(
            GateInput(
                profiles = listOf(ProfileDocument.parse("t.json", json).getOrThrow()),
                descriptor = bytes,
            ),
        )

    @Test
    fun `문서와 디스크립터를 요구한다`() {
        assertEquals(
            setOf(Resource.PROFILE_DOCUMENT, Resource.CONTRACT_DESCRIPTOR),
            Check04CrossRef().requires,
        )
    }

    @Test
    fun `픽스처는 계약과 맞는다`() {
        assertTrue(run(fixtureJson) is CheckResult.Passed, "픽스처가 계약과 어긋난다")
    }

    @Test
    fun `계약에 없는 스킬을 선언하면 실패한다`() {
        val r = run(mutate("\"skill_type\": \"navigate_to\"", "\"skill_type\": \"teleport\""))
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("teleport") })
    }

    @Test
    fun `계약에 없는 major를 선언하면 실패한다`() {
        // 픽스처에서 skill_type과 major는 줄바꿈 + 6칸 들여쓰기로 갈라져 있다.
        // 한 줄로 쓰면 치환이 빗나간다.
        val r = run(
            mutate(
                "\"skill_type\": \"navigate_to\",\n      \"major\": 1",
                "\"skill_type\": \"navigate_to\",\n      \"major\": 7",
            ),
        )
        assertTrue(r is CheckResult.Failed)
    }

    @Test
    fun `계약의 최신 minor를 넘으면 실패한다`() {
        // pick_place의 max_minor는 2다.
        val r = run(mutate("\"major\": 1,\n      \"minor\": 2", "\"major\": 1,\n      \"minor\": 5"))
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("minor") })
    }

    @Test
    fun `명시 선언된 minor 0을 넘으면 실패한다`() {
        // navigate_to는 max_minor를 0으로 명시 선언했다. 미선언으로 읽으면
        // 이 판정이 무너진다 — 존재성 구분이 값을 하는 지점이다.
        val r = run(
            mutate(
                "\"skill_type\": \"navigate_to\",\n      \"major\": 1,\n      \"minor\": 0",
                "\"skill_type\": \"navigate_to\",\n      \"major\": 1,\n      \"minor\": 3",
            ),
        )
        assertTrue(r is CheckResult.Failed)
    }

    @Test
    fun `계약에 없는 파라미터 키를 선언하면 실패한다`() {
        val r = run(mutate("\"key\": \"verify_grasp\"", "\"key\": \"verify_gasp\""))
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("verify_gasp") })
    }

    @Test
    fun `선언한 minor보다 나중에 생긴 파라미터를 쓰면 실패한다`() {
        // grip_force는 since_minor=2다. minor 0을 선언하면서 쓸 수 없다.
        val r = run(mutate("\"major\": 1,\n      \"minor\": 2", "\"major\": 1,\n      \"minor\": 0"))
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("grip_force") })
    }

    @Test
    fun `값 타입이 계약과 다르면 실패한다`() {
        val r = run(
            mutate(
                "\"key\": \"verify_grasp\", \"value_type\": \"BOOL\"",
                "\"key\": \"verify_grasp\", \"value_type\": \"STRING\"",
            ),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("verify_grasp") && it.message.contains("BOOL") })
    }

    @Test
    fun `필수 파라미터를 빠뜨리면 실패한다`() {
        // **한 방향만 보면 이것이 통과한다.** 없는 것을 선언하지 않았을 뿐이라
        // 참조 무결성은 깨지지 않기 때문이다. 양방향인 이유가 이 시험이다.
        val r = run(
            mutate(
                "        { \"key\": \"destination\", \"value_type\": \"STRING\", \"optional\": false, \"max_length\": 64 },\n",
                "",
            ),
        )
        r as CheckResult.Failed
        assertTrue(
            r.findings.any { it.message.contains("destination") },
            "필수 파라미터 누락을 못 잡았다: ${r.findings.map { it.message }}",
        )
    }

    @Test
    fun `계약이 필수라 한 것을 선택으로 선언하면 실패한다`() {
        // 키는 있으므로 양방향 대조를 둘 다 통과한다. 빠뜨린 게 아니라
        // 거짓말한 것뿐, 클라이언트는 그것 없이 태스크를 보내도 된다고 믿는다.
        val r = run(
            mutate(
                "\"key\": \"destination\", \"value_type\": \"STRING\", \"optional\": false",
                "\"key\": \"destination\", \"value_type\": \"STRING\", \"optional\": true",
            ),
        )
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("destination") })
    }

    @Test
    fun `선택 파라미터를 빠뜨리는 것은 허용한다`() {
        // 프로파일은 기종이 무엇을 지원하는지를 말한다. 선택 파라미터를
        // 지원하지 않는 기종이 있는 것이 정상이다.
        val trimmed = mutate(
            ",\n        { \"key\": \"grasp_attempts\", \"value_type\": \"INTEGER\", \"optional\": true, \"min_value\": 1, \"max_value\": 5, \"unit\": \"count\" }",
            "",
        )
        assertTrue(run(trimmed) is CheckResult.Passed, "선택 파라미터 누락을 실패시켰다")
    }

    @Test
    fun `빈 카탈로그를 프로파일 탓으로 오진하지 않는다`() {
        // 0바이트 디스크립터(buf가 중간에 죽어 빈 파일을 남긴 경우)는 예외
        // 없이 빈 색인이 된다. 소견이 "계약이 아는 것: "(뒤가 빔)이 되면
        // 사람이 프로파일을 들여다본다 — 진짜 원인은 디스크립터다.
        val r = run(fixtureJson, bytes = ByteArray(0))
        r as CheckResult.Failed
        assertTrue(
            r.findings.single().message.contains("디스크립터"),
            "원인을 디스크립터라고 말하지 않는다: ${r.findings.map { it.message }}",
        )
    }

    @Test
    fun `optional_fields가 없는 파라미터를 가리키면 실패한다`() {
        // 스키마는 점표기 패턴만 강제하므로 오타가 그대로 통과한다.
        val r = run(mutate("task.parameters.grip_force", "task.parameters.grip_forse"))
        r as CheckResult.Failed
        assertTrue(r.findings.any { it.message.contains("grip_forse") })
    }
}
