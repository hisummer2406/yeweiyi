package com.sansen.yeweiyi.logic.probe

import org.junit.Assert.*
import org.junit.Test

class ProbeProxySessionTest {
    private val query = hex("FEFE01034400000392")
    private val response = hex("FEFE010D44442401A6442401A6000000000873")
    private val session = ProbeProxySession(Serial4Mode.PROXY_ZHONGCHENG, 0, { 1_000L })

    private fun ready() {
        session.configureAddresses(intArrayOf(1))
        session.setSourceAvailable(true)
    }

    private fun sample() {
        session.sourceBytes(session.sourceRequest(0, query), response)
    }

    @Test fun startupAndUnconfiguredAddressesCannotAnswer() {
        sample()
        assertNull(session.reply(query))
        session.setSourceAvailable(true)
        sample()
        assertNull(session.reply(query))
        ready()
        sample()
        assertArrayEquals(response, session.reply(query))
    }

    @Test fun stoppedSourceRejectsInflightReplyEvenAfterRestart() {
        ready()
        val token = session.sourceRequest(0, query)
        session.setSourceAvailable(false)
        session.setSourceAvailable(true)
        session.sourceBytes(token, response)
        assertNull(session.reply(query))
        sample()
        assertArrayEquals(response, session.reply(query))
    }

    @Test fun explicitPauseCannotBeUndoneByAnInflightAvailabilityUpdate() {
        ready()
        sample()
        val token = session.sourceRequest(0, query)
        session.setPollingEnabled(false)
        session.setSourceAvailable(true)
        session.sourceBytes(token, response)
        sample()
        assertNull(session.reply(query))
        session.setPollingEnabled(true)
        assertNull(session.reply(query))
        sample()
        assertArrayEquals(response, session.reply(query))
    }

    @Test fun configReloadRejectsInflightReplyEvenForTheSameAddress() {
        ready()
        sample()
        val token = session.sourceRequest(0, query)
        session.configureAddresses(intArrayOf(1))
        session.sourceBytes(token, response)
        assertNull(session.reply(query))
        sample()
        assertArrayEquals(response, session.reply(query))
    }

    @Test fun removedAddressCannotRepopulateFromOldPollingSnapshot() {
        ready()
        sample()
        session.configureAddresses(intArrayOf(2))
        assertEquals(-1L, session.sourceRequest(0, query))
        sample()
        assertNull(session.reply(query))
    }

    @Test fun unchangedAvailabilityDoesNotEraseGoodSamples() {
        ready()
        sample()
        repeat(5) { session.setSourceAvailable(true) }
        assertArrayEquals(response, session.reply(query))
    }

    @Test fun faultInvalidatesOutstandingRequestAndCachedData() {
        ready()
        sample()
        val token = session.sourceRequest(0, query)
        session.probeLost(1)
        session.sourceBytes(token, response)
        assertNull(session.reply(query))
        sample()
        assertArrayEquals(response, session.reply(query))
    }

    @Test fun legacyModesAndMismatchedProtocolsNeverProduceProxyReplies() {
        for ((mode, type) in listOf(0 to 0, 1 to 0, 2 to 1, 3 to 0, 4 to 0)) {
            val s = ProbeProxySession(mode, type, { 1_000L })
            s.configureAddresses(intArrayOf(1))
            s.setSourceAvailable(true)
            s.sourceBytes(s.sourceRequest(type, query), response)
            assertNull("mode=$mode type=$type", s.reply(query))
        }
    }

    @Test fun wrongSourceProtocolCannotPopulateSession() {
        ready()
        session.sourceBytes(session.sourceRequest(1, query), response)
        assertNull(session.reply(query))
    }

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
