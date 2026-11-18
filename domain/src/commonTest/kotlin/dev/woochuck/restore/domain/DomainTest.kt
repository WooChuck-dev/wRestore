package dev.woochuck.restore.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {
    @Test fun roundTripPreservesAreasSlotsAndComponentBytes() {
        val enchants = ComponentValue("enchantments", byteArrayOf(0, 1, -128, 127, -1))
        val name = ComponentValue("custom-name", "Zażółć gęślą jaźń ✨".encodeToByteArray())
        val items = listOf(
            item(SlotArea.MAIN, 0, "minecraft:diamond_sword", 1, enchants, name),
            item(SlotArea.MAIN, 35, "minecraft:cobblestone", 64),
            item(SlotArea.ARMOR, 0, "minecraft:iron_helmet", 1, ComponentValue("damage", byteArrayOf(5))),
            item(SlotArea.ARMOR, 3, "minecraft:iron_boots"),
            item(SlotArea.OFFHAND, 0, "minecraft:shield", 1, ComponentValue("custom-data", ByteArray(70_000) { it.toByte() })),
        )
        val back = SnapshotCodec.decodeItems(SnapshotCodec.encodeItems(items)).getOrThrow()
        assertEquals(items, back)
        assertContentEquals(enchants.value, back[0].components[0].value)
    }

    @Test fun emptyInventoryRoundTrips() {
        assertEquals(emptyList(), SnapshotCodec.decodeItems(SnapshotCodec.encodeItems(emptyList())).getOrThrow())
    }

    @Test fun unknownSchemaVersionIsRejectedExplicitly() {
        val data = SnapshotCodec.encodeItems(emptyList()).also { it[0] = 99 }
        val e = SnapshotCodec.decodeItems(data).exceptionOrNull()
        assertTrue(e is UnsupportedSchemaException && e.version == 99)
    }

    @Test fun truncatedAndTrailingPayloadsAreRejected() {
        val data = SnapshotCodec.encodeItems(listOf(item(SlotArea.MAIN, 1)))
        assertTrue(SnapshotCodec.decodeItems(data.copyOf(data.size - 1)).isFailure)
        assertTrue(SnapshotCodec.decodeItems(data + byteArrayOf(0)).isFailure)
    }

    @Test fun hexRoundTripAndBadHex() {
        val b = byteArrayOf(0, 15, 16, -1, 127, -128)
        assertContentEquals(b, SnapshotCodec.fromHex(SnapshotCodec.toHex(b)).getOrThrow())
        assertTrue(SnapshotCodec.fromHex("abc").isFailure)
        assertTrue(SnapshotCodec.fromHex("zz").isFailure)
    }
}

class ConfigTest {
    @Test fun defaultIs50() = assertEquals(50, StorageConfig.parse(DEFAULT_CONFIG_TOML).maxSnapshotsPerPlayer)
    @Test fun missingKeyUsesDefault() = assertEquals(50, StorageConfig.parse("").maxSnapshotsPerPlayer)
    @Test fun customAndVeryLargeValuesAccepted() {
        assertEquals(1, StorageConfig.parse("[storage]\nmaxSnapshotsPerPlayer = 1").maxSnapshotsPerPlayer)
        assertEquals(2_000_000_000, StorageConfig.parse("[storage]\nmaxSnapshotsPerPlayer = 2_000_000_000 # lots").maxSnapshotsPerPlayer)
    }

    @Test fun invalidValuesDisablePruningAndReportError() {
        for (bad in listOf("0", "-5", "abc", "1.5", "99999999999")) {
            val c = StorageConfig.parse("[storage]\nmaxSnapshotsPerPlayer = $bad")
            assertNull(c.maxSnapshotsPerPlayer, bad); assertNotNull(c.error, bad)
        }
        assertNull(StorageConfig.parse("garbage line").maxSnapshotsPerPlayer)
    }

    @Test fun otherSectionsAreIgnored() = assertEquals(50, StorageConfig.parse("[other]\nmaxSnapshotsPerPlayer = 3").maxSnapshotsPerPlayer)
}

class SnapshotServiceTest {
    private val warnings = mutableListOf<String>()
    private fun service(repo: InMemoryRepo, limit: Int? = 50) = SnapshotService(repo, limit) { warnings += it }

