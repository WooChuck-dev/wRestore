package dev.woochuck.restore.domain

sealed interface SaveOutcome {
    /** Snapshot is durably stored. [pruneError] is set when retention cleanup failed (recoverable, logged). */
    data class Saved(val id: String, val pruned: Int, val pruneError: String?) : SaveOutcome
    data class Failed(val error: String) : SaveOutcome
}

/** Records death snapshots and applies retention. Never prunes before the new snapshot is saved. */
class SnapshotService(
    private val repo: SnapshotRepository,
    private val maxSnapshotsPerPlayer: Int?, // null = invalid config -> no pruning
    private val log: Log,
) {
    fun recordDeath(snapshot: Snapshot): SaveOutcome {
        require(snapshot.kind == SnapshotKind.DEATH) { "recordDeath needs a DEATH snapshot" }
        repo.insert(snapshot).onFailure { return SaveOutcome.Failed(it.message ?: it.toString()) }
        val limit = maxSnapshotsPerPlayer ?: return SaveOutcome.Saved(snapshot.id, 0, null)
        val pruned = repo.pruneDeaths(snapshot.playerUuid, limit)
        pruned.exceptionOrNull()?.let {
            log.warn("pruning failed for ${snapshot.playerUuid}: ${it.message}")
            return SaveOutcome.Saved(snapshot.id, 0, it.message ?: it.toString())
        }
        return SaveOutcome.Saved(snapshot.id, pruned.getOrThrow(), null)
    }

    /** Startup cleanup after the limit was lowered. Skipped when config was invalid. */
    fun pruneAllAfterLimitChange(): Int {
        val limit = maxSnapshotsPerPlayer ?: return 0
        val players = repo.playerUuidsWithDeaths().getOrElse { log.warn("startup pruning skipped: ${it.message}"); return 0 }
        var total = 0
        for (u in players) {
            repo.pruneDeaths(u, limit).onSuccess { total += it }.onFailure { log.warn("pruning failed for $u: ${it.message}") }
        }
        return total
    }
}
