plugins {
    application
}

application {
    mainClass.set("dev.picasso.mimic.cli.MainKt")
    // Windows 콘솔 코드페이지에서 한글 기동 거부 메시지가 깨진다.
    // §10.2에서 그것이 유일한 산출물이다.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// 프로파일 주도 에뮬레이터. 거동은 프로파일에서 오고 코드는 해석기다(§10.1).
dependencies {
    implementation(project(":profile-model"))
    implementation(project(":contracts"))
    implementation(libs.jackson.databind)
    implementation(libs.jsonschema.validator)

    // jsonschema-validator가 slf4j-api를 끌고 온다. 구현이 없으면 경고 세 줄이
    // 출력 앞에 붙는데, mimic은 곧 CLI가 되고 기동 거부 메시지가 유일한
    // 산출물이 된다(§10.2).
    runtimeOnly(libs.slf4j.nop)

    // 실제 포트를 여는 것은 CLI뿐이다. 시험은 in-process로 돈다.
    runtimeOnly(libs.grpc.netty.shaded)

    testImplementation(kotlin("test"))

    // 시험이 in-process 전송으로 표면을 실제로 지난다. 포트를 열지 않으므로
    // CI에서 흔들리지 않으면서 직렬화·스텁·StreamObserver를 전부 지난다.
    testImplementation(libs.grpc.inprocess)
}

tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/schema"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