    @Test fun defaultRetentionKeepsNewest50() {
        val repo = InMemoryRepo(); val s = service(repo)
        val ids = (1..60).map { snap().also { sn -> assertTrue(s.recordDeath(sn) is SaveOutcome.Saved) }.id }
        val kept = repo.rows.map { it.id }
        assertEquals(50, kept.size)
        assertEquals(ids.takeLast(50), kept)
    }

    @Test fun customLimitAndReductionPruneOldest() {
        val repo = InMemoryRepo()
        val s5 = service(repo, 5)
        val ids = (1..8).map { snap().also { sn -> s5.recordDeath(sn) }.id }
        assertEquals(ids.takeLast(5), repo.rows.map { it.id })
        assertEquals(2, service(repo, 3).pruneAllAfterLimitChange())
        assertEquals(ids.takeLast(3), repo.rows.map { it.id })
    }

    @Test fun otherPlayersAndRecoveryPointsAreNotCounted() {
        val repo = InMemoryRepo(); val s = service(repo, 2)
        repeat(3) { repo.insert(snap(kind = SnapshotKind.RECOVERY)) }
        repeat(3) { repo.insert(snap(player = "p2")) }
        repeat(4) { s.recordDeath(snap()) }
        assertEquals(2, repo.countDeaths("p1").getOrThrow())
        assertEquals(3, repo.countDeaths("p2").getOrThrow())
        assertEquals(3, repo.rows.count { it.kind == SnapshotKind.RECOVERY })
    }

    @Test fun failedWriteReportsFailureAndNeverPrunes() {
        val repo = InMemoryRepo(); val s = service(repo, 2)
        repeat(2) { s.recordDeath(snap()) }
        repo.failInsert = true
        val out = s.recordDeath(snap())
        assertTrue(out is SaveOutcome.Failed)
        assertEquals(2, repo.rows.size)
    }

    @Test fun failedPruningStillSavesAndIsLogged() {
        val repo = InMemoryRepo(); val s = service(repo, 1)
        s.recordDeath(snap()); repo.failPrune = true
        val out = s.recordDeath(snap())
        assertTrue(out is SaveOutcome.Saved && out.pruneError == "locked")
        assertEquals(2, repo.rows.size); assertEquals(1, warnings.size)
    }

    @Test fun invalidConfigNeverPrunes() {
        val repo = InMemoryRepo(); val s = service(repo, null)
        repeat(60) { s.recordDeath(snap()) }
        assertEquals(60, repo.rows.size)
        assertEquals(0, s.pruneAllAfterLimitChange())
    }

    @Test fun keepInventoryEmptyInventoryAndZeroXpDeathsAreSaved() {
        val repo = InMemoryRepo(); val s = service(repo)
        s.recordDeath(snap(keepInventory = true, items = emptyList(), xp = XpState(0, 0f, 0)))
        val got = repo.rows.single()
        assertEquals(true, got.keepInventory); assertTrue(got.items.isEmpty()); assertEquals(0, got.xp.points)
    }

    @Test fun xpStateIsStoredExactly() {
        val repo = InMemoryRepo(); val xp = XpState(30, 0.37f, 1507)
        service(repo).recordDeath(snap(xp = xp))
        assertEquals(xp, repo.rows.single().xp)
    }

    @Test fun recoveryKindIsRejectedByDeathPath() {
        assertFailsWith<IllegalArgumentException> { service(InMemoryRepo()).recordDeath(snap(kind = SnapshotKind.RECOVERY)) }
    }
}

class SqlTextTest {
    @Test fun parseRowsHandlesEscapesAndNulls() {
        val rows = parseRows("a\\tb\tc\\nd\t\\N\n\\\\\t\n")
        assertEquals(listOf(listOf("a\tb", "c\nd", null), listOf("\\", "")), rows)
        assertEquals(emptyList(), parseRows(""))
    }

    @Test fun sqlTextEscapesQuotesAndRejectsNul() {
        assertEquals("'O''Brien'", sqlText("O'Brien"))
        assertEquals("NULL", sqlText(null))
        assertFailsWith<IllegalArgumentException> { sqlText("a\u0000b") }
        assertEquals("'x''; DROP TABLE snapshots; --'", sqlText("x'; DROP TABLE snapshots; --"))
    }
}
