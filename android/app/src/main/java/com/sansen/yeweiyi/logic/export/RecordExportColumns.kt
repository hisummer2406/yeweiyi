package com.sansen.yeweiyi.logic.export

import com.sansen.yeweiyi.R
import com.sansen.yeweiyi.bean.GuanBiaoData
import com.sansen.yeweiyi.bean.OilWork
import com.sansen.yeweiyi.db.Alarm
import com.sansen.yeweiyi.db.CommitWork
import com.sansen.yeweiyi.db.OilInOut
import com.sansen.yeweiyi.db.OutStock
import com.sansen.yeweiyi.db.StockSave
import com.sansen.yeweiyi.logic.shift.ShiftRecordBuilder

/** 导出表格的一列：列名（字符串资源 id）+ 从一条记录取该列的值。 */
class ExportColumn<T>(val titleRes: Int, val value: (T) -> Any?)

/** 按罐号查当前罐配置。记录列表页显示的介质、算 kg 用的密度都取罐的当前设置，导出要跟它一致。 */
interface TankLookup {
    fun jieZhi(oilId: Int): String
    fun density(oilId: Int): Double
}

/** 把交班记录的 info（Gson 串）解析成各罐明细。 */
fun interface ShiftInfoParser {
    fun parse(info: String?): List<OilWork>
}

/** 交班导出的一行 = 一次交班里的一个罐。 */
class ShiftTankRow(val shift: CommitWork, val tank: OilWork)

/**
 * 各记录页导出的列定义。列顺序、列名、取值口径跟对应页面的列表表头和 Adapter 保持一致
 * （不含演示模式缩放），改列表显示时要同步改这里；RecordExportHeadersTest 会解析布局核对列名顺序。
 */
object RecordExportColumns {

    /** 同 fragment_recode_alarm / AlarmAdapter，介质用记录里存的 */
    @JvmField
    val ALARM: List<ExportColumn<Alarm>> = listOf(
        ExportColumn(R.string.guanhao) { it.guanHao },
        ExportColumn(R.string.medium) { it.jieZhi },
        ExportColumn(R.string.alarm_info) { it.info },
        ExportColumn(R.string.baojingshijian) { it.time },
    )

    /** 同 fragment_recode_inout / InOutAdapter：入库量L = 后量−前量，kg = L × 当前密度 */
    @JvmStatic
    fun inStock(tanks: TankLookup): List<ExportColumn<OilInOut>> = listOf(
        ExportColumn(R.string.tank_no) { it.oilId },
        ExportColumn(R.string.medium) { tanks.jieZhi(it.oilId) },
        ExportColumn(R.string.time) { it.time },
        ExportColumn(R.string.beginHeight) { it.beginHeigth },
        ExportColumn(R.string.qianliang) { it.beginVolum },
        ExportColumn(R.string.endHeight) { it.endHeigth },
        ExportColumn(R.string.houliang) { it.endVolum },
        ExportColumn(R.string.rukuliangl) { it.endVolum - it.beginVolum },
        ExportColumn(R.string.rukuliangkg) { (it.endVolum - it.beginVolum) * tanks.density(it.oilId) },
    )

    /** 同 fragment_recode_out / OutStockAdapter：出库量L = 前量−后量，kg = L × 当前密度 */
    @JvmStatic
    fun outStock(tanks: TankLookup): List<ExportColumn<OutStock>> = listOf(
        ExportColumn(R.string.tank_no) { it.oilId },
        ExportColumn(R.string.medium) { tanks.jieZhi(it.oilId) },
        ExportColumn(R.string.time) { it.time },
        ExportColumn(R.string.beginHeight) { it.beginHeigth },
        ExportColumn(R.string.qianliang) { it.beginVolum },
        ExportColumn(R.string.endHeight) { it.endHeigth },
        ExportColumn(R.string.houliang) { it.endVolum },
        ExportColumn(R.string.chukuliangl) { it.beginVolum - it.endVolum },
        ExportColumn(R.string.chukuliangkg) { (it.beginVolum - it.endVolum) * tanks.density(it.oilId) },
    )

    /** 同 fragment_recode_stock / StockSaveAdapter */
    @JvmStatic
    fun stock(tanks: TankLookup): List<ExportColumn<StockSave>> = listOf(
        ExportColumn(R.string.tank_no) { it.oilId },
        ExportColumn(R.string.medium) { tanks.jieZhi(it.oilId) },
        ExportColumn(R.string.kuncunshengchengshijian) { it.time },
        ExportColumn(R.string.yewei_u) { it.height },
        ExportColumn(R.string.rongji_u) { it.volum },
        ExportColumn(R.string.shuigao_u) { it.waterheigth },
        ExportColumn(R.string.wendu_u) { it.temperature },
        ExportColumn(R.string.zhiliang_u) { it.weight },
    )

    /**
     * 交班每罐一行：开始/结束时间同交班列表（不含序号），其余同 fragment_recode_detailcommitwork /
     * DetailCommitWorkAdapter。行数据用 [shiftTankRows] 展开。
     */
    @JvmStatic
    fun shift(tanks: TankLookup): List<ExportColumn<ShiftTankRow>> = listOf(
        ExportColumn(R.string.commitwork_start_time) { it.shift.time },
        ExportColumn(R.string.commitwork_end_time) { it.shift.endTime },
        ExportColumn(R.string.tank_no) { it.tank.oilId },
        // 同列表：优先开始交班时记录的介质，旧记录才退回当前罐设置（#49）
        ExportColumn(R.string.medium) {
            ShiftRecordBuilder.displayOilType(it.tank.jieZhi, tanks.jieZhi(it.tank.oilId))
        },
        ExportColumn(R.string.qiangao) { it.tank.beginHeight },
        ExportColumn(R.string.qianliang) { it.tank.beginVolume },
        ExportColumn(R.string.hougao) { it.tank.endHeight },
        ExportColumn(R.string.houliang) { it.tank.endVolume },
        ExportColumn(R.string.chukuliangl) { it.tank.shellVolume },
        ExportColumn(R.string.chukuliangkg) { it.tank.shellWeight },
    )

    /** 罐表页"U盘导出"当前预览的罐表：同 fragment_createtable 列表表头（不含编号）；表头行会被导入时跳过 */
    @JvmField
    val OIL_TABLE: List<ExportColumn<GuanBiaoData>> = listOf(
        ExportColumn(R.string.height_mm) { it.hight },
        ExportColumn(R.string.rongji_u) { it.rongJi },
    )

    @JvmStatic
    fun shiftTankRows(shifts: List<CommitWork>, parser: ShiftInfoParser): List<ShiftTankRow> =
        shifts.flatMap { shift -> parser.parse(shift.info).map { ShiftTankRow(shift, it) } }

    @JvmStatic
    fun <T> rows(columns: List<ExportColumn<T>>, records: List<T>): List<List<Any?>> =
        records.map { record -> columns.map { it.value(record) } }
}
