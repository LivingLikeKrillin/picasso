package dev.picasso.client.cli

import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.profile.RequirementSet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * ```
 * client --target <host:port> --robot <id> --requirements <file.json>
 *        --skill <type> [--param k=v ...] [--identity-override <client_id>]
 * ```
 *
 * **요구 집합은 파일이다**(§5.4). 기종을 식별해 분기하지 않는다.
 *
 * `mimic`의 CLI와 같은 이유로 `run(args): Int`를 노출한다 — `exitProcess`를
 * 안에 두면 종료 코드를 시험할 때 시험 JVM이 죽는다.
 */
class ClientCli {

    class Options(
        val target: String,
        val robotId: String,
        val requirements: RequirementSet,
        val skillType: String,
        val parameters: List<ParameterValue>,
        val identityOverride: String?,
    )

    /** 인자 해석만 한다. 붙어서 무엇을 할지는 호출자가 정한다. */
    fun parse(args: Array<String>, err: Appendable): Options? {
        var target: String? = null
        var robotId: String? = null
        var requirementsPath: Path? = null
        var skillType: String? = null
        var identityOverride: String? = null
        val parameters = mutableListOf<ParameterValue>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val value = args.getOrNull(i + 1)
            if (arg.startsWith("--") && value == null) return usage(err, "$arg 에 값이 없다")
            when (arg) {
                "--target" -> { target = value; i += 2 }
                "--robot" -> { robotId = value; i += 2 }
                "--requirements" -> { requirementsPath = Path.of(value!!); i += 2 }
                "--skill" -> { skillType = value; i += 2 }
                "--identity-override" -> { identityOverride = value; i += 2 }
                "--param" -> {
                    // **문자열로만 싣는다.** 타입을 인자에서 추론하면 "10"이
                    // INTEGER인지 STRING인지 CLI가 정하게 되고, 그것은
                    // 프로파일이 선언할 일이다(§10.4 ③).
                    val parts = value!!.split("=", limit = 2)
                    if (parts.size != 2 || parts[0].isBlank()) {
                        return usage(err, "--param 은 k=v 형식이다: '$value'")
                    }
                    parameters += ParameterValue.newBuilder()
                        .setKey(parts[0]).setStringValue(parts[1]).build()
                    i += 2
                }
                else -> return usage(err, "모르는 인자다: '$arg'")
            }
        }

        if (target.isNullOrBlank()) return usage(err, "--target 이 없다")
        if (robotId.isNullOrBlank()) return usage(err, "--robot 이 없다")
        if (skillType.isNullOrBlank()) return usage(err, "--skill 이 없다")
        val path = requirementsPath ?: return usage(err, "--requirements 가 없다")

        val requirements = runCatching {
            RequirementSet.parse(path.toString(), Files.readString(path).replace("\r\n", "\n"))
        }.getOrElse {
            // 요구 집합이 틀렸으면 붙기 전에 죽는다 — 협상에서 거절당하는
            // 것과 설정 파일이 깨진 것은 운영자에게 다른 사건이다.
            err.appendLine("요구 집합을 읽을 수 없다: ${it.message}")
            return null
        }

        return Options(target, robotId, requirements, skillType, parameters, identityOverride)
    }

    private fun usage(err: Appendable, message: String): Options? {
        err.appendLine(message)
        err.appendLine(
            "사용법: client --target <host:port> --robot <id> --requirements <file.json> " +
                "--skill <type> [--param k=v ...] [--identity-override <client_id>]",
        )
        return null
    }

    companion object {
        /** 거절이 왔을 때의 종료 코드. 통과(0)·인자 오류(2)와 구분한다. */
        const val REJECTED = 3
    }
}

fun main(args: Array<String>) {
    val err = System.err
    val options = ClientCli().parse(args, err) ?: exitProcess(2)

    val channel = io.grpc.ManagedChannelBuilder.forTarget(options.target).usePlaintext().build()
    try {
        val client = dev.picasso.client.PicassoClient(
            channel, options.requirements.clientId, options.identityOverride,
        )
        val negotiated = client.negotiate(options.robotId, options.requirements)
        if (!negotiated.accepted) {
            // **거절을 삼키지 않는다.** 사유가 유일한 산출물이다(§5.4).
            negotiated.rejectionsList.forEach {
                err.println("협상 거절: ${it.code} — ${it.detail}")
            }
            exitProcess(ClientCli.REJECTED)
        }

        val started = client.start(
            options.robotId, "cli-task", 1, options.skillType, options.parameters,
        )
        if (started.hasRejection()) {
            val rejection = started.rejection
            err.println("태스크 거절: ${rejection.code} — ${rejection.detail}")
            if (rejection.code == RejectionCode.REJECTION_CODE_UNSPECIFIED) exitProcess(1)
            exitProcess(ClientCli.REJECTED)
        }
        println("접수됨: ${started.handle.taskId}@${started.handle.revision}")
    } finally {
        channel.shutdownNow()
    }
}
