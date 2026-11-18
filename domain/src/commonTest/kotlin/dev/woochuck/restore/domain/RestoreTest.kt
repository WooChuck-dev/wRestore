package dev.woochuck.restore.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeGateway : PlayerGateway {
    var online = true
    var mayRestore = true
    var failCapture = false
    var failApplyOn: Set<String> = emptySet() // snapshot ids whose apply fails
    val applied = mutableListOf<String>()
    var current = snap(player = "target", kind = SnapshotKind.RECOVERY, items = listOf(item(SlotArea.MAIN, 0, "minecraft:dirt", 3)))

    override fun isOnline(playerUuid: String) = online
    override fun adminMayRestore() = mayRestore
    override fun captureCurrent(playerUuid: String) = if (failCapture) Result.failure(Exception("capture boom")) else Result.success(current)
    override fun apply(playerUuid: String, snapshot: Snapshot): Result<Unit> {
        applied += snapshot.id
        return if (snapshot.id in failApplyOn) Result.failure(Exception("apply boom ${snapshot.id}")) else Result.success(Unit)
    }
}

class RestoreServiceTest {
    private val repo = InMemoryRepo()
    private val gw = FakeGateway()
    private var now = 10_000L
    private var n = 0
    private val warnings = mutableListOf<String>()
    private val svc = RestoreService(repo, gw, ConfirmationStore(60_000), { "id${n++}" }, { now }, { warnings += it })
    private val death = snap(player = "target", items = listOf(item(SlotArea.MAIN, 1, "minecraft:diamond", 5))).also { repo.insert(it) }

    private fun results() = repo.audit.map { it.action to it.result }

    @Test fun happyPathCreatesRecoveryPointThenAppliesAndAudits() {
        assertIs<RestoreOutcome.Requested>(svc.request("admin", "Admin", death.id).outcome)
        val r = svc.confirm("admin", "Admin")
        val ok = assertIs<RestoreOutcome.Success>(r.outcome)
        assertEquals(listOf(death.id), gw.applied)
        val rp = repo.rows.single { it.kind == SnapshotKind.RECOVERY }
        assertEquals(ok.recoveryPointId, rp.id); assertEquals(death.id, rp.relatedSnapshotId)
        assertEquals(listOf("APPLY_REQUEST" to "REQUESTED", "APPLY" to "SUCCESS"), results())
        assertEquals("Admin", repo.audit.last().adminName); assertEquals("target", repo.audit.last().targetUuid)
    }

    @Test fun offlineTargetIsRejectedAtRequest() {
        gw.online = false
        assertIs<RestoreOutcome.TargetOffline>(svc.request("admin", "Admin", death.id).outcome)
        assertIs<RestoreOutcome.NothingPending>(svc.confirm("admin", "Admin").outcome)
        assertTrue(gw.applied.isEmpty())
    }

    @Test fun logoutBetweenRequestAndConfirmAbortsWithoutChanges() {
        svc.request("admin", "Admin", death.id)
        gw.online = false
        assertIs<RestoreOutcome.TargetOffline>(svc.confirm("admin", "Admin").outcome)
        assertTrue(gw.applied.isEmpty()); assertTrue(repo.rows.none { it.kind == SnapshotKind.RECOVERY })
    }

    @Test fun expiredConfirmationIsRejectedAndConsumed() {
        svc.request("admin", "Admin", death.id)
        now += 60_001
        assertIs<RestoreOutcome.Expired>(svc.confirm("admin", "Admin").outcome)
        now -= 60_001
        assertIs<RestoreOutcome.NothingPending>(svc.confirm("admin", "Admin").outcome)
        assertTrue(gw.applied.isEmpty())
    }

    @Test fun confirmationIsSingleUse() {
        svc.request("admin", "Admin", death.id)
        assertIs<RestoreOutcome.Success>(svc.confirm("admin", "Admin").outcome)
        assertIs<RestoreOutcome.NothingPending>(svc.confirm("admin", "Admin").outcome)
        assertEquals(1, gw.applied.size)
    }

