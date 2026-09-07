package dev.picasso.registry.observe

import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.Rejection
import dev.picasso.registry.store.Db

/** §8.3의 `capability_epoch_log.cause` 넷. */
enum class EpochCause { BINDING_CHANGED, RUNTIME_DEGRADED, OPERATOR_BLOCKED, RESTORED }

/** 적재의 결과. **접힌 것과 새로 쓴 것을 구분한다** — 시험이 그것을 봐야 한다. */
enum class Recorded { INSERTED, FOLDED }

/**
 * 관측 적재. **`registry`는 판정하지 않고 받아 적는다.**
 *
 * ## 입력이 헤더인 것이 경계다
 *
 * §3.2의 순환 회피 규칙 1은 *"`registry`는 `mimic`도 `harness`도 모른다"*이다.
 * `mimic`의 `Publication`을 받으면 그 순간 `registry → mimic`이 생긴다.
 * [MessageHeader]는 `contracts`의 타입이고 **양쪽이 이미 안다** — 그래서
 * 이것이 유일하게 순환을 만들지 않는 입력이다.
 *
 * ## 브로커가 없으므로 구독기도 없다
 *
 * §3.5는 MQTT라 하고 §13은 3a에 구독기를 적었다. 2단계에서 브로커를 안 붙인
 * 이유(§12.1의 결정성 — 내장 브로커는 자기 스레드를 벽시계로 돌린다)는
 * 3단계에서도 그대로다. **여기까지가 만들어졌고 구독기는 안 만들어졌다**는
 * 것을 §15에 적는다. 붙는 날 구독기가 [recordHeader]를 부르면 된다.
 */
class ObservationService(private val db: Db) {

    /**
     * 헤더 하나가 말하는 것을 세대 이력에 적는다.
     *
     * **같은 기체·세대·`profile_ref`·사유는 접는다.** 폴링(§10.3의 5초)과
     * 발행이 같은 사실을 반복해 주므로, 그대로 쌓으면 이력이 같은 줄
     * 수천 개가 되고 **사유가 묻힌다** — 진단 3번이 답해야 하는 것이
     * "언제 왜 바뀌었나"인데 안 바뀐 것으로 화면이 가득 찬다.
     *
     * **세대가 같아도 `profile_ref`가 다르면 새 줄이다.** 카나리 중에는 두
     * 개정판이 동시에 돌고(§9.7 ⑤), 그 전환은 세대가 아니라 `profile_ref`로
     * 관측된다(§5.5). 접으면 완료 기준 20이 볼 것이 사라진다.
     */
    fun recordHeader(
        header: MessageHeader,
        cause: EpochCause = EpochCause.BINDING_CHANGED,
        detailJson: String? = null,
    ): Recorded = db.transaction { c ->
        val ref = """{"profile_id":"${header.profileRef.profileId}",""" +
            """"revision":${header.profileRef.revision}}"""

        c.prepareStatement(
            """
            INSERT INTO capability_epoch_log
                   (robot_id, epoch, cause, profile_ref, detail, occurred_at)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, now())
            ON CONFLICT (robot_id, epoch, profile_ref, cause)
            DO UPDATE SET occurred_at = now()
            RETURNING (xmax = 0) AS inserted
            """.trimIndent(),
        ).use { s ->
            s.setString(1, header.robotId)
            s.setLong(2, header.capabilityEpoch)
            s.setString(3, cause.name)
            s.setString(4, ref)
            s.setString(5, detailJson)
            s.executeQuery().use { rs ->
                check(rs.next()) { "적재가 아무 행도 안 냈다" }
                if (rs.getBoolean(1)) Recorded.INSERTED else Recorded.FOLDED
            }
        }
    }

    /**
     * 핸드셰이크 거절 하나를 적는다. 완료 기준 13의 **보고 절반**.
     *
     * **`registry`는 거절을 판정하지 않는다.** 다섯 코드를 만드는 것은
     * `mimic`의 `Negotiator`이고(2단계에서 닫혔다), 여기서 다시 판정하면
     * 화면이 로봇과 다른 말을 하는 날이 온다. 어떤 코드도 특별 취급하지
     * 않는 것이 그 규율의 표현이다 — 받아 적을 뿐이다.
     *
     * @param requirements 거절당한 요구 문자열들. 원문 그대로 남긴다.
     */
    fun recordRejection(
        robotId: String,
        clientId: String,
        requirements: List<String>,
        rejection: Rejection,
    ): Long = db.transaction { c ->
        val requirementJson = requirements.joinToString(",", "[", "]") { jsonString(it) }
        val detail = """{"detail":${jsonString(rejection.detail)},""" +
            """"capability_epoch":${rejection.capabilityEpoch},""" +
            """"references":${
                rejection.referencesList.joinToString(",", "[", "]") {
                    """{"key":${jsonString(it.key.name)},"value":${jsonString(it.value)}}"""
                }
            }}"""

        c.prepareStatement(
            """
            INSERT INTO handshake_rejection
                   (robot_id, client_id, requirement, reason_code, detail, at)
            VALUES (?, ?, ?::jsonb, ?, ?::jsonb, now())
            RETURNING rejection_id
            """.trimIndent(),
        ).use { s ->
            s.setString(1, robotId)
            s.setString(2, clientId)
            s.setString(3, requirementJson)
            s.setString(4, rejection.code.name)
            s.setString(5, detail)
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
    }

    /**
     * JSON 문자열 리터럴 하나. **직접 쓰는 이유는 의존을 안 늘리려는 것이
     * 아니라, 이 자리에서 이스케이프를 빠뜨리면 요구 문자열 하나가 적재를
     * 통째로 깨뜨리기 때문이다** — 거절 사유에는 `"`가 흔하다.
     */
    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < 0x20 -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}
