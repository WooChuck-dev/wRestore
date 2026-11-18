package dev.woochuck.restore.domain

/** A restore waiting for `/restore confirm`. Bound to one admin, one snapshot and one target; single-use and time-limited. */
data class PendingRestore(
    val adminId: String,
    val adminName: String,
    val snapshotId: String,
    val targetUuid: String,
    val targetName: String,
    val expiresAtEpochMs: Long,
    val snapshotAtEpochMs: Long = 0,
    val itemCount: Int = 0,
)

class ConfirmationStore(private val ttlMs: Long = 60_000) {
    private val pending = HashMap<String, PendingRestore>()

    /** Replaces any earlier pending restore of the same admin. */
    fun create(
        adminId: String, adminName: String, snapshotId: String, targetUuid: String, targetName: String, now: Long,
        snapshotAtEpochMs: Long = 0, itemCount: Int = 0,
    ): PendingRestore =
        PendingRestore(adminId, adminName, snapshotId, targetUuid, targetName, now + ttlMs, snapshotAtEpochMs, itemCount).also { pending[adminId] = it }

    /** Removes and returns the admin's pending restore (single-use, even when expired). */
    fun take(adminId: String): PendingRestore? = pending.remove(adminId)

    fun cancel(adminId: String): Boolean = pending.remove(adminId) != null
}

/** What the restore service needs from the running server. Implemented by the Pumpkin adapter. */
interface PlayerGateway {
    fun isOnline(playerUuid: String): Boolean

    /** Fresh permission check for the acting admin (re-evaluated at confirmation time). */
    fun adminMayRestore(): Boolean

    /** Current inventory+XP of an online player as a RECOVERY snapshot (id/time/related id are filled in by the service). */
    fun captureCurrent(playerUuid: String): Result<Snapshot>

    /** Replaces the player's inventory and XP with [snapshot] and verifies the result. May fail partway: not transactional. */
    fun apply(playerUuid: String, snapshot: Snapshot): Result<Unit>
}

sealed interface RestoreOutcome {
    data class Requested(val pending: PendingRestore) : RestoreOutcome
    data object Denied : RestoreOutcome
    data object NothingPending : RestoreOutcome
    data object Expired : RestoreOutcome
    data object SnapshotNotFound : RestoreOutcome
    data class TargetOffline(val name: String) : RestoreOutcome

    /** Aborted before any player state was changed. */
    data class Aborted(val reason: String) : RestoreOutcome
    data class Success(val recoveryPointId: String) : RestoreOutcome

    /** Restore failed partway; the recovery point was applied back successfully. */
    data class FailedRolledBack(val error: String, val recoveryPointId: String) : RestoreOutcome

    /** Restore failed partway AND the rollback failed: the player's state is unknown. */
    data class FailedRollbackFailed(val error: String, val rollbackError: String, val recoveryPointId: String) : RestoreOutcome
}

data class RestoreReport(val outcome: RestoreOutcome, val auditError: String?)

