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
    }

    dependencies {
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
