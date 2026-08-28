package io.github.lsp1.quietnotify.xposed

internal class WindowTracker {
    private val starts = HashMap<String, Long>()

    @Synchronized
    fun shouldMute(key: String, durationMs: Long, nowMs: Long): Boolean {
        val start = starts[key]
        if (start == null || nowMs < start || nowMs - start >= durationMs) {
            starts[key] = nowMs
            return false
        }
        return true
    }

    @Synchronized
    fun retainPackages(packages: Set<String>) {
        starts.keys.removeAll { key -> key.substringAfter(':') !in packages }
    }

    @Synchronized
    fun clear() = starts.clear()
}
