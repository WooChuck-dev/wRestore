package dev.woochuck.restore.domain

const val DEFAULT_MAX_SNAPSHOTS: Int = 50

const val DEFAULT_CONFIG_TOML: String = "[storage]\nmaxSnapshotsPerPlayer = 50\n"

/** Result of reading config. [maxSnapshotsPerPlayer] == null means the config is invalid: pruning must be disabled. */
data class StorageConfig(val maxSnapshotsPerPlayer: Int?, val error: String?) {
    companion object {
        /** Minimal TOML subset: `[storage]` section with an integer `maxSnapshotsPerPlayer >= 1`. Missing key = default. */
        fun parse(text: String): StorageConfig {
            var section = ""
            var value: Int? = null
            for ((idx, raw) in text.lines().withIndex()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) { section = line.drop(1).dropLast(1).trim(); continue }
                val eq = line.indexOf('=')
                if (eq < 0) return invalid("line ${idx + 1}: expected key = value")
                if (section == "storage" && line.substring(0, eq).trim() == "maxSnapshotsPerPlayer") {
                    val v = line.substring(eq + 1).trim().replace("_", "").toIntOrNull()
                        ?: return invalid("maxSnapshotsPerPlayer must be an integer")
                    if (v < 1) return invalid("maxSnapshotsPerPlayer must be >= 1")
                    value = v
                }
            }
            return StorageConfig(value ?: DEFAULT_MAX_SNAPSHOTS, null)
        }

        private fun invalid(msg: String) = StorageConfig(null, msg)
    }
}
