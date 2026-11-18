@file:OptIn(UnsafeWasmMemoryApi::class, ComponentModelInternalApi::class)

package dev.woochuck.restore

import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.ComponentModelInternalApi
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.freeAllComponentModelReallocAllocatedMemory
import pumpkin.runtime.cabi_realloc

@WasmImport("wasi_snapshot_preview1", "fd_prestat_get")
private external fun fdPrestatGet(fd: Int, buf: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_prestat_dir_name")
private external fun fdPrestatDirName(fd: Int, path: Int, len: Int): Int

@WasmImport("wasi_snapshot_preview1", "path_open")
private external fun pathOpen(
    dirFd: Int, dirFlags: Int, path: Int, pathLen: Int, oflags: Int,
    rightsBase: Long, rightsInheriting: Long, fdFlags: Int, openedFd: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "fd_write")
private external fun fdWrite(fd: Int, iovs: Int, iovsLen: Int, nwritten: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun fdRead(fd: Int, iovs: Int, iovsLen: Int, nread: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_sync")
private external fun fdSync(fd: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_close")
private external fun fdClose(fd: Int): Int

/**
 * Minimal WASI preview1 file access to the host-preopened `/data` directory (spike).
 *
 * Root cause of earlier traps (verified in kotlin-stdlib `MemoryAllocation.kt`): the p1 adapter calls
 * `cabi_realloc`, which leaves Kotlin's "realloc allocator" open; any later `withScopedMemoryAllocator`
 * (including every `Logging.log`) then throws IllegalStateException. So: allocate buffers with
 * `cabi_realloc`, do ALL p1 work inside [batch], and only log after it returns.
 */
object WasiFs {
    private const val O_CREAT = 1
    private const val O_TRUNC = 8
    private const val ALL_RIGHTS = -1L

    /** Run p1 calls; frees realloc-allocated memory afterwards. Do not call host imports inside [block]. */
    fun <T> batch(block: () -> T): T = try {
        block()
    } finally {
        freeAllComponentModelReallocAllocatedMemory()
    }

    internal fun alloc(size: Int): Pointer = Pointer(cabi_realloc(0, 0, 8, size.coerceAtLeast(8)).toUInt())

    private fun cstr(s: String): Pair<Pointer, Int> {
        val b = s.encodeToByteArray()
        val p = alloc(b.size)
        b.forEachIndexed { i, x -> (p + i).storeByte(x) }
        return p to b.size
    }

    /** Names of all preopens seen by the last [findDataDir] call (diagnostics). */
    var seenPreopens: String = ""
        private set

    /** Preopen fd whose guest path is `data` (host: `preopened_dir(.., "data", ..)`), or null. */
    fun findDataDir(): Int? {
        val buf = alloc(8)
        val seen = StringBuilder()
        var found: Int? = null
        for (fd in 3..16) {
            val e = fdPrestatGet(fd, buf.address.toInt())
            if (e != 0) { seen.append("[fd$fd errno=$e]"); break } // EBADF ends the preopen list
            val len = (buf + 4).loadInt() // prestat: u8 tag at 0, u32 name length at 4
            val name = alloc(len)
            if (fdPrestatDirName(fd, name.address.toInt(), len) != 0) continue
            val s = ByteArray(len) { (name + it).loadByte() }.decodeToString()
            seen.append("[fd$fd='$s']")
            if (found == null && (s == "data" || s == "/data")) found = fd
        }
        seenPreopens = seen.toString()
        return found
    }

    fun write(dirFd: Int, name: String, data: ByteArray): String? {
        val (path, plen) = cstr(name)
        val out = alloc(4)
        var e = pathOpen(dirFd, 0, path.address.toInt(), plen, O_CREAT or O_TRUNC, ALL_RIGHTS, ALL_RIGHTS, 0, out.address.toInt())
        if (e != 0) return "path_open errno=$e"
        val fd = out.loadInt()
        val buf = alloc(data.size)
        data.forEachIndexed { i, b -> (buf + i).storeByte(b) }
        val iov = alloc(8)
        iov.storeInt(buf.address.toInt()); (iov + 4).storeInt(data.size)
        e = fdWrite(fd, iov.address.toInt(), 1, out.address.toInt())
        if (e == 0) e = fdSync(fd)
        fdClose(fd)
        return if (e != 0) "write/sync errno=$e" else null
    }

    /** Returns (bytes or null if missing, error or null). */
    fun read(dirFd: Int, name: String, max: Int = 4096): Pair<ByteArray?, String?> {
        val (path, plen) = cstr(name)
        val out = alloc(4)
        var e = pathOpen(dirFd, 0, path.address.toInt(), plen, 0, ALL_RIGHTS, ALL_RIGHTS, 0, out.address.toInt())
        if (e == 44) return null to null // ENOENT
        if (e != 0) return null to "path_open errno=$e"
        val fd = out.loadInt()
        val buf = alloc(max)
        val iov = alloc(8)
        iov.storeInt(buf.address.toInt()); (iov + 4).storeInt(max)
        e = fdRead(fd, iov.address.toInt(), 1, out.address.toInt())
        fdClose(fd)
        if (e != 0) return null to "read errno=$e"
        return ByteArray(out.loadInt()) { (buf + it).loadByte() } to null
    }
}
