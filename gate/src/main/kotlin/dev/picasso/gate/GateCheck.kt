package dev.picasso.gate

import dev.picasso.gate.input.GateInput

interface GateCheck {
    /** 설계 §11.2의 번호. "1".."9" */
    val id: String

    val name: String

    /** 이것이 없으면 아예 돌지 않는다. 부분적으로만 필요한 자원은 여기 넣지 않는다. */
    val requires: Set<Resource>

    fun run(input: GateInput): CheckResult
}
