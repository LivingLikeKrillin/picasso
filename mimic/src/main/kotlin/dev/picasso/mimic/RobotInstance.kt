package dev.picasso.mimic

import dev.picasso.contracts.v1.Capability
import dev.picasso.mimic.engine.Clock
import dev.picasso.mimic.engine.FaultRegistry
import dev.picasso.mimic.engine.Seeded
import dev.picasso.mimic.engine.TaskHost
import dev.picasso.mimic.transport.EventStream
import dev.picasso.mimic.transport.Publisher
import dev.picasso.mimic.transport.TransportFaults
import dev.picasso.mimic.profile.CapabilityProjection
import dev.picasso.profile.ProfileDocument
import java.util.concurrent.atomic.AtomicLong

/**
 * 가상 로봇 기체 하나.
 *
 * **한 프로세스가 여러 기체를 호스팅하고 기체는 요청 헤더의 `robot_id`로
 * 지정한다**(§10.2). 포트는 하나다. "엔드포인트만 바꿔 실물과 교체"는
 * 호스트·포트만 바뀌고 `robot_id`는 그대로라는 뜻이다. **PoC의 편의이지
 * 아키텍처 주장이 아니다**(ADR 21).
 *
 * 기동 순서는 §10.2다 — 프로파일 로드 → 스키마 검증(실패 시 기동 거부) →
 * 유효 능력 계산 → `Capability` 투영 → 상태머신 인스턴스화 → `session_id`
 * 발급 → 포트 개방 → `ONLINE` 발행. 이 청크는 그중 투영과 세션까지다.
 */
