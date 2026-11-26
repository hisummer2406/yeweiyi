package com.sansen.yeweiyi.logic.thingsboard.rpc.handler

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sansen.yeweiyi.db.OilCanTable
import com.sansen.yeweiyi.factory.ICreateTable
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcErrorCode
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcMethodHandler
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcRequestEnvelope
import com.sansen.yeweiyi.logic.thingsboard.rpc.RpcResult
import com.sansen.yeweiyi.repository.OilTankRepository
import java.math.BigDecimal

/**
 * `config.setTankTable`（#49）：云端只下发罐型和尺寸，罐表由本机 [ICreateTable] 生成并写入 `oiltablearray`，
 * 尺寸换算照搬 `CreateOilTableFragment.createtable_bt_build`。字段与范围见 `docs/远程配置下发-字段清单.md`；
 * 任一字段非法整条拒绝不写库，写库成功才回成功，再刷新罐配置（随之重发标定表属性供云端回显）。
 */
class SetTankTableHandler(
    private val oilTankRepository: OilTankRepository,
    private val createTable: ICreateTable,
    private val onTableUpdated: Runnable,
    private val now: () -> String,
) : RpcMethodHandler {

    override fun handle(request: RpcRequestEnvelope): RpcResult {
        val args: JsonObject = request.args
        val tank = SetTankSettingsHandler.intValue(args.get("tank"))
        if (tank == null || tank < 1 || tank > SetTankSettingsHandler.MAX_TANK) {
            return invalid("tank 必须是 1～${SetTankSettingsHandler.MAX_TANK} 的整数")
        }
        val set = oilTankRepository.findOilCanSetById(tank)
        if (set == null || set.enable != 1) {
            return invalid("罐 $tank 不存在或未启用")
        }
        val shapeElement = args.get("shape")
        val shape = if (shapeElement != null && shapeElement.isJsonPrimitive && shapeElement.asJsonPrimitive.isString) shapeElement.asString else null
        val fields = SHAPE_FIELDS[shape] ?: return invalid("shape 必须是 horizontal / vertical / cuboid 之一")

        val extra = args.keySet().filter { it != "tank" && it != "shape" && it !in fields }
        if (extra.isNotEmpty()) {
            return invalid("$shape 不认识的字段: ${extra.sorted()}")
        }
        val mm = HashMap<String, Double>()
        for (field in fields) {
            val min = if (field == HEAD) 0.0 else MIN_MM
            mm[field] = number(args.get(field)) ?: return invalid("$field 必须是数字（mm）")
            val v = mm.getValue(field)
            if (v < min || v > MAX_MM) {
                return invalid("$field 超出范围 ${min.toInt()}～${MAX_MM.toInt()} mm: $v")
            }
        }

        val table = try {
            generate(shape!!, mm)
        } catch (e: RuntimeException) {
            return RpcResult.failed(RpcErrorCode.INTERNAL_ERROR, "生成罐表失败: ${e.message}")
        }
        if (table.height == null || table.height.isEmpty()) {
            return invalid("尺寸生成的罐表为空")
        }
        table.pk_oilCanId = tank
        table.ArrayToString()
        table.gmt_modified = now()
        try {
            oilTankRepository.updateOilCanTableSync(table)
        } catch (e: Exception) {
            return RpcResult.failed(RpcErrorCode.INTERNAL_ERROR, "写入罐表失败: ${e.message}")
        }
        onTableUpdated.run()

        // 只计有限值的点：与标定属性上报口径相同（NaN 点不上报），也避免 Gson 序列化 NaN 失败
        val finite = table.height.indices.filter { it < table.volum.size && table.volum[it].isFinite() }
        val data = LinkedHashMap<String, Any>()
        data["tank"] = tank
        data["shape"] = shape
        data["pointCount"] = finite.size
        if (finite.isNotEmpty()) {
            data["maxHeightMm"] = table.height[finite.last()]
            data["maxVolumeL"] = table.volum[finite.last()]
        }
        return RpcResult.succeeded("罐表已更新", data)
    }

    /** 与 CreateOilTableFragment.createtable_bt_build 相同的单位换算与重载选择。 */
    private fun generate(shape: String, mm: Map<String, Double>): OilCanTable = when (shape) {
        // 现场：L = 罐长/1000，R1 = 直径/2000，C1 = 封头/1000 → (L, R1, C1, 0)
        HORIZONTAL -> createTable.craeteOilCanTalbe(mm.getValue(LENGTH) / 1000, mm.getValue(DIAMETER) / 2000, mm.getValue(HEAD) / 1000, 0.0)
        // 现场：H 输入框为罐高 L，R 输入框按直径 → (R1*2, L)
        VERTICAL -> createTable.craeteOilCanTalbe(mm.getValue(DIAMETER) / 2000 * 2, mm.getValue(HEIGHT) / 1000)
        // 现场：L 长，R 输入框为宽（R1*2），H 输入框为罐高（C1）→ (L, R1*2, C1)
        else -> createTable.craeteOilCanTalbe(mm.getValue(LENGTH) / 1000, mm.getValue(WIDTH) / 2000 * 2, mm.getValue(HEIGHT) / 1000)
    }

    companion object {
        const val METHOD = "config.setTankTable"
        const val HORIZONTAL = "horizontal"
        const val VERTICAL = "vertical"
        const val CUBOID = "cuboid"
        const val DIAMETER = "diameterMm"
        const val LENGTH = "lengthMm"
        const val HEAD = "headMm"
        const val HEIGHT = "heightMm"
        const val WIDTH = "widthMm"
        private const val MIN_MM = 100.0
        private const val MAX_MM = 20000.0

        @JvmField
        val SHAPE_FIELDS: Map<String?, List<String>> = mapOf(
            HORIZONTAL to listOf(DIAMETER, LENGTH, HEAD),
            VERTICAL to listOf(HEIGHT, DIAMETER),
            CUBOID to listOf(HEIGHT, LENGTH, WIDTH),
        )

        private fun number(value: JsonElement?): Double? {
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
            return try {
                val d = value.asBigDecimal.toDouble()
                if (d.isFinite()) d else null
            } catch (e: NumberFormatException) {
                null
            }
        }

        private fun invalid(message: String) = RpcResult.rejected(RpcErrorCode.INVALID_PARAMS, message)
    }
}
