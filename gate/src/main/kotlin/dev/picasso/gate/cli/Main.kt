package dev.picasso.gate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.picasso.gate.GateChecks
import dev.picasso.gate.ProfileDirectories
import dev.picasso.gate.GateRunner
import dev.picasso.gate.Resource
import dev.picasso.gate.buf.ProcessBufRunner
import java.nio.file.Path

class GateCommand : CliktCommand(name = "picasso-gate") {

    override fun help(context: Context) =
        "계약과 프로파일이 어긋나면 막는다. 건너뛴 검사는 통과가 아니며 출력에 남는다."

    private val repo by option("--repo", help = "저장소 루트")
        .path(mustExist = true).default(Path.of("."))

    /**
     * **여러 번 줄 수 있다.** §7.4가 픽스처와 실제 기종 프로파일을 다른
     * 디렉터리에 두라고 하는데, 하나만 보면 나머지가 검사 밖에 놓인다.
     */
    private val profileDirs by option("--profiles", help = "프로파일 문서 디렉터리. 여러 번 줄 수 있다")
        .path().multiple(default = ProfileDirectories.ALL.map(Path::of))

    private val schema by option("--schema").path().default(
        Path.of("profile/schema/capability-profile.schema.json"),
    )

    private val descriptor by option("--descriptor").path().default(
        Path.of("contracts/build/descriptor.binpb"),
    )

    private val baselineDirs by option(
        "--baseline-dir",
        help = "기준선 프로파일 문서 디렉터리. 호출 지점이 git에서 꺼내 놓는다. 여러 번 줄 수 있다",
    ).path().multiple()

    private val contractBaseline by option(
        "--contract-baseline",
        help = "buf breaking --against 에 그대로 넘어간다. contracts/ 기준 경로다",
    )

    private val bufCommand by option(
        "--buf",
        help = "buf 실행기. 절대 경로여야 한다. 여러 번 주면 인자로 이어진다",
    ).multiple()

    private val required by option(
        "--require",
        help = "반드시 있어야 하는 자원. 없으면 건너뜀이 아니라 실패다",
    ).convert { spec ->
        spec.split(",").map { Resource.valueOf(it.trim().uppercase()) }.toSet()
    }.default(GateChecks.REQUIRED_IN_CI)

    override fun run() {
        // toAbsolutePath()는 정규화하지 않는다. 순서를 뒤집으면 ".."가
        // 경로에 남아 소견 location에 그대로 노출된다.
        val root = repo.toAbsolutePath().normalize()

        val input = InputCollector(root).collect(
            profileDirs = profileDirs.map(root::resolve),
            schemaFile = root.resolve(schema),
            descriptorFile = root.resolve(descriptor),
            baselineDirs = baselineDirs.map(root::resolve),
            contractBaseline = contractBaseline,
            buf = bufCommand.takeIf { it.isNotEmpty() }?.let { ProcessBufRunner(it) },
        )

        val report = GateRunner(GateChecks.all(), required).run(input)
        echo(report.render())
        throw ProgramResult(report.exitCode)
    }
}

fun main(args: Array<String>) = GateCommand().main(args)
