package dev.woochuck.restore

import dev.woochuck.restore.domain.AuditRecord
import dev.woochuck.restore.domain.ConfirmationStore
import dev.woochuck.restore.domain.IdGenerator
import dev.woochuck.restore.domain.Listing
import dev.woochuck.restore.domain.PlayerGateway
import dev.woochuck.restore.domain.Refs
import dev.woochuck.restore.domain.RestoreOutcome
import dev.woochuck.restore.domain.RestoreService
import dev.woochuck.restore.domain.Snapshot
import dev.woochuck.restore.domain.SnapshotKind
import dev.woochuck.restore.domain.SnapshotRepository
import dev.woochuck.restore.domain.SnapshotSummary
import dev.woochuck.restore.domain.SqlSnapshotRepository
import pumpkin.Command
import pumpkin.Logging
import pumpkin.Server
import pumpkin.Text
import kotlin.time.Instant

// Must start with the plugin name exactly as in metadata() ("wRestore:"): the host rejects other namespaces.
const val PERMISSION_NODE = "wRestore:command.restore"
private const val PAGE_SIZE = 10

/** Repository that opens the database per call, so no host call ever happens inside a storage batch. */
object PluginRepo : SnapshotRepository {
    private fun <T> db(f: SqlSnapshotRepository.() -> Result<T>): Result<T> =
        Storage.use(Storage.DB_PATH) { sql -> SqlSnapshotRepository.open(sql).fold({ it.f() }, { Result.failure(it) }) }
            .fold({ it }, { Result.failure(it) })

    override fun insert(snapshot: Snapshot) = db { insert(snapshot) }
    override fun get(id: String) = db { get(id) }
    override fun listDeaths(playerUuid: String, offset: Int, limit: Int) = db { listDeaths(playerUuid, offset, limit) }
    override fun countDeaths(playerUuid: String) = db { countDeaths(playerUuid) }
    override fun playerUuidsWithDeaths() = db { playerUuidsWithDeaths() }
    override fun playerUuidByName(name: String) = db { playerUuidByName(name) }
    override fun idsWithPrefix(prefix: String, limit: Int) = db { idsWithPrefix(prefix, limit) }
    override fun pruneDeaths(playerUuid: String, keep: Int) = db { pruneDeaths(playerUuid, keep) }
    override fun insertAudit(record: AuditRecord) = db { insertAudit(record) }
}

/** Pumpkin implementation of the domain's view of the server, for one command execution. */
class PumpkinGateway(private val server: Server.Server, private val sender: Command.CommandSender) : PlayerGateway {
    private fun player(uuid: String) = Capture.parseUuid(uuid)?.let { server.getPlayerByUuid(it) }

    override fun isOnline(playerUuid: String) = player(playerUuid) != null
    override fun adminMayRestore() = sender.hasPermission(server, PERMISSION_NODE)

    override fun captureCurrent(playerUuid: String): Result<Snapshot> = runCatching {
        Capture.snapshot(player(playerUuid) ?: error("player offline"), SnapshotKind.RECOVERY, "pending", 0L)
    }

    override fun apply(playerUuid: String, snapshot: Snapshot): Result<Unit> = runCatching {
        Capture.apply(player(playerUuid) ?: error("player offline"), snapshot)
    }
}

class RestoreCommands(private val store: ConfirmationStore) {
    companion object {
        const val HISTORY = 10u; const val HISTORY_PAGE = 11u; const val INSPECT = 12u
        const val APPLY = 13u; const val CONFIRM = 14u; const val CANCEL = 15u; const val USAGE = 16u

        fun build(): Command.Command {
            fun lit(n: String, id: UInt) = Command.CommandNode.literal(n).also { it.executeWithHandlerId(id) }
            fun word(n: String, id: UInt? = null) =
                Command.CommandNode.argument(n, Command.ArgumentType.String(Command.StringType.SINGLE_WORD)).also { if (id != null) it.executeWithHandlerId(id) }
            // Nodes are consumed by `then`, so each is configured before being attached.
            val page = Command.CommandNode.argument("page", Command.ArgumentType.Integer(1 to null)).also { it.executeWithHandlerId(HISTORY_PAGE) }
            val player = word("player", HISTORY).also { it.then(page) }
            val history = Command.CommandNode.literal("history").also { it.then(player) }
            val inspect = Command.CommandNode.literal("inspect").also { it.then(word("id", INSPECT)) }
            val apply = Command.CommandNode.literal("apply").also { it.then(word("id", APPLY)) }
            return Command.Command(listOf("restore"), "Inspect and restore player death snapshots").also {
                it.executeWithHandlerId(USAGE)
                it.then(history); it.then(inspect); it.then(apply); it.then(lit("confirm", CONFIRM)); it.then(lit("cancel", CANCEL))
            }
        }
    }

