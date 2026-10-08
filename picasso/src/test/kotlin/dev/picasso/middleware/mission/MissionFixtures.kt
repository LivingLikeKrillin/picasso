package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome

/**
 * 시험이 쓰는 임무 정의 문서와 현장 데이터.
 *
 * [PREPARE_SEQUENCED_RACK] 는 코드 `PrepareSequencedRack` 을 **데이터로 옮긴 것**이다 — 동등성 시험이 그 둘이 같은
 * 계획을 내는지 칸마다 댄다. [withArrivalWait] 는 그 앞에 랙 도착 대기를 둔 버전이다(버전 2 의 모양).
 */
object MissionFixtures {

    const val RACK_PRESENT = "rack_present"
    const val WAIT_NODE = "rack-arrival"

    /** 랙 도착 신호를 내는 자리. 자원 검사가 이 자리의 바닥 소유를 본다. */
    const val DOCK = "RACK-DOCK"

    /**
     * 단위 노드 하나 — 슬롯마다 `pick_place`, 제시 자리는 material 로 짝짓는다.
     *
     * 시험이 문자열을 바꿔 틀린 문서를 만들므로 **칸마다 한 줄**이고 들여쓰기가 없다.
     */
    private val PLACE_NODE = listOf(
        "{",
        "\"kind\": \"unit\",",
        "\"id\": \"place\",",
        "\"skill\": \"pick_place\",",
        "\"forEach\": \"destination\",",
        "\"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"material\" },",
        "\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",",
        "\"unitId\": { \"from\": \"ITEM_ID\" },",
        "\"parameters\": { \"object_id\": { \"from\": \"PAIRED_ID\", \"otherwise\": \"\" }, \"destination\": { \"from\": \"ITEM_ID\" } },",
        "\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" },",
        "\"source\": { \"from\": \"PAIRED_ID\" },",
        "\"destination\": { \"from\": \"ITEM_ID\" }",
        "}",
    ).joinToString("\n")

    private fun document(vararg steps: String) = listOf(
        "{",
        "\"schemaVersion\": 1,",
        "\"workMasterId\": \"PrepareSequencedRack\",",
        "\"maxEvidence\": \"E2\",",
        "\"preferredOptionals\": { \"verify_grasp\": \"true\" },",
        "\"steps\": [",
        steps.joinToString(",\n"),
        "]",
        "}",
    ).joinToString("\n")

    /** 코드 `PrepareSequencedRack` 의 데이터판 — 버전 1 의 모양. */
    val PREPARE_SEQUENCED_RACK: String = document(PLACE_NODE)

    /** 맨 앞에 «랙 도착 신호가 기대 값이 될 때까지 대기» 를 둔 버전 — 버전 2 의 모양. 대기 노드는 한 줄이다. */
    fun withArrivalWait(
        deadlineSeconds: Long = 120,
        onDeadline: DeadlineOutcome = DeadlineOutcome.OPERATOR_HOLD,
        signal: String = RACK_PRESENT,
        expect: String = "true",
    ): String = document(
        "{\"kind\": \"wait\", \"id\": \"$WAIT_NODE\", \"signal\": \"$signal\", \"expect\": \"$expect\", " +
            "\"deadlineSeconds\": $deadlineSeconds, \"onDeadline\": \"${onDeadline.name}\"}",
        PLACE_NODE,
    )

    /** 현장 신호 사양 — 랙 도착(자리 있음), 안전 신호 하나, 텍스트 신호 하나. */
    val SIGNALS: List<SignalSpec> = listOf(
        SignalSpec(RACK_PRESENT, location = DOCK, kind = SignalKind.BOOLEAN),
        SignalSpec("guard_closed", kind = SignalKind.BOOLEAN, safety = true),
        SignalSpec("lot_code", kind = SignalKind.TEXT),
    )

    /** 현장 기체들이 제공하는 스킬. */
    val SITE_SKILLS: Set<String> = setOf("pick_place", "navigate_to", "inspect")

    fun parsed(text: String): MissionDefinition = when (val p = MissionDefinitionParser.parse(text)) {
        is MissionParse.Parsed -> p.definition
        is MissionParse.Unreadable -> error("시험 정의를 못 읽었다: ${p.problems}")
    }
}
