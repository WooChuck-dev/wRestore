# Spec 001 — Death snapshots and restore (wRestore 0.1.0-beta.1)

Status: decisions D-001..D-007 approved. Gates G0 and G1 passed (see spike-g1.md); core implementation next.

## Decisions
- D-001 Snapshot only on player death (plus internal pre-restore recovery points).
- D-002 Restore replaces main/hotbar/armor/offhand + XP; not health/hunger/effects/position. Explicit confirmation; persisted recovery point first (abort if it fails); audit; best-effort rollback; honest outcome reporting.
- D-003 Online-only restore. Offline history readable. Abort if target leaves before confirm. No queueing.
- D-004 Keep the newest N death snapshots per player; recovery points/audit not counted; never delete before the new snapshot is durable.
- D-005 SQLite first choice, conditional on PoC. No silent backend switch.
- D-006 `[storage] maxSnapshotsPerPlayer = 50`, min 1, no max, applied on restart; invalid config never prunes.
- D-007 Snapshot on every death incl. keepInventory/empty; state must be pre-death; vanilla behavior unchanged.

## Data model
id, playerUuid, playerName, timestampUtc, world, position, deathCause?, keepInventory?, inventory (main+hotbar), armor, offhand, xpLevel, xpExact, schemaVersion. Items keep full data-component fidelity (versioned format, round-trip tested).

## Commands (shape subject to platform)
`/restore history <player> [page]`, `inspect <id>`, `apply <id>`, `confirm`, `cancel`. Confirmation is bound to admin + snapshot + target, time-limited, single-use; permission and online status re-checked at confirm.

## Versions
Plugin SemVer 0.1.0-beta.1; future wRestore API version (separate); storage schema version (separate).