    @Test fun confirmationIsBoundToTheRequestingAdmin() {
        svc.request("admin", "Admin", death.id)
        assertIs<RestoreOutcome.NothingPending>(svc.confirm("other", "Other").outcome)
        assertTrue(gw.applied.isEmpty())
        assertIs<RestoreOutcome.Success>(svc.confirm("admin", "Admin").outcome)
    }

    @Test fun newRequestReplacesThePreviousOne() {
        val second = snap(player = "target").also { repo.insert(it) }
        svc.request("admin", "Admin", death.id); svc.request("admin", "Admin", second.id)
        svc.confirm("admin", "Admin")
        assertEquals(listOf(second.id), gw.applied)
    }

    @Test fun cancelDiscardsPending() {
        svc.request("admin", "Admin", death.id)
        assertIs<RestoreOutcome.Aborted>(svc.cancel("admin", "Admin").outcome)
        assertIs<RestoreOutcome.NothingPending>(svc.confirm("admin", "Admin").outcome)
        assertIs<RestoreOutcome.NothingPending>(svc.cancel("admin", "Admin").outcome)
    }

    @Test fun unauthorizedAdminIsDeniedAtRequestAndAtConfirm() {
        gw.mayRestore = false
        assertIs<RestoreOutcome.Denied>(svc.request("admin", "Admin", death.id).outcome)
        gw.mayRestore = true
        svc.request("admin", "Admin", death.id)
        gw.mayRestore = false // permission revoked in between
        assertIs<RestoreOutcome.Denied>(svc.confirm("admin", "Admin").outcome)
        assertTrue(gw.applied.isEmpty())
        assertTrue(results().any { it.second == "DENIED" })
    }

    @Test fun failedRecoveryPointAbortsBeforeTouchingThePlayer() {
        svc.request("admin", "Admin", death.id)
        repo.failInsert = true
        val r = svc.confirm("admin", "Admin").outcome
        assertTrue(r is RestoreOutcome.Aborted && "recovery point" in r.reason)
        assertTrue(gw.applied.isEmpty())
    }

    @Test fun failedCaptureAbortsBeforeTouchingThePlayer() {
        svc.request("admin", "Admin", death.id); gw.failCapture = true
        assertIs<RestoreOutcome.Aborted>(svc.confirm("admin", "Admin").outcome)
        assertTrue(gw.applied.isEmpty())
    }

    @Test fun partialFailureRollsBackToTheRecoveryPointAndReportsIt() {
        svc.request("admin", "Admin", death.id)
        gw.failApplyOn = setOf(death.id)
        val r = assertIs<RestoreOutcome.FailedRolledBack>(svc.confirm("admin", "Admin").outcome)
        assertEquals(2, gw.applied.size); assertEquals(r.recoveryPointId, gw.applied.last())
        assertEquals("FAILED_ROLLED_BACK", repo.audit.last().result)
    }

    @Test fun failedRollbackIsReportedExplicitly() {
        svc.request("admin", "Admin", death.id)
        val failing = object : PlayerGateway by gw {
            override fun apply(playerUuid: String, snapshot: Snapshot) = Result.failure<Unit>(Exception("nope ${snapshot.kind}"))
        }
        val s = RestoreService(repo, failing, ConfirmationStore(60_000), { "x${n++}" }, { now }, { })
        s.request("admin", "Admin", death.id)
        val r = assertIs<RestoreOutcome.FailedRollbackFailed>(s.confirm("admin", "Admin").outcome)
        assertTrue("DEATH" in r.error && "RECOVERY" in r.rollbackError)
        assertEquals("FAILED_ROLLBACK_FAILED", repo.audit.last().result)
    }

    @Test fun unknownSnapshotIsRejected() {
        assertIs<RestoreOutcome.SnapshotNotFound>(svc.request("admin", "Admin", "missing").outcome)
    }

    @Test fun auditWriteFailureIsSurfaced() {
        val bad = object : SnapshotRepository by repo {
            override fun insertAudit(record: AuditRecord) = Result.failure<Unit>(Exception("audit disk"))
        }
        val s = RestoreService(bad, gw, ConfirmationStore(), { "y${n++}" }, { now }, { warnings += it })
        val r = s.request("admin", "Admin", death.id)
        assertEquals("audit disk", r.auditError); assertEquals(1, warnings.size)
    }
}
