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
class MimicServer(private val registry: RobotRegistry, builder: ServerBuilder<*>) {

    private val taskService = TaskServiceImpl(registry)

    private val server: Server = builder
        .addService(SkillServiceImpl(registry))
        .addService(taskService)
        .addService(EventServiceImpl(registry))
        .build()

    /**
     * 시간을 흘린다 — 시계를 전진시키고, 그것이 만든 전이를 반영하고,
     * 열려 있는 스트림에 민다.
     *
     * **셋이 한 함수인 것이 요점이다.** 시계 참조만 밖으로 내주면 호출자가
     * 전진만 시키고 `tick()`을 부르지 않아 아무 일도 안 일어나거나, 부르더라도
     * 밀어내기를 빠뜨려 열린 스트림이 멈춘다 — 그러면 결함이 시험 실패가
     * 아니라 **정지**로 나타난다.
     *
     * **Chunk 6의 `AdvanceClock`(§10.5)도 여기로 내려와야 한다.** 제어 채널이
     * 자기 전진 경로를 따로 만들면 시험과 운영이 서로 다른 코드로 시간을
     * 흘리게 되고, `harness`를 다시 설계하게 된다.
     */
    fun advance(duration: java.time.Duration) {
        registry.clocks.forEach { it.advance(duration) }
        taskService.settleAll()
    }

    /** 시계를 건드리지 않고 전이만 반영해 민다. */
    fun settle() = taskService.settleAll()

    /**
     * §10.5의 `Step`. 한 기체를 한 칸 돌린다.
     *
     * **제어 채널도 이 문으로 지난다** — [advance]와 같은 이유다. 따로
     * 만들면 시험과 운영이 서로 다른 코드로 상태를 움직이게 되고, 밀어내기를
     * 빠뜨리면 열린 스트림이 멈춘다.
     */
    fun step(hosted: RobotRegistry.Hosted): Int = taskService.step(hosted)

    /**
     * 시간을 안 흘리고 이미 생긴 전이만 민다. `ForceFault`가 쓴다 —
     * 정착시키면 `CANCELLING` 창이 닫히고, 안 밀면 열린 스트림이 그 전이를
     * 통째로 놓친다.
     */
    fun push(hosted: RobotRegistry.Hosted) = taskService.push(hosted)

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
