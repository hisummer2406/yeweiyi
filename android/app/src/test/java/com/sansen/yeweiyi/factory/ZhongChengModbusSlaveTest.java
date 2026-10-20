package com.sansen.yeweiyi.factory;

import com.sansen.yeweiyi.utils.CRCheck;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * {@link ZhongChengModbusSlave} 按《中成液位modbus协议.docx》举例报文逐字节核对。
 */
public class ZhongChengModbusSlaveTest {

    /** 文档 2 号罐"全部信息"举例的 6 个字段线上字节 */
    private static final String[] TANK2_FIELD_HEX = {
            "3FBABB71", "3DEB08B4", "41DC8000", "462C98F0", "462744F4", "43AA7F71",
    };

    private static byte[] hex(String s) {
        return CRCheck.hexToBytes(s.replace(" ", ""));
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X", x & 0xFF));
        }
        return sb.toString();
    }

    private static float[] fields(String... hexFields) {
        float[] f = new float[hexFields.length];
        for (int i = 0; i < hexFields.length; i++) {
            f[i] = Float.intBitsToFloat((int) Long.parseLong(hexFields[i], 16));
        }
        return f;
    }

    private static Map<Integer, float[]> tank2Only() {
        Map<Integer, float[]> tanks = new HashMap<>();
        tanks.put(2, fields(TANK2_FIELD_HEX));
        return tanks;
    }

    @Test
    public void 文档举例_2号罐全部信息应答逐字节一致() {
        ZhongChengModbusSlave.Request req = ZhongChengModbusSlave.findRequest(hex("08 03 03 f3 00 0c b5 21"));
        assertNotNull(req);
        byte[] resp = ZhongChengModbusSlave.buildResponse(req, tank2Only(), 12);
        assertEquals("080318" + String.join("", TANK2_FIELD_HEX) + "64B2", toHex(resp));
    }

    @Test
    public void 文档举例_2号罐仅油高应答逐字节一致() {
        Map<Integer, float[]> tanks = new HashMap<>();
        tanks.put(2, fields("3FBABD2C", "0", "0", "0", "0", "0"));
        ZhongChengModbusSlave.Request req = ZhongChengModbusSlave.findRequest(hex("08 03 03 f3 00 02 34 e5"));
        assertNotNull(req);
        assertEquals("0803043FBABD2C3E4F", toHex(ZhongChengModbusSlave.buildResponse(req, tanks, 12)));
    }

    /** 现场截图场景：地址1000、长度4，字节数必须是 8，而不是旧代码写死的 0x60 */
    @Test
    public void 现场截图请求_字节数按请求长度返回() {
        Map<Integer, float[]> tanks = new HashMap<>();
        tanks.put(1, fields(TANK2_FIELD_HEX));
        ZhongChengModbusSlave.Request req = ZhongChengModbusSlave.findRequest(hex("08 03 03 e7 00 04 f4 e3"));
        assertNotNull(req);
        assertEquals(0x03E7, req.start);
        assertEquals(4, req.quantity);
        byte[] resp = ZhongChengModbusSlave.buildResponse(req, tanks, 12);
        assertEquals(3 + 8 + 2, resp.length);
        assertEquals("080308" + TANK2_FIELD_HEX[0] + TANK2_FIELD_HEX[1], toHex(resp).substring(0, 22));
        assertCrcValid(resp);
    }

    @Test
    public void 未配置的罐寄存器返回0() {
        ZhongChengModbusSlave.Request req = ZhongChengModbusSlave.findRequest(hex("08 03 03 e7 00 0c 00 00"));
        assertNull("CRC 错误的请求应被忽略", req);

        byte[] body = hex("080303E7000C");
        int crc = CRCheck.crc16Modbus(body);
        byte[] frame = hex(toHex(body) + String.format("%02X%02X", crc & 0xFF, (crc >> 8) & 0xFF));
        req = ZhongChengModbusSlave.findRequest(frame);
        byte[] resp = ZhongChengModbusSlave.buildResponse(req, tank2Only(), 12);
        assertEquals("080318" + repeat("00", 24), toHex(resp).substring(0, 6 + 48));
    }

    @Test
    public void 跨罐读取_1号罐末尾到2号罐开头() {
        // 1号罐水体积(1010) + 2号罐油高(1012)：线上起始 1009，4 个寄存器
        Map<Integer, float[]> tanks = tank2Only();
        tanks.put(1, fields("0", "0", "0", "0", "0", "3F800000"));
        byte[] resp = ZhongChengModbusSlave.buildResponse(request(1009, 4), tanks, 12);
        assertEquals("080308" + "3F800000" + TANK2_FIELD_HEX[0], toHex(resp).substring(0, 22));
    }

    @Test
    public void 越界地址返回异常码02() {
        assertEquals("088302", toHex(ZhongChengModbusSlave.buildResponse(request(998, 2), tank2Only(), 12)).substring(0, 6));
        assertEquals("088302", toHex(ZhongChengModbusSlave.buildResponse(request(999 + 144 - 1, 2), tank2Only(), 12)).substring(0, 6));
    }

    @Test
    public void 非法数量返回异常码03() {
        byte[] resp = ZhongChengModbusSlave.buildResponse(request(999, 0), tank2Only(), 12);
        assertEquals("088303", toHex(resp).substring(0, 6));
        assertCrcValid(resp);
    }

    @Test
    public void 请求前有噪声或粘连时仍能找到帧() {
        ZhongChengModbusSlave.Request req = ZhongChengModbusSlave.findRequest(hex("36 32 00 08 03 03 e7 00 04 f4 e3 00"));
        assertNotNull(req);
        assertEquals(4, req.quantity);
        assertNull(ZhongChengModbusSlave.findRequest(hex("01 69 32 30 31 30 30 03")));
    }

    private static ZhongChengModbusSlave.Request request(int start, int qty) {
        byte[] body = new byte[]{0x08, 0x03, (byte) (start >> 8), (byte) start, (byte) (qty >> 8), (byte) qty};
        int crc = CRCheck.crc16Modbus(body);
        byte[] frame = new byte[8];
        System.arraycopy(body, 0, frame, 0, 6);
        frame[6] = (byte) crc;
        frame[7] = (byte) (crc >> 8);
        return ZhongChengModbusSlave.findRequest(frame);
    }

    private static void assertCrcValid(byte[] frame) {
        byte[] body = new byte[frame.length - 2];
        System.arraycopy(frame, 0, body, 0, body.length);
        int crc = CRCheck.crc16Modbus(body);
        assertEquals(crc & 0xFF, frame[frame.length - 2] & 0xFF);
        assertEquals((crc >> 8) & 0xFF, frame[frame.length - 1] & 0xFF);
    }

    @Test
    public void 负温度按IEEE754发出() {
        Map<Integer, float[]> tanks = new HashMap<>();
        tanks.put(1, new float[]{0f, 0f, -5f, 0f, 0f, 0f});
        // 1 号罐温度：文档 1004 → 线上 0x03EB，2 个寄存器
        ZhongChengModbusSlave.Request req = new ZhongChengModbusSlave.Request(0x03EB, 2);
        byte[] resp = ZhongChengModbusSlave.buildResponse(req, tanks, 12);
        assertEquals("080304C0A00000", toHex(resp).substring(0, 14));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
