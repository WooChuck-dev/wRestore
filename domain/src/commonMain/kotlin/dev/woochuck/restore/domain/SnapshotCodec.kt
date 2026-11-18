package dev.woochuck.restore.domain

class UnsupportedSchemaException(val version: Int) : Exception("Unsupported snapshot payload schema version $version")

/**
 * Versioned binary encoding of a snapshot's item list (payload v1, big-endian):
 * u8 version | u16 itemCount | item* ; item = u8 area | u8 slot | str key | u16 count | u16 compCount | (str name | u32 len | bytes)*
 * str = u16 length + UTF-8.
 */
object SnapshotCodec {
    fun encodeItems(items: List<ItemRecord>): ByteArray {
        val w = Writer()
        w.u8(SCHEMA_VERSION)
        require(items.size <= 0xFFFF) { "too many items" }
        w.u16(items.size)
        for (i in items) {
            w.u8(i.area.ordinal); w.u8(i.slot); w.str(i.registryKey)
            w.u16(i.count); w.u16(i.components.size)
            for (c in i.components) { w.str(c.name); w.u32(c.value.size); w.bytes(c.value) }
        }
        return w.toByteArray()
    }

    fun decodeItems(data: ByteArray): Result<List<ItemRecord>> = runCatching {
        val r = Reader(data)
        val version = r.u8()
        if (version != SCHEMA_VERSION) throw UnsupportedSchemaException(version)
        val n = r.u16()
        val out = ArrayList<ItemRecord>(n)
        repeat(n) {
            val areaIdx = r.u8()
            val area = SlotArea.entries.getOrNull(areaIdx) ?: error("bad slot area $areaIdx")
            val slot = r.u8(); val key = r.str(); val count = r.u16()
            val comps = List(r.u16()) { ComponentValue(r.str(), r.bytes(r.u32())) }
            out += ItemRecord(area, slot, key, count, comps)
        }
        check(r.remaining() == 0) { "trailing bytes in payload" }
        out
    }

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(HEX[v shr 4]).append(HEX[v and 15]) }
        return sb.toString()
    }

    fun fromHex(s: String): Result<ByteArray> = runCatching {
        require(s.length % 2 == 0) { "odd hex length" }
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
    }

    private const val HEX = "0123456789abcdef"

    private class Writer {
        private var buf = ByteArray(256); private var n = 0
        private fun ensure(k: Int) { if (n + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + k)) }
        fun u8(v: Int) { ensure(1); buf[n++] = v.toByte() }
        fun u16(v: Int) { require(v in 0..0xFFFF) { "u16 out of range: $v" }; u8(v shr 8); u8(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buf, n); n += b.size }
        fun str(s: String) { val b = s.encodeToByteArray(); u16(b.size); bytes(b) }
        fun toByteArray(): ByteArray = buf.copyOf(n)
    }

    private class Reader(private val d: ByteArray) {
        private var p = 0
        fun remaining() = d.size - p
        private fun need(k: Int) { check(k >= 0 && p + k <= d.size) { "truncated payload" } }
        fun u8(): Int { need(1); return d[p++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int { val v = (u16().toLong() shl 16) or u16().toLong(); check(v <= Int.MAX_VALUE) { "length too large" }; return v.toInt() }
        fun bytes(k: Int): ByteArray { need(k); return d.copyOfRange(p, p + k).also { p += k } }
        fun str(): String = bytes(u16()).decodeToString()
    }
}
