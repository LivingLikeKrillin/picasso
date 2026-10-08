package dev.picasso.middleware.mission

import dev.picasso.middleware.ActiveMission
import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.MissionCatalog
import java.time.Instant

/** 활성화의 결과. */
sealed interface Activation {
    /** 섰다 — 이 WorkMaster 의 다음 작업 지시부터 이 버전으로 계획한다. */
    data class Activated(val workMasterId: String, val missionVersion: Int) : Activation

    /** 거부됐다 — 활성 버전은 그대로다. 거부를 전부 든다. */
    data class Refused(val refusals: List<MissionRefusal>) : Activation
}

/**
 * 메모리에 사는 임무 카탈로그 — 시험과 운영 호스트가 쓴다. 저장과 이력은 여기 없다(호스트가 붙인다).
 *
 * **코드 케이퍼빌리티와 데이터 정의가 한 카탈로그에 함께 선다.** 활성화한 적이 없는 WorkMaster 는 코드 케이퍼빌리티로
 * 답하고(버전 없음), 활성화하면 그 WorkMaster 는 그 정의로 답한다.
 *
 * ## 활성화는 관문을 지난다
 *
 * 읽을 수 없거나 검증기가 거부한 정의는 서지 않고 **활성 버전이 그대로다.** 통과하면 그 WorkMaster 의 다음 버전 번호
 * (1부터 오른다)가 활성이 된다. 거부된 시도는 번호를 쓰지 않는다.
 *
 * 검증기 입력(신호 사양·바닥 소유·현장 기체의 스킬)은 활성화 호출이 준다 — 미들웨어는 바닥 소유를 관문에만 넘기고
 * 쥐지 않는다.
 *
 * ## 스레드
 *
 * 활성화와 조회는 다른 스레드에서 올 수 있다(화면이 활성화하고 진행 루프가 조회한다). 미들웨어는 잠금이 없으므로 이
 * 카탈로그가 **스스로** 안전하다. 활성화는 다음 작업 지시부터 적용된다 — 도는 실행은 쥔 버전으로 끝난다.
 */
class InMemoryMissionCatalog(
    code: List<LogicalCapability> = MissionCatalog.codeCapabilities(),
    private val now: () -> Instant = { Instant.now() },
) : MissionCatalog {

    private val lock = Any()
    private val coded: Map<String, LogicalCapability> = code.associateBy { it.workMasterId }
    private val activated = mutableMapOf<String, ActiveMission>()
    private val issued = mutableMapOf<String, Int>()

    override fun active(workMasterId: String): ActiveMission? = synchronized(lock) {
        activated[workMasterId] ?: coded[workMasterId]?.let { ActiveMission(it, missionVersion = null) }
    }

    /**
     * 정의를 읽고 검증해 다음 버전으로 세운다.
     *
     * @param definitionJson 임무 정의 문서([MissionDefinitionParser] 의 표기).
     * @param signals 현장 신호 사양.
     * @param floors 바닥 소유.
     * @param siteSkills 현장 기체들이 제공하는 스킬 이름의 합.
     */
    fun activate(
        definitionJson: String,
        signals: List<SignalSpec>,
        floors: FloorOwnership,
        siteSkills: Set<String>,
    ): Activation {
        val at = now()
        val definition = when (val parsed = MissionDefinitionParser.parse(definitionJson)) {
            is MissionParse.Unreadable -> return Activation.Refused(
                parsed.problems.map {
                    MissionRefusal(MissionRefusalKind.UNREADABLE, null, it, "임무 정의 문서 버전 ${MissionDefinitionParser.SCHEMA_VERSION} 의 모양", at)
                },
            )
            is MissionParse.Parsed -> parsed.definition
        }
        val refusals = MissionValidator.validate(definition, signals, floors, siteSkills, at)
        if (refusals.isNotEmpty()) return Activation.Refused(refusals)

        val capability = DefinedCapability(definition)
        synchronized(lock) {
            val version = (issued[definition.workMasterId] ?: 0) + 1
            issued[definition.workMasterId] = version
            activated[definition.workMasterId] = ActiveMission(capability, version)
            return Activation.Activated(definition.workMasterId, version)
        }
    }
}
