package com.sansen.yeweiyi.fragment

import android.widget.TextView
import com.sansen.yeweiyi.application.MyApplication
import com.sansen.yeweiyi.manager.OilcanDataManager
import com.sansen.yeweiyi.utils.SharePrefUtil
import com.sansen.yeweiyi.view.CustomProgressView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class OilCanDetailTest {
    private fun field(name: String) = OilCanFragment::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    @Test
    fun reusedDetailSwitchesToTankFourAndRefreshesImmediately() {
        val detail = mock(OilCanFragment::class.java)
        doCallRealMethod().`when`(detail).UpdatePostion(anyInt())
        field("oilCanPos").setInt(detail, 2)
        field("oilcan_tv_tankno").set(detail, mock(TextView::class.java))

        detail.UpdatePostion(4)

        assertEquals(4, field("oilCanPos").getInt(detail))
        verify(detail).setData()
    }

    @Test
    fun selectionBeforeViewCreationKeepsTankNumberWithoutTouchingViews() {
        val detail = mock(OilCanFragment::class.java)
        doCallRealMethod().`when`(detail).UpdatePostion(anyInt())
        detail.UpdatePostion(4)
        assertEquals(4, field("oilCanPos").getInt(detail))
        verify(detail, never()).setData()
    }

    @Test
    fun missingTankClearsEveryReadingAndTheTankGraphic() {
        val detail = mock(OilCanFragment::class.java)
        doCallRealMethod().`when`(detail).setData()
        field("oilCanPos").setInt(detail, 4)
        val manager = mock(OilcanDataManager::class.java)
        detail.mOilcanDataManager = manager
        val fields = listOf("oilcan_tv_tankno", "oilcan_tv_oiltype", "oilcan_tv_oillevel",
            "oilcan_tv_oilvolume", "oilcan_tv_oilweight", "oilcan_tv_oiladdible",
            "oilcan_tv_waterlevel", "oilcan_tv_temperature", "oilcan_tv_zhiliang", "oilcan_tv_midu")
        val values = fields.map { name ->
            mock(TextView::class.java).also { field(name).set(detail, it) }
        }
        val graphic = mock(CustomProgressView::class.java)
        field("oilcan_view_progress").set(detail, graphic)
        val app = mock(MyApplication::class.java)
        mockStatic(MyApplication::class.java).use { application ->
            application.`when`<MyApplication> { MyApplication.getInstance() }.thenReturn(app)
            mockStatic(SharePrefUtil::class.java).use {
                detail.setData()
            }
        }

        verify(manager).getData(4)
        values.forEach { verify(it).setText("") }
        verify(graphic).setCoord(0, 0, 0)
        verify(graphic).invalidate()
    }
}
