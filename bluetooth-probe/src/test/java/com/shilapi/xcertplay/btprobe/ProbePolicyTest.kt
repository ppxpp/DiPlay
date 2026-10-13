package com.shilapi.xcertplay.btprobe

import org.junit.Assert.*
import org.junit.Test

class ProbePolicyTest {
    @Test fun phoneAndMusicDoNotSelectASerialTarget() {
        assertNotNull(ProbePolicy.connectionBlock(true, 3, true))
        assertNotNull(ProbePolicy.connectionBlock(true, 7, true))
        assertNull(ProbePolicy.connectionBlock(true, 0x81, true))
    }
    @Test fun unknownFirmwareOrBusyChannelMustNotBeTakenOver() {
        assertNotNull(ProbePolicy.connectionBlock(false, 0x81, true))
        assertNotNull(ProbePolicy.connectionBlock(true, null, true))
        assertNotNull(ProbePolicy.connectionBlock(true, 0x81, false))
    }
    @Test fun acceptedCommandAndArbitraryBytesAreNotIap2Success() {
        assertFalse(ProbePolicy.iap2Verified(true, 0, false))
        assertFalse(ProbePolicy.iap2Verified(true, 100, false))
        assertFalse(ProbePolicy.iap2Verified(true, 0, true))
        assertTrue(ProbePolicy.iap2Verified(true, 10, true))
    }
}
