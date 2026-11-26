package com.sansen.yeweiyi.logic.probe

import org.junit.Assert.*
import org.junit.Test

class Serial4ModeTest {
    @Test fun persistedModeNumbersRemainCompatibleWithOldSettings() {
        assertEquals(0, Serial4Mode.OVERFLOW)
        assertEquals(1, Serial4Mode.RELAY_MODBUS)
        assertEquals(2, Serial4Mode.PROXY_ZHONGCHENG)
        assertEquals(3, Serial4Mode.PROXY_MODBUS)
    }

    @Test fun legacyControlModesAcceptEveryExistingProbeProtocol() {
        for (mode in listOf(0, 1)) {
            for (probeType in 0..2) {
                assertTrue("mode=$mode probeType=$probeType", Serial4Mode.matches(mode, probeType))
                assertTrue(Serial4Mode.allowsControl(mode))
                assertFalse(Serial4Mode.isProxy(mode))
            }
        }
    }

    @Test fun persistedLegacyValuesKeepControlOwnership() {
        assertTrue(Serial4Mode.allowsControl(0))
        assertTrue(Serial4Mode.allowsControl(1))
        assertFalse(Serial4Mode.isProxy(0))
        assertFalse(Serial4Mode.isProxy(1))
    }

    @Test fun bothProxyModesExcludeLegacyControl() {
        for (mode in 2..3) {
            assertTrue(Serial4Mode.isProxy(mode))
            assertFalse(Serial4Mode.allowsControl(mode))
        }
    }

    @Test fun mismatchedProbeProtocolsAreRejected() {
        assertTrue(Serial4Mode.matches(2, 0))
        assertTrue(Serial4Mode.matches(3, 1))
        assertFalse(Serial4Mode.matches(2, 1))
        assertFalse(Serial4Mode.matches(3, 0))
        assertFalse(Serial4Mode.matches(2, 2))
        assertFalse(Serial4Mode.matches(3, 2))
    }

    @Test fun unknownModesDoNotGainControlOrProxyOwnership() {
        for (mode in listOf(-1, 4, 100)) {
            assertFalse(Serial4Mode.allowsControl(mode))
            assertFalse(Serial4Mode.isProxy(mode))
            assertFalse(Serial4Mode.matches(mode, 0))
        }
    }
}
