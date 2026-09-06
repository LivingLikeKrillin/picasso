package dev.picasso.mimic.profile

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import dev.picasso.profile.ProfileDocument
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 프로파일을 받아들일 수 없다. **기동하지 않는다.**
 *
 * §10.2·§10.6 — 능력을 모르는 채 표면을 열면 소비자가 없는 능력을 믿게 된다.
 */
class ProfileRejected(message: String, val findings: List<String> = emptyList()) :
    IllegalStateException(
        if (findings.isEmpty()) message else "$message\n${findings.joinToString("\n")}",
    )

/** 프로파일 출처. 파일 모드가 2단계, 레지스트리 모드가 3단계다(§10.2). */
fun interface ProfileSource {
    fun load(reference: Path): ProfileDocument
}

/**
 * 파일에서 읽는다(`--profile <path>`).
 *
 * 게이트 검사 3번과 같은 스키마를 같은 로케일로 돌린다 — 기동 거부 메시지가
 * 환경에 따라 달라지면 `harness`가 그것을 다룰 수 없다.
 *
 * **게이트 검사 3번의 구조 규칙 넷은 보지 않는다**(발행 간격 뒤집힘,
 * `(skill_type, major)` 중복, 스킬 내 key 중복, 어댑터 전용 `error_type`).
 * §10.2가 요구하는 것은 스키마 검증뿐이라 사양 위반은 아니지만, `mimic`이
 * 게이트가 거절할 프로파일로 기동할 수 있다는 비대칭이 남는다(§15).
 */
class FileProfileSource(private val schemaPath: Path) : ProfileSource {

    private val mapper = ObjectMapper()

    private val schema: JsonSchema by lazy {
        val text = runCatching { Files.readString(schemaPath) }.getOrElse {
            throw ProfileRejected("능력 프로파일 스키마를 읽을 수 없다: $schemaPath (${it.message})")
        }
        JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(text, SchemaValidatorsConfig.builder().locale(Locale.KOREAN).build())
    }

    override fun load(reference: Path): ProfileDocument {
        val text = runCatching { Files.readString(reference) }.getOrElse {
            throw ProfileRejected("프로파일을 읽을 수 없다: $reference (${it.message})")
        }

        val document = ProfileDocument.parse(reference.toString(), text).getOrElse {
            throw ProfileRejected("프로파일이 JSON이 아니다: $reference (${it.message})")
        }

        val violations = schema.validate(mapper.readTree(text))
            .map { "${it.instanceLocation}: ${it.message}" }
            .sorted()

        if (violations.isNotEmpty()) {
            throw ProfileRejected("프로파일이 스키마를 통과하지 못했다: $reference", violations)
        }

        return document
    }
}
