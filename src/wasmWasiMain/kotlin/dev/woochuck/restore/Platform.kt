@file:OptIn(UnsafeWasmMemoryApi::class)

package dev.woochuck.restore

import dev.woochuck.restore.domain.SqlExecutor
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

@WasmImport("wasi_snapshot_preview1", "clock_time_get")
private external fun clockTimeGet(id: Int, precision: Long, out: Int): Int

@WasmImport("wasi_snapshot_preview1", "random_get")
private external fun randomGet(buf: Int, len: Int): Int

/** WASI p1 clock and randomness. Call only inside [WasiFs.batch] (see WasiFs KDoc). */
object WasiPlatform {
    /** UTC wall clock, epoch milliseconds. */
    fun nowEpochMs(): Long {
        val out = WasiFs.alloc(8)
        val e = clockTimeGet(0, 1_000_000L, out.address.toInt())
        check(e == 0) { "clock_time_get errno=$e" }
        return out.loadLong() / 1_000_000L
    }

    /** Random (v4) UUID string from the host CSPRNG. */
    fun randomUuid(): String {
        val p = WasiFs.alloc(16)
        val e = randomGet(p.address.toInt(), 16)
        check(e == 0) { "random_get errno=$e" }
        val b = ByteArray(16) { (p + it).loadByte() }
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val h = StringBuilder()
        for (x in b) { val v = x.toInt() and 0xFF; h.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15]) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}

/** SqlExecutor over one open handle of the composed SQLite component. */
private class HandleSql(private val h: Int) : SqlExecutor {
    override fun exec(sql: String) = Sqlite.exec(h, sql)
    override fun query(sql: String) = Sqlite.query(h, sql)
}

/**
 * Opens `data/wrestore.db` (or ":memory:") for the duration of [block], then closes it, so no file stays open between
 * operations. Everything runs inside one [WasiFs.batch]: [block] must not call host/Logging APIs.
 */
object Storage {
    const val DB_PATH = "data/wrestore.db"

    /** [path] is deliberately required: a default once let the self-test run against the production database. */
    fun <T> use(path: String, block: (SqlExecutor) -> T): Result<T> = runCatching {
        WasiFs.batch {
            val h = Sqlite.open(path).getOrThrow()
            try { block(HandleSql(h)) } finally { Sqlite.close(h) }
        }
    }
}
