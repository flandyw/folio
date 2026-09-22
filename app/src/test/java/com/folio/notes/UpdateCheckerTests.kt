package com.folio.notes

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTests {
    @Test fun rateLimited403ReportsRetryDelayWithoutLeakingBody() {
        val now = 1_700_000_000L
        val message = githubUpdateErrorMessage(
            responseCode = 403,
            rateRemaining = "0",
            rateResetEpochSeconds = now + 300,
            retryAfterSeconds = null,
            rateLimitedBody = false,
            nowEpochSeconds = now
        )
        assertTrue(message.contains("update limit reached"))
        assertTrue(message.contains("5 min"))
        assertFalse(message.contains("1.2.3.4"))
    }

    @Test fun rateLimitBodyIsDetectedEvenWhenHeaderMissing() {
        val message = githubUpdateErrorMessage(
            responseCode = 403,
            rateRemaining = null,
            rateResetEpochSeconds = null,
            retryAfterSeconds = 120,
            rateLimitedBody = true,
            nowEpochSeconds = 1_700_000_000L
        )
        assertTrue(message.contains("update limit reached"))
        assertTrue(message.contains("2 min"))
    }

    @Test fun forbiddenWithoutRateLimitStaysGeneric() {
        val message = githubUpdateErrorMessage(
            responseCode = 403,
            rateRemaining = "59",
            rateResetEpochSeconds = null,
            retryAfterSeconds = null,
            rateLimitedBody = false,
            nowEpochSeconds = 1_700_000_000L
        )
        assertTrue(message.contains("refused"))
        assertFalse(message.contains("limit reached"))
    }

    @Test fun tooManyRequestsUsesRetryAfter() {
        val message = githubUpdateErrorMessage(
            responseCode = 429,
            rateRemaining = null,
            rateResetEpochSeconds = null,
            retryAfterSeconds = null,
            rateLimitedBody = false,
            nowEpochSeconds = 1_700_000_000L
        )
        assertTrue(message.contains("update limit reached"))
    }

    @Test fun autoCheckIsThrottledToOncePerDay() {
        val now = 1_700_000_000_000L
        assertTrue(shouldAutoUpdateCheck(now, 0L))
        assertFalse(shouldAutoUpdateCheck(now, now - 60_000L))
        assertTrue(shouldAutoUpdateCheck(now, now - AUTO_UPDATE_CHECK_INTERVAL_MILLIS))
        assertTrue(shouldAutoUpdateCheck(now, now - AUTO_UPDATE_CHECK_INTERVAL_MILLIS - 1))
        // Clock moved backwards: don't block the user from updating.
        assertTrue(shouldAutoUpdateCheck(now - 10_000L, now))
    }

    @Test fun redirectHostsStayOnGithubOwnedInfrastructure() {
        assertTrue(isTrustedUpdateUrl("https://api.github.com/repos/flandyw/folio/releases/latest"))
        assertTrue(isTrustedUpdateUrl("https://github.com/flandyw/folio/releases/download/v0.2.1/folio-0.2.1.apk"))
        assertTrue(isTrustedUpdateUrl("https://objects.githubusercontent.com/foo/bar.apk"))
        assertFalse(isTrustedUpdateUrl("http://github.com/flandyw/folio/releases/download/v0.2.1/folio-0.2.1.apk"))
        assertFalse(isTrustedUpdateUrl("https://example.com/folio-0.2.1.apk"))
        assertFalse(isTrustedUpdateUrl("not a url"))
    }
}
