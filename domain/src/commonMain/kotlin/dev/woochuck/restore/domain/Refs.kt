package dev.woochuck.restore.domain

/** What an admin's last `/restore history` showed: lets short references like `2` or `latest` mean something. */
class Listing(val playerUuid: String, val idsByNumber: Map<Int, String>)

object Refs {
    private const val MIN_PREFIX = 6

    /** Number shown next to a history row (`#3`, `3`), a unique id prefix (>= 6 hex chars), `latest`, or a full id. */
    fun resolve(rawRef: String, listing: Listing?, repo: SnapshotRepository): Result<String> {
        val ref = rawRef.trim().lowercase().removePrefix("#")
        if (ref.isEmpty()) return fail("empty reference")
        if (ref == "latest") {
            val l = listing ?: return fail("run /restore history <player> first, then use latest")
            val newest = repo.listDeaths(l.playerUuid, 0, 1).getOrElse { return Result.failure(it) }.firstOrNull()
                ?: return fail("no death snapshots for that player")
            return Result.success(newest.id)
        }
        if (ref.length <= 4 && ref.all { it.isDigit() }) {
            val l = listing ?: return fail("run /restore history <player> first, then use the row number")
            return l.idsByNumber[ref.toInt()]?.let { Result.success(it) } ?: fail("no row #$ref in your last history list")
        }
        if (ref.length < MIN_PREFIX || ref.any { !(it in '0'..'9' || it in 'a'..'f' || it == '-') }) return fail("unknown reference '$rawRef'")
        val ids = repo.idsWithPrefix(ref, 2).getOrElse { return Result.failure(it) }
        return when {
            ids.isEmpty() -> fail("no snapshot starts with '$ref'")
            ids.size > 1 && ids.none { it == ref } -> fail("'$ref' matches more than one snapshot; type more characters")
            else -> Result.success(ids.firstOrNull { it == ref } ?: ids.single())
        }
    }

    fun short(id: String): String = id.take(8)

    private fun fail(msg: String) = Result.failure<String>(IllegalArgumentException(msg))
}
