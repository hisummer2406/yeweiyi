package com.sansen.yeweiyi.logic.thingsboard.rpc.handler

import com.sansen.yeweiyi.db.OilCanSet
import com.sansen.yeweiyi.db.OilCanTable
import com.sansen.yeweiyi.factory.ICreateTable
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResultStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 尺寸到 EllipseTable 参数的换算须与 CreateOilTableFragment.createtable_bt_build 一致（EllipseTable 含 Log，这里用替身记录参数）。 */
class SetTankTableHandlerTest {

    private class RecordingTable : ICreateTable {
        var call: String? = null
        private fun table(): OilCanTable = OilCanTable().apply {
            height = intArrayOf(10, 20, 30)
            volum = doubleArrayOf(1.5, 3.0, 4.5)
        }

        override fun craeteOilCanTalbe(L: Double, R: Double, c1: Double, c2: Double): OilCanTable {
            call = "horizontal($L,$R,$c1,$c2)"; return table()
        }

        override fun craeteOilCanTalbe(L: Double, K: Double, G: Double): OilCanTable {
            call = "cuboid($L,$K,$G)"; return table()
        }

        override fun craeteOilCanTalbe(L: Double, K: Double): OilCanTable {
            call = "vertical($L,$K)"; return table()
        }
    }

    private lateinit var repo: FakeOilTankRepository
    private lateinit var gen: RecordingTable
    private var refreshes = 0
    private lateinit var handler: SetTankTableHandler

    @Before
    fun setUp() {
        repo = FakeOilTankRepository()
        repo.byId[2] = OilCanSet().apply { pk_oilCanId = 2; enable = 1 }
        repo.byId[3] = OilCanSet().apply { pk_oilCanId = 3; enable = 0 }
        gen = RecordingTable()
        refreshes = 0
        handler = SetTankTableHandler(repo, gen, Runnable { refreshes++ }) { "2026-11-14 12:00" }
    }

    private fun call(args: String): RpcResult {
        val json = "{\"method\":\"config.setTankTable\",\"params\":{\"protocolVersion\":1," +
            "\"commandId\":\"cmd-1\",\"expiresAtMs\":9999999999999,\"args\":$args}}"
        return handler.handle(RpcRequestEnvelope.parse("req-1", json))
    }

    private fun assertRejected(result: RpcResult) {
        assertEquals(RpcResultStatus.REJECTED, result.status)
        assertEquals(RpcErrorCode.INVALID_PARAMS, result.errorCode)
        assertNull("被拒绝时不能写库", repo.lastSavedTable)
        assertEquals(0, refreshes)
    }

    @Test
    fun horizontalUsesTheSiteConversionAndWritesTheTank() {
        val r = call("{\"tank\":2,\"shape\":\"horizontal\",\"diameterMm\":2600,\"lengthMm\":8000,\"headMm\":600}")
        assertEquals(RpcResultStatus.SUCCEEDED, r.status)
        assertEquals("horizontal(8.0,1.3,0.6,0.0)", gen.call)
        val saved = repo.lastSavedTable!!
        assertEquals(2, saved.pk_oilCanId)
        assertEquals("10 20 30 ", saved.heightstrarray)
        assertEquals("1.5 3.0 4.5 ", saved.volumstrarray)
        assertEquals("2026-11-14 12:00", saved.gmt_modified)
        assertEquals(1, refreshes)
        assertEquals(3, r.data["pointCount"])
        assertEquals(30, r.data["maxHeightMm"])
    }

    @Test
    fun flatHeadIsAllowed() {
        assertEquals(RpcResultStatus.SUCCEEDED, call("{\"tank\":2,\"shape\":\"horizontal\",\"diameterMm\":1200,\"lengthMm\":3000,\"headMm\":0}").status)
        assertEquals("horizontal(3.0,0.6,0.0,0.0)", gen.call)
    }

    @Test
    fun verticalPassesDiameterThenHeight() {
        assertEquals(RpcResultStatus.SUCCEEDED, call("{\"tank\":2,\"shape\":\"vertical\",\"heightMm\":2500,\"diameterMm\":2000}").status)
        assertEquals("vertical(2.0,2.5)", gen.call)
    }

