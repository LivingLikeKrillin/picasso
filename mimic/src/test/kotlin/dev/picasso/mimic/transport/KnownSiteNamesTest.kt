package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.mimic.engine.TaskMachineFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 계약의 `GetKnownSiteNames`(ADR 35).
 *
 * ## 이 RPC가 왜 생겼나
 *
 * 사이트 이름은 사이트가 저작해 로봇에 등록해 두는 것이고, 등록은 계약 밖의
 * 배포 작업이다. 그러면 **등록했는지를 무엇으로 아는가**가 남는다 — 여기
 * 오기 전까지는 운영자가 레지스트리에 *"했다"* 고 적는 것뿐이었고, 통째로
 * 빠뜨렸거나 엉뚱한 기체에 했어도 화면은 초록이었다.
 *
 * ## 셋을 가른다
 *
 * | 상태 | 답 |
 * |---|---|
 * | 이름을 호스팅 못 하는 기종 | `unsupported = true` |
 * | 호스팅하는데 하나도 없음 | 빈 목록, `unsupported = false` |
 * | 아는 이름이 있음 | 목록 + `total_count` |
 *
 * **가운데를 첫째와 접으면 등록할 자리가 없는 기체에게 등록을 요구하게 된다.**
 * 원장이 "0"과 "모른다"를 가른 것과 같은 규율이다.
 */
class KnownSiteNamesTest {

    private fun fixture() = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()))

    private fun GrpcFixture.ask() = skills.getKnownSiteNames(
        GetKnownSiteNamesRequest.newBuilder().setHeader(GrpcFixture.requestHeader("r1")).build(),
    )

    @Test
    fun `기동한 기체는 아무 이름도 모른다`() {
        // **빈 목록이 기본값인 것이 요점이다.** 기동하자마자 뭔가 안다고
        // 답하면 등록 확인이 언제나 통과한다.
        fixture().use {
            val answer = it.ask()
            assertFalse(answer.unsupported)
            assertEquals(emptyList(), answer.namesList)
            assertEquals(0, answer.totalCount)
        }
    }

    @Test
    fun `심어 준 이름을 그대로 답한다`() {
        fixture().use { f ->
            f.registry.require(GrpcFixture.requestHeader("r1")).instance.knownSiteNames =
                listOf("dock-3", "shelf-b")

            val answer = f.ask()
            assertFalse(answer.unsupported)
            assertEquals(listOf("dock-3", "shelf-b"), answer.namesList)
            assertEquals(2, answer.totalCount)
        }
    }

    @Test
    fun `호스팅 못 하는 기종과 하나도 없는 기종을 가른다`() {
        // **이 시험이 이 파일의 이유다.** 접으면 G1처럼 등록할 자리가 아예
        // 없는 기체에게 "등록하라"고 요구하게 된다(§2.3).
        fixture().use { f ->
            f.registry.require(GrpcFixture.requestHeader("r1")).instance.knownSiteNames = null

            val answer = f.ask()
            assertTrue(answer.unsupported)
            assertEquals(emptyList(), answer.namesList)
            assertEquals(0, answer.totalCount, "못 하는 기종이 개수를 냈다")
        }
    }

    @Test
    fun `배열 한계를 넘으면 자르되 전체 개수를 말한다`() {
        // 프로파일의 `protocol_limits.max_array_length` 가 목록을 자른다.
        // **세지 않으면 지도가 큰 사이트에서 목록이 조용히 잘리고 소비자가
        // 그것을 전부로 읽는다.**
        fixture().use { f ->
            val instance = f.registry.require(GrpcFixture.requestHeader("r1")).instance
            val limit = instance.document.maxArrayLength
            instance.knownSiteNames = (1..(limit + 5)).map { "wp-$it" }

            val answer = f.ask()
            assertEquals(limit, answer.namesList.size, "한계를 안 지켰다")
            assertEquals(limit + 5, answer.totalCount, "잘린 것을 안 알렸다")
            assertTrue(answer.totalCount > answer.namesList.size)
        }
    }

    @Test
    fun `헤더와 페이로드의 robot_id 가 다르면 거절한다`() {
        // `GetCapabilities` 와 같은 규칙이다 — 헤더가 권위이고 페이로드는
        // 복사본이다(§5.5).
        fixture().use { f ->
            val ex = kotlin.runCatching {
                f.skills.getKnownSiteNames(
                    GetKnownSiteNamesRequest.newBuilder()
                        .setHeader(GrpcFixture.requestHeader("r1"))
                        .setRobotId("other")
                        .build(),
                )
            }.exceptionOrNull()
            assertTrue(ex is io.grpc.StatusRuntimeException, "거절하지 않았다: $ex")
        }
    }
}
