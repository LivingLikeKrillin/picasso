package dev.picasso.gate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 설계 §2.3의 실물 조사를 **데이터로** 붙든다.
 *
 * ## 왜 이것이 있어야 하나
 *
 * §2.3은 계약이 실물을 담을 수 있는지 확인한 절이고 §4의 여러 결정이 그 위에
 * 선다. 그런데 그 근거가 **산문으로만** 있었다. 산문은 코드와 갈라지고,
 * 갈라진 것이 안 보인다 — 예를 들어 `Support` 3값이 왜 필요한지는 §2.3의
 * 한 문단이 유일한 근거였다.
 *
 * `profile/vendors/`가 그 표를 데이터로 옮긴 것이고 이 시험이 그것을 읽는다.
 *
 * ## 이것은 능력 프로파일이 아니다
 *
 * `profile/profiles/`의 것들과 다른 스키마를 쓰고 `mimic`이 이것으로 돌지
 * 않는다. **실물 기종의 능력 프로파일을 만들려면 여기 담긴 것으로는
 * 모자란다** — 스킬 어휘도 프로토콜 한계도 없고, 그것이 이 문서가 기록하는
 * 사실 자체다. 하네스가 `profile/profiles/`에 거는 제약(§15.27)을 여기 씌우면
 * 조사 기록이 하네스 맞춤이 되고, 그러면 "벤더 문서에서 파생했다"가
 * 거짓말이 된다.
 */
class VendorSurveyTest {

    private val mapper = ObjectMapper()
    private val dir = Path.of("..", "profile", "vendors").normalize()
    private val schemaPath = Path.of("..", "profile", "schema", "vendor-survey.schema.json")

    private fun surveys(): List<Pair<String, JsonNode>> =
        Files.list(dir).use { stream ->
            stream.filter { it.name.endsWith(".json") }
                .map { it.name to mapper.readTree(Files.readString(it)) }
                .toList()
        }.sortedBy { it.first }

    private fun value(node: JsonNode, vararg path: String): String {
        var cursor = node
        path.forEach { cursor = cursor.path(it) }
        return cursor.asText()
    }

    @Test
    fun `조사 문서가 스키마를 통과한다`() {
        val schema = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Files.readString(schemaPath), CONFIG)

        val all = surveys()
        // **하나도 없으면 아래 시험 전부가 공허하게 초록이다.**
        assertTrue(all.size >= 3, "조사 문서가 셋보다 적다: ${all.map { it.first }}")

