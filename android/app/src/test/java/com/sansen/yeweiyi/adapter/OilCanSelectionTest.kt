package com.sansen.yeweiyi.adapter

import android.util.SparseArray
import android.view.View
import com.sansen.yeweiyi.bean.OilCan
import com.sansen.yeweiyi.view.MinOilCanView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

/** 执行真实绑定和点击回调；只替换 Android 容器及绘制控件。 */
class OilCanSelectionTest {
    private val adapter = mock(OilCanAdapter::class.java, CALLS_REAL_METHODS)
    private val selected = mutableListOf<Int>()

    init {
        adapter.setOnItemClickListener(object : OilCanAdapter.OnItemClickListener {
            override fun onClick(tankNo: Int) { selected.add(tankNo) }
            override fun onLongClick(position: Int) = Unit
        })
    }

    private fun tanks(vararg numbers: Int): SparseArray<OilCan> {
        @Suppress("UNCHECKED_CAST")
        val data = mock(SparseArray::class.java) as SparseArray<OilCan>
        `when`(data.size()).thenReturn(numbers.size)
        numbers.forEachIndexed { index, number ->
            val tank = OilCan().apply { oilCanPos = number }
            `when`(data.keyAt(index)).thenReturn(number)
            `when`(data.get(number)).thenReturn(tank)
        }
        return data
    }

    private fun holder(): OilCanAdapter.ViewHolder =
        mock(OilCanAdapter.ViewHolder::class.java).apply {
            min_oil = mock(MinOilCanView::class.java)
        }

    private fun click(viewHolder: OilCanAdapter.ViewHolder) {
        val listener = ArgumentCaptor.forClass(View.OnClickListener::class.java)
        verify(viewHolder.min_oil, atLeastOnce()).setOnClickListener(listener.capture())
        listener.value.onClick(viewHolder.min_oil)
    }

    @Test
    fun tanksOneTwoFourRenderAndSelectTheirActualNumbers() {
        val data = tanks(1, 2, 4)
        adapter.setData(data)
        for (position in 0..2) {
            val holder = holder()
            adapter.onBindViewHolder(holder, position)
            verify(holder.min_oil).setOilCan(data.get(intArrayOf(1, 2, 4)[position]))
            click(holder)
        }
        assertEquals(listOf(1, 2, 4), selected)
    }

    @Test
    fun onlyTankFourDoesNotOpenTankOne() {
        adapter.setData(tanks(4))
        val holder = holder()
        adapter.onBindViewHolder(holder, 0)
        click(holder)
        assertEquals(listOf(4), selected)
    }

    @Test
    fun multipleGapsDoNotTurnListPositionsIntoTankNumbers() {
        adapter.setData(tanks(2, 5, 16))
        for (position in 0..2) {
            val holder = holder()
            adapter.onBindViewHolder(holder, position)
            click(holder)
        }
        assertEquals(listOf(2, 5, 16), selected)
    }

    @Test
    fun pendingListRefreshStillSelectsTheTankRenderedOnTheCard() {
        adapter.setData(tanks(1, 2, 4))
        val holder = holder()
        adapter.onBindViewHolder(holder, 2)
        adapter.setData(tanks(1, 2, 5))
        click(holder)
        assertEquals(listOf(4), selected)
    }

    @Test
    fun recycledCardUsesNewBindingInsteadOfPreviousTank() {
        adapter.setData(tanks(1, 2, 4))
        val holder = holder()
        adapter.onBindViewHolder(holder, 2)
        click(holder)
        adapter.onBindViewHolder(holder, 0)
        click(holder)
        assertEquals(listOf(4, 1), selected)
    }
}
