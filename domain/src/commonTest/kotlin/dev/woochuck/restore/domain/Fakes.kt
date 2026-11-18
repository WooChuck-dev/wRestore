package dev.woochuck.restore.domain

class InMemoryRepo : SnapshotRepository {
    val rows = mutableListOf<Snapshot>()
    val audit = mutableListOf<AuditRecord>()
    var failInsert = false
    var failPrune = false

    override fun insert(snapshot: Snapshot): Result<Unit> {
        if (failInsert) return Result.failure(Exception("disk full"))
        if (rows.any { it.id == snapshot.id }) return Result.failure(Exception("duplicate id"))
        rows += snapshot
        return Result.success(Unit)
    }

    override fun get(id: String) = Result.success(rows.firstOrNull { it.id == id })

    override fun listDeaths(playerUuid: String, offset: Int, limit: Int) = Result.success(
        rows.filter { it.kind == SnapshotKind.DEATH && it.playerUuid == playerUuid }.asReversed().drop(offset).take(limit)
            .map { SnapshotSummary(it.id, it.playerName, it.createdAtEpochMs, it.world, it.keepInventory, it.deathCause, it.items.size) },
    )

    override fun countDeaths(playerUuid: String) =
        Result.success(rows.count { it.kind == SnapshotKind.DEATH && it.playerUuid == playerUuid })

    override fun playerUuidsWithDeaths() =
        Result.success(rows.filter { it.kind == SnapshotKind.DEATH }.map { it.playerUuid }.distinct())

    override fun playerUuidByName(name: String) =
        Result.success(rows.lastOrNull { it.playerName.equals(name, ignoreCase = true) }?.playerUuid)

    override fun idsWithPrefix(prefix: String, limit: Int) =
        Result.success(rows.map { it.id }.filter { it.startsWith(prefix) }.take(limit))

    override fun pruneDeaths(playerUuid: String, keep: Int): Result<Int> {
        if (failPrune) return Result.failure(Exception("locked"))
        val deaths = rows.filter { it.kind == SnapshotKind.DEATH && it.playerUuid == playerUuid }
        val drop = deaths.dropLast(keep)
        rows.removeAll(drop.toSet())
        return Result.success(drop.size)
    }

    override fun insertAudit(record: AuditRecord): Result<Unit> { audit += record; return Result.success(Unit) }
}

var seq = 0

fun snap(
    player: String = "p1",
    kind: SnapshotKind = SnapshotKind.DEATH,
    items: List<ItemRecord> = emptyList(),
    keepInventory: Boolean? = false,
    xp: XpState = XpState(0, 0f, 0),
) = Snapshot(
    id = "s${seq++}", kind = kind, playerUuid = player, playerName = "Name", createdAtEpochMs = 1_000L + seq, world = "overworld",
    x = 1.5, y = 64.0, z = -3.25, deathCause = "fell", keepInventory = keepInventory, xp = xp, items = items,
)

fun item(area: SlotArea, slot: Int, key: String = "minecraft:diamond_sword", count: Int = 1, vararg comps: ComponentValue) =
    ItemRecord(area, slot, key, count, comps.toList())
