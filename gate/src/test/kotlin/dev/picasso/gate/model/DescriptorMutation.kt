package dev.picasso.gate.model

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.UnknownFieldSet

/**
 * 계약 저작 실수를 만들어 내는 도우미.
 *
 * **레지스트리 없이 파싱한다.** 그러면 커스텀 옵션이 unknown field로 남아
 * 그대로 다시 직렬화되므로, 확장을 Java로 생성하지 않고도 옵션을 손댈 수 있다.
 * `ContractIndex.from`이 그 바이트를 진짜 디스크립터처럼 읽는다.
 *
 * 이 방식을 쓰는 이유: 실수를 흉내 낸 픽스처를 커밋하면 계약이 바뀔 때 낡는다.
 * 여기서는 언제나 **현재의 진짜 디스크립터**를 변형한다.
 */
object DescriptorMutation {

    /** `skill_catalog.proto`의 옵션 필드 번호. proto 파일과 같아야 한다. */
    private const val SINCE_MINOR = 50001
    private const val SKILL_TYPE_MAJOR = 50003

    private fun set(bytes: ByteArray): FileDescriptorSet.Builder =
        FileDescriptorSet.parseFrom(bytes).toBuilder()

    /** 카탈로그 파일과 그 안의 메시지를 찾아 고친다. */
    private fun edit(
        bytes: ByteArray,
        messageName: String,
        block: (DescriptorProto.Builder) -> Unit,
    ): ByteArray {
        val builder = set(bytes)
        var touched = false
        builder.fileBuilderList.forEach { file ->
            file.messageTypeBuilderList.forEach { msg ->
                if (msg.name == messageName) {
                    block(msg)
                    touched = true
                }
            }
        }
        check(touched) { "메시지를 찾지 못했다: $messageName — 계약이 바뀌었나?" }
        return builder.build().toByteArray()
    }

    /** 같은 `(name, major)` 메시지를 하나 더 만든다. 옵션은 그대로 복사된다. */
    fun duplicateSkill(bytes: ByteArray, messageName: String): ByteArray {
        val builder = set(bytes)
        val file = builder.fileBuilderList.first { f ->
            f.messageTypeList.any { it.name == messageName }
        }
        val original = file.messageTypeList.first { it.name == messageName }
        file.addMessageType(original.toBuilder().setName("${messageName}Copy"))
        return builder.build().toByteArray()
    }

    /** 파라미터 하나를 `repeated`로 만든다. */
    fun makeRepeated(bytes: ByteArray, messageName: String, fieldName: String): ByteArray =
        edit(bytes, messageName) { msg ->
            msg.fieldBuilderList.first { it.name == fieldName }
                .label = FieldDescriptorProto.Label.LABEL_REPEATED
        }

    /** `skill_type_major` 옵션을 지운다. proto2 기본값 0이 조용히 들어오는 경우. */
    fun dropMajor(bytes: ByteArray, messageName: String): ByteArray =
        edit(bytes, messageName) { msg ->
            msg.options = msg.options.toBuilder()
                .setUnknownFields(without(msg.options.unknownFields, SKILL_TYPE_MAJOR))
                .build()
        }

    /** 파라미터의 `since_minor`를 바꾼다. `max_minor`를 넘기면 도달 불가능해진다. */
    fun setSinceMinor(
        bytes: ByteArray,
        messageName: String,
        fieldName: String,
        value: Long,
    ): ByteArray = edit(bytes, messageName) { msg ->
        val field = msg.fieldBuilderList.first { it.name == fieldName }
        field.options = field.options.toBuilder()
            .setUnknownFields(
                without(field.options.unknownFields, SINCE_MINOR).toBuilder()
                    .addField(
                        SINCE_MINOR,
                        UnknownFieldSet.Field.newBuilder().addVarint(value).build(),
                    )
                    .build(),
            )
            .build()
    }

    /** 스킬 메시지를 다른 메시지 안으로 옮긴다. */
    fun nestSkill(bytes: ByteArray, messageName: String): ByteArray {
        val builder = set(bytes)
        val file = builder.fileBuilderList.first { f ->
            f.messageTypeList.any { it.name == messageName }
        }
        val index = file.messageTypeList.indexOfFirst { it.name == messageName }
        val original = file.getMessageType(index)
        file.removeMessageType(index)
        file.addMessageType(
            DescriptorProto.newBuilder().setName("Wrapper").addNestedType(original),
        )
        return builder.build().toByteArray()
    }

    private fun without(fields: UnknownFieldSet, number: Int): UnknownFieldSet =
        UnknownFieldSet.newBuilder().apply {
            fields.asMap().forEach { (num, field) -> if (num != number) addField(num, field) }
        }.build()
}
