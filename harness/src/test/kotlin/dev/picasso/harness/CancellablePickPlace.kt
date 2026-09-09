package dev.picasso.harness

import java.nio.file.Files
import java.nio.file.Path

/**
 * 취소할 수 있는 `pick_place`를 든 프로파일 — 픽스처의 사본.
 *
 * 픽스처 `minimal.json`의 `pick_place`는 `cancel_support: NO`다. 그 값은
 * 완료 기준 7(취소 불가 스킬은 `CANCEL_UNSUPPORTED`)의 근거라 픽스처에서
 * 바꿀 수 없다. 그런데 §4.4의 잔여 물리 상태와 시나리오 ②(시퀀싱)는 **든 채
 * 취소되는** 스킬이 있어야 시험이 된다.
 *
 * 그래서 사본을 만들되 **바꾸는 값은 그 하나뿐**이고, 하나뿐인지 확인한다 —
 * 둘 이상이면 무엇을 바꿨는지 모르는 채 시험하는 것이다.
 */
object CancellablePickPlace {

    private val original: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

    private const val NEEDLE = "\"cancel_support\": \"NO\""

    fun profile(): Path {
        val raw = Files.readString(original)
        check(raw.split(NEEDLE).size == 2) { "픽스처에 cancel_support NO 가 하나가 아니다" }
        val copy = Files.createTempFile("picasso-cancellable-pick-place-", ".json")
        Files.writeString(copy, raw.replace(NEEDLE, "\"cancel_support\": \"YES\""))
        copy.toFile().deleteOnExit()
        return copy
    }
}