    @Test
    fun cuboidPassesLengthWidthHeight() {
        assertEquals(RpcResultStatus.SUCCEEDED, call("{\"tank\":2,\"shape\":\"cuboid\",\"heightMm\":1500,\"lengthMm\":3000,\"widthMm\":2000}").status)
        assertEquals("cuboid(3.0,2.0,1.5)", gen.call)
    }

    @Test
    fun rejectsBadTankShapeAndFields() {
        assertRejected(call("{\"tank\":3,\"shape\":\"vertical\",\"heightMm\":2500,\"diameterMm\":2000}"))
        assertRejected(call("{\"tank\":17,\"shape\":\"vertical\",\"heightMm\":2500,\"diameterMm\":2000}"))
        assertRejected(call("{\"tank\":2,\"shape\":\"sphere\",\"heightMm\":2500,\"diameterMm\":2000}"))
        assertRejected(call("{\"tank\":2,\"shape\":\"vertical\",\"heightMm\":2500}"))
        assertRejected(call("{\"tank\":2,\"shape\":\"vertical\",\"heightMm\":2500,\"diameterMm\":2000,\"lengthMm\":1}"))
        assertRejected(call("{\"tank\":2,\"shape\":\"vertical\",\"heightMm\":\"2500\",\"diameterMm\":2000}"))
        assertRejected(call("{\"tank\":2,\"shape\":\"vertical\",\"heightMm\":99,\"diameterMm\":2000}"))
        assertRejected(call("{\"tank\":2,\"shape\":\"cuboid\",\"heightMm\":1500,\"lengthMm\":20001,\"widthMm\":2000}"))
        assertRejected(call("{\"tank\":2,\"shape\":\"horizontal\",\"diameterMm\":2600,\"lengthMm\":8000,\"headMm\":-1}"))
        assertNull(gen.call)
    }

    @Test
    fun nonFinitePointsAreNotCountedInTheReceipt() {
        val nanTable = object : ICreateTable by gen {
            override fun craeteOilCanTalbe(L: Double, R: Double, c1: Double, c2: Double): OilCanTable = OilCanTable().apply {
                height = intArrayOf(10, 20, 30)
                volum = doubleArrayOf(1.5, 3.0, Double.NaN)
            }
        }
        handler = SetTankTableHandler(repo, nanTable, Runnable { refreshes++ }) { "t" }
        val r = call("{\"tank\":2,\"shape\":\"horizontal\",\"diameterMm\":2600,\"lengthMm\":8000,\"headMm\":600}")
        assertEquals(RpcResultStatus.SUCCEEDED, r.status)
        assertEquals(2, r.data["pointCount"])
        assertEquals(20, r.data["maxHeightMm"])
        assertEquals(3.0, r.data["maxVolumeL"])
    }

    @Test
    fun aFailedWriteIsFailedAndDoesNotRefresh() {
        repo.throwOnTableUpdate = true
        val r = call("{\"tank\":2,\"shape\":\"vertical\",\"heightMm\":2500,\"diameterMm\":2000}")
        assertEquals(RpcResultStatus.FAILED, r.status)
        assertEquals(RpcErrorCode.INTERNAL_ERROR, r.errorCode)
        assertEquals(0, refreshes)
    }

    @Test
    fun shapeFieldListsAreTheDocumentedOnes() {
        assertArrayEquals(arrayOf("diameterMm", "lengthMm", "headMm"), SetTankTableHandler.SHAPE_FIELDS["horizontal"]!!.toTypedArray())
        assertArrayEquals(arrayOf("heightMm", "diameterMm"), SetTankTableHandler.SHAPE_FIELDS["vertical"]!!.toTypedArray())
        assertArrayEquals(arrayOf("heightMm", "lengthMm", "widthMm"), SetTankTableHandler.SHAPE_FIELDS["cuboid"]!!.toTypedArray())
    }
}
