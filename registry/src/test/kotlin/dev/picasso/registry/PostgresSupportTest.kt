package dev.picasso.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **시험 인프라가 정직한지 먼저 본다.**
 *
 * 컨테이너가 안 떠도 초록인 설정은 DB를 안 쓰는 것과 같다. 이 저장소는
 * 이번 세션에만 그 모양을 세 번 봤다 — 러너가 죽어 있는데 "결함이 잡혔다"로
 * 읽은 것, 주입기가 조용히 죽어 무변경 트리를 시험한 것, 오래된 결과 XML을
 * 새 결과로 읽은 것. **도구가 일하고 있다는 것부터 단언한다.**
 */
class PostgresSupportTest {

    @BeforeTest
    fun reset() {
        applied = PostgresSupport.reset()
    }

    private var applied = 0

    @Test
    fun `컨테이너가 실제로 뜬다`() {
        val version = PostgresSupport.queryOne("SELECT version()") { it.getString(1) }
        assertTrue(version.startsWith("PostgreSQL 16"), "엉뚱한 DB에 붙었다: $version")
    }

    @Test
    fun `마이그레이션이 실제로 적용된다`() {
        // 0이면 Flyway가 아무것도 안 했다는 뜻이고, 그러면 아래 시험들이
        // 전부 빈 스키마 위에서 돈다.
        assertTrue(applied > 0, "적용된 마이그레이션이 없다")

        // **개수가 아니라 이름을 본다.** 세기만 하면 이름을 바꿔도 통과한다
        // (실측: audit_log 를 audit_log_unused 로 바꾸는 주입이 안 잡혔다).
        val tables = PostgresSupport.connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' ORDER BY table_name",
                ).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
        assertEquals(
            listOf(
                "adapter", "adapter_version", "audit_log", "capability_epoch_log",
                "capability_profile", "change_plan", "change_plan_step",
                "consumer", "consumer_requirement",
                "flyway_schema_history", "handshake_rejection",
                "profile_revision", "profile_skill", "profile_skill_param",
                "revision_test_request", "revision_test_run",
                "robot", "robot_binding", "skill_type", "task",
            ),
            tables,
            "마이그레이션이 만드는 테이블 목록이 다르다",
        )
    }

    @Test
    fun `JSONB를 쓸 수 있다`() {
        // **H2로는 안 되는 것을 확인한다.** 스키마가 JSONB를 쓰므로(§3.4)
        // 대체 DB로 바꾸면 시험이 지나는 DB와 운영이 도는 DB가 달라진다.
        val kind = PostgresSupport.queryOne(
            """
            SELECT data_type FROM information_schema.columns
            WHERE table_name = 'profile_revision' AND column_name = 'document'
            """.trimIndent(),
        ) { it.getString(1) }
        assertEquals("jsonb", kind)

        PostgresSupport.execute(
            """
            INSERT INTO capability_profile (vendor, model) VALUES ('acme', 'a1');
            INSERT INTO profile_revision
                (profile_id, revision, document, document_hash, schema_version, status, created_by)
            SELECT profile_id, 1, '{"skills":[{"skill_type":"navigate_to"}]}'::jsonb,
                   'h', '1.0.0', 'DRAFT', 'tester'
            FROM capability_profile WHERE vendor = 'acme';
            """.trimIndent(),
        )
        val skill = PostgresSupport.queryOne(
            "SELECT document->'skills'->0->>'skill_type' FROM profile_revision",
        ) { it.getString(1) }
        assertEquals("navigate_to", skill, "JSONB 경로 질의가 안 된다")
    }

    @Test
    fun `시험 사이에 스키마가 초기화된다`() {
        // 앞 시험이 넣은 행이 남으면 뒤 시험이 자기가 만들지 않은 상태 위에서
        // 돌고, 실행 순서에 따라 결과가 달라진다.
        assertEquals(
            0,
            PostgresSupport.queryOne("SELECT count(*) FROM capability_profile") { it.getInt(1) },
            "앞 시험의 행이 남았다",
        )
    }

    @Test
    fun `기체당 활성 바인딩은 하나다`() {
        // 부분 유니크 인덱스가 **DB에서** 강제하는지 본다. 응용 계층에만
        // 두면 동시 요청 둘이 지나간다.
        PostgresSupport.execute(
            """
            INSERT INTO capability_profile (vendor, model) VALUES ('acme', 'a1');
            INSERT INTO profile_revision
                (profile_revision_id, profile_id, revision, document, document_hash,
                 schema_version, status, created_by)
            SELECT 1, profile_id, 1, '{}'::jsonb, 'h', '1.0.0', 'ACTIVE', 't'
            FROM capability_profile;
            INSERT INTO adapter (adapter_id, vendor, name) VALUES (1, 'acme', 'drv');
            INSERT INTO adapter_version
                (adapter_version_id, adapter_id, version, contract_semver, registered_by)
            VALUES (1, 1, '1.0.0', '0.3.0', 't');
            INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1', 's', 'sn1');
            INSERT INTO robot_binding
                (robot_id, adapter_version_id, profile_revision_id, bound_by)
            VALUES ('r1', 1, 1, 't');
            """.trimIndent(),
        )

        val second = runCatching {
            PostgresSupport.execute(
                """
                INSERT INTO robot_binding
                    (robot_id, adapter_version_id, profile_revision_id, bound_by)
                VALUES ('r1', 1, 1, 't');
                """.trimIndent(),
            )
        }
        assertTrue(second.isFailure, "활성 바인딩이 둘이 됐다")

        // 해제한 뒤에는 받아야 한다 — 인덱스가 조건부인 뜻이 그것이다.
        PostgresSupport.execute("UPDATE robot_binding SET unbound_at = now()")
        PostgresSupport.execute(
            """
            INSERT INTO robot_binding
                (robot_id, adapter_version_id, profile_revision_id, bound_by)
            VALUES ('r1', 1, 1, 't');
            """.trimIndent(),
        )
        assertEquals(
            2,
            PostgresSupport.queryOne("SELECT count(*) FROM robot_binding") { it.getInt(1) },
            "해제 뒤 재바인딩이 안 된다",
        )
    }
}
