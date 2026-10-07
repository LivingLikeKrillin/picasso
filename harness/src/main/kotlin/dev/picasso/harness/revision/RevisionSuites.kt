package dev.picasso.harness.revision

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import dev.picasso.capability.CapabilityProjection
import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.OptionalFieldSupport
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.SkillCatalog
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.Support
import dev.picasso.contracts.v1.TaskState
import dev.picasso.harness.Harness
import dev.picasso.harness.Suite
import dev.picasso.mimic.profile.FileProfileSource
import dev.picasso.mimic.profile.ProfileRejected
import dev.picasso.profile.LimitsNeeded
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.Requirement
import dev.picasso.profile.RequirementSet
import java.nio.file.Path
import java.time.Duration

/** 검사 하나가 어긋난 자리. [check] 는 `CONTRACT.capabilities` 처럼 스위트와 검사와 대상을 잇는다. */
data class SuiteFailure(val check: String, val expected: String, val observed: String)

/**
 * 스위트 하나의 결과. [checked] 는 실제로 돌린 검사의 식별자다 — PASS 도 그 수를 실어 검사가 돌았음을 보인다.
 */
data class SuiteOutcome(val suite: Suite, val checked: List<String>, val failures: List<SuiteFailure>) {
    val checks: Int get() = checked.size

    /** 검사가 하나도 안 돌았으면 통과가 아니다. */
    val passed: Boolean get() = failures.isEmpty() && checks > 0

    /** `revision_test_run.detail` 에 들어갈 JSON(스펙 §5.4). */
    fun detailJson(): String = MAPPER.writeValueAsString(
        mapOf(
            "checks" to checks,
            "failures" to failures.map { mapOf("check" to it.check, "expected" to it.expected, "observed" to it.observed) },
        ),
    )

    private companion object {
        val MAPPER = ObjectMapper()
    }
}

/**
 * 개정판 시험 3종(picasso-ops P2·S1d 스펙 §5.4, ADR 49). **후보 문서 하나로 mimic 을 띄워 돈다.**
 *
 * 문서는 파일 경로로 받아 [Harness] 와 같은 [FileProfileSource] 를 지난다. 스키마를 어긴 문서 위에 시험이 서지
 * 않게 하려는 것이다. 시나리오마다 새 [Harness] 를 띄운다 — 앞 시나리오가 남긴 상태(쥔 물체, 결함)가 다음 시나리오의
 * 전제를 바꾸지 않게 하려는 것이다.
 *
 * @param seed 모든 시나리오가 쓰는 시드. DETERMINISM 은 같은 시드로 두 번 돈다
 * @param stepLimit 태스크 하나에 미는 가상 초의 상한. 넘으면 그 시나리오는 FAIL 이다(스펙 §5.2)
 */
