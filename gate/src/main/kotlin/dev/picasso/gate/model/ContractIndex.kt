package dev.picasso.gate.model

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors
import com.google.protobuf.ExtensionRegistry

/** 프로파일의 value_type과 같은 집합. 설계 §4.3. */
enum class ValueType { BOOL, INTEGER, NUMBER, STRING, ENUM }

data class ParameterDef(
    val key: String,
    val valueType: ValueType,
    val sinceMinor: Int,
    val isOptional: Boolean,
)

data class SkillTypeDef(
    val name: String,
    val major: Int,
    val maxMinor: Int,
    /** 명시 선언인가. 0을 선언한 것과 미선언을 구분한다. */
    val maxMinorDeclared: Boolean,
    val parameters: List<ParameterDef>,
    /** 어느 proto 메시지에서 왔는가. 소견의 location에 쓴다. */
    val protoMessage: String,
) {
    /** 이 minor에서 유효하고 필수인 파라미터. 검사 4번의 양방향 대조가 쓴다. */
    fun requiredParametersAt(minor: Int): List<ParameterDef> =
        parameters.filter { it.sinceMinor <= minor && !it.isOptional }

    /** 이 minor에서 유효한 파라미터 전부. */
    fun parametersAt(minor: Int): List<ParameterDef> =
        parameters.filter { it.sinceMinor <= minor }
}

/**
 * 계약이 소유하는 스킬 어휘. 설계 §5.2·§8.3 결정 4.
 *
 * 최상위 메시지만 훑는다 — 스킬 카탈로그의 규약은 "스킬 타입 하나의 major
 * 하나가 최상위 메시지 하나"다. 중첩 메시지에 옵션을 달면 무시된다.
 */
