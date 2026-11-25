package dev.lilt.player

import dev.lilt.player.data.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesReleaseTags() {
        assertTrue(UpdateChecker.isNewerVersion("0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewerVersion("v0.2.1", "0.2.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0", "0.99.99"))
        assertFalse(UpdateChecker.isNewerVersion("0.2.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewerVersion("v0.1.9", "0.2.0"))
    }

    @Test
    fun ignoresPrereleaseSuffixAndBadInput() {
        assertFalse(UpdateChecker.isNewerVersion("0.2.0-beta", "0.2.0"))
        assertTrue(UpdateChecker.isNewerVersion("0.3.0-rc1", "0.2.9"))
        assertFalse(UpdateChecker.isNewerVersion("not-a-version", "0.2.0"))
    }
}