class RevisionSuites(
    private val schema: Path,
    private val seed: Long = 0,
    private val stepLimit: Int = 3600,
) {

    /** 세 스위트를 차례로 돈다. 문서가 적재에서 거절되면 셋 다 FAIL 이다(스펙 §5.2). */
    fun run(document: Path): List<SuiteOutcome> {
        val doc = try {
            FileProfileSource(schema).load(document)
        } catch (e: ProfileRejected) {
            val failure = SuiteFailure("LOAD", "스키마를 지나는 문서", "${e.message} ${e.findings}")
            return Suite.entries.map { SuiteOutcome(it, listOf("LOAD"), listOf(failure)) }
        }
        val expected = CapabilityProjection.of(doc)
        return listOf(
            guarded(Suite.CONTRACT) { contract(document, doc, expected) },
            guarded(Suite.NEGATIVE) { negative(document, expected) },
            guarded(Suite.DETERMINISM) { determinism(document, expected) },
        )
    }

    /** 스위트 안의 예외는 그 스위트의 FAIL 이다(스펙 §5.2). 실행기가 죽으면 같은 요청이 15분마다 다시 돈다. */
    private fun guarded(suite: Suite, body: () -> SuiteOutcome): SuiteOutcome = try {
        body()
    } catch (e: Exception) {
        SuiteOutcome(suite, listOf("${suite.name}.exception"), listOf(SuiteFailure("${suite.name}.exception", "예외 없음", "${e::class.simpleName}: ${e.message}")))
    }

    // ── CONTRACT

    private fun contract(document: Path, doc: ProfileDocument, expected: Capability): SuiteOutcome {
        val failures = mutableListOf<SuiteFailure>()
        val checked = mutableListOf<String>()

        harness(document).use { h ->
            val client = h.client(CLIENT)
            checked += "CONTRACT.negotiate"
            val negotiated = client.negotiate(ROBOT, requirements(expected, withOptional = true))
            if (!negotiated.accepted) {
                failures += SuiteFailure(
                    "CONTRACT.negotiate", "수락",
                    negotiated.rejectionsList.joinToString { "${it.code}: ${it.detail}" },
                )
            }
            checked += "CONTRACT.capabilities"
            val actual = client.capabilities(ROBOT)
            if (actual != expected) {
                failures += SuiteFailure(
                    "CONTRACT.capabilities", "프로파일의 투영",
                    "응답 스킬=${actual.skillsList.map { "${it.skillType}@${it.major}.${it.minor}" }}",
                )
            }
        }

        expected.skillsList.forEach { skill ->
            checked += "CONTRACT.task:${skill.skillType}"
            harness(document).use { h ->
                val run = runTask(h, skill, expected)
                verdict(run, skill, doc)?.let { failures += SuiteFailure("CONTRACT.task:${skill.skillType}", "종착(성공 또는 선언된 실패)", it) }
            }
        }
        return SuiteOutcome(Suite.CONTRACT, checked, failures)
    }

    /** 멈춘 상태가 계약상 올바른가. 올바르면 널, 아니면 관측값이다. */
    private fun verdict(run: TaskRun, skill: SkillDeclaration, doc: ProfileDocument): String? {
        run.rejection?.let { return "거절: $it" }
        val last = run.follower!!.updates.lastOrNull() ?: return "갱신 없음"
        return when (last.state) {
            TaskState.TASK_STATE_SUCCEEDED -> null
            in DECLARED_STOPS -> {
                val declared = doc.failureModes.filter { it.skillType == null || it.skillType == skill.skillType }.map { it.errorType }
                if (last.fault.errorType in declared) null else "선언 안 된 결함: ${last.state} ${last.fault.errorType}"
            }
            else -> "가상 ${stepLimit}초 안에 멈추지 않았다: ${last.state}"
        }
    }

    // ── NEGATIVE

    private fun negative(document: Path, expected: Capability): SuiteOutcome {
        val failures = mutableListOf<SuiteFailure>()
        val checked = mutableListOf<String>()
        val declared = expected.skillsList.map { it.skillType }.toSet()
        val requiredOptional = requiredOptionalKeys(expected)

        fun probe(check: String, expectedCode: RejectionCode, observed: () -> RejectionCode?) {
            checked += "NEGATIVE.$check"
            val code = observed()
            if (code != expectedCode) failures += SuiteFailure("NEGATIVE.$check", expectedCode.name, code?.name ?: "수락")
        }

        (catalogSkills() - declared).sorted().forEach { skill ->
            probe("skill_absent:$skill", RejectionCode.REJECTION_CODE_SKILL_ABSENT) { startRejection(document, skill, emptyList()) }
        }

        expected.skillsList.forEach { skill ->
            val minimal = MinimalParameters.of(skill, requiredOptional)
            skill.parametersList.filterNot { it.optional }.forEach { p ->
                probe("parameter_missing:${skill.skillType}.${p.key}", RejectionCode.REJECTION_CODE_PARAMETER_INVALID) {
                    startRejection(document, skill.skillType, minimal.filterNot { it.key == p.key })
                }
            }
            skill.parametersList.forEach { p ->
                MinimalParameters.violations(p).forEach { (name, bad) ->
                    probe("parameter_$name:${skill.skillType}.${p.key}", RejectionCode.REJECTION_CODE_PARAMETER_INVALID) {
                        startRejection(document, skill.skillType, minimal.filterNot { it.key == p.key } + bad)
                    }
                }
            }
            if (skill.cancelSupport == Support.SUPPORT_NO) {
                probe("cancel_unsupported:${skill.skillType}", RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED) {
                    controlRejection(document, skill, expected) { client, handle -> client.cancel(ROBOT, handle).let { if (it.hasRejection()) it.rejection.code else null } }
                }
            }
            if (skill.pauseSupport == Support.SUPPORT_NO) {
                probe("pause_unsupported:${skill.skillType}", RejectionCode.REJECTION_CODE_PAUSE_UNSUPPORTED) {
                    controlRejection(document, skill, expected) { client, handle -> client.pause(ROBOT, handle).let { if (it.hasRejection()) it.rejection.code else null } }
                }
            }
        }

        expected.optionalFieldsList.filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }.forEach { field ->
            probe("required_optional_missing:${field.parameterPath}", RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING) {
                harness(document).use { h ->
                    val response = h.client(CLIENT).negotiate(ROBOT, requirements(expected, withOptional = false))
                    response.rejectionsList.map { it.code }.firstOrNull { it == RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING }
                        ?: response.rejectionsList.firstOrNull()?.code
                }
            }
        }
        return SuiteOutcome(Suite.NEGATIVE, checked, failures)
    }

    private fun startRejection(document: Path, skillType: String, parameters: List<ParameterValue>): RejectionCode? =
        harness(document).use { h ->
            val started = h.client(CLIENT).start(ROBOT, "negative", 1, skillType, parameters)
            if (started.hasRejection()) started.rejection.code else null
        }

    private fun controlRejection(
        document: Path,
        skill: SkillDeclaration,
        expected: Capability,
        control: (PicassoClient, dev.picasso.contracts.v1.TaskHandle) -> RejectionCode?,
    ): RejectionCode? = harness(document).use { h ->
        val client = h.client(CLIENT)
        val started = client.start(ROBOT, "negative", 1, skill.skillType, MinimalParameters.of(skill, requiredOptionalKeys(expected)))
        if (started.hasRejection()) error("유효한 태스크가 거절됐다: ${started.rejection.code} ${started.rejection.detail}")
        control(client, started.handle)
    }

    // ── DETERMINISM

    private fun determinism(document: Path, expected: Capability): SuiteOutcome {
        val failures = mutableListOf<SuiteFailure>()
        expected.skillsList.forEach { skill ->
            val first = harness(document, seed).use { h -> trace(h, runTask(h, skill, expected)) }
            val second = harness(document, seed).use { h -> trace(h, runTask(h, skill, expected)) }
            if (first != second) {
                val at = first.indices.firstOrNull { it >= second.size || first[it] != second[it] } ?: first.size
                failures += SuiteFailure(
                    "DETERMINISM.trace:${skill.skillType}", "같은 시드에서 같은 이벤트·가상 시각·진행률",
                    "${at}번째부터 다르다(길이 ${first.size}·${second.size})",
                )
            }
        }
        return SuiteOutcome(Suite.DETERMINISM, expected.skillsList.map { "DETERMINISM.trace:${it.skillType}" }, failures)
    }

    /**
     * 한 실행의 자취. 발행된 것 전부(상태·이벤트·연결)와 태스크 갱신이며, `session_id`·`event_id` 는 지운다 —
     * 둘 다 JVM 전역 카운터를 품어 같은 시드로도 실행마다 다르다.
     */
    private fun trace(h: Harness, run: TaskRun): List<String> =
        h.publisher.publications.map { "${it.topic} ${normalize(it.message)}" } +
            run.follower?.updates.orEmpty().map { normalize(it).toString() } +
            listOf("rejection=${run.rejection}")

    // ── 공통

    private class TaskRun(val rejection: String?, val follower: TaskFollower?)

    /** 최소 값으로 태스크를 걸고 멈출 때까지 가상 1초씩 민다. 소요 시간을 미리 알면 그것이 기종 지식이 된다. */
    private fun runTask(h: Harness, skill: SkillDeclaration, expected: Capability): TaskRun {
        val client = h.client(CLIENT)
        val started = client.start(ROBOT, "contract-${skill.skillType}", 1, skill.skillType, MinimalParameters.of(skill, requiredOptionalKeys(expected)))
        if (started.hasRejection()) return TaskRun("${started.rejection.code}: ${started.rejection.detail}", null)
        val follower = client.follow(ROBOT, started.handle)
        repeat(stepLimit) {
            if (follower.updates.lastOrNull()?.state in STOPS) return TaskRun(null, follower)
            h.advance(STEP)
        }
        return TaskRun(null, follower)
    }

    private fun harness(document: Path, seedOverride: Long = seed) = Harness(mapOf(ROBOT to document), schema, seedOverride)

    private fun requirements(expected: Capability, withOptional: Boolean) = RequirementSet(
        CLIENT,
        expected.skillsList.map { Requirement(it.skillType, it.major, it.minor) },
        if (withOptional) expected.optionalFieldsList.filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }.map { it.parameterPath } else emptyList(),
        LimitsNeeded(),
    )

    private fun requiredOptionalKeys(expected: Capability): Set<String> =
        expected.optionalFieldsList
            .filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }
            .map { it.parameterPath.removePrefix(MinimalParameters.PATH_PREFIX) }
            .toSet()

    /** 계약 카탈로그의 스킬 이름. 선언하지 않은 스킬 탐침이 이 중 프로파일에 없는 것을 쓴다. */
    private fun catalogSkills(): Set<String> =
        SkillCatalog.getDescriptor().messageTypes
            .map { it.options.getExtension(SkillCatalog.skillTypeName) }
            .filter { it.isNotBlank() }
            .toSet()

    private companion object {
        const val ROBOT = "candidate"
        const val CLIENT = "revision-test"
        val STEP: Duration = Duration.ofSeconds(1)

        /** 선언된 결함으로 멈출 수 있는 상태. 결함이 프로파일에 선언된 것이어야 올바르다. */
        val DECLARED_STOPS = setOf(TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_RETRIABLE, TaskState.TASK_STATE_NEEDS_INTERVENTION)
        val STOPS = DECLARED_STOPS + setOf(TaskState.TASK_STATE_SUCCEEDED, TaskState.TASK_STATE_CANCELLED)

        /** 같은 실행을 두 번 돌려도 달라지는 칸. JVM 전역 카운터를 품는다. */
        val VOLATILE = setOf("session_id", "event_id")

        fun normalize(message: Message): Message {
            val builder = message.toBuilder()
            message.descriptorForType.fields.forEach { field ->
                when {
                    field.name in VOLATILE -> builder.clearField(field)
                    field.javaType == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE &&
                        !field.isRepeated && message.hasField(field) ->
                        builder.setField(field, normalize(message.getField(field) as Message))
                    field.javaType == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE && field.isRepeated ->
                        builder.setField(field, (message.getField(field) as List<*>).map { normalize(it as Message) })
                }
            }
            return builder.build()
        }
    }
}