class RestoreService(
    private val repo: SnapshotRepository,
    private val gateway: PlayerGateway,
    private val store: ConfirmationStore,
    private val ids: IdGenerator,
    private val clock: () -> Long,
    private val log: Log,
) {
    /** Step 1: validate and create a pending confirmation. Changes nothing on the player. */
    fun request(adminId: String, adminName: String, snapshotId: String): RestoreReport {
        val now = clock()
        if (!gateway.adminMayRestore()) return finish(adminId, adminName, "?", snapshotId, "APPLY_REQUEST", RestoreOutcome.Denied)
        val snap = repo.get(snapshotId).getOrElse {
            return finish(adminId, adminName, "?", snapshotId, "APPLY_REQUEST", RestoreOutcome.Aborted("cannot read snapshot: ${it.message}"))
        } ?: return finish(adminId, adminName, "?", snapshotId, "APPLY_REQUEST", RestoreOutcome.SnapshotNotFound)
        if (!gateway.isOnline(snap.playerUuid)) {
            return finish(adminId, adminName, snap.playerUuid, snapshotId, "APPLY_REQUEST", RestoreOutcome.TargetOffline(snap.playerName))
        }
        val p = store.create(adminId, adminName, snap.id, snap.playerUuid, snap.playerName, now, snap.createdAtEpochMs, snap.items.size)
        return finish(adminId, adminName, snap.playerUuid, snapshotId, "APPLY_REQUEST", RestoreOutcome.Requested(p))
    }

    fun cancel(adminId: String, adminName: String): RestoreReport {
        val p = store.take(adminId)
        return finish(adminId, adminName, p?.targetUuid ?: "?", p?.snapshotId, "APPLY_CANCEL", if (p == null) RestoreOutcome.NothingPending else RestoreOutcome.Aborted("cancelled"))
    }

    /** Step 2: re-check everything, create a persisted recovery point, apply, verify, roll back on failure. */
    fun confirm(adminId: String, adminName: String): RestoreReport {
        val now = clock()
        val p = store.take(adminId) ?: return finish(adminId, adminName, "?", null, "APPLY", RestoreOutcome.NothingPending)
        fun done(o: RestoreOutcome) = finish(adminId, adminName, p.targetUuid, p.snapshotId, "APPLY", o)
        if (now > p.expiresAtEpochMs) return done(RestoreOutcome.Expired)
        if (!gateway.adminMayRestore()) return done(RestoreOutcome.Denied)
        if (!gateway.isOnline(p.targetUuid)) return done(RestoreOutcome.TargetOffline(p.targetName))
        val snap = repo.get(p.snapshotId).getOrElse { return done(RestoreOutcome.Aborted("cannot read snapshot: ${it.message}")) }
            ?: return done(RestoreOutcome.SnapshotNotFound)
        if (snap.playerUuid != p.targetUuid) return done(RestoreOutcome.Aborted("snapshot no longer belongs to the confirmed target"))

        val current = gateway.captureCurrent(p.targetUuid).getOrElse { return done(RestoreOutcome.Aborted("cannot capture current state: ${it.message}")) }
        val recovery = current.copy(id = ids.next(), kind = SnapshotKind.RECOVERY, createdAtEpochMs = now, relatedSnapshotId = p.snapshotId)
        // No recovery point on disk => no change to the player.
        repo.insert(recovery).onFailure { return done(RestoreOutcome.Aborted("recovery point not saved: ${it.message}")) }

        val applied = gateway.apply(p.targetUuid, snap)
        if (applied.isSuccess) return done(RestoreOutcome.Success(recovery.id))
        val error = applied.exceptionOrNull()?.message ?: "unknown error"
        val rollback = gateway.apply(p.targetUuid, recovery)
        return done(
            if (rollback.isSuccess) RestoreOutcome.FailedRolledBack(error, recovery.id)
            else RestoreOutcome.FailedRollbackFailed(error, rollback.exceptionOrNull()?.message ?: "unknown error", recovery.id),
        )
    }

    private fun finish(adminId: String, adminName: String, target: String, snapshotId: String?, action: String, o: RestoreOutcome): RestoreReport {
        val (result, error) = describe(o)
        val rec = AuditRecord(ids.next(), clock(), adminId, adminName, target, snapshotId, action, result, error)
        val auditError = repo.insertAudit(rec).exceptionOrNull()?.message
        auditError?.let { log.warn("audit write failed ($action $result): $it") }
        return RestoreReport(o, auditError)
    }

    private fun describe(o: RestoreOutcome): Pair<String, String?> = when (o) {
        is RestoreOutcome.Requested -> "REQUESTED" to null
        RestoreOutcome.Denied -> "DENIED" to null
        RestoreOutcome.NothingPending -> "NOTHING_PENDING" to null
        RestoreOutcome.Expired -> "EXPIRED" to null
        RestoreOutcome.SnapshotNotFound -> "SNAPSHOT_NOT_FOUND" to null
        is RestoreOutcome.TargetOffline -> "TARGET_OFFLINE" to null
        is RestoreOutcome.Aborted -> "ABORTED" to o.reason
        is RestoreOutcome.Success -> "SUCCESS" to null
        is RestoreOutcome.FailedRolledBack -> "FAILED_ROLLED_BACK" to o.error
        is RestoreOutcome.FailedRollbackFailed -> "FAILED_ROLLBACK_FAILED" to "${o.error}; rollback: ${o.rollbackError}"
    }
}
