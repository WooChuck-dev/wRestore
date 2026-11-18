package dev.woochuck.restore.domain

/** Storage schema version for snapshots (separate from plugin SemVer and the future public API version). */
const val SCHEMA_VERSION: Int = 1

enum class SnapshotKind { DEATH, RECOVERY }

/** Where an item lives. MAIN 0..35 (0..8 is the hotbar), ARMOR 0..3 = helmet, chestplate, leggings, boots; OFFHAND 0. */
enum class SlotArea { MAIN, ARMOR, OFFHAND }

/** One serialized data component (name = WIT `data-component` case, value = host-serialized bytes). */
class ComponentValue(val name: String, val value: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ComponentValue && name == other.name && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * name.hashCode() + value.contentHashCode()
    override fun toString(): String = "ComponentValue($name, ${value.size}B)"
}

data class ItemRecord(
    val area: SlotArea,
    val slot: Int,
    val registryKey: String,
    val count: Int,
    val components: List<ComponentValue>,
)

/** Exact XP state as Pumpkin keeps it: level, progress (0..1) and points WITHIN the current level (not a grand total). */
data class XpState(val level: Int, val progress: Float, val points: Int)

data class Snapshot(
    val id: String,
    val kind: SnapshotKind,
    val playerUuid: String,
    val playerName: String,
    val createdAtEpochMs: Long, // UTC
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val deathCause: String?,
    val keepInventory: Boolean?,
    val xp: XpState,
    val items: List<ItemRecord>,
    /** For RECOVERY points: the death snapshot being applied. */
    val relatedSnapshotId: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
)

data class SnapshotSummary(
    val id: String,
    val playerName: String,
    val createdAtEpochMs: Long,
    val world: String,
    val keepInventory: Boolean?,
    val deathCause: String?,
    val itemCount: Int,
)

data class AuditRecord(
    val id: String,
    val atEpochMs: Long,
    val adminUuid: String,
    val adminName: String,
    val targetUuid: String,
    val snapshotId: String?,
    val action: String,
    val result: String,
    val error: String?,
)

/** Storage boundary. Implementations must not report success unless the data is durably written. */
interface SnapshotRepository {
    fun insert(snapshot: Snapshot): Result<Unit>
    fun get(id: String): Result<Snapshot?>
    fun listDeaths(playerUuid: String, offset: Int, limit: Int): Result<List<SnapshotSummary>>
    fun countDeaths(playerUuid: String): Result<Int>
    fun playerUuidsWithDeaths(): Result<List<String>>

    /** UUID of the most recent snapshot owner with this name (case-insensitive ASCII); works for offline players. */
    fun playerUuidByName(name: String): Result<String?>

    /** Ids starting with [prefix] (lowercase hex/dashes only), at most [limit], any kind. */
    fun idsWithPrefix(prefix: String, limit: Int): Result<List<String>>

    /** Deletes the oldest DEATH snapshots of a player beyond [keep]. Never touches RECOVERY points. Returns deleted count. */
    fun pruneDeaths(playerUuid: String, keep: Int): Result<Int>
    fun insertAudit(record: AuditRecord): Result<Unit>
}

fun interface IdGenerator { fun next(): String }

fun interface Log { fun warn(message: String) }
