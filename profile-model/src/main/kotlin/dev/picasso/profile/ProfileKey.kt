package dev.picasso.profile

/**
 * 프로파일 문서의 동일성. 설계 §8.3의 `capability_profile(vendor, model) UNIQUE`.
 * 파일 이름도 개정 번호도 아니다 — 둘 다 바뀌어도 같은 기종이다.
 */
data class ProfileKey(val vendor: String, val model: String) {
    override fun toString() = "$vendor/$model"
}
