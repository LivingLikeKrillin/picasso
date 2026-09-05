import org.gradle.api.tasks.PathSensitivity

plugins {
    application
}

application {
    mainClass.set("dev.picasso.gate.cli.MainKt")
    // Windows 콘솔 코드페이지에서 한글 소견이 깨진다. 게이트가 무엇을
    // 막았는지가 유일한 산출물인데 읽을 수 없으면 소용이 없다.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
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
    // 그래서 buf build가 ./gradlew build보다 먼저 돌아야 한다:
    //   mkdir -p contracts/build && ( cd contracts && ../tools/buf build -o build/descriptor.binpb )
    // Gradle 태스크 의존이 아니라 순서에 기댄다 — gate는 contracts에
    // 빌드 의존을 걸지 않는다(설계 §3.2의 단서). 순서를 어기면 테스트가
    // 만드는 법을 찍고 실패한다.
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
    inputs.files(
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/schema"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("picasso.descriptor", descriptor.absolutePath)
}
