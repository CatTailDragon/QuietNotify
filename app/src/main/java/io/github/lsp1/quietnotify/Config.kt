package io.github.lsp1.quietnotify

import android.content.SharedPreferences

object Config {
    const val GROUP = "rules"
    const val ENABLED = "enabled"
    const val HEADS_UP_ENABLED = "heads_up_enabled"
    const val SOUND_ENABLED = "sound_enabled"
    const val RULE_PREFIX = "rule."
    const val DEFAULT_DURATION_MS = 5 * 60 * 1000L
    const val MIN_DURATION_MS = 1_000L
    const val MAX_DURATION_MS = 24 * 60 * 60 * 1000L

    fun readRules(preferences: SharedPreferences): Map<String, Long> =
        preferences.all.asSequence()
            .filter { (key, value) -> key.startsWith(RULE_PREFIX) && value is Long }
            .mapNotNull { (key, value) ->
                val duration = value as Long
                if (duration in MIN_DURATION_MS..MAX_DURATION_MS) {
                    key.removePrefix(RULE_PREFIX) to duration
                } else {
                    null
                }
            }
            .toMap()

    fun formatDuration(durationMs: Long): String = when {
        durationMs % 3_600_000L == 0L -> "${durationMs / 3_600_000L} 小时"
        durationMs % 60_000L == 0L -> "${durationMs / 60_000L} 分钟"
        else -> "${durationMs / 1_000L} 秒"
    }
}
