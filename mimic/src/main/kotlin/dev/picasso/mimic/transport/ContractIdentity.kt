package dev.picasso.mimic.transport

import java.io.InputStream
import java.util.Properties

/**
 * 헤더가 싣는 계약의 신원(§5.5).
 *
 * `contracts` 모듈이 빌드 때 구워 넣은 리소스를 읽는다. **못 읽으면
 * 기동하지 않는다** — 조용히 빈 문자열을 실으면 소비자에게 "계약이 무엇인지
 * 모른다"가 "계약이 없다"로 보이고, §6.2의 메시지마다 판정이 그 자리에서
 * 무력해진다.
 *
 * §5.5는 `buf` 모듈 다이제스트라 했으나 여기 실리는 것은 protobuf 플러그인이
 * 뽑은 디스크립터 셋의 SHA-256이다. 이유와 한계는 `contracts/build.gradle.kts`와
 * §15에 있다.
 */
object ContractIdentity {

    const val RESOURCE = "/picasso-contract.properties"

    private val identity: Pair<String, String> by lazy {
        from(ContractIdentity::class.java.getResourceAsStream(RESOURCE))
    }

    val semver: String get() = identity.first
    val digest: String get() = identity.second

    /** 시험이 부재를 확인할 수 있도록 스트림을 인자로 받는다. */
    internal fun from(stream: InputStream?): Pair<String, String> {
        val properties = Properties()
        (stream ?: error("계약 신원 리소스를 찾을 수 없다: $RESOURCE")).use(properties::load)

        val semver = properties.getProperty("semver").orEmpty()
        val digest = properties.getProperty("digest").orEmpty()

        // 파일은 있는데 키가 비는 경우가 있다 — 굽는 태스크가 반쯤 돈 경우다.
        // 그것도 기동 실패다.
        check(semver.isNotBlank()) { "계약 신원에 semver가 없다: $RESOURCE" }
        check(digest.isNotBlank()) { "계약 신원에 digest가 없다: $RESOURCE" }

        return semver to digest
    }
}
