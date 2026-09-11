plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    // grpc-protobuf가 protobuf-java 3.25.x를 끌고 온다. protoc 4.28.3이 생성한
    // 코드는 런타임 4.28 이상을 요구하므로(RuntimeVersion.validateProtobufGencodeVersion)
    // 낮은 쪽이 이기면 컴파일이 아니라 **런타임에** 터진다.
    //
    // **모듈마다 건다.** contracts에만 걸면 mimic·gate는 자기 클래스패스를
    // 따로 해소하고, 거기서는 "높은 쪽이 이긴다"는 Gradle의 기본 규칙에
    // 기대게 된다. 기본 규칙은 계약이 아니다.
    configurations.configureEach {
        resolutionStrategy.force(
            "com.google.protobuf:protobuf-java:${rootProject.libs.versions.protobuf.get()}",
        )
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)

        // ★**언어 판을 적는다.** 안 적으면 `KotlinVersion.DEFAULT` 를 따르고, 그것은
        // **플러그인 판을 올릴 때 함께 움직인다** — 2.0.21 → 2.4.20 에서 실제로 2.0 → 2.4 로
        // 옮겨 갔고 아무것도 안 깨진 것은 운이 좋았다는 뜻이지 안전하다는 뜻이 아니다(§15.138).
        //
        // **고정이 변화를 막는 장치는 아니다.** 다음에 플러그인을 올리면 `KotlinLanguageVersionTest`
        // 가 빨개지고, 이 줄을 함께 고쳐야 한다 — 즉 언어 판이 바뀌는 것이 **diff 에 보이는 결정**이
        // 된다. 도장·해시가 문서에 대해 하는 일과 같다.
        compilerOptions {
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        }
    }

    dependencies {
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
