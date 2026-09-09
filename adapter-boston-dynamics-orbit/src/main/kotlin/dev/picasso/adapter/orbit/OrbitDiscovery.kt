package dev.picasso.adapter.orbit

import dev.picasso.uplink.report.DiscoveredRobotReport
import dev.picasso.uplink.report.DiscoveryAck
import dev.picasso.uplink.report.RobotDiscovery

/**
 * 플릿에 물어 기체 목록을 얻고 **적재 문으로 올린다** — ADR 37 의 *발견* 이 여기서 실물이 된다.
 *
 * ## 이 클래스가 ADR 37 의 미결 하나를 닫는다
 *
 * 그 ADR 은 *"발견 경로를 무엇으로 실증하는가 — 어댑터의 남쪽에 플릿 흉내가 필요한데 공용 도구가 없다"* 를
 * 미결로 남겼고, §15.101 이 문을 만들면서도 *"그 문으로 올리는 어댑터는 없다"* 고 적었다. 이것이 그 어댑터다.
 *
 * ## `robot_id` 는 **별명**이다
 *
 * 플릿이 주는 후보가 둘이다 — `hostname` 과 `nickname`. 별명을 고른다.
 *
 * | 후보 | 무엇 | 왜 안 고르나 |
 * |---|---|---|
 * | `Robot.hostname` | 로봇에 닿는 **주소** | 주소는 바뀐다. 계약의 `robot_id` 는 기체의 신원이라 바뀌면 안 된다(§15.78 의 이름과 주소) |
 * | `Robot.nickname` | 사람이 붙인 **이름** | — **파견이 이것으로 기체를 지목한다.** 벤더 안에서도 이것이 신원 노릇을 한다 |
 *
 * **대가**: 별명은 Orbit 인스턴스 안에서만 유일하다. 사이트가 여럿이고 별명이 겹치면 `robot_id` 가 부딪치며,
 * 그때 적재 문이 *다른 문으로 들어온 기체* 로 거절하거나 같은 기체로 착각한다. 배포가 그것을 피해야 하고,
 * 우리가 접두사를 붙여 피하지 않는 것은 **그러면 파견이 쓰는 이름과 계약이 쓰는 이름이 갈리기 때문이다.**
 *
 * ## 일련번호는 안 싣는다
 *
 * 플릿의 기체 자원에 없다(§15.103). 주소를 그 자리에 넣으면 거짓말이므로 **없는 채로** 올리고, 원장이 그것을
 * 널로 받는다. 선언 경로에서는 여전히 요구한다 — 사람이 적는 자리에는 그것을 아는 사람이 있다.
 */
class OrbitDiscovery(
    private val link: OrbitLink,
    /** 이 어댑터가 배포된 사이트. **플릿은 우리 `site_id` 를 모른다**(ADR 37 의 미결을 §15.101 이 이렇게 닫았다). */
    private val site: String,
    /**
     * 이 배포의 이름(ADR 37 결정 2). 원장이 *"이 발견이 누구의 것인가"* 에 답하는 근거다.
     * **널이면 안 밝히는 것이고**, 그때 원장은 그 기체의 출처를 모르는 채로 든다.
     */
    private val instanceId: String? = null,
    private val sink: RobotDiscovery = RobotDiscovery.NONE,
) {

    /**
     * 한 번 훑어 올린다.
     *
     * **주기를 안 둔다.** 스케줄러는 배치 쪽의 일이고(런처), 여기서 스레드를 돌리면 시험이 벽시계에 매인다 —
     * 어댑터 호스트의 펌프를 스레드 없이 둔 것과 같은 이유다.
     *
     * @return 플릿이 목록을 안 주면 실패다. **빈 목록과 다르다** — 앞은 *"못 물어봤다"* 이고 뒤는 *"플릿에
     *   기체가 없다"* 이며, 접으면 링크가 끊긴 것이 기체가 사라진 것으로 읽힌다.
     */
    fun sweep(): Result<DiscoveryAck> {
        val fleet = link.fleet
            ?: return Result.failure(IllegalStateException("이 Orbit 이 기체 목록을 안 준다 — 발견이 선언으로 내려앉는다(ADR 37 결정 5)"))

        return fleet.robots().map { robots ->
            sink.report(site, instanceId, robots.map { it.toReport() })
        }
    }

    private fun OrbitRobot.toReport() = DiscoveredRobotReport(
        // 별명이 신원이다. 위 표를 볼 것.
        robotId = nickname,
        // 플릿이 안 준다. 지어내지 않는다.
        serialNumber = null,
        // 주소를 여기 남긴다 — **접속 정보가 아니라 사람이 읽는 표시다**(원장의 `endpoint` 는 발견 경로에서 비어 있다).
        displayName = "$nickname@$hostname",
    )
}
