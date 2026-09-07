package dev.picasso.registry.web

import dev.picasso.gate.GateChecks
import dev.picasso.registry.diag.DiagnosticsService
import dev.picasso.registry.ingest.HandshakeIngestService
import dev.picasso.registry.ingest.TaskIngestService
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.catalog.SiteCatalog
import dev.picasso.registry.ledger.LedgerService
import dev.picasso.registry.ledger.RegistryLedgerQuery
import dev.picasso.registry.plan.ChangePlanService
import dev.picasso.registry.plan.Preconditions
import dev.picasso.registry.observe.ObservationService
import dev.picasso.registry.revision.RevisionValidator
import dev.picasso.registry.store.Db
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
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

    @Bean
    open fun ledger(db: Db): LedgerService = LedgerService(db)

    @Bean
    open fun bindings(db: Db): BindingService = BindingService(db)

    /**
     * §9.3의 두 조회를 게이트에 물린다. **이 빈이 없으면 검사 6번은 축소를
     * 분류만 하고**, 레지스트리는 소비자가 남아 있는 능력의 제거를 통과시킨다.
     */
    @Bean
    open fun ledgerQuery(db: Db): RegistryLedgerQuery = RegistryLedgerQuery(db)

    @Bean
    open fun handshakeIngest(
        ledger: LedgerService,
        observations: ObservationService,
    ): HandshakeIngestService = HandshakeIngestService(ledger, observations)

    @Bean
    open fun taskIngest(db: Db): TaskIngestService = TaskIngestService(db)

    /** §9.6의 업스트림 표면. 상위 시스템이 폴링한다. */
    @Bean
    open fun siteCatalog(db: Db): SiteCatalog = SiteCatalog(db)

    /**
     * **빈 토큰은 전부 401이다**(§15.38). 배포에서 토큰을 빠뜨린 것과 일부러
     * 안 쓰는 것을 구별할 방법이 없고, 전자가 압도적으로 흔하다.
     */
    @Bean
    open fun ingestToken(
        @Value("\${picasso.ingest.token:}") token: String,
    ): IngestToken = IngestToken(token)

    /**
     * 적재 경로를 **경로로** 지킨다. 엔드포인트마다 확인하는 구조는 새 것을
     * 더할 때 잊으면 조용히 새는 문이 된다.
     */
    @Bean
    open fun ingestGuard(token: IngestToken): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(IngestTokenInterceptor(token))
                .addPathPatterns(IngestTokenInterceptor.GUARDED)
        }
    }

    /**
     * **활성화는 `BindingService`를 지난다**(§8.4 ③). 계획이 status를 직접
     * 쓰면 승인 조건(TESTED/SUPERSEDED, 세 스위트 PASS)을 안 지나는 두 번째
     * 활성화 경로가 생긴다.
     */
    @Bean
    open fun changePlans(
        db: Db,
        ledger: LedgerService,
        bindings: BindingService,
    ): ChangePlanService = ChangePlanService(db, Preconditions(db, ledger), bindings)

    /**
     * §11.1 — **검증은 게이트가 한다.** 여기서는 그 입력을 모아 줄 뿐이다.
     * 파일이 없으면 널로 넘긴다: 빈 문자열은 게이트에게 "있음"으로 보여
     * 자원 부재를 표현할 수 없다.
     */
    @Bean
    open fun revisionValidator(
        @Value("\${picasso.profile.schema:}") schemaPath: String,
        ledger: RegistryLedgerQuery,
    ): RevisionValidator = RevisionValidator(
        schemaJson = schemaPath.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf(Files::isRegularFile)
            ?.let(Files::readString),
        descriptor = RegistryApplication::class.java.getResourceAsStream("/picasso.desc")
            ?.use { it.readBytes() },
        checks = GateChecks.all(),
        ledger = ledger,
    )
}

fun main(args: Array<String>) {
    runApplication<RegistryApplication>(*args)
}
