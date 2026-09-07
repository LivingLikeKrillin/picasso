import com.google.protobuf.gradle.id

plugins {
    application
    // **제어 채널의 proto가 여기 있다**(§10.5 — contracts/ 에 두지 않는다).
    // 이 배선이 없으면 proto를 넣어도 **아무 코드도 생성되지 않고 빌드는
    // 초록이다**(실측). 그 다음에 오는 것은 "왜 클래스가 없지"이고,
    // 그 사이의 상태가 이 저장소가 반복해 물린 조용한 통과의 모양이다.
    alias(libs.plugins.protobuf)
}

application {
    mainClass.set("dev.picasso.mimic.cli.MainKt")
    // Windows 콘솔 코드페이지에서 한글 기동 거부 메시지가 깨진다.
    // §10.2에서 그것이 유일한 산출물이다.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// 프로파일 주도 에뮬레이터. 거동은 프로파일에서 오고 코드는 해석기다(§10.1).
dependencies {
    // 보고자가 계약 메시지를 protobuf JSON으로 적고 보낸다. 적재 표면이
    // 같은 규약으로 읽으므로 여기서 다른 규약을 쓰면 밀어 넣는 날 갈린다.
    implementation(libs.protobuf.java.util)

    // §3.5의 MQTT 발행. **추상은 이 라이브러리를 모른다** —
    // `Publisher`가 인터페이스이고 구현 하나가 이것을 쓴다.
    implementation(libs.paho.mqttv5)
    implementation(project(":profile-model"))
    implementation(project(":contracts"))
    implementation(libs.jackson.databind)
    implementation(libs.jsonschema.validator)

    // jsonschema-validator가 slf4j-api를 끌고 온다. 구현이 없으면 경고 세 줄이
    // 출력 앞에 붙는데, mimic은 곧 CLI가 되고 기동 거부 메시지가 유일한
    // 산출물이 된다(§10.2).
    runtimeOnly(libs.slf4j.nop)

    // **implementation이다.** 제어 채널이 루프백에만 바인딩하려면 주소를
    // 지정해야 하고, 그것은 NettyServerBuilder뿐이라 컴파일 시점에 필요하다.
    implementation(libs.grpc.netty.shaded)

    // grpc-java 생성 코드가 @javax.annotation.Generated를 단다. JDK 11부터
    // javax.annotation이 JDK에 없어 이것 없이는 생성 코드가 컴파일되지 않는다.
    compileOnly(libs.tomcat.annotations)

    testImplementation(kotlin("test"))

    // 시험이 in-process 전송으로 표면을 실제로 지난다. 포트를 열지 않으므로
    // CI에서 흔들리지 않으면서 직렬화·스텁·StreamObserver를 전부 지난다.
    testImplementation(libs.grpc.inprocess)
}

tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/schema"),
        // 재생 버퍼 크기가 기종마다 다른 것을 보는 시험이 읽는다. 없으면
        // 프로파일을 고쳐도 :mimic:test가 UP-TO-DATE로 넘어가 낡은 채 초록이다.
        rootProject.file("profile/profiles"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${rootProject.libs.versions.protobuf.get()}" }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${rootProject.libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { id("grpc") }

            // contracts와 같은 이유로 main만이다 — all()은 test에도 걸린다.
            if (sourceSet.name != "main") return@configureEach

            // **디스크립터를 만들지 않는다.** 제어 채널은 계약이 아니므로
            // contract_digest에 들어가면 안 된다(§10.5). 그 값은 모든 헤더에
            // 실리므로 섞이면 소비자가 계약이 바뀐 줄 안다.
            generateDescriptorSet = false
        }
    }
}
