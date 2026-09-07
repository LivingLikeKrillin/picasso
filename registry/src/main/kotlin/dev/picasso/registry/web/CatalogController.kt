package dev.picasso.registry.web

import dev.picasso.registry.catalog.SiteCapability
import dev.picasso.registry.catalog.SiteCatalog
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * §9.6의 업스트림 표면 — **"지금 이 사이트가 할 수 있는 일".**
 *
 * ## 진단과 다른 표면이다
 *
 * 진단 여섯(`diag` 이하)은 **운영자**가 무엇이 왜 그런지 보는 자리다. 여기는
 * **상위 시스템**(MES·WMS/WCS·SCADA·ERP·라인 제어기)이 라우팅을 정하려고
 * 묻는 자리이며, 답의 단위부터 다르다 — 진단은 기체 한 줄, 여기는 능력 한 줄.
 *
 * 한 컨트롤러에 섞으면 진단 응답을 바꿀 때 상위 시스템이 깨지고, 그때
 * "운영자용 필드를 하나 더했을 뿐"이라는 변경이 계약 파기가 된다.
 *
 * ## 상위가 무엇인지 몰라도 된다
 *
 * §3.2가 상위 시스템 어댑터(ACL) 구현을 비목표로 두면서 **붙을 자리는
 * 만든다**고 했다. 그 자리가 여기다. MES가 오든 WMS가 오든 이 GET 하나를
 * 폴링하면 되고, `registry`는 상대가 무엇인지 알 필요가 없다.
 *
 * ## retain 스트림 대신 폴링이다
 *
 * §9.6은 `picasso/{major}/{site}/site/catalog`를 retain 스트림으로도 규정하지만
 * 브로커가 없다(§15.30). 같은 절이 *"없을 때: 상위가 폴링으로 대체"*라고
 * 적어 두었고, 이 표면이 그 폴링이다.
 *
 * ## 토큰이 없다
 *
 * read-only이며 §8.5의 승인 경계는 조작에 걸린다. 적재 경로와
 * `/requirements`만 관문 뒤에 있다(§15.38).
 *
 * (경로 패턴을 주석에 그대로 쓰지 않는다 — 슬래시 뒤 별표가 블록 주석을
 * 열어 파일 끝까지 삼킨다. 실측으로 세 번 물렸다.)
 */
@RestController
class CatalogController(private val catalog: SiteCatalog) {

    /**
     * 그 사이트의 능력 목록.
     *
     * **기체가 아니라 능력이 단위다**(§9.6). 상위는 "3번 로봇"이 아니라
     * "이 공장에서 `pick_place`가 되는가"를 알아야 하고, 기체 한 대가 빠졌을
     * 때 이 목록이 흔들리면 상위가 없는 장애에 반응한다.
     *
     * 질의 파라미터 이름을 명시하는 이유는 [DiagController]와 같다 — Kotlin이
     * `-java-parameters` 없이는 이름 정보를 안 넣는다.
     */
    @GetMapping("/catalog")
    fun catalog(@RequestParam(name = "site") site: String): List<SiteCapability> =
        catalog.capabilities(site)
}
