@file:OptIn(UnsafeWasmMemoryApi::class)

package dev.woochuck.restore

import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

// Hand-lowered canonical ABI for the composed `wrestore:sqlite/db@0.1.0` component (components/sqlite).
// result<T,string> is returned via a retptr: u8 tag at +0, payload at +4 (u32, or string ptr+len).
@WasmImport("wrestore:sqlite/db@0.1.0", "open")
private external fun sqOpen(path: Int, len: Int, ret: Int)

@WasmImport("wrestore:sqlite/db@0.1.0", "exec")
private external fun sqExec(handle: Int, sql: Int, len: Int, ret: Int)

@WasmImport("wrestore:sqlite/db@0.1.0", "query")
private external fun sqQuery(handle: Int, sql: Int, len: Int, ret: Int)

@WasmImport("wrestore:sqlite/db@0.1.0", "close")
private external fun sqClose(handle: Int)

/** Call only inside [WasiFs.batch]: results live in realloc-allocated memory. */
object Sqlite {
    private fun str(s: String): Pair<Int, Int> {
        val b = s.encodeToByteArray()
        val p = WasiFs.alloc(b.size)
        b.forEachIndexed { i, x -> (p + i).storeByte(x) }
        return p.address.toInt() to b.size
    }

    private fun readStr(p: Pointer): String {
        val ptr = Pointer((p + 4).loadInt().toUInt())
        val len = (p + 8).loadInt()
        return ByteArray(len) { (ptr + it).loadByte() }.decodeToString()
    }

    fun open(path: String): Result<Int> {
        val (p, l) = str(path); val ret = WasiFs.alloc(12)
        sqOpen(p, l, ret.address.toInt())
        return if (ret.loadByte().toInt() == 0) Result.success((ret + 4).loadInt()) else Result.failure(Exception(readStr(ret)))
    }

    fun exec(h: Int, sql: String): Result<Unit> {
        val (p, l) = str(sql); val ret = WasiFs.alloc(12)
        sqExec(h, p, l, ret.address.toInt())
        return if (ret.loadByte().toInt() == 0) Result.success(Unit) else Result.failure(Exception(readStr(ret)))
    }

    fun query(h: Int, sql: String): Result<String> {
        val (p, l) = str(sql); val ret = WasiFs.alloc(12)
        sqQuery(h, p, l, ret.address.toInt())
        return if (ret.loadByte().toInt() == 0) Result.success(readStr(ret)) else Result.failure(Exception(readStr(ret)))
    }

    fun close(h: Int) = sqClose(h)
}
