package dev.picasso.profile

/** 구조 규칙 위반 하나. `pointer`는 문서 안의 JSON Pointer다. */
data class StructuralViolation(val pointer: String, val message: String)

/**
 * 스키마로 표현할 수 없는 규칙 넷(설계 §11.2).
 *
 * JSON Schema 2020-12에는 필드 간 수치 비교가 없고 `uniqueItems`는 항목
 * 전체를 비교하므로 부분 키 중복을 잡지 못한다. 게이트 검사 3번과 `mimic`의
 * 기동이 같은 규칙을 보도록 여기 한 자리에 둔다(§15.286). 그래야 게이트가
 * 거절할 프로파일로 에뮬레이터가 기동하지 않는다.
 */
fun ProfileDocument.structuralViolations(): List<StructuralViolation> {
    val out = mutableListOf<StructuralViolation>()

    if (publishIntervalMinSeconds > publishIntervalMaxSeconds) {
        out += StructuralViolation(
            "/publish_interval",
            "publish_interval이 뒤집혔다: " +
                "min_seconds=$publishIntervalMinSeconds > " +
                "max_seconds=$publishIntervalMaxSeconds",
        )
    }

    skills.groupingBy { it.skillType to it.major }.eachCount()
        .filterValues { it > 1 }
        .forEach { (key, count) ->
            out += StructuralViolation(
                "/skills",
                "(skill_type, major) 중복이다: ${key.first}@${key.second} 가 ${count}번 선언됐다 " +
                    "— 설계 §5.2가 이 쌍을 동일성으로 규정한다",
            )
        }

    skills.forEachIndexed { i, s ->
        s.parameters.groupingBy { it.key }.eachCount()
            .filterValues { it > 1 }
            .forEach { (key, count) ->
                out += StructuralViolation(
                    "/skills/$i/parameters",
                    "${s.skillType}@${s.major} 안에서 파라미터 key 중복이다: " +
                        "'$key'가 ${count}번 — 어느 선언이 유효한지 미정의가 된다",
                )
            }

        s.parameters.forEachIndexed { j, p ->
            val lo = p.minValue
            val hi = p.maxValue
            if (lo != null && hi != null && lo > hi) {
                out += StructuralViolation(
                    "/skills/$i/parameters/$j",
                    "범위가 뒤집혔다: ${s.skillType}@${s.major}.${p.key} " +
                        "min_value=$lo > max_value=$hi",
                )
            }
        }
    }

    return out
}
