// 이 모듈은 proto 파일과 그 디스크립터만 담는다.
// 프로젝트 내 의존이 0이어야 한다 — 게이트 검사 5번이 이를 강제한다.
dependencies {
    // 여기에 project(...) 의존을 추가하면 안 된다.
}

dependencies {
    implementation(project(":gate"))
}
