package com.sansen.yeweiyi.factory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link LD2Company} 里两个纯函数的单元测试——{@code maxChannelNeeded} 和 {@code channelAddress}
 * 都不依赖 Context/SparseArray/Log，可直接在 JVM 上运行：
 *     gradle :app:testDebugUnitTest --tests "com.sansen.yeweiyi.factory.LD2CompanyTest"
 *
 * 背景（见 docs/重构方案.md 8.2、CLAUDE.md "Known landmines"）：4-20mA（probeType=2）此前请求帧里的
 * 通道数被硬编码成 "02"，导致探针地址在 01/02 以外的罐永远读不到数据；通道号拼接 "0"+(i+1) 对
 * 10 通道及以上又不补零，与 {@link com.sansen.yeweiyi.bean.OilCan#getProbeAdr()} 的补零规则不对称。
 * 这两个测试把修复后的行为钉住。
 */
public class LD2CompanyTest {

    // ---------------------------------------------------------------
    // maxChannelNeeded：请求帧里该问多少个寄存器
    // ---------------------------------------------------------------

    @Test
    public void maxChannelNeeded_空数组时地板值为1() {
        assertEquals(1, LD2Company.maxChannelNeeded(new int[]{}));
    }

    @Test
    public void maxChannelNeeded_null时地板值为1() {
        assertEquals(1, LD2Company.maxChannelNeeded(null));
    }

    @Test
    public void maxChannelNeeded_只有两个罐时仍是2_回归此前硬编码行为() {
        assertEquals(2, LD2Company.maxChannelNeeded(new int[]{1, 2}));
    }

    @Test
    public void maxChannelNeeded_取最大探针地址而不是罐数() {
        // 3 个罐，但最大探针地址是 5——之前硬编码成 2 时，这种配置下 3/4/5 号罐永远读不到数据。
        assertEquals(5, LD2Company.maxChannelNeeded(new int[]{1, 2, 5}));
    }

    @Test
    public void maxChannelNeeded_非正数地址被忽略() {
        assertEquals(3, LD2Company.maxChannelNeeded(new int[]{0, -1, 3}));
    }

    // ---------------------------------------------------------------
    // channelAddress：通道下标 -> 补零后的地址字符串，要跟 OilCan.getProbeAdr() 对称
    // ---------------------------------------------------------------

    @Test
    public void channelAddress_单数字通道补零() {
        assertEquals("01", LD2Company.channelAddress(0));
        assertEquals("09", LD2Company.channelAddress(8));
    }

    @Test
    public void channelAddress_两位数通道不再多补一个0_回归修复前的010() {
        assertEquals("10", LD2Company.channelAddress(9));
        assertEquals("16", LD2Company.channelAddress(15));
    }
}
