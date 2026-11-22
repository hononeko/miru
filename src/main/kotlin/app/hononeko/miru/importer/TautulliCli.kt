package app.hononeko.miru.importer

import app.hononeko.miru.config.AppConfigLoader
import app.hononeko.miru.persistence.DatabaseFactory
import app.hononeko.miru.persistence.repository.ExposedMediaServerRepository
import app.hononeko.miru.persistence.repository.ExposedPlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.ExposedUserRepository
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Path

/**
 * CLI entrypoint for importing historical data (`miru import tautulli --file <path> [--dry-run] [--server-id <id>]`).
 */
object TautulliCli {
    private const val USAGE =
        "Usage: import tautulli --file <path> [--dry-run] [--server-id <id>]"

    private data class ParsedCliArgs(
        val filePath: String,
        val dryRun: Boolean,
        val serverId: String?,
    )

    fun run(
        args: List<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
        env: Map<String, String> = System.getenv(),
        hocon: ApplicationConfig? = defaultHoconConfig(),
    ): Int {
        if (args.isEmpty()) {
            err.println(USAGE)
            return 1
        }
        val subcommand = args.first()
        if (subcommand in setOf("--help", "-h", "help")) {
            out.println(USAGE)
            return 0
        }
        if (subcommand != "tautulli") {
            err.println("Unsupported import source '$subcommand'. Expected 'tautulli'.")
            err.println(USAGE)
            return 1
        }

        val parsed = parseArgs(args.drop(1), err) ?: return 1
        if (parsed.filePath.isEmpty()) {
            out.println(USAGE)
            return 0
        }

        val config =
            AppConfigLoader
                .loadDatabaseAndSessionConfig(env = env, hocon = hocon)
                .fold(
                    ifLeft = { error ->
                        err.println("Configuration error: ${error.message}")
                        error.fieldErrors.forEach { (field, detail) ->
                            err.println("  - $field: $detail")
                        }
                        return 1
                    },
                    ifRight = { it },
                )

        val dbHandle =
            DatabaseFactory
                .createAndMigrate(config.database)
                .fold(
                    ifLeft = { error ->
                        err.println("Database initialization failed: ${error.message}")
                        return 1
                    },
                    ifRight = { it },
                )

        return dbHandle.use { handle ->
            val fallbackBaseUrl =
                env["PLEX_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
                    ?: "http://localhost:32400"
            val importer =
                TautulliImporterService(
                    mediaServerRepository = ExposedMediaServerRepository(handle),
                    userRepository = ExposedUserRepository(handle),
                    playbackHistoryRepository = ExposedPlaybackHistoryRepository(handle),
                    fallbackBaseUrl = fallbackBaseUrl,
                    defaultWatchedThresholdPercent = config.session.watchedThresholdPercent,
                )

            val reportResult =
                runBlocking {
                    importer.import(
                        file = Path.of(parsed.filePath),
                        options =
                            TautulliImportOptions(
                                dryRun = parsed.dryRun,
                                serverId = parsed.serverId,
                                watchedThresholdPercent = config.session.watchedThresholdPercent,
                            ),
                    )
                }

            reportResult.fold(
                ifLeft = { error ->
                    err.println("Tautulli import failed: ${error.message}")
                    1
                },
                ifRight = { report ->
                    printSummary(report, out)
                    0
                },
            )
        }
    }

    private fun parseArgs(
        args: List<String>,
        err: PrintStream,
    ): ParsedCliArgs? {
        var filePath: String? = null
        var dryRun = false
        var serverId: String? = null
        var index = 0

        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--help" || arg == "-h" -> return ParsedCliArgs(filePath = "", dryRun = false, serverId = null)
                arg == "--dry-run" -> dryRun = true
                arg == "-f" || arg == "--file" -> {
                    val value = args.getOrNull(index + 1)
                    if (value.isNullOrBlank()) {
                        err.println("Option '$arg' requires a file path.")
                        return null
                    }
                    filePath = value
                    index++
                }
                arg.startsWith("--file=") -> {
                    val value = arg.removePrefix("--file=").trim()
                    if (value.isEmpty()) {
                        err.println("Option '--file=' requires a non-empty file path.")
                        return null
                    }
                    filePath = value
                }
                arg == "--server-id" -> {
                    val value = args.getOrNull(index + 1)
                    if (value.isNullOrBlank()) {
                        err.println("Option '--server-id' requires a server ID.")
                        return null
                    }
                    serverId = value.trim()
                    index++
                }
                arg.startsWith("--server-id=") -> {
                    val value = arg.removePrefix("--server-id=").trim()
                    if (value.isEmpty()) {
                        err.println("Option '--server-id=' requires a non-empty server ID.")
                        return null
                    }
                    serverId = value
                }
                else -> {
                    err.println("Unknown option '$arg'.")
                    err.println(USAGE)
                    return null
                }
            }
            index++
        }

        if (filePath.isNullOrBlank()) {
            err.println("Missing required --file <path> option.")
            err.println(USAGE)
            return null
        }

        return ParsedCliArgs(filePath = filePath, dryRun = dryRun, serverId = serverId)
    }

    private fun printSummary(
        report: TautulliImportReport,
        out: PrintStream,
    ) {
        val modeLabel = if (report.dryRun) "DRY-RUN" else "IMPORT"
        out.println("Tautulli $modeLabel complete:")
        out.println("  serverId:                ${report.serverId}")
        out.println("  usersDiscovered:         ${report.usersDiscovered}")
        out.println("  usersUpserted:           ${report.usersUpserted}")
        out.println("  historyRowsRead:         ${report.historyRowsRead}")
        out.println("  historyImported:         ${report.historyImported}")
        out.println("  historySkippedDuplicate: ${report.historySkippedDuplicate}")
        out.println("  malformedRows:           ${report.malformedRows}")
        if (report.warnings.isNotEmpty()) {
            out.println("  warnings (${report.warnings.size}):")
            report.warnings.forEach { warning ->
                out.println("    - $warning")
            }
        }
    }

    private fun defaultHoconConfig(): ApplicationConfig? =
        runCatching { ApplicationConfig("application.conf") }.getOrNull()
}
