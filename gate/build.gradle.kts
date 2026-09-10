import org.gradle.api.tasks.PathSensitivity

plugins {
    application
    // **`api`가 필요하다.** `GateInput`이 `ProfileDocument`를 노출하므로
    // `registry`가 그것을 채우려면 `profile-model`이 소비자에게 보여야
    // 한다. `implementation`으로 두면 registry 가 게이트를 못 부른다.
    `java-library`
}

application {
    mainClass.set("dev.picasso.gate.cli.MainKt")
    // Windows 콘솔 코드페이지에서 한글 소견이 깨진다. 게이트가 무엇을
    // 막았는지가 유일한 산출물인데 읽을 수 없으면 소용이 없다.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    api(project(":profile-model"))
    implementation(libs.protobuf.java)
    implementation(libs.jsonschema.validator)
    implementation(libs.jackson.databind)
    implementation(libs.clikt)

    // networknt가 slf4j-api를 끌고 오는데 구현이 없으면 경고 세 줄이
    // 게이트 출력 앞에 붙는다. 게이트가 무엇을 막았는지가 유일한 산출물이다.
    runtimeOnly(libs.slf4j.nop)

    // 루트 build.gradle.kts가 junit-jupiter만 넣는다. kotlin.test는 별도다.
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // 테스트가 실제 contracts 디스크립터를 읽는다.
    // 픽스처를 커밋하면 proto가 바뀔 때 조용히 낡기 때문이다.
    //
    // **이제 Gradle 이 그 순서를 강제한다.** 예전에는 `tools/buf build` 를 손으로 먼저
    // 돌려야 했고, 안 돌리면 **낡은 디스크립터가 낡은 코드와 사이좋게 초록이었다**
    // (§15.22 의 실측 — 옵션을 더하고 시험을 돌렸는데 옛 판정이 나왔다).
    //
    // **이것은 `project(":contracts")` 의존이 아니다.** 태스크 순서 하나이고 `gate` 의
    // 클래스패스에는 아무것도 안 들어온다 — §3.2 가 막는 것은 뒤엣것이다(게이트 5번이
    // 그 자리를 계속 지킨다). 대신 얻는 것은 *proto 를 고치면 게이트 시험이 그것을 본다* 는
    // 보장이고, 그것이 없으면 이 모듈의 시험 전부가 조용히 낡는다.
    dependsOn(":contracts:generateProto")
    val descriptor = rootProject.file("contracts/build/descriptor.binpb")

    // inputs 선언이 없으면 디스크립터가 깨지거나 바뀌어도 테스트가
    // UP-TO-DATE로 넘어간다. 그러면 위 명분이 통째로 무너진다.
    inputs.files(descriptor)
        .withPropertyName("contractDescriptor")
        .withPathSensitivity(PathSensitivity.NONE)

    // 디스크립터와 같은 이유다. 이 선언이 없으면 픽스처나 스키마를 고치고
    // ./gradlew build를 돌려도 게이트 시험이 UP-TO-DATE로 넘어가 초록이 난다.
    // 실측으로 확인된 구멍이다 — 픽스처를 CRLF로 바꿔 다섯 시험이 깨지는
    // 상태에서도 BUILD SUCCESSFUL이 났다.
    // 음성 하네스가 복사하는 트리 중 검사가 보는 것을 전부 선언한다.
    // 실측으로 확인된 구멍이다 — case.json의 targets를 999로 바꿔도
    // UP-TO-DATE로 초록이었다.
    // **선언과 시험이 같은 목록을 본다.** 아래 `repoInputs` 하나가 두 곳에 쓰인다 — Gradle 의 입력 선언과,
    // 시험이 저장소 파일을 읽을 때 그 경로가 선언 안인지 확인하는 `Repo` 의 목록. 두 벌로 두면 어느 날
    // 한쪽만 늘고, **그것이 이 파일이 네 번 물린 자리다**(§15.115).
    val repoInputs = listOf(
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/schema"),
        rootProject.file("gate/negative"),
        rootProject.file("contracts/proto"),
        rootProject.file("contracts/build.gradle.kts"),
        rootProject.file("build.gradle.kts"),
        rootProject.file("tools/buf"),
        // **검사 7이 읽는 것들.** 이것이 없으면 기종 문자열을 출하 소스에
        // 넣어도 :gate:test가 UP-TO-DATE로 넘어가 BUILD SUCCESSFUL이 난다 —
        // 그것을 잡는 것이 유일한 일인 검사가 빌드 시스템에 의해 건너뛰어진다
        // (실측: 확인했다). 이 저장소가 같은 방식으로 두 번 물렸다.
        rootProject.file("profile/profiles"),
        rootProject.file("client/src/main"),
        rootProject.file("mimic/src/main"),
        rootProject.file("harness/src/main"),
        // 검사 7번이 넷째 모듈을 보게 됐다(ADR 33). 이 줄이 없으면 거기에
        // 기종 문자열을 넣어도 UP-TO-DATE 로 초록이 난다 — 위 주석이 세 번
        // 물렸다고 적은 그것이다.
        rootProject.file("adapter-core/src/main"),
        // NegativeSuiteTest가 ci.yml과 디렉터리 목록을 대조한다.
        rootProject.file(".github/workflows/ci.yml"),
        // **VendorSurveyTest·ProfileProvenanceTest가 읽는 것들.** 앞의 것은
        // 이 줄이 없는 채로 먼저 들어왔다(431649f) — 조사 문서를 고쳐도
        // :gate:test 가 UP-TO-DATE 로 넘어가 초록이 나는 상태였다. 이 파일의
        // 주석이 같은 구멍에 두 번 물렸다고 적어 둔 바로 그것이며 세 번째다.
        rootProject.file("profile/vendors"),
        rootProject.file("profile/provenance"),
        rootProject.file("profile/distance"),
        // **DocumentClaimsTest 가 읽는 것들.** 같은 구멍의 **네 번째**다 — 이 줄이 없는 채로 먼저 들어왔고,
        // 문서만 고친 변경에서 :gate:test 가 UP-TO-DATE 로 넘어가 **깨진 링크를 넣어도 초록이었다**(실측).
        // 세는 시험을 만들어 놓고 그 시험이 안 도는 상태였다.
        rootProject.file("README.md"),
        rootProject.file("docs"),
        // ★**문을 만들자마자 둘이 더 나왔다.** `DocumentClaimsTest` 가 모듈 수를 `settings.gradle.kts` 에서,
        // 진단 수를 `DiagController` 에서 세는데 **둘 다 선언 밖이었다** — 모듈을 더하거나 진단을 더해도
        // 시험이 안 도는 상태였다. 같은 구멍의 다섯째이고, 이번에는 사람이 아니라 `Repo` 가 찾았다.
        rootProject.file("settings.gradle.kts"),
        rootProject.file("registry/src/main/kotlin/dev/picasso/registry/web/DiagController.kt"),
    ) +
        // **모듈 문은 세지 않는다 — 빌드가 아는 목록에서 만든다.** 손으로 적으면 모듈이 늘 때 한쪽만 늘고,
        // 그것이 이 파일이 반복해 물린 자리다. `DocumentClaimsTest` 가 *모든 모듈에 문이 있는가* 도 본다.
        rootProject.subprojects.map { rootProject.file("${it.name}/README.md") }
    inputs.files(repoInputs).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // 시험이 읽는 순간 대조한다. **절대 경로를 안 넘긴다** — 기계마다 달라 태스크 입력이 흔들린다.
    systemProperty(
        "picasso.gate.declaredInputs",
        repoInputs.joinToString("|") {
            // **구분자를 안 만진다** — Path 의 이름 조각을 이어 붙이면 플랫폼과 무관하다.
            rootProject.projectDir.toPath().relativize(it.toPath()).joinToString("/")
        },
    )

    // 하네스가 읽는다. 값이 바뀌면 Test.systemProperties가 태스크 입력이라
    // 다시 돈다.
    listOf("picasso.negative.strict", "picasso.buf").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }

    systemProperty("picasso.descriptor", descriptor.absolutePath)
}
