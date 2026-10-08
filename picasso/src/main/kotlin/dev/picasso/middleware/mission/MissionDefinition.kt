package dev.picasso.middleware.mission

import dev.picasso.middleware.Evidence

/**
 * 임무 정의 — WorkMaster 하나를 실행 단위로 펼치는 규칙을 **데이터로** 적은 것(ADR 50).
 *
 * 문서 하나가 WorkMaster 하나의 정의이고, 노드는 직선이다(분기·병렬은 없다). 노드는 둘이다 — 로봇 스킬을 부르는
 * 단위 노드([UnitStep])와 설비 신호를 기다리는 대기 노드([WaitStep]). 단위 하나의 생애(9상태)와 보장 전이는 엔진이
 * 고정하고, 정의는 그 안쪽을 그리지 않는다.
 *
 * 이 모양은 [MissionDefinitionParser] 가 읽은 그대로다 — **모양과 형만 맞다.** 기한이 양수인지, 참조한 신호가 있는지
 * 같은 판정은 [MissionValidator] 의 일이고, 활성화는 그것을 통과해야 선다([InMemoryMissionCatalog]).
 *
 * @param schemaVersion 이 문서 모양의 버전. 지금은 1 뿐이다. 임무 버전(활성화마다 오르는 번호)과 다르다.
 * @param maxEvidence 이 정의가 제공할 수 있는 최고 근거 등급(E0~E2).
 * @param preferredOptionals 로봇이 선언하면 붙이는 선택 파라미터(`LogicalCapability.preferredOptionals`).
 */
data class MissionDefinition(
    val schemaVersion: Int,
    val workMasterId: String,
    val maxEvidence: Evidence,
    val preferredOptionals: Map<String, String>,
    val steps: List<MissionStep>,
)

/** 직선 노드 하나. [id] 는 정의 안에서 겹치면 안 된다 — 검증기가 본다. */
sealed interface MissionStep {
    val id: String
}

/**
 * 단위 노드(`kind: "unit"`) — 작업 지시 설비 중 [forEach] 쓰임인 것마다 로봇 단위 하나.
 *
 * 값은 전부 [ValueSource] 로 적는다. 짝([pairWith])이 있으면 반복 중인 설비의 속성 값과 같은 값을 가진 다른 쓰임의 설비를
 * 찾는다. 짝이 없으면 단위는 계획 때 `FAILED` 이고 실패 분류는 [whenUnpaired] 다 — 시작도 못 하는 부족은 계획에서 드러난다.
 *
 * @param skill 계약 카탈로그의 스킬 이름.
 * @param unitId 단위 id 의 출처. **반복 중인 설비의 id 만 허용한다**(파서가 막는다) — 작업 응답과 리비전이 단위 id 로
 *   접으므로 비거나 갈리면 안 된다.
 * @param parameters 파라미터 이름 → 값 출처. 값이 없으면(대체값도 없으면) 그 파라미터는 빠진다.
 */
data class UnitStep(
    override val id: String,
    val skill: String,
    val forEach: String,
    val unitId: ValueSource,
    val parameters: Map<String, ValueSource>,
    val expectedIdentity: ValueSource?,
    val source: ValueSource?,
    val destination: ValueSource?,
    val pairWith: Pairing?,
    val whenUnpaired: String?,
) : MissionStep

/**
 * 설비 대기 노드(`kind: "wait"`) — 이름 있는 신호가 [expect] 를 읽을 때까지 기다린다.
 *
 * [deadlineSeconds]·[onDeadline] 이 널일 수 있는 것은 **파서가 값을 판정하지 않기 때문이다.** 없거나 허용 밖인 것은
 * 검증기의 기한 검사가 거부한다 — 그래야 그 검사를 빼는 결함이 검증기 시험에 잡힌다.
 */
data class WaitStep(
    override val id: String,
    val signal: String,
    val expect: String,
    val deadlineSeconds: Long?,
    val onDeadline: String?,
) : MissionStep

/** 짝 규칙 — [equipmentUse] 쓰임의 설비 중 [property] 가 반복 중인 설비의 같은 속성과 같은 것. 여럿이면 목록에서 뒤엣것. */
data class Pairing(val equipmentUse: String, val property: String)

/**
 * 값 하나의 출처.
 *
 * @param property [ValueFrom.ITEM_PROPERTY] 일 때 읽을 속성 이름. 다른 출처에서는 널이다.
 * @param otherwise 출처가 값을 못 낼 때(속성이 없다, 짝이 없다) 쓸 대체값. 널이면 값이 없다(`null`).
 */
data class ValueSource(val from: ValueFrom, val property: String? = null, val otherwise: String? = null)

/**
 * 값 출처의 종류 셋.
 *
 * - [ITEM_ID] — 반복 중인 설비의 id. 언제나 있다
 * - [ITEM_PROPERTY] — 반복 중인 설비의 속성. 그 속성이 없으면 값이 없다
 * - [PAIRED_ID] — 짝지은 설비의 id. 짝이 없으면 값이 없다
 */
enum class ValueFrom { ITEM_ID, ITEM_PROPERTY, PAIRED_ID }
