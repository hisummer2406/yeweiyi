package com.sansen.yeweiyi.factory;

import com.sansen.yeweiyi.bean.OilCan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 探针ID（十进制输入）→ 中成协议地址字节。期望帧取自
 * temp/液位仪全部串口协议/协议探针长度查询.doc 的"地址1-12的探针查询码"。
 * 只调用拼帧方法，不触发 android.* 调用，可直接在 JVM 上运行。
 */
public class ZhongChengAddressTest {

    private static String chanDuFrame(String probeId) {
        OilCan oilCan = new OilCan();
        oilCan.setProbeAdr(probeId);
        return ZhongChengCompany.getInstance().sendChangDuInfo("FEFE", oilCan.getProbeAddressHex(), null);
    }

    @Test
    public void 长度查询帧与协议文档一致() {
        assertEquals("FEFE0103420000" + "0BE4", chanDuFrame("1"));
        assertEquals("FEFE0203420000" + "024B", chanDuFrame("2"));
        assertEquals("FEFE0903420000" + "00D6", chanDuFrame("9"));
        // ID 10 必须发 0x0A，原先会把 "10" 当十六进制发成 0x10
        assertEquals("FEFE0A03420000" + "0979", chanDuFrame("10"));
        assertEquals("FEFE0C03420000" + "022A", chanDuFrame("12"));
    }

    @Test
    public void 地址字节按十进制ID换算() {
        OilCan oilCan = new OilCan();
        oilCan.setProbeAdr("16");
        assertEquals(16, oilCan.getProbeAddressByte());
        assertEquals("10", oilCan.getProbeAddressHex());
        // LD2 按十进制通道号匹配，getProbeAdr 保持原样
        assertEquals("16", oilCan.getProbeAdr());
    }

    @Test
    public void 无效地址返回null() {
        OilCan oilCan = new OilCan();
        oilCan.setProbeAdr("0");
        assertNull(oilCan.getProbeAddressHex());
        oilCan.setProbeAdr("256");
        assertNull(oilCan.getProbeAddressHex());
        oilCan.setProbeAdr("abc");
        assertNull(oilCan.getProbeAddressHex());
    }
}