class ContractIndex private constructor(
    private val skills: List<SkillTypeDef>,
) {
    fun skillTypes(): List<String> = skills.map { it.name }.distinct()

    fun find(name: String, major: Int): SkillTypeDef? =
        skills.firstOrNull { it.name == name && it.major == major }

    fun all(): List<SkillTypeDef> = skills

    /**
     * 같은 (name, major)가 둘 이상인 것들. 설계 §8.3의 UNIQUE(name, major).
     * 비어 있지 않으면 검사 4번이 실패시켜야 한다 — find가 조용히 하나만
     * 보게 두면 무결성이 무너진다.
     */
    fun duplicates(): List<Pair<String, Int>> =
        skills.groupingBy { it.name to it.major }.eachCount()
            .filterValues { it > 1 }
            .keys.toList()

    companion object {
        private const val PKG = "picasso.v1"
        private const val OPT_SKILL_NAME = "$PKG.skill_type_name"
        private const val OPT_SKILL_MAJOR = "$PKG.skill_type_major"
        private const val OPT_SKILL_MAX_MINOR = "$PKG.skill_type_max_minor"
        private const val OPT_SINCE_MINOR = "$PKG.since_minor"
        private const val OPT_IS_OPTIONAL = "$PKG.is_optional"

        private const val WKT_DESCRIPTOR = "google/protobuf/descriptor.proto"

        fun from(descriptorBytes: ByteArray): ContractIndex {
            // 1) 먼저 파싱해 확장 정의를 찾는다. 이 시점의 옵션은 unknown field다.
            val first = FileDescriptorSet.parseFrom(descriptorBytes)
            val (registry, extByName) = buildRegistry(first)

            // 2) 같은 바이트를 레지스트리와 함께 다시 파싱한다.
            //    이것을 빠뜨리면 hasField가 전부 false로 나온다 — 오류 없이.
            val set = FileDescriptorSet.parseFrom(descriptorBytes, registry)

            val skills = set.fileList.flatMap { file ->
                file.messageTypeList.mapNotNull { msg -> toSkill(file, msg, extByName) }
            }

            return ContractIndex(skills)
        }

        private fun buildRegistry(
            set: FileDescriptorSet,
        ): Pair<ExtensionRegistry, Map<String, Descriptors.FieldDescriptor>> {
            val byName = set.fileList.associateBy { it.name }
            val built = LinkedHashMap<String, Descriptors.FileDescriptor>()

            // 잘 알려진 파일은 다시 세우지 않고 생성된 디스크립터를 씨앗으로 넣는다.
            //
            // 다시 세우면 확장의 containingType이 새로 만든 MessageOptions가 되는데
            // msg.options는 컴파일된 DescriptorProtos.MessageOptions라
            // verifyContainingType이 인스턴스 불일치로 거부한다:
            //   IllegalArgumentException: FieldDescriptor does not match message type.
            // 컴파일은 통과하고 런타임에 죽으며 예외가 원인을 설명하지 않는다.
            built[WKT_DESCRIPTOR] = DescriptorProtos.getDescriptor()

            fun build(proto: FileDescriptorProto): Descriptors.FileDescriptor =
                built.getOrPut(proto.name) {
                    // dependencyList만 넘기면 된다 — public/weak 의존은 그 목록의
                    // 인덱스이지 별도 파일이 아니다.
                    val deps = proto.dependencyList.map { depName ->
                        val dep = byName[depName]
                            ?: error("디스크립터에 의존 파일이 없다: $depName")
                        build(dep)
                    }
                    Descriptors.FileDescriptor.buildFrom(proto, deps.toTypedArray())
                }

            set.fileList.forEach { build(it) }

            val registry = ExtensionRegistry.newInstance()
            val byFullName = LinkedHashMap<String, Descriptors.FieldDescriptor>()

            built.values.forEach { fd ->
                fd.extensions.forEach { ext ->
                    // 스칼라 확장은 1-인자로 충분하다. 메시지형 커스텀 옵션을
                    // 나중에 추가하면 add(ext, DynamicMessage.getDefaultInstance(...))
                    // 2-인자를 써야 한다.
                    registry.add(ext)
                    byFullName[ext.fullName] = ext
                }
            }

            return registry to byFullName
        }

        private fun toSkill(
            file: FileDescriptorProto,
            msg: DescriptorProto,
            ext: Map<String, Descriptors.FieldDescriptor>,
        ): SkillTypeDef? {
            val opts = msg.options
            val nameField = ext[OPT_SKILL_NAME] ?: return null
            if (!opts.hasField(nameField)) return null

            val majorField = ext[OPT_SKILL_MAJOR]
                ?: error("$OPT_SKILL_MAJOR 확장이 계약에 없다")
            val maxMinorField = ext[OPT_SKILL_MAX_MINOR]
                ?: error("$OPT_SKILL_MAX_MINOR 확장이 계약에 없다")

            val name = opts.getField(nameField) as String
            val major = (opts.getField(majorField) as Number).toInt()
            val declared = opts.hasField(maxMinorField)
            val maxMinor =
                if (declared) (opts.getField(maxMinorField) as Number).toInt() else 0

            return SkillTypeDef(
                name = name,
                major = major,
                maxMinor = maxMinor,
                maxMinorDeclared = declared,
                parameters = msg.fieldList.map { f -> toParameter(f, ext) },
                protoMessage = "${file.name}:${msg.name}",
            )
        }

        private fun toParameter(
            f: FieldDescriptorProto,
            ext: Map<String, Descriptors.FieldDescriptor>,
        ): ParameterDef {
            val opts = f.options

            val since = ext[OPT_SINCE_MINOR]
                ?.takeIf { opts.hasField(it) }
                ?.let { (opts.getField(it) as Number).toInt() }
                ?: 0

            val isOptional = ext[OPT_IS_OPTIONAL]
                ?.takeIf { opts.hasField(it) }
                ?.let { opts.getField(it) as Boolean }
                ?: false

            return ParameterDef(
                key = f.name,
                valueType = valueTypeOf(f),
                sinceMinor = since,
                isOptional = isOptional,
            )
        }

        /**
         * skill_catalog.proto의 매핑표와 같아야 한다.
         * 그 밖의 타입은 카탈로그에 쓰지 않기로 했으므로 만나면 실패한다 —
         * 조용히 넘기면 검사 4번이 그 파라미터를 못 보게 된다.
         */
        private fun valueTypeOf(f: FieldDescriptorProto): ValueType =
            when (f.type) {
                FieldDescriptorProto.Type.TYPE_BOOL -> ValueType.BOOL
                FieldDescriptorProto.Type.TYPE_INT64 -> ValueType.INTEGER
                FieldDescriptorProto.Type.TYPE_DOUBLE -> ValueType.NUMBER
                FieldDescriptorProto.Type.TYPE_STRING -> ValueType.STRING
                FieldDescriptorProto.Type.TYPE_ENUM -> ValueType.ENUM
                else -> error(
                    "스킬 카탈로그가 지원하지 않는 타입이다: ${f.name} = ${f.type}. " +
                        "skill_catalog.proto의 매핑표를 보라.",
                )
            }
    }
}