        all.forEach { (name, node) ->
            val problems = schema.validate(node)
            assertTrue(problems.isEmpty(), "$name 이 스키마를 어긴다: ${problems.map { it.message }}")
        }
    }

    @Test
    fun `근거 없는 판정이 없다`() {
        // `evidence` 를 스키마가 필수로 요구하지만 **공백 한 칸도 통과한다.**
        // 판정만 남기면 나중에 그것이 조사인지 짐작인지 구분되지 않는다.
        surveys().forEach { (name, node) ->
            node.path("coverage").fields().forEach { (item, value) ->
                val evidence = value.path("evidence").asText()
                assertTrue(
                    evidence.trim().length >= 10,
                    "$name 의 $item 에 근거가 없다시피 하다: '$evidence'",
                )
            }
        }
    }

    // ── §2.3이 발견한 것들 (그리고 2026-09-08 에 갱신된 것들)

    @Test
    fun `1차 근거를 읽자 취소 판정이 둘이나 뒤집혔다`() {
        // **이 시험이 붙드는 것은 값이 아니라 교훈이다.**
        //
        // 2026-09-05 조사에서 Spot 은 `UNKNOWN`, Digit 은 `NO` 였다. 2026-09-08
        // 에 **벤더 1차 원문**을 전수로 읽으니 **둘 다 `YES`** 였다 —
        // Spot 은 `MissionService.StopMission`, Digit 은 `remove-action`.
        //
        // 교훈이 둘이고 방향이 다르다.
        //
        // 1. **`UNKNOWN` 을 `NO` 로 접으면 안 된다**(§7.2의 3값). 접었으면
        //    Spot 을 "취소 못 하는 로봇" 으로 적었을 것이다.
        // 2. **`NO` 도 근거가 약하면 `UNKNOWN` 과 같은 값어치다.** Digit 의
        //    `NO` 는 벤더 문서가 아니라 **제3자 래퍼 코드**를 보고 적은 것이었고,
        //    그 래퍼가 API 의 부분집합이라 있는 것을 못 봤다. 근거 등급을
        //    적어 두었는데도(§15.65) 그 위에 `NO` 를 얹은 것이 실수였다.
        //
        // 그래서 이 시험의 이름이 "모른다를 없다로 접지 마라" 가 아니라
        // **"1차 근거를 읽어라"** 다.
        val byModel = surveys().associate { (_, n) ->
            value(n, "model") to value(n, "cancel_support")
        }

        assertEquals("YES", byModel["Spot"], "미션 계층의 StopMission 이 취소다")
        assertEquals("YES", byModel["Digit"], "remove-action 이 취소다")
        assertEquals("NO", byModel["G1"], "G1 은 SDK 전수에 프리미티브가 없다")
    }

    @Test
    fun `아직 모르는 것이 남아 있다`() {
        // **3값의 셋째가 데이터에서 사라지면 그 값은 스키마에만 있는 것이 되고,**
        // 다음 사람이 "아무도 안 쓰는 값"이라 지운다. 지우고 나면 조사자가
        // 모르는 것을 `NO` 로 적게 되고, 그 `NO` 는 근거 없는 판정이다.
        //
        // 위 시험이 `UNKNOWN` 을 하나 소비했으므로 남은 것이 있는지 여기서 센다.
        // 없어지는 날은 이 시험이 빨개지고, 그때 물어야 할 것은 "정말 다
        // 알아냈는가"이지 "이 시험을 지울까"가 아니다.
        val unresolved = surveys().flatMap { (name, node) ->
            THREE_VALUED.filter { value(node, it) == "UNKNOWN" }.map { "$name#$it" }
        }

        assertTrue(
            unresolved.isNotEmpty(),
            "조사 전체에 UNKNOWN 이 하나도 없다 — 3값의 셋째가 죽은 값이 됐다",
        )
    }

    @Test
    fun `종착이 래치되지 않는 실물이 있다`() {
        // §4.4의 래치 불변식과 TERMINAL_STATE_VIOLATED 가 이 하나 때문에 있다.
        val notLatching = surveys()
            .filter { (_, n) -> value(n, "terminal_latches") == "NO" }
            .map { (_, n) -> value(n, "model") }

        assertEquals(
            listOf("Digit"),
            notLatching,
            "래치하지 않는 실물이 Digit 하나라는 §2.3의 사실이 바뀌었다",
        )
    }

    @Test
    fun `어느 실물도 전부 채우지 못한다`() {
        // §2.3의 결론이다 — *"어느 실물도 프로파일을 전부 채우지 못하며
        // 그것이 정상이다."* 하나라도 FULL 로만 채워진 것이 있으면 그것은
        // 조사가 아니라 희망이다.
        surveys().forEach { (name, node) ->
            val levels = node.path("coverage").properties().map { it.value.path("level").asText() }
            assertTrue(
                levels.any { it != "FULL" },
                "$name 이 §7.2를 전부 채운다고 적혀 있다 — 조사가 아니라 희망이다",
            )
        }
    }

    @Test
    fun `아무 실물도 주지 않는 항목이 있다`() {
        // **§7.2가 요구하는 것 중 셋 다 NONE 인 항목**이 있다. 프로토콜 한계가
        // 그것이며, 그래서 그 값은 언제나 우리가 정하는 것이지 로봇에게서
        // 받는 것이 아니다.
        val all = surveys()
        val items = all.first().second.path("coverage").properties().map { it.key }

        val universallyMissing = items.filter { item ->
            all.all { (_, n) -> value(n, "coverage", item, "level") == "NONE" }
        }

        assertTrue(
            "protocol_limits" in universallyMissing,
            "프로토콜 한계를 주는 실물이 생겼다면 §7.2의 그 항목을 다시 봐야 한다: $universallyMissing",
        )
    }

    @Test
    fun `배타 제어가 예외 없이 참이다`() {
        // §4.9의 첫 문장 — *"실물 로봇은 예외 없이 배타적 제어 소유권 모델을
        // 갖는다."* 그 주장이 조사 데이터와 어긋나면 §4.9가 서지 않는다.
        surveys().forEach { (name, node) ->
            assertEquals(
                "YES",
                value(node, "exclusive_control_required"),
                "$name 이 배타 제어 모델이 아니라면 §4.9의 전제가 틀렸다",
            )
        }
    }

    @Test
    fun `라이선스 제약이 있는 기종이 기록돼 있다`() {
        // ADR 31이 어댑터를 우리가 소유한다고 정했고, 그 대가가 라이선스다.
        // 제약을 아는 기종에서 그것이 비어 있으면 배포 형태를 정할 근거가
        // 사라진다.
        val spot = surveys().single { (_, n) -> value(n, "model") == "Spot" }.second
        val license = value(spot, "license")

        assertTrue("20191101-BDSDK-SL" in license, "Spot 라이선스 식별자가 없다: $license")
        assertTrue("§2(c)" in license && "§2(b)" in license, "제약 조항이 없다: $license")
    }

    private companion object {

        /** `Support` 3값 어휘를 쓰는 조사 필드들. */
        val THREE_VALUED = listOf(
            "exclusive_control_required",
            "pause_support",
            "cancel_support",
            "terminal_latches",
        )

        val CONFIG: SchemaValidatorsConfig =
            SchemaValidatorsConfig.builder().locale(Locale.KOREAN).build()
    }
}
