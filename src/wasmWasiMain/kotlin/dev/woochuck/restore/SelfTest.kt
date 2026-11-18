package dev.woochuck.restore

import dev.woochuck.restore.domain.AuditRecord
import dev.woochuck.restore.domain.ComponentValue
import dev.woochuck.restore.domain.ItemRecord
import dev.woochuck.restore.domain.Log
import dev.woochuck.restore.domain.SaveOutcome
import dev.woochuck.restore.domain.SlotArea
import dev.woochuck.restore.domain.Snapshot
import dev.woochuck.restore.domain.SnapshotKind
import dev.woochuck.restore.domain.SnapshotService
import dev.woochuck.restore.domain.SqlExecutor
import dev.woochuck.restore.domain.SqlSnapshotRepository
import dev.woochuck.restore.domain.XpState

/**
 * Live check of [SqlSnapshotRepository] against the real SQLite component, on an in-memory database (touches no files).
 * Runs at every plugin load; returns the list of failed checks (empty = all passed). Call inside [WasiFs.batch].
 */
fun runRepositorySelfTest(sql: SqlExecutor): List<String> {
    val failed = mutableListOf<String>()
    fun check(name: String, ok: Boolean) { if (!ok) failed += name }
    val repo = SqlSnapshotRepository.open(sql).getOrElse { return listOf("open: ${it.message}") }

    val tricky = ComponentValue("custom-name", ByteArray(300) { (it * 7).toByte() })
    val items = listOf(
        ItemRecord(SlotArea.MAIN, 3, "minecraft:diamond_sword", 1, listOf(tricky)),
        ItemRecord(SlotArea.ARMOR, 2, "minecraft:iron_leggings", 1, emptyList()),
        ItemRecord(SlotArea.OFFHAND, 0, "minecraft:shield", 1, emptyList()),
    )
    fun snap(n: Int, kind: SnapshotKind = SnapshotKind.DEATH, player: String = "u1") = Snapshot(
        id = "t$n", kind = kind, playerUuid = player, playerName = "O'Brien \"x\"\tz", createdAtEpochMs = 1_700_000_000_000L + n,
        world = "overworld", x = 1.0000000001, y = -64.5, z = 123456.789, deathCause = null, keepInventory = n % 2 == 0,
        xp = XpState(30, 0.37f, 1507), items = if (n == 1) items else emptyList(), relatedSnapshotId = if (kind == SnapshotKind.RECOVERY) "t1" else null,
    )

    for (n in 1..5) check("insert$n", repo.insert(snap(n)).isSuccess)
    check("duplicate id rejected", repo.insert(snap(1)).isFailure)
    check("insert recovery", repo.insert(snap(6, SnapshotKind.RECOVERY)).isSuccess)
    check("insert other player", repo.insert(snap(7, player = "u2")).isSuccess)

    check("roundtrip", repo.get("t1").getOrNull() == snap(1))
    check("get missing", repo.get("nope").getOrNull() == null)
    val page = repo.listDeaths("u1", 0, 2).getOrNull()
    check("list newest first", page?.map { it.id } == listOf("t5", "t4"))
    check("list offset", repo.listDeaths("u1", 4, 10).getOrNull()?.map { it.id } == listOf("t1"))
    check("count", repo.countDeaths("u1").getOrNull() == 5)
    check("players", repo.playerUuidsWithDeaths().getOrNull()?.sorted() == listOf("u1", "u2"))

    check("prune count", repo.pruneDeaths("u1", 2).getOrNull() == 3)
    check("prune kept newest", repo.listDeaths("u1", 0, 10).getOrNull()?.map { it.id } == listOf("t5", "t4"))
    check("prune keeps recovery", repo.get("t6").getOrNull() != null)
    check("prune keeps other player", repo.countDeaths("u2").getOrNull() == 1)
    check("prune is idempotent", repo.pruneDeaths("u1", 2).getOrNull() == 0)

    check("audit", repo.insertAudit(AuditRecord("a1", 1L, "adm", "Admin", "u1", "t5", "APPLY", "SUCCESS", null)).isSuccess)

    // service on top: limit 1 keeps only the newest
    val warns = mutableListOf<String>()
    val out = SnapshotService(repo, 1, Log { warns += it }).recordDeath(snap(8))
    check("service saved+pruned", out is SaveOutcome.Saved && out.pruned == 2 && repo.countDeaths("u1").getOrNull() == 1)

    check("newer schema refused", sql.exec("PRAGMA user_version = 99").isSuccess && SqlSnapshotRepository.open(sql).isFailure)
    return failed
}
