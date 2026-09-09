package dev.picasso.adapter.host

import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.uplink.Publisher
import dev.picasso.uplink.report.RegistryLink
import dev.picasso.uplink.report.SiteNameSummary

/**
 * 호스트의 발행을 레지스트리 적재로 감싸는 자리 — 미믹 CLI 의 `RegistryLink.wrap(outbound, software, siteNames)` 와 같은
 * 결선을, 어댑터가 답하는 것으로 채운다.
 *
 * 생존 보고(§9.3 의 관측선)에 실리는 둘이 어댑터에서 온다: 로봇 소프트웨어(못 읽으면 `null`)와 사이트 이름 요약(ADR 35).
 * 요약은 **셋을 접지 않는다** — 안다(개수)·못 한다(`unsupported`)·못 물어봤다(`null`). 레지스트리가 그 셋을 따로 든다.
 */
object HostUplink {

    /** [RegistryLink.wrap] 에 넣을 사이트 이름 요약. `Unavailable` 은 `null` — 0 개도 못 함도 아니다. */
    fun siteNames(adapter: RobotAdapter): SiteNameSummary? = when (val names = adapter.knownSiteNames()) {
        is SiteNames.Known -> SiteNameSummary(unsupported = false, count = names.names.size)
        SiteNames.Unsupported -> SiteNameSummary(unsupported = true, count = 0)
        is SiteNames.Unavailable -> null
    }

    /** 발행자를 레지스트리 적재로 감싼다. 연계가 없으면 그대로 돌려준다(`RegistryLink.none()`). */
    fun wrap(link: RegistryLink, outbound: Publisher, adapter: RobotAdapter): Publisher =
        link.wrap(outbound, software = { adapter.robotSoftware() }, siteNames = { siteNames(adapter) })
}
