package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class PioneerSourceRecoveryPolicyTest {
    private fun departed() = PioneerSourceRecoveryPolicy().apply {
        sample(300, true, true, 0)
        sample(205, true, true, 500)
    }

    @Test fun missingStreamRecoversOnlyAfterSettledReturnAndIsBounded() {
        val p = departed()
        assertNull(p.sample(0, true, false, 1000))
        assertNull(p.sample(300, true, false, 1500))
        assertNull(p.sample(300, true, false, 2199))
        assertEquals(PioneerSourceRecoveryPolicy.Action.FOCUS_AND_PLAY, p.sample(300, true, false, 2200))
        assertNull(p.sample(300, true, false, 3699))
        assertEquals(PioneerSourceRecoveryPolicy.Action.FOCUS_AND_PLAY, p.sample(300, true, false, 3700))
        assertNull(p.sample(300, true, false, 10000))
    }

    @Test fun noRecoveryWhileOnOtherSources() {
        val p = departed()
        assertNull(p.sample(201, true, false, 2000))
        assertNull(p.sample(0, true, false, 10000))
    }

    @Test fun phonePauseCancelsEvenIfPlaybackStartsAgainAwayFromCarPlaySource() {
        val p = departed()
        p.sample(205, false, false, 1000)
        p.sample(205, true, false, 1500)
        p.sample(300, true, false, 2000)
        assertNull(p.sample(300, true, false, 10000))
    }

    @Test fun pauseDuringSettleCancelsRecovery() {
        val p = departed()
        p.sample(300, true, false, 1500)
        assertNull(p.sample(300, false, false, 2300))
        assertNull(p.sample(300, true, false, 3000))
    }

    @Test fun existingStreamOnlyReclaimsFocusAndNeverSendsPlay() {
        val p = departed()
        p.sample(300, true, true, 1000)
        assertEquals(PioneerSourceRecoveryPolicy.Action.FOCUS, p.sample(300, true, true, 1700))
        assertNull(p.sample(300, true, true, 5000))
    }

    @Test fun successfulStreamAfterFirstAttemptStopsRetrying() {
        val p = departed()
        p.sample(300, true, false, 1000)
        assertEquals(PioneerSourceRecoveryPolicy.Action.FOCUS_AND_PLAY, p.sample(300, true, false, 1700))
        assertEquals(PioneerSourceRecoveryPolicy.Action.FOCUS, p.sample(300, true, true, 3200))
        assertNull(p.sample(300, true, true, 5000))
    }

    @Test fun initialConnectionAndSessionReplacementNeverAutoStartMusic() {
        val p = PioneerSourceRecoveryPolicy()
        assertNull(p.sample(300, true, false, 0))
        assertNull(p.sample(300, true, false, 5000))
        val previous = departed()
        previous.sample(300, true, false, 1500)
        previous.reset()
        assertNull(previous.sample(300, true, false, 5000))
    }

    @Test fun pausedDepartureDoesNotArmAndRapidSourceChangesRestartTheSettleWindow() {
        val p = PioneerSourceRecoveryPolicy()
        p.sample(300, false, true, 0)
        p.sample(201, false, true, 500)
        p.sample(300, true, true, 1000)
        assertNull(p.sample(300, true, true, 5000))
        val rapid = departed()
        rapid.sample(300, true, true, 1000)
        rapid.sample(201, true, true, 1200)
        assertNull(rapid.sample(300, true, true, 1500))
        assertNull(rapid.sample(300, true, true, 2199))
        assertEquals(PioneerSourceRecoveryPolicy.Action.FOCUS, rapid.sample(300, true, true, 2200))
    }
}