class RobotInstance(
    val robotId: String,
    /**
     * **이름이 프로퍼티와 달라야 한다.** 같으면 초기화 구문 안의
     * `{ document }` 람다가 프로퍼티가 아니라 **이 파라미터를**
     * 캡처한다 — 그러면 폴링이 문서를 갈아 끼워도 엔진은 기동 시점의
     * 것을 영영 쓴다.
     *
     * 실측으로 물렸다: `declaredCapability`는 동명 파라미터가 없어
     * 멀쩡히 갱신됐고 `document`만 안 됐다. 그 비대칭이 단서였다.
     */
    initialDocument: ProfileDocument,
    val clock: Clock,
    seed: Long = 0,
    /** 발행이 나갈 곳. 붙이지 않으면 아무 데도 안 나간다(§15.30). */
    publisher: Publisher = Publisher.NONE,
    /**
     * §5.5의 토픽 두 번째 레벨.
     *
     * **`val`인 것은 핸드셰이크 보고가 이것을 실어야 하기 때문이다**
     * (§8.3의 `consumer.site`가 "토픽의 site"다). 헤더에는 site가 없다.
     */
    val site: String = "default",
) {
    /**
     * §4.8 — 기체 단위이며 발신자가 온라인이 될 때마다 새로 발급한다.
     * 재기동하면 세션이 바뀌고 소비자는 스냅샷부터 다시 세운다.
     *
     * **§5.5는 ULID라 하지만 여기서는 기동 카운터를 쓴다.** ULID의 난수부를
     * 시드에서 뽑으면 §12.1의 결정성 규율("시드 + 가상 시계 고정 = 동일
     * 이벤트 시퀀스")과 "재기동하면 새 세션"이 충돌하고, `UUID.randomUUID()`를
     * 쓰면 결정성이 깨진다. 세션의 요건은 "온라인이 될 때마다 새것"이고
     * 그것은 카운터로 족하다. 한계는 §15에 적었다.
     */
    var sessionId: String = newSessionId()
        private set

    /**
     * 새 세션을 발급한다. **재생 버퍼가 넘쳐 못 보낸 이벤트를 버렸을 때만**
     * 부른다(§10.6).
     *
     * 세션이 바뀌면 소비자는 스냅샷부터 다시 세운다. 그것이 "너에게 안 간
     * 구간이 있다"를 알리는 유일한 방법이고, 단절만으로 바꾸면 버퍼링이
     * 무의미해지므로 **넘칠 때만** 바꾼다.
     */
    fun renewSession() {
        sessionId = newSessionId()
    }

    private fun newSessionId(): String = "%s-%013d-%06d".format(
        robotId,
        clock.now().toEpochMilli(),
        STARTUP_COUNTER.incrementAndGet(),
    )

    /**
     * 시드 기반 난수. **기체마다 하나다** — 하나를 공유하면 인출 순서가
     * 비결정적이라 §12.1이 깨진다.
     *
     * **`private`인 것이 요점이다.** 공개하면 전송·제어 코드가 그대로 잡을 수
     * 있고, 그 순간 §12.1이 막으려는 바로 그 일이 일어난다 — 실측으로
     * `EventServiceImpl.getSnapshot`에 `instance.random.fraction()` 한 줄을
     * 넣으면 **소비자가 스냅샷을 뜰 때마다 추첨 스트림이 밀리는데** 스위트가
     * 통째로 초록이었다. 시험이 못 잡는 문은 컴파일러가 닫는다.
     *
     * §10.5의 `SetSeed`는 [reseed]라는 이름 붙은 문으로만 지난다.
     */
    private val random: Seeded = Seeded(seed)

    /**
     * §10.5의 `SetSeed`. **객체를 갈지 않고 안을 다시 심는다** — 갈면
     * [tasks]가 든 참조가 낡아 시드를 바꿔도 아무 일이 안 일어난다.
     */
    fun reseed(seed: Long) = random.reseed(seed)

    /**
     * §7.2의 투영. 능력을 하드코딩할 자리가 없다(§12.2의 10번).
     *
     * **프로파일이 선언한 전부다** — 런타임 축소는 여기를 안 건드린다.
     * 선언한 적 없는 스킬(`SKILL_ABSENT`)과 있었는데 사라진 스킬
     * (`CAPABILITY_WITHDRAWN`)을 가르려면 둘을 다 알아야 하기 때문이다.
     */
    /**
     * 지금 쓰는 프로파일 문서. **`var`인 것이 §8.4 ④다** — 레지스트리에서
     * 당긴 개정판이 여기로 들어온다.
     */
    var document: ProfileDocument = initialDocument
        private set

    /** 지금 문서가 온 개정판. 파일 모드면 `null`이다(§5.5의 `profile_ref`). */
    var profileRevisionId: Long? = null
        private set

    var declaredCapability: Capability = CapabilityProjection.of(document)
        private set

    /**
     * §8.2의 런타임 축소로 지금 못 쓰는 스킬들.
     *
     * **되돌릴 수 있다** — 로봇 유래 축소는 일시적일 수 있고(센서 하나가
     * 죽었다가 살아난다) 그때 개정판을 새로 내는 것은 §8.2가 말하는 축이
     * 아니다.
     */
    private val withdrawn = linkedSetOf<String>()

    val withdrawnSkills: Set<String> get() = withdrawn.toSet()

    /**
     * 지금 쓸 수 있는 능력. `GetCapabilities`와 헤더의 `capability_epoch`가
     * 함께 말하는 그것이다.
     */
    val capability: Capability
        get() = if (withdrawn.isEmpty()) {
            declaredCapability
        } else {
            declaredCapability.toBuilder()
                .clearSkills()
                .addAllSkills(declaredCapability.skillsList.filterNot { it.skillType in withdrawn })
                .build()
        }

    /**
     * §8.2의 런타임 축소. **네 가지가 한꺼번에 움직인다** — 유효 능력에서
     * 빠지고, `capability_epoch`가 오르고, `CapabilityChanged`가 나가고,
     * 그 스킬의 `StartTask`가 `CAPABILITY_WITHDRAWN`으로 거절된다.
     *
     * **진행 중이던 태스크는 죽이지 않는다.** 축소는 "새로 못 받는다"이지
     * "하던 것을 무를 수 있다"가 아니다 — 로봇이 물건을 든 채 있을 수 있고,
     * 임의로 종착시키면 §4.4의 취소 의미론(복구를 동반한다)을 우회한다.
     * 소비자가 원하면 `CancelTask`를 보내면 된다.
     *
     * @return 실제로 바뀌었으면 참. 이미 축소된 것을 또 축소하면 거짓이며
     *   세대가 안 오른다 — 유령 세대는 소비자의 캐시를 헛되이 무효화한다.
     */
    fun withdraw(skillType: String): Boolean {
        require(declaredCapability.skillsList.any { it.skillType == skillType }) {
            "선언한 적 없는 스킬은 축소할 수 없다: $skillType"
        }
        if (!withdrawn.add(skillType)) return false
        bumpCapabilityEpoch()
        events.capabilityChanged(removed = listOf(skillType))
        return true
    }

    /** 축소를 되돌린다. **세대는 또 오른다** — 되돌아가지 않는다(§5.5의 ETag). */
    fun restore(skillType: String): Boolean {
        if (!withdrawn.remove(skillType)) return false
        bumpCapabilityEpoch()
        events.capabilityChanged(added = listOf(skillType))
        return true
    }

    /**
     * 능력의 ETag(§5.5). 유효 능력 집합이 바뀔 때마다 증가하며 소비자가 매
     * 메시지에서 O(1)로 캐시 유효성을 판정한다.
     *
     * **0이 아니라 1에서 시작한다.** proto3의 uint64는 암묵 존재라 0이 곧
     * "싣지 않았다"이고, 0에서 시작하면 첫 세대가 헤더에서 사라진다.
     *
     * **`var`인 것이 의도다.** 런타임 축소(완료 기준 14)가 이것을 올리고,
     * 발행 헤더와 응답 헤더가 이미 매 메시지에서 읽는다. 6b에서 `val`을
     * 바꾸려면 그 두 경로를 함께 건드려야 하므로 지금 열어 둔다.
     */
    var capabilityEpoch: Long = 1
        private set

    /** 능력이 바뀌었다. 세대를 올린다(§5.5의 ETag). */
    fun bumpCapabilityEpoch(): Long = ++capabilityEpoch

    /**
     * 엔진의 전이에 발행 축을 입힌다(§4.7·§4.8).
     *
     * **기체가 소유한다.** 밖에서 만들어 넣으면 리스너와 스트림이 서로를
     * 필요로 해 `lateinit` 춤이 필요해지고, 그 춤은 시험에만 있고 운영에는
     * 없는 조립 순서를 만든다.
     */
    /**
     * §10.5의 전송 장애 층. **발행자와 이벤트 스트림 사이에 선다** —
     * 번호가 붙은 뒤에 끼어들어야 버린 자리가 구멍으로 남는다.
     */
    val transport: TransportFaults = TransportFaults(publisher)

    val events: EventStream = EventStream(this, transport, site)

    /**
     * 이 기체가 지금 안고 있는 결함들(§4.6). **기체 단위다** — 스킬 수준인지
     * 로봇 수준인지는 `references`가 말한다.
     */
    val faults: FaultRegistry = FaultRegistry(clock)

    /**
     * §10.5의 `SetSingleStep`. 켜면 **RPC 진입이 `tick()`을 돌리지 않는다** —
     * 걸음을 사람이 센다.
     *
     * 밀어내기까지 멈추지는 않는다. 열린 스트림에 이미 생긴 전이를 안 밀면
     * 결함이 실패가 아니라 **정지**로 나타난다.
     */
    var singleStep: Boolean = false

    /** 이 기체가 호스팅하는 태스크들(§4.4). */
    /**
     * 이 기체가 호스팅하는 태스크들(§4.4).
     *
     * **선언된 능력 전부와 축소 목록을 따로 준다.** 유효 능력만 주면
     * `SKILL_ABSENT`("선언한 적 없다")와 `CAPABILITY_WITHDRAWN`("있었는데
     * 사라졌다")을 가를 수 없는데, 계약이 둘을 나눈 이유가 **소비자의
     * 대응이 다르기 때문**이다 — 앞엣것은 요구 집합이 틀린 것이고 뒤엣것은
     * 캐시를 다시 세우면 된다.
     */
    val tasks: TaskHost = TaskHost(
        // **매번 읽는다.** 스냅샷이면 개정판이 바뀌어도 엔진이 옛것을 쓴다.
        { declaredCapability }, { document }, clock, events, faults, random,
        withdrawn = { withdrawn },
    )

    /**
     * §10.3의 폴링. 레지스트리에서 당겨 바뀌었으면 반영한다.
     *
     * **배경 스레드를 두지 않는다.** 5초 주기는 운영값이고, 시험은 제어
     * 채널로 한 번 당긴다 — `AdvanceClock`이 시계를 명시적으로 미는 것과
     * 같은 이유다(§12.1).
     *
     * **진행 중이던 태스크는 안 건드린다.** 각 `TaskMachine`이 생성 시점의
     * 스킬 선언과 소요시간을 들고 있으므로 여기가 바뀌어도 그것들은 그대로
     * 간다 — 그것이 §8.4의 pinning이며, 없으면 활성화가 **진행 중인 로봇의
     * 발밑을 바꾼다.**
     *
     * @return 실제로 바뀌었으면 참.
     */
    fun pull(source: RegistrySource): Boolean {
        val binding = source.binding(robotId) ?: return false
        if (binding.profileRevisionId == profileRevisionId) return false

        val next = ProfileDocument.parse("registry-${binding.profileRevisionId}", binding.documentJson)
            .getOrElse { return false }

        document = next
        declaredCapability = CapabilityProjection.of(next)
        profileRevisionId = binding.profileRevisionId

        // §8.2 — **epoch 증가는 발신자가 한다.** 레지스트리가 아니라 여기다.
        bumpCapabilityEpoch()
        events.capabilityChanged(cause = dev.picasso.contracts.v1.CapabilityChangeCause.CAPABILITY_CHANGE_CAUSE_BINDING_CHANGED)
        return true
    }

    private companion object {
        val STARTUP_COUNTER = AtomicLong()
    }
}
