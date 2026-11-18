package dev.woochuck.restore.domain

/** Text-only SQL access matching the `wrestore:sqlite/db` component (rows: '\n', columns: '\t', NULL = "\N"). */
interface SqlExecutor {
    fun exec(sql: String): Result<Unit>
    fun query(sql: String): Result<String>
}

/** Decodes the component's text row encoding. */
fun parseRows(text: String): List<List<String?>> {
    if (text.isEmpty()) return emptyList()
    return text.removeSuffix("\n").split('\n').map { line -> line.split('\t').map(::unescapeCell) }
}

private fun unescapeCell(c: String): String? {
    if (c == "\\N") return null
    if (!c.contains('\\')) return c
    val sb = StringBuilder(); var i = 0
    while (i < c.length) {
        val ch = c[i]
        if (ch == '\\' && i + 1 < c.length) {
            when (c[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); '\\' -> sb.append('\\'); else -> sb.append(ch).append(c[i + 1]) }
            i += 2
        } else { sb.append(ch); i++ }
    }
    return sb.toString()
}

internal fun sqlText(s: String?): String {
    if (s == null) return "NULL"
    require('\u0000' !in s) { "NUL character not allowed in stored text" }
    return "'" + s.replace("'", "''") + "'"
}

private fun sqlInt(v: Long?): String = v?.toString() ?: "NULL"

class SqlSnapshotRepository private constructor(private val sql: SqlExecutor) : SnapshotRepository {

    companion object {
        const val DB_VERSION = 1

        /** Opens the schema: creates it on a fresh database, refuses a database written by a newer plugin. */
        fun open(sql: SqlExecutor): Result<SqlSnapshotRepository> = runCatching {
            val ver = sql.query("PRAGMA user_version").getOrThrow().trim().toIntOrNull() ?: error("cannot read user_version")
            if (ver > DB_VERSION) error("database schema version $ver is newer than supported $DB_VERSION")
            if (ver == 0) {
                sql.exec(
                    "BEGIN; " +
                        "CREATE TABLE IF NOT EXISTS snapshots(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, " +
                        "player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, created_at INTEGER NOT NULL, world TEXT NOT NULL, " +
                        "x_bits INTEGER NOT NULL, y_bits INTEGER NOT NULL, z_bits INTEGER NOT NULL, death_cause TEXT, keep_inventory INTEGER, " +
                        "xp_level INTEGER NOT NULL, xp_progress_bits INTEGER NOT NULL, xp_total INTEGER NOT NULL, related_id TEXT, " +
                        "schema_version INTEGER NOT NULL, item_count INTEGER NOT NULL, payload_hex TEXT NOT NULL); " +
                        "CREATE INDEX IF NOT EXISTS idx_snap_player ON snapshots(player_uuid, kind, seq); " +
                        "CREATE TABLE IF NOT EXISTS audit(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, at INTEGER NOT NULL, " +
                        "admin_uuid TEXT NOT NULL, admin_name TEXT NOT NULL, target_uuid TEXT NOT NULL, snapshot_id TEXT, action TEXT NOT NULL, " +
                        "result TEXT NOT NULL, error TEXT); " +
                        "PRAGMA user_version = $DB_VERSION; COMMIT",
                ).onFailure { sql.exec("ROLLBACK") }.getOrThrow() // a failed multi-statement exec leaves the transaction open
            }
            SqlSnapshotRepository(sql)
        }
    }

    override fun insert(snapshot: Snapshot): Result<Unit> = runCatching {
        require(snapshot.x.isFinite() && snapshot.y.isFinite() && snapshot.z.isFinite()) { "non-finite position" }
        val payload = SnapshotCodec.toHex(SnapshotCodec.encodeItems(snapshot.items))
        sql.exec(
            "INSERT INTO snapshots(id,kind,player_uuid,player_name,created_at,world,x_bits,y_bits,z_bits,death_cause,keep_inventory," +
                "xp_level,xp_progress_bits,xp_total,related_id,schema_version,item_count,payload_hex) VALUES(" +
                "${sqlText(snapshot.id)},${sqlText(snapshot.kind.name)},${sqlText(snapshot.playerUuid)},${sqlText(snapshot.playerName)}," +
                "${snapshot.createdAtEpochMs},${sqlText(snapshot.world)},${snapshot.x.toRawBits()},${snapshot.y.toRawBits()},${snapshot.z.toRawBits()}," +
                "${sqlText(snapshot.deathCause)},${sqlInt(snapshot.keepInventory?.let { if (it) 1L else 0L })}," +
                "${snapshot.xp.level},${snapshot.xp.progress.toRawBits()},${snapshot.xp.points},${sqlText(snapshot.relatedSnapshotId)}," +
                "${snapshot.schemaVersion},${snapshot.items.size},'$payload')",
        ).getOrThrow()
    }

