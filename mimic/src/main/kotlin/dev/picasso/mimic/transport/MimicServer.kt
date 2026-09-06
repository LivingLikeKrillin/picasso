package dev.picasso.mimic.transport

import io.grpc.Server
import io.grpc.ServerBuilder

/**
 * 계약 표면을 세운다.
 *
 * **`ServerBuilder`를 인자로 받는다.** in-process(시험)와 Netty(CLI)가 같은
 * 코드로 서야 한다 — 시험이 세우는 것과 CLI가 세우는 것이 다르면 시험이
 * 표면을 증명하지 못한다.
 */
class MimicServer(registry: RobotRegistry, builder: ServerBuilder<*>) {

    private val server: Server = builder
        .addService(SkillServiceImpl(registry))
        .addService(TaskServiceImpl(registry))
        .build()

    val port: Int get() = server.port

    fun start(): MimicServer = apply { server.start() }

    fun shutdown() {
        server.shutdownNow()
    }

    /** CLI가 프로세스를 살려 두는 방법. 없으면 기동하자마자 종료한다. */
    fun awaitTermination() {
        server.awaitTermination()
    }
}
