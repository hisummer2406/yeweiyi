package com.sansen.yeweiyi.utils

import com.sansen.yeweiyi.application.MyApplication
import com.sansen.yeweiyi.logic.Const
import com.sansen.yeweiyi.logic.export.TankLookup
import com.sansen.yeweiyi.manager.OilcanDataManager

/** 列表 Adapter 同款口径：罐不存在时介质取 Const.getOilType(0)、密度取 0。 */
object CurrentTankLookup : TankLookup {
    override fun jieZhi(oilId: Int): String {
        val tank = OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilId)
        return Const.getOilType(tank?.oilType ?: 0)
    }

    override fun density(oilId: Int): Double =
        OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilId)?.density ?: 0.0
}