    override fun get(id: String): Result<Snapshot?> = runCatching {
        val rows = parseRows(
            sql.query(
                "SELECT id,kind,player_uuid,player_name,created_at,world,x_bits,y_bits,z_bits,death_cause,keep_inventory,xp_level," +
                    "xp_progress_bits,xp_total,related_id,schema_version,payload_hex FROM snapshots WHERE id=${sqlText(id)}",
            ).getOrThrow(),
        )
        val r = rows.firstOrNull() ?: return@runCatching null
        val version = r[15]!!.toInt()
        if (version > SCHEMA_VERSION) throw UnsupportedSchemaException(version)
        Snapshot(
            id = r[0]!!, kind = SnapshotKind.valueOf(r[1]!!), playerUuid = r[2]!!, playerName = r[3]!!,
            createdAtEpochMs = r[4]!!.toLong(), world = r[5]!!,
            x = Double.fromBits(r[6]!!.toLong()), y = Double.fromBits(r[7]!!.toLong()), z = Double.fromBits(r[8]!!.toLong()),
            deathCause = r[9], keepInventory = r[10]?.let { it == "1" },
            xp = XpState(r[11]!!.toInt(), Float.fromBits(r[12]!!.toInt()), r[13]!!.toInt()),
            items = SnapshotCodec.decodeItems(SnapshotCodec.fromHex(r[16]!!).getOrThrow()).getOrThrow(),
            relatedSnapshotId = r[14], schemaVersion = version,
        )
    }

    override fun listDeaths(playerUuid: String, offset: Int, limit: Int): Result<List<SnapshotSummary>> = runCatching {
        require(offset >= 0 && limit in 1..1000) { "bad paging" }
        parseRows(
            sql.query(
                "SELECT id,player_name,created_at,world,keep_inventory,death_cause,item_count FROM snapshots " +
                    "WHERE kind='DEATH' AND player_uuid=${sqlText(playerUuid)} ORDER BY seq DESC LIMIT $limit OFFSET $offset",
            ).getOrThrow(),
        ).map { SnapshotSummary(it[0]!!, it[1]!!, it[2]!!.toLong(), it[3]!!, it[4]?.let { v -> v == "1" }, it[5], it[6]!!.toInt()) }
    }

    override fun countDeaths(playerUuid: String): Result<Int> = runCatching {
        sql.query("SELECT COUNT(*) FROM snapshots WHERE kind='DEATH' AND player_uuid=${sqlText(playerUuid)}").getOrThrow().trim().toInt()
    }

    override fun playerUuidsWithDeaths(): Result<List<String>> = runCatching {
        parseRows(sql.query("SELECT DISTINCT player_uuid FROM snapshots WHERE kind='DEATH'").getOrThrow()).map { it[0]!! }
    }

    override fun playerUuidByName(name: String): Result<String?> = runCatching {
        parseRows(sql.query("SELECT player_uuid FROM snapshots WHERE lower(player_name)=lower(${sqlText(name)}) ORDER BY seq DESC LIMIT 1").getOrThrow())
            .firstOrNull()?.get(0)
    }

    override fun idsWithPrefix(prefix: String, limit: Int): Result<List<String>> = runCatching {
        require(prefix.isNotEmpty() && prefix.all { it in '0'..'9' || it in 'a'..'f' || it == '-' }) { "bad id prefix" }
        parseRows(sql.query("SELECT id FROM snapshots WHERE id LIKE '$prefix%' ORDER BY seq DESC LIMIT $limit").getOrThrow()).map { it[0]!! }
    }

    override fun pruneDeaths(playerUuid: String, keep: Int): Result<Int> = runCatching {
        require(keep >= 1) { "keep must be >= 1" }
        val p = sqlText(playerUuid)
        // One statement: delete everything except the newest `keep` DEATH snapshots of this player.
        sql.exec(
            "DELETE FROM snapshots WHERE kind='DEATH' AND player_uuid=$p AND seq NOT IN " +
                "(SELECT seq FROM snapshots WHERE kind='DEATH' AND player_uuid=$p ORDER BY seq DESC LIMIT $keep)",
        ).getOrThrow()
        sql.query("SELECT changes()").getOrThrow().trim().toInt()
    }

    override fun insertAudit(record: AuditRecord): Result<Unit> = runCatching {
        sql.exec(
            "INSERT INTO audit(id,at,admin_uuid,admin_name,target_uuid,snapshot_id,action,result,error) VALUES(" +
                "${sqlText(record.id)},${record.atEpochMs},${sqlText(record.adminUuid)},${sqlText(record.adminName)}," +
                "${sqlText(record.targetUuid)},${sqlText(record.snapshotId)},${sqlText(record.action)},${sqlText(record.result)},${sqlText(record.error)})",
        ).getOrThrow()
    }
}
