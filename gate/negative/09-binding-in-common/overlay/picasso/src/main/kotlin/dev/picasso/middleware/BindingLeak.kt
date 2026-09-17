package dev.picasso.middleware

/**
 * 음성 케이스 — **이 파일은 게이트가 막아야 한다.**
 *
 * 미들웨어가 결속 정본을 직접 들면 자리 이름이 어느 기종의 무엇에 묶이는지를 공통 계층이 알게 된다.
 * 그 순간 좌표와 프레임이 어댑터 밖으로 새고, 기종 비인지가 한 겹 무너진다.
 */
class BindingLeak(private val source: dev.picasso.adapter.core.SiteBindingSource) {
    fun active() = source.activeMap()
}
