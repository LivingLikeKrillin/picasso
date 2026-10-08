package dev.picasso.middleware.mission

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.middleware.Evidence

/** 임무 정의를 읽은 결과 — 읽었거나, 읽을 수 없는 자리를 **모두** 든다. */
sealed interface MissionParse {
    data class Parsed(val definition: MissionDefinition) : MissionParse

    /** 틀린 곳 전부. 하나만 알리면 고치고 다시 내고 또 거부되는 왕복이 틀린 곳 수만큼 생긴다. */
    data class Unreadable(val problems: List<String>) : MissionParse
}

/**
 * 임무 정의 JSON 의 **엄격한** 읽기 — 모르는 키, 빠진 필수 칸, 형이 틀린 값을 모두 모아 한 번에 알린다.
 *
 * **모양과 형만 본다.** 기한이 양수인지, 기한 뒤 상태가 허용된 값인지, 참조한 신호·스킬이 있는지는 [MissionValidator] 의
 * 일이다. 파서가 그것까지 보면 검증기의 검사를 빼는 결함이 파서에 가려 안 잡힌다.
 *
 * 값은 `JsonFormat` 으로 `Struct` 에 읽는다 — 새 의존을 들이지 않는다(결정 (차)). `Struct` 의 수는 실수로 오므로
 * 정수 칸은 정수인지 따로 본다. 문법과 중복 키는 `JsonFormat` 이 너그러워 [StrictJson] 이 먼저 본다.
 *
 * ## 표기
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "workMasterId": "PrepareSequencedRack",
 *   "maxEvidence": "E2",
 *   "preferredOptionals": { "verify_grasp": "true" },
 *   "steps": [
 *     { "kind": "wait", "id": "rack-arrival", "signal": "rack_present", "expect": "true",
 *       "deadlineSeconds": 120, "onDeadline": "OPERATOR_HOLD" },
 *     { "kind": "unit", "id": "place", "skill": "pick_place", "forEach": "destination",
 *       "pairWith": { "equipmentUse": "source", "property": "material" },
 *       "whenUnpaired": "NO_SOURCE_FOR_MATERIAL",
 *       "unitId": { "from": "ITEM_ID" },
 *       "parameters": {
 *         "object_id": { "from": "PAIRED_ID", "otherwise": "" },
 *         "destination": { "from": "ITEM_ID" }
 *       },
 *       "expectedIdentity": { "from": "ITEM_PROPERTY", "property": "material" },
 *       "source": { "from": "PAIRED_ID" },
 *       "destination": { "from": "ITEM_ID" } }
 *   ]
 * }
 * ```
 *
 * 최상위 다섯 칸은 모두 필수다. 단위 노드는 `id`·`skill`·`forEach`·`unitId`·`parameters` 가 필수이고 나머지는 선택이며,
 * `pairWith` 와 `whenUnpaired` 는 함께 온다. 대기 노드는 `id`·`signal`·`expect` 가 필수이고 `deadlineSeconds`·`onDeadline`
 * 은 형만 본다(없음은 검증기가 거부한다).
 */
object MissionDefinitionParser {

    /** 지금 읽을 줄 아는 문서 모양의 버전. */
    const val SCHEMA_VERSION = 1

    fun parse(text: String): MissionParse {
        val scanned = StrictJson.scan(text)
        if (scanned.broken) return MissionParse.Unreadable(scanned.problems)
        val root = try {
            Struct.newBuilder().also { JsonFormat.parser().merge(text, it) }.build()
        } catch (e: InvalidProtocolBufferException) {
            return MissionParse.Unreadable(scanned.problems + "$: JSON 객체가 아니다 — ${e.message}")
        }
        val reader = Reader()
        val definition = reader.definition(root.fieldsMap)
        val problems = scanned.problems + reader.problems
        return if (problems.isEmpty() && definition != null) MissionParse.Parsed(definition) else MissionParse.Unreadable(problems)
    }

    private val TOP = setOf("schemaVersion", "workMasterId", "maxEvidence", "preferredOptionals", "steps")
    private val UNIT = setOf(
        "kind", "id", "skill", "forEach", "unitId", "parameters",
        "expectedIdentity", "source", "destination", "pairWith", "whenUnpaired",
    )
    private val WAIT = setOf("kind", "id", "signal", "expect", "deadlineSeconds", "onDeadline")
    private val PAIRING = setOf("equipmentUse", "property")
    private val VALUE_SOURCE = setOf("from", "property", "otherwise")

    /** 정의가 낼 수 있는 등급. E3(업무 확인)은 상위의 ack 이라 케이퍼빌리티가 약속할 수 없다. */
    private val EVIDENCE = listOf(Evidence.E0, Evidence.E1, Evidence.E2)

