package app.champion

import org.junit.Assert.*
import org.junit.Test

class SyncScheduleTest {
    @Test fun recoveryAndServiceShareTheBackgroundInterval() {
        val plan = SyncSchedule()
        assertTrue(plan.due(1_000_000))
        plan.lastAttempt = 1_000_000
        plan.succeeded(15_000)
        assertFalse(plan.due(1_005_000))
        assertFalse(plan.due(1_014_999))
        assertTrue(plan.due(1_015_000))
    }

    @Test fun restartDoesNotCauseAnImmediateDuplicateRequest() {
        val persisted = SyncSchedule(lastAttempt = 1_000_000, retryAt = 0, nextPollMs = 15_000, failures = 0)
        assertFalse(persisted.copy().due(1_005_000))
        assertTrue(persisted.copy().due(1_015_000))
    }

    @Test fun foregroundCannotBypassServerRateLimitAfterRestart() {
        val plan = SyncSchedule(lastAttempt = 1_000_000)
        plan.failed(1_000_000, serverRetryAt = 4_600_000)
        val restarted = plan.copy()
        assertFalse(restarted.due(1_060_000))
        assertFalse(restarted.due(4_599_999))
        assertTrue(restarted.due(4_600_000))
    }

    @Test fun historyBackfillContinuesSoonerThanNormalPolling() {
        val plan = SyncSchedule(lastAttempt = 1_000_000)
        plan.succeeded(2_000)
        assertFalse(plan.due(1_001_999))
        assertTrue(plan.due(1_002_000))
    }

    @Test fun failuresBackOffAndSuccessfulRecoveryResetsDelay() {
        val plan = SyncSchedule(lastAttempt = 1_000_000)
        repeat(8) { plan.failed(1_000_000) }
        assertEquals(1_300_000L, plan.retryAt)
        assertFalse(plan.due(1_299_999))
        plan.lastAttempt = 2_200_000
        plan.succeeded(15_000)
        assertTrue(plan.due(2_215_000))
        plan.failed(2_500_000)
        assertEquals(2_515_000L, plan.retryAt)
    }

    @Test fun clockCorrectionDoesNotBlockRecoveryIndefinitely() {
        val plan = SyncSchedule(lastAttempt = 2_000_000, retryAt = 3_000_000)
        assertTrue(plan.due(1_000_000))
    }
}
