package dev.woochuck.restore

import dev.woochuck.restore.domain.ComponentValue
import dev.woochuck.restore.domain.ItemRecord
import dev.woochuck.restore.domain.Snapshot
import dev.woochuck.restore.domain.SnapshotKind
import dev.woochuck.restore.domain.SlotArea
import dev.woochuck.restore.domain.XpState
import pumpkin.DataComponents
import pumpkin.GameRules
import pumpkin.ItemStack
import pumpkin.Player
import pumpkin.Uuid

/** Reads a live player into plain domain objects and back. Calls host APIs: never use inside [WasiFs.batch]. */
object Capture {
    fun uuidString(u: Uuid.Uuid): String {
        val hex = u.high.toString(16).padStart(16, '0') + u.low.toString(16).padStart(16, '0')
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** Inverse of [uuidString]; null when [s] is not a canonical UUID. */
    fun parseUuid(s: String): Uuid.Uuid? {
        val h = s.replace("-", "")
        if (h.length != 32 || s.length != 36) return null
        val high = h.substring(0, 16).toULongOrNull(16) ?: return null
        val low = h.substring(16).toULongOrNull(16) ?: return null
        return Uuid.Uuid(high, low)
    }

    fun item(area: SlotArea, slot: Int, stack: ItemStack.ItemStack): ItemRecord = ItemRecord(
        area, slot, stack.getRegistryKey(), stack.getCount().toInt(),
        stack.getComponents().map { c -> ComponentValue(c.component.name, ByteArray(c.value.size) { c.value[it].toByte() }) },
    )

    /** Effective keepInventory game rule (the death event's own flag is hard-coded false by the host). */
    private fun keepInventory(player: Player.Player): Boolean? =
        (player.getWorld().getGameRule(GameRules.GameRule.KEEP_INVENTORY) as? GameRules.GameRuleValue.Bool)?.value

    fun snapshot(player: Player.Player, kind: SnapshotKind, id: String, nowEpochMs: Long, relatedId: String? = null): Snapshot {
        val inv = player.getInventory()
        val items = ArrayList<ItemRecord>()
        inv.asInventory().getAllItems().forEachIndexed { i, s -> if (s != null) items += item(SlotArea.MAIN, i, s) }
        listOf(inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()).forEachIndexed { i, s ->
            if (s != null) items += item(SlotArea.ARMOR, i, s)
        }
        inv.getOffHand()?.let { items += item(SlotArea.OFFHAND, 0, it) }
        val (x, y, z) = player.getPosition()
        return Snapshot(
            id = id, kind = kind, playerUuid = uuidString(player.getId()), playerName = player.getName(),
            createdAtEpochMs = nowEpochMs, world = player.getWorld().getName(), x = x, y = y, z = z,
            deathCause = null, // TODO: the death message is a text-component; no plain-text accessor verified yet
            keepInventory = keepInventory(player),
            xp = XpState(player.getExperienceLevel(), player.getExperienceProgress(), player.getExperiencePoints()),
            items = items, relatedSnapshotId = relatedId,
        )
    }

    fun death(player: Player.Player, id: String, nowEpochMs: Long): Snapshot = snapshot(player, SnapshotKind.DEATH, id, nowEpochMs)

    /** Builds a host item stack from a record. Throws on unknown components or invalid counts. */
    private fun build(r: ItemRecord): ItemStack.ItemStack {
        require(r.count in 1..255) { "invalid count ${r.count} for ${r.registryKey}" }
        val stack = ItemStack.ItemStack(r.registryKey, r.count.toUByte())
        for (c in r.components) {
            val component = DataComponents.DataComponent.entries.firstOrNull { it.name == c.name }
                ?: error("unknown data component ${c.name} on ${r.registryKey}")
            stack.setComponent(component, c.value.map { it.toUByte() })
        }
        return stack
    }

    /**
     * Replaces the player's inventory and XP with [s], then re-reads the player and verifies it matches.
     * All stacks are built before anything is changed, so a bad record fails without touching the player.
     * Not transactional: failures after the first mutation leave the player partly changed (the caller rolls back).
     */
    fun apply(player: Player.Player, s: Snapshot) {
        val inv = player.getInventory()
        val generic = inv.asInventory()
        val size = generic.getSize().toInt()
        val main = arrayOfNulls<ItemStack.ItemStack>(size)
        val armor = arrayOfNulls<ItemStack.ItemStack>(4)
        var off: ItemStack.ItemStack? = null
        for (r in s.items) {
            val st = build(r)
            when (r.area) {
                SlotArea.MAIN -> { require(r.slot in 0 until size) { "main slot ${r.slot} out of range $size" }; main[r.slot] = st }
                SlotArea.ARMOR -> { require(r.slot in 0..3) { "armor slot ${r.slot}" }; armor[r.slot] = st }
                SlotArea.OFFHAND -> off = st
            }
        }
        inv.clearAll()
        generic.setAllItems(main.toList())
        inv.setHelmet(armor[0]); inv.setChestplate(armor[1]); inv.setLeggings(armor[2]); inv.setBoots(armor[3])
        inv.setOffHand(off)
        player.setExperienceLevel(s.xp.level)
        player.setExperiencePoints(s.xp.points) // points within the level (host validates 0..pointsInLevel)

        val now = snapshot(player, SnapshotKind.RECOVERY, "verify", 0L)
        check(now.xp.level == s.xp.level && now.xp.points == s.xp.points) {
            "XP mismatch after restore: expected level ${s.xp.level}/${s.xp.points} pts, got ${now.xp.level}/${now.xp.points}"
        }
        val actual = now.items.associateBy { Triple(it.area, it.slot, 0) }
        check(actual.size == s.items.size) { "item count mismatch after restore: expected ${s.items.size}, got ${actual.size}" }
        for (want in s.items) {
            val got = actual[Triple(want.area, want.slot, 0)] ?: error("missing ${want.area} slot ${want.slot} after restore")
            check(got.registryKey == want.registryKey && got.count == want.count) { "item mismatch in ${want.area} slot ${want.slot}" }
            for (c in want.components) check(got.components.any { it == c }) { "component ${c.name} not preserved in ${want.area} slot ${want.slot}" }
        }
    }
}
