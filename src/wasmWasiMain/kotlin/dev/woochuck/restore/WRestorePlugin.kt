package dev.woochuck.restore

import dev.woochuck.restore.domain.ConfirmationStore
import dev.woochuck.restore.domain.DEFAULT_CONFIG_TOML
import dev.woochuck.restore.domain.Log
import dev.woochuck.restore.domain.SaveOutcome
import dev.woochuck.restore.domain.SnapshotService
import dev.woochuck.restore.domain.SqlSnapshotRepository
import dev.woochuck.restore.domain.StorageConfig
import plugin.PluginMetadata
import plugin.PluginContext
import plugin.PumpkinPlugin
import pumpkin.Command
import pumpkin.Event
import pumpkin.Logging
import pumpkin.Permission
import pumpkin.Server

class WRestorePlugin : PumpkinPlugin() {
    private var maxSnapshots: Int? = null // null = invalid config: never prune
    private val commands = RestoreCommands(ConfirmationStore(60_000))

    override fun metadata() = PluginMetadata(
        name = "wRestore",
        version = "0.1.0-beta.1",
        authors = listOf("WooChuck"),
        description = "Death inventory snapshots and admin restore",
        dependencies = listOf(),
        permissions = listOf("fs.read.data", "fs.write.data"),
    )

    private fun info(m: String) = Logging.log(Logging.Level.INFO, m)
    private fun warn(m: String) = Logging.log(Logging.Level.WARN, m)
    private fun error(m: String) = Logging.log(Logging.Level.ERROR, m)

    override fun onLoad(context: PluginContext): Result<Unit> {
        info("wRestore loading")
        val config = loadConfig()
        maxSnapshots = config.maxSnapshotsPerPlayer
        config.error?.let { warn("config.toml invalid ($it): retention pruning is DISABLED until fixed and the plugin restarted") }

        // Everything below runs in one storage batch: collect log lines, print them afterwards (see WasiFs KDoc).
        val lines = mutableListOf<String>()
        val result = Storage.use(":memory:") { sql -> // NEVER the real database: the self-test writes and sets user_version
            val failed = runRepositorySelfTest(sql)
            lines += if (failed.isEmpty()) "repository self-test: all checks passed" else "repository self-test FAILED: $failed"
            null
        }
        result.onFailure { lines += "self-test could not run: ${it.message}" }
        val startup = Storage.use(Storage.DB_PATH) { sql ->
            val repo = SqlSnapshotRepository.open(sql).getOrThrow()
            val pruned = SnapshotService(repo, maxSnapshots, Log { lines += "WARN $it" }).pruneAllAfterLimitChange()
            lines += "database ready (${Storage.DB_PATH}), startup pruned=$pruned"
        }
        startup.onFailure { lines += "WARN database unavailable: ${it.message}: death snapshots will FAIL until fixed" }
        lines.forEach { if (it.startsWith("WARN") || it.contains("FAILED")) warn(it) else info(it) }

        context.registerEvent(DEATH_HANDLER_ID, Event.EventType.PLAYER_DEATH_EVENT, Event.EventPriority.NORMAL, true)
        context.registerPermission(
            Permission.Permission(PERMISSION_NODE, "Inspect and restore player death snapshots", Permission.PermissionDefault.Op(Permission.PermissionLevel.THREE), emptyList()),
        ).onFailure { warn("could not register permission $PERMISSION_NODE: ${it.message}") }
        context.registerCommand(RestoreCommands.build(), PERMISSION_NODE)
        info("wRestore loaded")
        return Result.success(Unit)
    }

    /** Reads `config.toml` from the data folder, creating the default file when missing. Invalid config disables pruning. */
    private fun loadConfig(): StorageConfig = try {
        val text = WasiFs.batch {
            val dir = WasiFs.findDataDir() ?: return@batch null
            val (bytes, err) = WasiFs.read(dir, "config.toml")
            when {
                err != null -> null
                bytes != null -> bytes.decodeToString()
                else -> { WasiFs.write(dir, "config.toml", DEFAULT_CONFIG_TOML.encodeToByteArray()); DEFAULT_CONFIG_TOML }
            }
        }
        if (text == null) StorageConfig(null, "cannot read config.toml") else StorageConfig.parse(text)
    } catch (t: Throwable) {
        StorageConfig(null, "config error: ${t.message}")
    }

    /**
     * Captures a snapshot on every player death. The event fires before Pumpkin clears the inventory
     * (living.rs broadcast_death_message -> Player::handle_killed). Never throws and never modifies the event.
     */
    override fun handleEvent(eventId: UInt, server: Server.Server, event: Event.Event): Event.Event {
        val death = (event as? Event.Event.PlayerDeathEvent)?.value ?: return event
        try {
            val (id, now) = WasiFs.batch { WasiPlatform.randomUuid() to WasiPlatform.nowEpochMs() }
            val snapshot = Capture.death(death.player, id, now)
            val lines = mutableListOf<String>()
            val outcome = Storage.use(Storage.DB_PATH) { sql ->
                val repo = SqlSnapshotRepository.open(sql).getOrThrow()
                SnapshotService(repo, maxSnapshots, Log { lines += it }).recordDeath(snapshot)
            }
            lines.forEach { warn(it) }
            when (val o = outcome.getOrNull()) {
                is SaveOutcome.Saved -> {
                    info("death snapshot saved id=${o.id} player=${snapshot.playerName} items=${snapshot.items.size} pruned=${o.pruned}")
                    o.pruneError?.let { warn("retention cleanup failed (snapshot is saved): $it") }
                }
                is SaveOutcome.Failed -> error("death snapshot NOT saved for ${snapshot.playerName}: ${o.error}")
                null -> error("death snapshot NOT saved for ${snapshot.playerName}: ${outcome.exceptionOrNull()?.message}")
            }
        } catch (t: Throwable) {
            error("death snapshot NOT saved: ${t.message}")
        }
        return event
    }

    override fun handleCommand(commandId: UInt, sender: Command.CommandSender, server: Server.Server, args: Command.ConsumedArgs): Result<Int> =
        Result.success(commands.handle(commandId, sender, server, args))

    override fun onUnload(context: PluginContext): Result<Unit> {
        info("wRestore unloaded")
        return Result.success(Unit)
    }

    private companion object { const val DEATH_HANDLER_ID = 1u }
}
