package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTests {
    @Test fun releaseTagsMapToInstalledVersionCodes() {
        assertEquals(133L, releaseVersionCode("v1.3.3"))
        assertEquals(99L, releaseVersionCode("v0.9.9"))
        assertEquals(12L, releaseVersionCode("v0.2.12")) // historical tags
        assertNull(releaseVersionCode("vlatest"))
        assertTrue(releaseVersionCode("v1.3.4")!! > releaseVersionCode("v1.3.3")!!)
    }

    @Test fun automaticChecksAreDailyAndClockChangesDoNotStrandThem() {
        val now = 1_800_000_000_000L
        assertTrue(shouldAutoUpdateCheck(now, 0))
        assertFalse(shouldAutoUpdateCheck(now, now - AUTO_UPDATE_CHECK_INTERVAL_MILLIS + 1))
        assertTrue(shouldAutoUpdateCheck(now, now - AUTO_UPDATE_CHECK_INTERVAL_MILLIS))
        assertTrue(shouldAutoUpdateCheck(now, now + 1000))
    }

    @Test fun githubRateLimitUsesLaterOfResetAndRetryAfter() {
        val now = 1_800_000_000_000L
        assertEquals(now + 3_600_000, updateRetryAtMillis(403, "0", now / 1000 + 3600, 60, false, now))
        assertEquals(now + 120_000, updateRetryAtMillis(429, null, null, 120, false, now))
        assertEquals(now + UPDATE_FAILURE_RETRY_MILLIS, updateRetryAtMillis(403, null, null, null, true, now))
        assertNull(updateRetryAtMillis(403, "42", null, null, false, now))
        assertNull(updateRetryAtMillis(500, "0", null, null, true, now))
    }
}
