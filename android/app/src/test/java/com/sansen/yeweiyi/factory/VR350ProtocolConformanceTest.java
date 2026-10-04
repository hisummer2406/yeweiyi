package com.sansen.yeweiyi.factory;

import com.sansen.yeweiyi.utils.DataUtils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 对照《维德路特协议，VR协议.doc》(SS160控制器后台通讯协议) 里的官方举例报文，
 * 核对 {@link VR350Factory} 依赖的两个静态工具方法（报文头匹配、CRC 校验）在
 * 真实厂商报文上的行为，用来在没有真机/外部主机的情况下排查"VR协议连不通"。
 *
 * 不直接测 VR350Factory 本身：它的构造函数依赖 Handler/HandlerThread 和
 * OilcanDataManager.getInstance(Context)，纯 JVM 单测跑不起来（同 CLAUDE.md
 * 里 factory/ 层不可测性的说明），只能测它调用的这两个静态方法。
 *
 * 运行：
 *     gradle :app:testDebugUnitTest --tests "com.sansen.yeweiyi.factory.VR350ProtocolConformanceTest"
 */
public class VR350ProtocolConformanceTest {

    /**
     * 文档 3.1 举例：查询所有油罐实时库存数据的命令原文。
     * "01 69 32 30 31 30 30 03"
     */
    private static final byte[] QUERY_ALL_TANKS = new byte[]{
            0x01, 0x69, 0x32, 0x30, 0x31, 0x30, 0x30, 0x03
    };

    /**
     * 文档 3.2 举例：控制器返回两个油罐数据的完整应答，本常量只取从帧头 01
     * 到 "&&" 为止（含）的部分——即 VR350Factory.backOilcanData() 里传给
     * getCrcPC() 计算校验和的那一段，不含 CRC 本身也不含结尾 0x03。
     */
    private static final String RESPONSE_BEFORE_CRC =
            "01"
                    + "693230313030"                 // i20100
                    + "31343130323830393139"          // 14-10-28 09:19
                    + "3031"                          // 01号罐
                    + "37"                            // 油品
                    + "30303030"                      // 油罐状态
                    + "3037"                          // 07组数据
                    + "3434303834433436"              // 1 Tank Volume
                    + "3434303834433436"              // 2 V20
                    + "3435453641364138"              // 3 Cross Volume
                    + "3432313945463131"              // 4 Height
                    + "3030303030303030"              // 5 water
                    + "3432383433383532"              // 6 temp
                    + "3030303030303030"              // 7 Water Volume
                    + "3032"                          // 02号罐
                    + "33"
                    + "30303030"
                    + "3037"
                    + "3030303030303030"
                    + "3030303030303030"
                    + "3030303030303030"
                    + "3030303030303030"
                    + "3030303030303030"
                    + "3432303030303030"
                    + "3030303030303030"
                    + "2626";                         // &&

    /**
     * 文档给出的 CRC 是十六进制值 E2BC，在线上以 ASCII 字符 'E''2''B''C' 的
     * 形式发送（即 45 32 42 43）——跟 getCrcPC() 的返回值格式一致，可以直接比较。
     */
    private static final String EXPECTED_CRC_ON_WIRE = "45324243";

    /**
     * [标准值] VR350Factory.initData() 用 data.contains("69 32 30 31 30 30")
     * 判断是不是一条 VR 查询——这里直接喂文档官方举例的查询报文过
     * bytesToHexString()，确认真实报文确实会命中这个分支。
     */
    @Test
    public void 官方查询报文能命中VR协议头匹配() {
        String hex = DataUtils.bytesToHexString(QUERY_ALL_TANKS, QUERY_ALL_TANKS.length);
        assertTrue("bytesToHexString 输出=" + hex, hex.contains("69 32 30 31 30 30"));
    }

    /**
     * [标准值] VR350Factory.backOilcanData() 用 getCrcPC(stringBuffer.toString())
     * 算校验和——这里喂文档官方举例的完整应答（去掉 CRC 和结尾 03），
     * 核对算出来的校验和是否等于文档里给出的 "45 32 42 43"。
     *
     * 这条测试目前是通过的：说明 CRC 算法本身跟文档一致，"VR协议不通"
     * 不是这里的锅，可以把嫌疑从"回复算出来的CRC不对"上排除。
     */
    @Test
    public void 官方应答报文的CRC计算结果与文档一致() {
        String crc = DataUtils.getCrcPC(RESPONSE_BEFORE_CRC);
        assertEquals(EXPECTED_CRC_ON_WIRE, crc);
    }
}
