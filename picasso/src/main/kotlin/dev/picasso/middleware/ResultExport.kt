package dev.picasso.middleware

import dev.picasso.contracts.wire.ContractIdentity

/**
 * 결과 통보([JobResponse])를 밖이 읽는 모양으로 **인코딩한다**(ADR 48, `docs/orchestration.md` §8).
 *
 * ## 전송은 여기 없다
 *
 * [LedgerExport] 와 같은 자리다 — 문자열만 만들고 파일도 소켓도 건드리지 않는다. 결과 통보를 꺼내(`pending()`)
 * 나르고 닫는(`ack`) 것은 **picasso 를 세우는 담는 쪽**이며, 그것을 어디에 어떻게 쓸지도 담는 쪽이 정한다.
 * 읽는 쪽은 이 줄의 모양만 본다.
 *
 * ## 한 줄이 제 판과 제 인스턴스를 든다
 *
 * 대장 한 벌은 안내 파일이 판을 들지만, 결과 통보는 담는 쪽이 줄 단위로 나른다 — 안내 파일이 있을지는 담는 쪽의
 * 결정이다. 그래서 줄마다 판과 [Middleware.instanceId] 를 싣는다. 실행 식별자는 그 인스턴스 안의 셈이라, 둘을
 * 짝지어야 다시 뜬 뒤에도 한 시도를 가리킨다.
 *
 * ## 「없음」을 키 누락으로 적지 않는다
 *
 * [LedgerExport] 와 같은 규칙이다. 빈 목록은 `[]` 이고 키를 빼지 않는다 — 빼면 «완료된 단위가 없다» 와
 * «이 판이 그 칸을 안 낸다» 가 같은 모양이 된다.
 */
object ResultExport {

    /** 이 규약의 판. 줄마다 싣는다. */
    const val SCHEMA_VERSION: String = "1"

    /** 담는 쪽이 파일로 나를 때 쓰는 이름. 담는 쪽이 정할 일이지만 읽는 쪽이 하나로 찾게 이름은 여기서 준다. */
    const val JOB_RESPONSES: String = "job-responses.jsonl"

    /** 결과 통보 한 줄씩, 낸 순서대로. 마지막 줄에도 줄바꿈이 붙는다. */
    fun jobResponses(responses: List<JobResponse>, instanceId: String): String =
        responses.joinToString("") { line(it, instanceId) + "\n" }

    /**
     * **상류 확인 상태(`ack`)는 안 싣는다.** 그것은 나르는 쪽의 상태이고, 실으면 같은 통보가 확인 전후로 두 모양이 된다.
     */
    fun line(r: JobResponse, instanceId: String): String = Obj()
        .str("schemaVersion", SCHEMA_VERSION)
        .str("contractSemver", ContractIdentity.semver)
        .str("instanceId", instanceId)
        .str("jobResponseId", r.jobResponseId)
        .str("jobOrderId", r.jobOrderId)
        .str("executionId", r.executionId)
        .num("version", r.version)
        .str("physicalState", r.physicalState.name)
        .str("requiredEvidence", r.requiredEvidence.name)
        .str("reachedEvidence", r.reachedEvidence.name)
        .raw("completedUnits", LedgerExport.arrayOfStrings(r.completedUnits))
        .raw("unverifiedUnits", LedgerExport.arrayOfStrings(r.unverifiedUnits))
        .raw("inDoubtUnits", LedgerExport.arrayOfStrings(r.inDoubtUnits))
        .raw("incompleteUnits", LedgerExport.mapOfStrings(r.incompleteUnits))
        .bool("operatorRequired", r.operatorRequired)
        .raw("residualHold", LedgerExport.proto(r.residualHold))
        .bool("autoResolvesInDoubt", r.autoResolvesInDoubt)
        .raw("results", LedgerExport.mapOfStrings(r.results))
        .raw("blockedBy", LedgerExport.arrayOfStrings(r.blockedBy))
        .str("connection", r.connection)
        .done()
}
