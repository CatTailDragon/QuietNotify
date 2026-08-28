package io.github.lsp1.quietnotify.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTrackerTest {
    private val tracker = WindowTracker()

    @Test
    fun fixedWindow_doesNotSlide() {
        assertFalse(tracker.shouldMute("0:a", 1_000, 10_000))
        assertTrue(tracker.shouldMute("0:a", 1_000, 10_900))
        assertFalse(tracker.shouldMute("0:a", 1_000, 11_000))
    }

    @Test
    fun applicationsAndUsersHaveIndependentWindows() {
        assertFalse(tracker.shouldMute("0:a", 1_000, 1))
        assertFalse(tracker.shouldMute("0:b", 1_000, 2))
        assertFalse(tracker.shouldMute("10:a", 1_000, 3))
        assertTrue(tracker.shouldMute("0:a", 1_000, 4))
    }

    @Test
    fun clockRollbackStartsNewWindow() {
        assertFalse(tracker.shouldMute("0:a", 1_000, 100))
        assertFalse(tracker.shouldMute("0:a", 1_000, 99))
    }

    @Test
    fun removedPackagesAreForgotten() {
        assertFalse(tracker.shouldMute("0:a", 1_000, 1))
        tracker.retainPackages(emptySet())
        assertFalse(tracker.shouldMute("0:a", 1_000, 2))
    }
}
