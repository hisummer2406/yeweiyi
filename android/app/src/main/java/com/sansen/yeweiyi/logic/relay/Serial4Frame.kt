package com.sansen.yeweiyi.logic.relay

import com.sansen.yeweiyi.utils.CRCheck
import com.sansen.yeweiyi.utils.DataUtils

/**
 * 串口 4 联动帧（高高/低低液位时由 AsyManager.openOne 下发）。
 * 罐号字节按十六进制拼：原先用 %02d 按十进制拼，防溢柜协议 8 号罐起、Modbus 11 号罐起罐号都会错。
 */
object Serial4Frame {

    /** 《防溢阀自用串口报警协议》：FE FE 01 01 <罐号+2> <功能码> CRC12。功能码 0 油正常/1 油低低/2 油低/3 油高/4 油高高/5 水高/6 水正常。 */
    @JvmStatic
    fun overflowValve(tank: Int, code: Int): ByteArray {
        val body = "FEFE0101" + hex(tank + 2) + hex(code)
        return withCrc(body, CRCheck.crc12(0, DataUtils.HexString2Bytes(body)))
    }

    /** Modbus RTU 06 写单个寄存器：从站 1，寄存器地址 罐号-1，值 [value]（CRC 低字节在前）。 */
    @JvmStatic
    fun modbusWrite(tank: Int, value: Int): ByteArray {
        val body = "010600" + hex(tank - 1) + "00" + hex(value)
        return withCrc(body, CRCheck.crc162(DataUtils.HexString2Bytes(body)))
    }

    private fun withCrc(body: String, crc: String) = DataUtils.HexString2Bytes(body + crc.padStart(4, '0'))

    private fun hex(v: Int) = String.format("%02X", v and 0xFF)
}
