package dev.picasso.middleware

import java.net.http.HttpClient

/** 검사 11 의 음성 케이스 — 선언되지 않은 자리에서 밖으로 나간다. */
object OutboundLeak {
    val client: HttpClient = HttpClient.newHttpClient()
}
