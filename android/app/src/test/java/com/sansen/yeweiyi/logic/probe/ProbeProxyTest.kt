package com.sansen.yeweiyi.logic.probe

import com.sansen.yeweiyi.factory.LDCompany
import com.sansen.yeweiyi.factory.ZhongChengCompany
import com.sansen.yeweiyi.utils.CRCheck
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProbeProxyTest {
    private var time = 1_000L
    private val proxy = ProbeProxy(0, { time })
    private val modbus = ProbeProxy(1, { time })

    // 应答摘自 docs/协议/新版中成探针协议.doc 与 协议探针长度查询.doc。
    private val level = hex("FEFE010D44442401A6442401A6000000000873")
    private val temperature = hex("FEFE010B45018B03600360036001600ECB")
    private val length = hex("FEFE0208420022190B070AFA0053")
    private val levelQuery = hex("FEFE01034400000392")
    private val temperatureQuery = hex("FEFE0103450000007B")
    private val lengthQuery = hex("FEFE0203420000024B")

    private fun cache(p: ProbeProxy, query: ByteArray, response: ByteArray) {
        p.onSourceRequest(query)
        p.onSourceBytes(response)
    }

    @Test fun officialFramesAreReturnedUnchangedForAllThreeQueries() {
        listOf(levelQuery to level, temperatureQuery to temperature, lengthQuery to length).forEach { (q, r) ->
            cache(proxy, q, r)
            assertArrayEquals(r, proxy.reply(q))
        }
    }

    @Test fun existingAppQueryBuildersCanReadTheProxy() {
        cache(proxy, levelQuery, level)
        val actual = ZhongChengCompany.getInstance().sendProbeInfo("FEFE", "01", null)
        assertArrayEquals(level, proxy.reply(hex(actual)))
        val q = hex(LDCompany.getInstance().sendProbeInfo("", "01", null))
        val response = modbusFrame(hex("010304000003E8"))
        cache(modbus, q, response)
        assertArrayEquals(response, modbus.reply(q))
    }

    @Test fun rawHeightRemains1000ForDevicesWithDifferentCorrections() {
        val raw = zcFrame(hex("FEFE010D44447A0000447A000000000000"))
        cache(proxy, levelQuery, raw)
        val a = proxy.reply(levelQuery)!!
        val b = proxy.reply(levelQuery)!!
        val original = java.nio.ByteBuffer.wrap(a, 5, 4).float.toDouble()
        assertEquals(1100.0, original + 100, 0.0)
        assertEquals(1300.0, java.nio.ByteBuffer.wrap(b, 5, 4).float + 300.0, 0.0)
        assertArrayEquals(raw, a)
        assertArrayEquals(raw, b)
    }

    @Test fun cachesAreIsolatedByAddressAndFunction() {
        cache(proxy, levelQuery, level)
        assertNull(proxy.reply(temperatureQuery))
        assertNull(proxy.reply(lengthQuery))
        assertNull(proxy.reply(zcFrame(hex("FEFE0203440000"))))
        cache(proxy, temperatureQuery, temperature)
        assertArrayEquals(level, proxy.reply(levelQuery))
        assertArrayEquals(temperature, proxy.reply(temperatureQuery))
    }

    @Test fun responseRequiresMatchingSourceRequest() {
        proxy.onSourceBytes(level)
        assertNull(proxy.reply(levelQuery))
        proxy.onSourceRequest(temperatureQuery)
        proxy.onSourceBytes(level)
        assertNull(proxy.reply(levelQuery))
        proxy.onSourceRequest(levelQuery)
        time += 1_000
        proxy.onSourceBytes(level)
        assertNull(proxy.reply(levelQuery))
    }

    @Test fun halfFramesAndConcatenatedResponsesAreReassembled() {
        proxy.onSourceRequest(levelQuery)
        proxy.onSourceRequest(temperatureQuery)
        proxy.onSourceBytes(level.copyOfRange(0, 8))
        assertNull(proxy.reply(levelQuery))
        proxy.onSourceBytes(level.copyOfRange(8, level.size) + temperature)
        assertArrayEquals(level, proxy.reply(levelQuery))
        assertArrayEquals(temperature, proxy.reply(temperatureQuery))
    }

    @Test fun mixedSourceFramesRetainTheirKeysAcrossRandomChunkBoundaries() {
        val random = java.util.Random(314159)
        for ((p, pairs) in listOf(
            proxy to listOf(levelQuery to level, temperatureQuery to temperature, lengthQuery to length),
            modbus to listOf(
                modbusFrame(hex("010300020002")) to modbusFrame(hex("010304000003E8")),
                modbusFrame(hex("0A0300020002")) to modbusFrame(hex("0A0304000007D0"))
            )
        )) {
            repeat(100) {
                p.clear()
                pairs.forEach { (query, _) -> p.onSourceRequest(query) }
                val bytes = pairs.fold(byteArrayOf(0x55, 0x00)) { stream, (_, response) -> stream + response }
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + 1 + random.nextInt(9))
                    p.onSourceBytes(bytes.copyOfRange(offset, end))
                    time++
                    offset = end
                }
                pairs.forEach { (query, response) -> assertArrayEquals(response, p.reply(query)) }
            }
        }
    }

    @Test fun callersMayReuseInputBuffersWhileBothStreamsHavePartialFrames() {
        for ((p, pair) in listOf(
            proxy to (levelQuery to level),
            modbus to (modbusFrame(hex("010300020002")) to modbusFrame(hex("010304000003E8")))
        )) {
            val (query, response) = pair
            p.onSourceRequest(query)
            val sourcePrefix = response.copyOfRange(0, 5)
            p.onSourceBytes(sourcePrefix)
            sourcePrefix.fill(0)
            p.onSourceBytes(response.copyOfRange(5, response.size))
            val queryPrefix = query.copyOfRange(0, 4)
            assertNull(p.reply(queryPrefix))
            queryPrefix.fill(0)
            assertArrayEquals(response, p.reply(query.copyOfRange(4, query.size)))
        }
    }

    @Test fun latestValidResponseWinsWhenSameAddressFramesArriveTogether() {
        val latest = zcFrame(hex("FEFE010D44447A0000447A000000000000"))
        proxy.onSourceRequest(levelQuery)
        proxy.onSourceBytes(level + latest)
        assertArrayEquals(latest, proxy.reply(levelQuery))
    }

    @Test fun everyPossibleSplitPreservesOfficialFrames() {
        for ((q, r) in listOf(levelQuery to level, temperatureQuery to temperature, lengthQuery to length)) {
            for (i in 1 until r.size) {
                proxy.clear()
                proxy.onSourceRequest(q)
                proxy.onSourceBytes(r.copyOfRange(0, i))
                proxy.onSourceBytes(r.copyOfRange(i, r.size))
                assertArrayEquals("response split $i", r, proxy.reply(q))
            }
            for (i in 1 until q.size) {
                proxy.clear()
                cache(proxy, q, r)
                assertNull(proxy.reply(q.copyOfRange(0, i)))
                time += 10
                assertArrayEquals("query split $i", r, proxy.reply(q.copyOfRange(i, q.size)))
            }
        }
    }

    @Test fun expiredQueryFragmentDoesNotBecomeAReply() {
        cache(proxy, levelQuery, level)
        assertNull(proxy.reply(levelQuery.copyOfRange(0, 5)))
        time += 100
        assertNull(proxy.reply(levelQuery.copyOfRange(5, levelQuery.size)))
        assertArrayEquals(level, proxy.reply(levelQuery))
    }

    @Test fun expiredSourceFragmentDoesNotCombineWithNewRequest() {
        proxy.onSourceRequest(levelQuery)
        proxy.onSourceBytes(level.copyOfRange(0, 8))
        time += 1_000
        proxy.onSourceRequest(levelQuery)
        proxy.onSourceBytes(level.copyOfRange(8, level.size))
        assertNull(proxy.reply(levelQuery))
        proxy.onSourceBytes(level)
        assertArrayEquals(level, proxy.reply(levelQuery))
    }

    @Test fun badCrcDoesNotReplaceExistingSampleOrRenewItsAge() {
        cache(proxy, levelQuery, level)
        time += 9_000
        val bad = level.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        cache(proxy, levelQuery, bad)
        assertArrayEquals(level, proxy.reply(levelQuery))
        time += 1_000
        assertNull(proxy.reply(levelQuery))
    }

    @Test fun sourceResynchronizesAfterNoiseAndCorruptFrame() {
        val bad = level.copyOf().also { it[it.lastIndex] = 0 }
        proxy.onSourceRequest(levelQuery)
        proxy.onSourceBytes(hex("FFFEFE00") + bad + level)
        assertArrayEquals(level, proxy.reply(levelQuery))
    }

    @Test fun cachedReadsNeverExtendSamplingLifetime() {
        cache(proxy, levelQuery, level)
        repeat(9) { time += 1_000; assertArrayEquals(level, proxy.reply(levelQuery)) }
        time += 1_000
        assertNull(proxy.reply(levelQuery))
        cache(proxy, levelQuery, level)
        assertArrayEquals(level, proxy.reply(levelQuery))
    }

    @Test fun temperatureAndLengthHaveIndependentExpiry() {
        cache(proxy, levelQuery, level)
        cache(proxy, temperatureQuery, temperature)
        cache(proxy, lengthQuery, length)
        time += ProbeProxy.LEVEL_TTL_MS
        assertNull(proxy.reply(levelQuery))
        assertNotNull(proxy.reply(temperatureQuery))
        time = 1_000 + ProbeProxy.TEMPERATURE_TTL_MS
        assertNull(proxy.reply(temperatureQuery))
        assertNotNull(proxy.reply(lengthQuery))
        time = 1_000 + ProbeProxy.LENGTH_TTL_MS
        assertNull(proxy.reply(lengthQuery))
    }

    @Test fun invalidatingOneAddressPreservesOtherAddresses() {
        cache(proxy, levelQuery, level)
        cache(proxy, temperatureQuery, temperature)
        cache(proxy, lengthQuery, length)
        proxy.invalidateAddress(1)
        assertNull(proxy.reply(levelQuery))
        assertNull(proxy.reply(temperatureQuery))
        assertArrayEquals(length, proxy.reply(lengthQuery))
        proxy.onSourceBytes(level)
        assertNull(proxy.reply(levelQuery))
        cache(proxy, levelQuery, level)
        assertArrayEquals(level, proxy.reply(levelQuery))
    }

    @Test fun clearRemovesSamplesOutstandingRequestsAndFragments() {
        cache(proxy, levelQuery, level)
        proxy.onSourceRequest(temperatureQuery)
        proxy.onSourceBytes(temperature.copyOfRange(0, 8))
        proxy.reply(levelQuery.copyOfRange(0, 5))
        proxy.clear()
        proxy.onSourceBytes(temperature.copyOfRange(8, temperature.size))
        assertNull(proxy.reply(levelQuery.copyOfRange(5, levelQuery.size)))
        assertNull(proxy.reply(levelQuery))
        assertNull(proxy.reply(temperatureQuery))
    }

    @Test fun callerCannotMutateCachedBytes() {
        val input = level.copyOf()
        cache(proxy, levelQuery, input)
        input.fill(0)
        proxy.reply(levelQuery)!!.fill(0)
        assertArrayEquals(level, proxy.reply(levelQuery))
    }

    @Test fun unsupportedWritesBadQueriesAndOtherProtocolsReceiveNoAnswer() {
        cache(proxy, levelQuery, level)
        assertNull(proxy.reply(zcFrame(hex("FEFE0103430000"))))
        assertNull(proxy.reply(levelQuery.copyOf().also { it[it.lastIndex] = 0 }))
        assertNull(proxy.reply(zcFrame(hex("FEFE0003440000"))))
        assertNull(proxy.reply(modbusFrame(hex("010300020002"))))
        assertArrayEquals(level, proxy.reply(levelQuery))
    }

    @Test fun onlyLatestCompleteQueryInABatchIsAnswered() {
        cache(proxy, levelQuery, level)
        cache(proxy, temperatureQuery, temperature)
        assertArrayEquals(temperature, proxy.reply(levelQuery + temperatureQuery))
    }

    @Test fun boundedBuffersRecoverAfterOverflow() {
        cache(proxy, levelQuery, level)
        assertNull(proxy.reply(ByteArray(4096) { 0xFE.toByte() }))
        assertArrayEquals(level, proxy.reply(levelQuery))
        proxy.onSourceRequest(temperatureQuery)
        proxy.onSourceBytes(ByteArray(4096) { 0xFE.toByte() })
        proxy.onSourceBytes(temperature)
        assertArrayEquals(temperature, proxy.reply(temperatureQuery))
    }

    @Test fun modbusReadRangeAndAddressMustMatch() {
        val q = modbusFrame(hex("010300020002"))
        val r = modbusFrame(hex("010304000003E8"))
        cache(modbus, modbusFrame(hex("010300060002")), r)
        assertNull(modbus.reply(q))
        cache(modbus, q, r)
        assertArrayEquals(r, modbus.reply(q))
        assertNull(modbus.reply(modbusFrame(hex("010300060002"))))
        assertNull(modbus.reply(modbusFrame(hex("010300020001"))))
        assertNull(modbus.reply(modbusFrame(hex("020300020002"))))
        assertNull(modbus.reply(modbusFrame(hex("010600020002"))))
    }

    @Test fun modbusRejectsExceptionsAndWrongByteCount() {
        val q = modbusFrame(hex("010300020002"))
        cache(modbus, q, modbusFrame(hex("018302")))
        assertNull(modbus.reply(q))
        cache(modbus, q, modbusFrame(hex("0103020001")))
        assertNull(modbus.reply(q))
        val r = modbusFrame(hex("010304000003E8"))
        cache(modbus, q, r)
        assertArrayEquals(r, modbus.reply(q))
    }

    @Test fun unsupportedSourceQueryCancelsPreviousModbusResponseContext() {
        val q = modbusFrame(hex("010300020002"))
        modbus.onSourceRequest(q)
        modbus.onSourceRequest(modbusFrame(hex("010300060002")))
        modbus.onSourceBytes(modbusFrame(hex("010304000003E8")))
        assertNull(modbus.reply(q))
    }

    @Test fun unsupportedSourceQueryDropsPartialModbusResponse() {
        val q = modbusFrame(hex("010300020002"))
        val r = modbusFrame(hex("010304000003E8"))
        modbus.onSourceRequest(q)
        modbus.onSourceBytes(r.copyOfRange(0, 4))
        modbus.onSourceRequest(modbusFrame(hex("010300060002")))
        modbus.onSourceRequest(q)
        modbus.onSourceBytes(r.copyOfRange(4, r.size))
        assertNull(modbus.reply(q))
        modbus.onSourceBytes(r)
        assertArrayEquals(r, modbus.reply(q))
    }

    @Test fun modbusFramesSupportEverySplitAndExpiry() {
        val q = modbusFrame(hex("0A0300020002"))
        val r = modbusFrame(hex("0A0304000003E8"))
        for (i in 1 until r.size) {
            modbus.clear()
            modbus.onSourceRequest(q)
            modbus.onSourceBytes(r.copyOfRange(0, i))
            modbus.onSourceBytes(r.copyOfRange(i, r.size))
            assertArrayEquals(r, modbus.reply(q))
        }
        time += ProbeProxy.LEVEL_TTL_MS
        assertNull(modbus.reply(q))
    }

    @Test fun concurrentSamplingAndReadingReturnOnlyWholeFrames() {
        val p = ProbeProxy(0, { 1_000L })
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writer = executor.submit {
                start.await()
                repeat(1_000) { cache(p, levelQuery, level) }
            }
            val reader = executor.submit {
                start.await()
                repeat(1_000) { p.reply(levelQuery)?.let { bytes -> assertArrayEquals(level, bytes) } }
            }
            start.countDown()
            writer.get(5, TimeUnit.SECONDS)
            reader.get(5, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }

    private fun zcFrame(body: ByteArray): ByteArray = body + hex(CRCheck.crc12(body.size - 2, body.copyOfRange(2, body.size)))
    private fun modbusFrame(body: ByteArray): ByteArray {
        val crc = CRCheck.crc16Modbus(body)
        return body + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
