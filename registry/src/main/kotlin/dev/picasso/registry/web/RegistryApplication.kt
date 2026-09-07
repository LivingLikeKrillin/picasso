package dev.picasso.registry.web

import dev.picasso.gate.GateChecks
import dev.picasso.registry.diag.DiagnosticsService
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionValidator
import dev.picasso.registry.store.Db
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.nio.file.Files
import java.nio.file.Path

/**
 * §3.4의 HTTP 표면. **`web/`만 프레임워크를 안다.**
 *
 * ## 왜 도메인이 스프링을 모르는가
 *
 * §3.4가 *"도메인 로직은 프레임워크를 모른다"*고 못박았다. 그 값은 시험에서
 * 나온다 — 진단의 거동을 보려고 서버를 띄워야 하면 스위트가 느려지고,
 * 그러면 **결함 주입 라운드가 줄어든다.** 이 저장소가 지켜 온 것이 그것이다.
 *
 * 그래서 여기는 배선만 한다. 답이 무엇인가는 [DiagnosticsService]가 정한다.
 *
 * ## `DataSource` 자동설정을 끈다
 *
 * [Db]가 트랜잭션 경계의 주인이다(§8.5의 "API 한 번 = 트랜잭션 한 번").
 * 스프링의 `DataSource`·트랜잭션 매니저를 함께 두면 경계가 둘이 되고,
 * 그때 어느 쪽이 커밋했는지 코드를 읽어야 알게 된다.
 */
@SpringBootApplication(
    exclude = [
        DataSourceAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
    ],
)
open class RegistryApplication {

    @Bean
    open fun db(
        @Value("\${picasso.db.url}") url: String,
        @Value("\${picasso.db.user}") user: String,
        @Value("\${picasso.db.password}") password: String,
    ): Db = Db(url, user, password)

    @Bean
    open fun diagnostics(db: Db): DiagnosticsService = DiagnosticsService(db)

    @Bean
    open fun observations(db: Db): ObservationService = ObservationService(db)

    /**
     * §11.1 — **검증은 게이트가 한다.** 여기서는 그 입력을 모아 줄 뿐이다.
     * 파일이 없으면 널로 넘긴다: 빈 문자열은 게이트에게 "있음"으로 보여
     * 자원 부재를 표현할 수 없다.
     */
    @Bean
    open fun revisionValidator(
        @Value("\${picasso.profile.schema:}") schemaPath: String,
    ): RevisionValidator = RevisionValidator(
        schemaJson = schemaPath.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf(Files::isRegularFile)
            ?.let(Files::readString),
        descriptor = RegistryApplication::class.java.getResourceAsStream("/picasso.desc")
            ?.use { it.readBytes() },
        checks = GateChecks.all(),
    )
}

fun main(args: Array<String>) {
    runApplication<RegistryApplication>(*args)
}
