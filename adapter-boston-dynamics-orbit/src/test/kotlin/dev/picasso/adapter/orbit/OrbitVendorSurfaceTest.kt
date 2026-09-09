package dev.picasso.adapter.orbit

import dev.picasso.adapter.core.VendorManifest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 남쪽 포트가 짚은 이름이 **벤더 원문에 실제로 있는가.**
 *
 * 어댑터 셋에 붙은 것과 같은 검사이며, 여기서는 원본이 둘이라 한 가지가 더 있다 — **이름 공간이 근거 등급을
 * 나른다.** `GET /robots` 는 게시 스펙의 것이고 `bosdyn-orbit:` 이 붙은 것은 벤더의 클라이언트 소스에만 있는
 * 것이다. 후자를 전자인 척 적으면 *"벤더가 공개 API 로 약속했다"* 가 거짓이 된다.
 *
 * ## 이 매니페스트를 만들면서 잡은 것
 *
 * 추출기의 첫 판이 클라이언트 경로를 정규식으로 읽었고, **파견 경로를 놓쳤다** — 벤더가 쿼리까지 한 문자열에
 * 담아 쓰기 때문이다. 동시에 `application/json` 같은 문자열은 경로로 읽었다. 놓친 것은 §15.83 이 적어 둔 사실과
 * 안 맞아서 드러났다: **아는 이름 하나를 확인하는 것이 새 추출기의 첫 시험이다.**
 */
class OrbitVendorSurfaceTest {

    @Test
    fun `짚은 벤더 이름이 원문에 있고 멤버마다 짚은 것이 있다`() {
        VendorManifest.load().verify(
            OrbitLink::class.java,
            FleetLayer::class.java,
            OrbitRobot::class.java,
            DispatchLayer::class.java,
            RunLayer::class.java,
            OrbitRun::class.java,
            OrbitRunEvent::class.java,
        )
    }

    @Test
    fun `게시 스펙과 클라이언트가 이름 공간으로 갈려 있다`() {
        val manifest = VendorManifest.load()

        // 게시 스펙 쪽 — 경로는 슬래시로 시작하고 메서드가 앞에 붙는다.
        assertTrue(manifest.has("GET /robots"), "게시 스펙의 발견 경로가 매니페스트에 없다")
        assertTrue(manifest.has("Robot.nickname"))

        // **일련번호는 기체 자원에 없다.** 이것이 부재의 단언이며, 이 저장소에서 부재는 근거와 함께 적는다 —
        // 벤더의 낱말로 다시 물어도 없다(`Robot.serial`·`Robot.serialNumber` 둘 다 0 건). 다만 Orbit 이 모르는
        // 것은 아니다: `Run.robotSerial` 에는 있다.
        assertTrue(!manifest.has("Robot.serial") && !manifest.has("Robot.serialNumber"))
        assertTrue(manifest.has("Run.robotSerial"), "Orbit 이 일련번호를 아예 모른다면 이 단언이 뒤집힌다")

        // 클라이언트 쪽 — 접두사가 붙고, 그 경로는 게시 스펙에 **없다.**
        assertTrue(manifest.has("bosdyn-orbit:calendar/mission/dispatch/{robot_nickname}?currentDriverId={driver_id}"))
        assertTrue(
            !manifest.has("POST /calendar/mission/dispatch/{nickname}"),
            "파견이 게시 스펙에 있는 것처럼 적혔다 — 근거 등급이 갈리는 자리다",
        )
    }

    @Test
    fun `Run 의 미션 상태는 값 집합이 없다`() {
        // 열거값이 있으면 추출기가 `Run.missionStatus=…` 로 냈을 것이다. 하나도 없다는 것이
        // **자유 문자열**이라는 뜻이고, 그것이 결과 어휘에서 이 벤더 표면의 거리다.
        val manifest = VendorManifest.load()
        assertTrue(manifest.has("Run.missionStatus"))
        assertTrue(
            manifest.symbolsStartingWith("Run.missionStatus=").isEmpty(),
            "값 집합이 생겼다면 어댑터의 매핑을 짐작이 아니라 대조로 바꿀 수 있다",
        )
        // 대조군 — 같은 스키마의 다른 필드에는 값 집합이 있다. 없어서 못 찾는 것과 원래 안 내는 것을 가른다.
        assertTrue(manifest.symbolsStartingWith("Run.runType=").isNotEmpty(), "추출기가 열거를 통째로 못 읽고 있다")
    }
}