    /**
     * 읽는 동안 틀린 곳을 모은다. 칸 하나가 틀려도 **나머지를 계속 읽는다** — 그래야 한 번에 다 알린다.
     * 틀린 칸의 함수는 널을 돌려주고, 널을 받은 쪽은 문제를 다시 적지 않는다.
     */
    private class Reader {
        val problems = mutableListOf<String>()

        private fun problem(path: String, what: String) {
            problems += "$path: $what"
        }

        private fun unknownKeys(fields: Map<String, Value>, allowed: Set<String>, path: String) {
            (fields.keys - allowed).sorted().forEach { problem(path, "모르는 키 '$it'") }
        }

        private fun present(fields: Map<String, Value>, key: String, path: String, required: Boolean): Value? {
            val value = fields[key]
            if (value == null) {
                if (required) problem("$path.$key", "빠졌다")
                return null
            }
            return value
        }

        fun string(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): String? {
            val value = present(fields, key, path, required) ?: return null
            if (!value.hasStringValue()) {
                problem("$path.$key", "문자열이어야 한다(${kindOf(value)})")
                return null
            }
            if (value.stringValue.isEmpty() && required) {
                problem("$path.$key", "비었다")
                return null
            }
            return value.stringValue
        }

        fun integer(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): Long? {
            val value = present(fields, key, path, required) ?: return null
            val n = if (value.hasNumberValue()) value.numberValue else null
            // `Struct` 는 수를 실수로 준다 — 1.5 나 1e300 을 정수로 접으면 쓴 값과 다른 값으로 돈다.
            if (n == null || n != Math.rint(n) || n < Long.MIN_VALUE.toDouble() || n > Long.MAX_VALUE.toDouble()) {
                problem("$path.$key", "정수여야 한다(${kindOf(value)})")
                return null
            }
            return n.toLong()
        }

        fun obj(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): Map<String, Value>? {
            val value = present(fields, key, path, required) ?: return null
            if (!value.hasStructValue()) {
                problem("$path.$key", "객체여야 한다(${kindOf(value)})")
                return null
            }
            return value.structValue.fieldsMap
        }

        fun stringMap(fields: Map<String, Value>, key: String, path: String): Map<String, String>? {
            val map = obj(fields, key, path) ?: return null
            val out = linkedMapOf<String, String>()
            map.forEach { (k, v) ->
                if (v.hasStringValue()) out[k] = v.stringValue else problem("$path.$key.$k", "문자열이어야 한다(${kindOf(v)})")
            }
            return out
        }

        fun definition(fields: Map<String, Value>): MissionDefinition? {
            val path = "$"
            unknownKeys(fields, TOP, path)
            val schemaVersion = integer(fields, "schemaVersion", path)
            if (schemaVersion != null && schemaVersion != SCHEMA_VERSION.toLong()) {
                problem("$path.schemaVersion", "모르는 문서 버전이다($schemaVersion) — 읽을 줄 아는 것은 $SCHEMA_VERSION 이다")
            }
            val workMasterId = string(fields, "workMasterId", path)
            val maxEvidence = string(fields, "maxEvidence", path)?.let { name ->
                EVIDENCE.firstOrNull { it.name == name }
                    ?: null.also { problem("$path.maxEvidence", "${EVIDENCE.joinToString("·")} 중 하나여야 한다($name)") }
            }
            val optionals = stringMap(fields, "preferredOptionals", path)
            val steps = steps(fields, path)
            if (problems.isNotEmpty()) return null
            return MissionDefinition(
                schemaVersion!!.toInt(), workMasterId!!, maxEvidence!!, optionals!!, steps!!,
            )
        }

        private fun steps(fields: Map<String, Value>, path: String): List<MissionStep>? {
            val value = present(fields, "steps", path, required = true) ?: return null
            if (!value.hasListValue()) {
                problem("$path.steps", "배열이어야 한다(${kindOf(value)})")
                return null
            }
            val items = value.listValue.valuesList
            if (items.isEmpty()) problem("$path.steps", "노드가 하나도 없다")
            return items.mapIndexedNotNull { index, item ->
                val at = "$path.steps[$index]"
                if (!item.hasStructValue()) {
                    problem(at, "객체여야 한다(${kindOf(item)})")
                    return@mapIndexedNotNull null
                }
                val node = item.structValue.fieldsMap
                when (val kind = string(node, "kind", at)) {
                    null -> null
                    "unit" -> unit(node, at)
                    "wait" -> wait(node, at)
                    else -> null.also { problem("$at.kind", "unit 이나 wait 여야 한다($kind)") }
                }
            }
        }

