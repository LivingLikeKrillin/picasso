package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **원인이 번들에 없고 주입한 쪽만 아는 사건**(§15.179).
 *
 * ## 왜 정답이 따로 나가나
 *
 * 읽는 쪽은 사건 번들로 질의를 만들고 모델이 낸 1순위 원인을 정답과 맞대 잰다. 그런데 정답이 번들
 * 안에 있으면 **답이 입력을 복창하기만 해도 만점**이고, 그 지표는 아무것도 재지 않는다. 실제로 세 번
 * 그렇게 됐다 — 분류 이름을 정답으로 뒀고, 분류에서 기종을 되짚었고, 결함 이름이 원인을 말했다.
 *
 * 그래서 번들은 **증상만** 싣고(불투명한 정지 코드), 원인은 `handoff/narrator/ground-truth.jsonl` 로
 * 따로 나가며, 코드의 뜻은 **코퍼스의 벤더 문서**에만 있다. 읽는 쪽이 번들을 되읽는 대신 문서를
 * 인용해 좁혀야 답이 나온다 — 되읽기와 근거로 좁히기가 여기서 갈린다.
 *
 * ## 이 시험이 드는 것
 *
 * 정답이 성립하려면 넷이 동시에 참이어야 하는데, 앞의 둘은 읽는 쪽이 자기 시험으로 막는다. 여기서
 * 드는 것은 **사건을 만드는 쪽만 보증할 수 있는 것**이다: 정답의 말이 번들에 없는가, 그리고 주입한
 * 코드를 코퍼스가 풀 수 있는가.
 */
class GroundTruthTest {

    private fun parse(line: String): Map<String, Value> {
        val b = Struct.newBuilder()
        JsonFormat.parser().merge(line, b)
        return b.build().fieldsMap
    }

    private fun rows(): List<Map<String, Value>> =
        Files.readString(GROUND_TRUTH).trim().lines().filter { it.isNotBlank() }.map { parse(it) }

    private fun incidents(): Map<String, String> = Files
        .readString(HANDOFF.resolve("run-1").resolve(LedgerExport.INCIDENTS))
        .trim().lines().filter { it.isNotBlank() }
        .associateBy { parse(it).getValue("incidentId").stringValue }

    private fun words(row: Map<String, Value>, key: String): List<String> =
        row.getValue(key).listValue.valuesList.map { it.stringValue }

    @Test
    fun `정답이 가리키는 사건이 인계한 한 벌에 실재한다`() {
        // 정답표가 없는 사건을 가리키면 채점이 통째로 비고, 그 침묵은 «모델이 못 맞혔다» 와 구별되지 않는다.
        val incidents = incidents()
        rows().forEach { row ->
            val id = row.getValue("incidentId").stringValue
            val line = assertNotNull(incidents[id], "정답표가 없는 사건을 가리킨다: $id (한 벌: ${incidents.keys})")
            // 시나리오가 바뀌면 `incident-N` 이 밀린다. 코드를 함께 대조해 **어느 사건인지**를 고정한다.
            val fault = parse(line).getValue("fault")
            assertTrue(fault.hasStructValue(), "$id 에 결함 원문이 없다 — 정답이 다른 사건에 붙었다")
            assertEquals(
                row.getValue("vendorCode").stringValue,
                fault.structValue.fieldsMap.getValue("vendorDetail").stringValue,
                "$id 의 정지 코드가 정답표와 다르다 — 번호가 밀렸다",
            )
        }
    }

    @Test
    fun `정답의 말이 번들 어디에도 없다`() {
        // ★★**이것이 이 요청의 핵심이다.** 한 낱말이라도 새면 복창이 만점을 받고, 그 지표는
        //   모델이 아니라 자기 입력을 잰다. 오답 후보(`distractors`)도 같다 — 번들에 있으면
        //   그럴듯해서가 아니라 거기 적혀 있어서 나온다.
        val incidents = incidents()
        rows().forEach { row ->
            val id = row.getValue("incidentId").stringValue
            val line = assertNotNull(incidents[id])
            val leaked = (words(row, "cause") + words(row, "distractors")).filter { it in line }
            assertEquals(emptyList(), leaked, "$id 의 번들이 정답의 말을 싣고 있다")
        }
    }

    @Test
    fun `주입한 코드를 코퍼스가 푼다`() {
        // 코드의 뜻이 문서에 없으면 아무도 못 좁힌다. 그러면 이 사건은 «안 좁혀지는 것이 정상» 이
        // 아니라 **재료가 없는 것**이고, 둘은 다른 실패다.
        val corpus = Files.readString(STOP_CODES)
        rows().forEach { row ->
            val code = row.getValue("vendorCode").stringValue
            assertTrue(code in corpus, "벤더 문서가 이 코드를 안 푼다: $code (${STOP_CODES.fileName})")
        }
    }

    @Test
    fun `안 좁혀지는 사건이 하나는 있다`() {
        // ★전부 좁혀지면 «안 좁혀진다» 가 한 번도 정답이 되지 않고, 그러면 그 답을 낼 줄 아는지를
        //  잴 수가 없다. 과잉 단정을 잡는 자리가 여기다.
        val all = rows()
        assertTrue(all.size >= 4, "표본이 넷보다 적다: ${all.size}")

        val narrow = all.groupBy { it.getValue("narrowable").boolValue }
        assertTrue(narrow[false].orEmpty().isNotEmpty(), "안 좁혀지는 사건이 없다")
        assertTrue(narrow[true].orEmpty().isNotEmpty(), "좁혀지는 사건이 없다 — 대조군이 비었다")
    }

    @Test
    fun `정답이 가리키는 사건은 미들웨어가 분류하지 못한 것이다`() {
        // ★이 골든의 전제는 «미들웨어가 못 가른 벤더 코드를 설명 층이 문서로 좁힌다» 이다.
        //  어댑터가 이 코드를 매핑하면 분류가 붙고 전제가 사라지므로, 그때 조용히 넘어가지 않는다.
        val incidents = incidents()
        rows().forEach { row ->
            val id = row.getValue("incidentId").stringValue
            val line = assertNotNull(incidents[id])
            assertEquals(
                "UNCLASSIFIED",
                parse(line).getValue("failureClass").stringValue,
                "$id 에 분류가 붙었다 — 매핑이 늘었으면 이 골든의 전제를 다시 봐야 한다",
            )
        }
    }

    @Test
    fun `사람이 읽을 사유가 비어 있지 않다`() {
        // 정답을 또 잘못 짰는지 사람이 검토할 수 있어야 한다. 낱말 목록만으로는 그 검토가 안 된다.
        rows().forEach { row ->
            val id = row.getValue("incidentId").stringValue
            assertTrue(row.getValue("why").stringValue.length > 30, "$id 의 사유가 비었다")
            assertTrue(words(row, "cause").isNotEmpty(), "$id 의 정답 낱말이 비었다")
        }
    }

    private companion object {
        val HANDOFF: Path = Path.of("..", "handoff", "narrator").normalize()
        val GROUND_TRUTH: Path = HANDOFF.resolve("ground-truth.jsonl")
        val STOP_CODES: Path = Path.of("..", "docs", "vendors", "fixture-stop-codes.md").normalize()
    }
}