    /** Per admin: what their last `/restore history` showed, so `2`, `#2` and `latest` have a meaning. */
    private val listings = HashMap<String, Listing>()

    private val ids = IdGenerator { WasiFs.batch { WasiPlatform.randomUuid() } }
    private val clock = { WasiFs.batch { WasiPlatform.nowEpochMs() } }

    private fun say(s: Command.CommandSender, msg: String) = s.sendMessage(Text.TextComponent.text(msg))
    private fun arg(args: Command.ConsumedArgs, key: String): String? = (args.getValue(key) as? Command.Arg.Simple)?.value

    private fun adminId(s: Command.CommandSender): String = s.asPlayer()?.let { Capture.uuidString(it.getId()) } ?: "console:${s.getName()}"

    /** Returns the exit code Pumpkin expects (1 = success). Never throws: errors go to the sender. */
    fun handle(id: UInt, sender: Command.CommandSender, server: Server.Server, args: Command.ConsumedArgs): Int = try {
        if (!sender.hasPermission(server, PERMISSION_NODE)) { say(sender, "You do not have permission to use /restore."); 0 }
        else when (id) {
            HISTORY, HISTORY_PAGE -> history(sender, server, arg(args, "player") ?: "", if (id == HISTORY_PAGE) integer(args, "page") else 1)
            INSPECT -> resolve(sender, args)?.let { inspect(sender, it) } ?: 0
            APPLY -> resolve(sender, args)?.let { apply(sender, server, it) } ?: 0
            CONFIRM -> confirm(sender, server)
            CANCEL -> cancel(sender, server)
            USAGE -> { listOf("/restore history <player> [page]", "/restore inspect <#row|latest|id-prefix>", "/restore apply <#row|latest|id-prefix>  (then /restore confirm or /restore cancel)").forEach { say(sender, it) }; 1 }
            else -> { say(sender, "Unknown /restore subcommand."); 0 }
        }
    } catch (t: Throwable) {
        say(sender, "wRestore error: ${t.message}"); 0
    }

    private fun integer(args: Command.ConsumedArgs, key: String): Int =
        ((args.getValue(key) as? Command.Arg.Num)?.value?.getOrNull() as? Command.Number.Int32)?.value ?: 1

    /** Turns `#2`, `2`, `latest` or an id prefix into a full snapshot id, or tells the sender why not. */
    private fun resolve(sender: Command.CommandSender, args: Command.ConsumedArgs): String? =
        Refs.resolve(arg(args, "id") ?: "", listings[adminId(sender)], PluginRepo).getOrElse { say(sender, it.message ?: "Unknown snapshot reference."); null }

    private fun service(sender: Command.CommandSender, server: Server.Server) =
        RestoreService(PluginRepo, PumpkinGateway(server, sender), store, ids, clock) { Logging.log(Logging.Level.WARN, it) }

    private fun resolvePlayer(server: Server.Server, name: String): Pair<String, String>? {
        Capture.parseUuid(name)?.let { return name.lowercase() to name }
        PluginRepo.playerUuidByName(name).getOrNull()?.let { return it to name }
        return server.getPlayerByName(name)?.let { Capture.uuidString(it.getId()) to it.getName() }
    }

    private fun fmt(ms: Long) = Instant.fromEpochMilliseconds(ms).toString() // UTC, ISO-8601

    private fun history(sender: Command.CommandSender, server: Server.Server, who: String, page: Int): Int {
        val (uuid, label) = resolvePlayer(server, who) ?: run { say(sender, "No snapshots or online player named '$who'."); return 0 }
        val total = PluginRepo.countDeaths(uuid).getOrElse { say(sender, "Storage error: ${it.message}"); return 0 }
        if (total == 0) { say(sender, "No death snapshots for $label."); return 1 }
        val pages = (total + PAGE_SIZE - 1) / PAGE_SIZE
        val p = page.coerceIn(1, pages)
        val rows = PluginRepo.listDeaths(uuid, (p - 1) * PAGE_SIZE, PAGE_SIZE).getOrElse { say(sender, "Storage error: ${it.message}"); return 0 }
        say(sender, "Death snapshots of $label (page $p/$pages, $total total, newest first):")
        val first = (p - 1) * PAGE_SIZE + 1
        listings[adminId(sender)] = Listing(uuid, rows.mapIndexed { i, r -> first + i to r.id }.toMap())
        rows.forEachIndexed { i, r -> say(sender, line(first + i, r)) }
        say(sender, "Use /restore inspect <#row> or /restore apply <#row> (also: latest, or the first characters of the id).")
        return 1
    }