        private fun unit(node: Map<String, Value>, path: String): UnitStep? {
            unknownKeys(node, UNIT, path)
            val id = string(node, "id", path)
            val skill = string(node, "skill", path)
            val forEach = string(node, "forEach", path)
            val pairWith = obj(node, "pairWith", path, required = false)?.let { pairing(it, "$path.pairWith") }
            val whenUnpaired = string(node, "whenUnpaired", path, required = false)
            if (("pairWith" in node) != ("whenUnpaired" in node)) {
                problem(path, "pairWith 와 whenUnpaired 는 함께 온다 — 짝이 없을 때의 실패 분류가 없으면 그 단위를 어떻게 둘지 모른다")
            }
            val paired = "pairWith" in node
            val unitId = source(node, "unitId", path, required = true, paired = paired)
            if (unitId != null && unitId.from != ValueFrom.ITEM_ID) {
                problem("$path.unitId.from", "ITEM_ID 여야 한다(${unitId.from}) — 단위 id 는 반복 중인 설비의 id 다")
            }
            val parameters = obj(node, "parameters", path)?.let { map ->
                val out = linkedMapOf<String, ValueSource>()
                map.forEach { (name, v) ->
                    val at = "$path.parameters.$name"
                    if (!v.hasStructValue()) {
                        problem(at, "값 출처 객체여야 한다(${kindOf(v)})")
                    } else {
                        valueSource(v.structValue.fieldsMap, at, paired)?.let { out[name] = it }
                    }
                }
                out
            }
            val expected = source(node, "expectedIdentity", path, required = false, paired = paired)
            val source = source(node, "source", path, required = false, paired = paired)
            val destination = source(node, "destination", path, required = false, paired = paired)
            if (id == null || skill == null || forEach == null || unitId == null || parameters == null) return null
            return UnitStep(id, skill, forEach, unitId, parameters, expected, source, destination, pairWith, whenUnpaired)
        }

        private fun pairing(fields: Map<String, Value>, path: String): Pairing? {
            unknownKeys(fields, PAIRING, path)
            val use = string(fields, "equipmentUse", path)
            val property = string(fields, "property", path)
            return if (use == null || property == null) null else Pairing(use, property)
        }

        private fun source(node: Map<String, Value>, key: String, path: String, required: Boolean, paired: Boolean): ValueSource? =
            obj(node, key, path, required)?.let { valueSource(it, "$path.$key", paired) }

        private fun valueSource(fields: Map<String, Value>, path: String, paired: Boolean): ValueSource? {
            unknownKeys(fields, VALUE_SOURCE, path)
            val fromName = string(fields, "from", path) ?: return null
            val from = ValueFrom.entries.firstOrNull { it.name == fromName }
                ?: return null.also { problem("$path.from", "${ValueFrom.entries.joinToString("·")} 중 하나여야 한다($fromName)") }
            val property = string(fields, "property", path, required = from == ValueFrom.ITEM_PROPERTY)
            if (from != ValueFrom.ITEM_PROPERTY && "property" in fields) {
                problem("$path.property", "${ValueFrom.ITEM_PROPERTY} 에만 온다")
            }
            if (from == ValueFrom.PAIRED_ID && !paired) {
                problem("$path.from", "${ValueFrom.PAIRED_ID} 인데 이 노드에 pairWith 가 없다")
            }
            val otherwise = string(fields, "otherwise", path, required = false)
            if (from == ValueFrom.ITEM_PROPERTY && property == null) return null
            return ValueSource(from, property, otherwise)
        }

        private fun wait(node: Map<String, Value>, path: String): WaitStep? {
            unknownKeys(node, WAIT, path)
            val id = string(node, "id", path)
            val signal = string(node, "signal", path)
            // 기대 값은 빈 문자열도 값이다 — TEXT 신호가 비었을 때를 기다릴 수 있다.
            val expect = present(node, "expect", path, required = true)?.let { v ->
                if (v.hasStringValue()) v.stringValue else null.also { problem("$path.expect", "문자열이어야 한다(${kindOf(v)})") }
            }
            val deadline = integer(node, "deadlineSeconds", path, required = false)
            val onDeadline = string(node, "onDeadline", path, required = false)
            if (id == null || signal == null || expect == null) return null
            return WaitStep(id, signal, expect, deadline, onDeadline)
        }

        private fun kindOf(value: Value): String = when (value.kindCase) {
            Value.KindCase.NULL_VALUE -> "null"
            Value.KindCase.NUMBER_VALUE -> "수 ${value.numberValue}"
            Value.KindCase.STRING_VALUE -> "문자열 '${value.stringValue}'"
            Value.KindCase.BOOL_VALUE -> "참거짓 ${value.boolValue}"
            Value.KindCase.STRUCT_VALUE -> "객체"
            Value.KindCase.LIST_VALUE -> "배열"
            else -> "값 없음"
        }
    }
}
