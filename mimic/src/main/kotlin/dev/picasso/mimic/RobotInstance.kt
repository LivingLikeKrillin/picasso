package dev.picasso.mimic

import dev.picasso.contracts.v1.Capability
import dev.picasso.mimic.engine.Clock
import dev.picasso.mimic.engine.Seeded
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
    val document: ProfileDocument,
    val clock: Clock,
    seed: Long = 0,
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
    val sessionId: String = "%s-%013d-%06d".format(
        robotId,
        clock.now().toEpochMilli(),
        STARTUP_COUNTER.incrementAndGet(),
    )

    /**
     * 시드 기반 난수. **기체마다 하나다** — 하나를 공유하면 인출 순서가
     * 비결정적이라 §12.1이 깨진다.
     */
    val random: Seeded = Seeded(seed)

    /** §7.2의 투영. 능력을 하드코딩할 자리가 없다(§12.2의 10번). */
    val capability: Capability = CapabilityProjection.of(document)

    /**
     * 능력의 ETag(§5.5). 유효 능력 집합이 바뀔 때마다 증가하며 소비자가 매
     * 메시지에서 O(1)로 캐시 유효성을 판정한다.
     *
     * **0이 아니라 1에서 시작한다.** proto3의 uint64는 암묵 존재라 0이 곧
     * "싣지 않았다"이고, 0에서 시작하면 첫 세대가 헤더에서 사라진다.
     *
     * 이 청크에서는 상수다 — 런타임 축소·갱신(완료 기준 14·15)이 세대를
     * 올리는 유일한 경로이고 그것은 제어 채널 청크가 만든다.
     */
    val capabilityEpoch: Long = 1

    private companion object {
        val STARTUP_COUNTER = AtomicLong()
    }
}