    private fun line(n: Int, r: SnapshotSummary) =
        "#$n  ${Refs.short(r.id)}  ${fmt(r.createdAtEpochMs)}  ${r.world}  items=${r.itemCount}  keepInventory=${r.keepInventory ?: "?"}"

    private fun inspect(sender: Command.CommandSender, id: String): Int {
        val s = PluginRepo.get(id).getOrElse { say(sender, "Storage error: ${it.message}"); return 0 }
            ?: run { say(sender, "No snapshot with id '$id'."); return 0 }
        say(sender, "${s.kind} ${Refs.short(s.id)} (${s.id}) of ${s.playerName}")
        say(sender, "at ${fmt(s.createdAtEpochMs)} in ${s.world} (${s.x.toInt()}, ${s.y.toInt()}, ${s.z.toInt()}) keepInventory=${s.keepInventory ?: "?"} schema=${s.schemaVersion}")
        say(sender, "XP: level ${s.xp.level}, ${s.xp.points} points in level, progress ${s.xp.progress}")
        s.relatedSnapshotId?.let { say(sender, "Recovery point for snapshot $it") }
        if (s.items.isEmpty()) say(sender, "Inventory was empty.")
        s.items.forEach { say(sender, "${it.area.name.lowercase()}[${it.slot}] ${it.registryKey} x${it.count}" + if (it.components.isEmpty()) "" else " (${it.components.size} components)") }
        return 1
    }

    private fun apply(sender: Command.CommandSender, server: Server.Server, id: String): Int {
        val r = service(sender, server).request(adminId(sender), sender.getName(), id)
        say(sender, outcomeText(r.outcome)); r.auditError?.let { say(sender, "WARNING: audit record could not be saved: $it") }
        return if (r.outcome is RestoreOutcome.Requested) 1 else 0
    }

    private fun confirm(sender: Command.CommandSender, server: Server.Server): Int {
        val r = service(sender, server).confirm(adminId(sender), sender.getName())
        say(sender, outcomeText(r.outcome)); r.auditError?.let { say(sender, "WARNING: audit record could not be saved: $it") }
        return if (r.outcome is RestoreOutcome.Success) 1 else 0
    }

    private fun cancel(sender: Command.CommandSender, server: Server.Server): Int {
        val r = service(sender, server).cancel(adminId(sender), sender.getName())
        say(sender, if (r.outcome is RestoreOutcome.NothingPending) "Nothing to cancel." else "Restore cancelled.")
        return 1
    }

    private fun outcomeText(o: RestoreOutcome): String = when (o) {
        is RestoreOutcome.Requested -> "Restore snapshot ${Refs.short(o.pending.snapshotId)} (${fmt(o.pending.snapshotAtEpochMs)}, ${o.pending.itemCount} items) to ${o.pending.targetName}? This REPLACES their inventory and XP " +
            "(a recovery point is saved first). Run /restore confirm within 60 s, or /restore cancel."
        RestoreOutcome.Denied -> "You do not have permission to restore snapshots."
        RestoreOutcome.NothingPending -> "No restore is waiting for confirmation (run /restore apply <id> first)."
        RestoreOutcome.Expired -> "The confirmation expired. Nothing was changed. Run /restore apply <id> again."
        RestoreOutcome.SnapshotNotFound -> "No such snapshot."
        is RestoreOutcome.TargetOffline -> "${o.name} is offline. Only online players can be restored. Nothing was changed."
        is RestoreOutcome.Aborted -> "Aborted, nothing was changed: ${o.reason}"
        is RestoreOutcome.Success -> "Restore complete and verified. Recovery point of the previous state: ${Refs.short(o.recoveryPointId)} (use /restore inspect ${Refs.short(o.recoveryPointId)})"
        is RestoreOutcome.FailedRolledBack -> "Restore FAILED (${o.error}). The previous state was put back from recovery point ${Refs.short(o.recoveryPointId)}."
        is RestoreOutcome.FailedRollbackFailed -> "Restore FAILED (${o.error}) AND the rollback FAILED (${o.rollbackError}). " +
            "The player's state is unknown. Recovery point: ${Refs.short(o.recoveryPointId)}. Check them manually."
    }
}
