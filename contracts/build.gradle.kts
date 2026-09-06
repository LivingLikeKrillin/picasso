import com.google.protobuf.gradle.id
import java.security.MessageDigest

plugins {
    alias(libs.plugins.protobuf)
}

// 계약의 semver. §5.5가 헤더에 싣는 contract_semver다.
// 1단계에서 ParameterDeclaration의 존재 방식을 바꾼 것이 유일한 파괴적
// 변경이었고 그때 소비자가 0이었다. 0.1.0에서 시작한다.
val contractSemver = "0.1.0"

// 이 모듈은 proto 파일과 거기서 나온 것만 담는다.
// 프로젝트 내 의존이 0이어야 한다 — 게이트 검사 5번이 이를 강제한다.
// 외부 라이브러리 의존은 그 규칙의 대상이 아니다.
dependencies {
    // 여기에 project(...) 의존을 추가하면 안 된다.
    api(libs.protobuf.java)

    // 서비스 스텁도 계약의 일부다 — §3.5가 전송 경계를 계약으로 정했다.
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)

    // grpc-java 생성 코드가 @javax.annotation.Generated를 단다. JDK 11부터
    // javax.annotation이 JDK에 없어 이것 없이는 **생성 코드가 컴파일되지 않는다.**
    // 런타임에는 필요 없다.
    compileOnly(libs.tomcat.annotations)

    testImplementation(kotlin("test"))
    testImplementation(libs.grpc.inprocess)
}

// grpc-protobuf가 protobuf-java 3.25.x를 끌고 온다. protoc 4.28.3이 생성한
// 코드는 런타임 4.28 이상을 요구하므로(RuntimeVersion.validateProtobufGencodeVersion),
// 낮은 쪽이 이기면 컴파일이 아니라 **런타임에** 터진다. 기본 해소 규칙이
// 높은 쪽을 고르지만 그것은 추측이다. 못박는다.
configurations.configureEach {
    resolutionStrategy.force("com.google.protobuf:protobuf-java:${libs.versions.protobuf.get()}")
}

sourceSets {
    main {
        proto { srcDir("proto") }
    }
}

// 코드 생성은 buf가 아니라 여기서 한다. 이유는 buf.gen.yaml 주석에 있다.
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { id("grpc") }

            // **main 소스셋만이다.** all()은 test에도 걸리고 두 태스크가 같은
            // 경로에 쓰면 generateTestProto가 main의 디스크립터를 덮는다.
            // test proto는 비어 있으므로 그러면 계약과 무관한 다이제스트가
            // 헤더에 실린다 — Gradle이 암묵 의존으로 잡아 준 실측이다.
            if (sourceSet.name != "main") return@configureEach

            // 헤더의 contract_digest가 이 파일의 SHA-256이다.
            //
            // §5.5는 buf 모듈 다이제스트라고 했으나 buf는 Docker 래퍼이고
            // 게이트는 CI가 buf build를 먼저 돌리는 **순서**에 기대고 있다.
            // 런타임 헤더까지 그 순서에 매달면 mimic이 Docker 없이 기동하지
            // 못한다. 목적(계약의 신원)은 같고 계산 방법만 다르다(§15).
            //
            // includeSourceInfo를 켜면 **주석 한 줄에 다이제스트가 변하고**
            // 전 소비자가 CONTRACT_REVISION_MISMATCH 경보를 받는다.
            generateDescriptorSet = true
            descriptorSetOptions.includeSourceInfo = false
            descriptorSetOptions.includeImports = true
            descriptorSetOptions.path =
                "${layout.buildDirectory.get().asFile}/contract-descriptor/picasso.desc"
        }
    }
}

// 계약 신원을 리소스로 굽는다. mimic이 클래스패스에서 읽는다.
val contractIdentityDir = layout.buildDirectory.dir("generated/contract-identity")

val contractIdentity = tasks.register("contractIdentity") {
    val descriptor = layout.buildDirectory.file("contract-descriptor/picasso.desc")
    val outDir = contractIdentityDir
    val semver = contractSemver

    dependsOn("generateProto")
    inputs.file(descriptor)
    inputs.property("semver", semver)
    outputs.dir(outDir)

    doLast {
        val bytes = descriptor.get().asFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b) }

        val file = outDir.get().file("picasso-contract.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("semver=$semver\ndigest=$digest\n")
    }
}

sourceSets {
    main {
        resources { srcDir(contractIdentity) }
    }
}
