package com.sansen.yeweiyi.factory;

import com.sansen.yeweiyi.utils.CRCheck;

import java.util.Map;

/**
 * 《中成液位modbus协议》对外 Modbus 从站的纯 Java 部分：请求解析、寄存器映像切片、组帧。
 * 不依赖 Context/Handler，便于 JVM 单测；由 {@link VR350Factory} 调用。
 *
 * 协议要点：
 *   - 从站地址固定 0x08，只支持功能码 03
 *   - 线上寄存器地址 = 文档编号 - 1，1 号罐油高 = 文档 1000 = 线上 0x03E7
 *   - 每罐 12 个寄存器（6 个 float）：油高、水高、温度、油水体积、油体积、水体积
 *   - float 为 IEEE754 大端（以文档举例报文为准），CRC16/MODBUS 低字节在前
 */
public final class ZhongChengModbusSlave {

    public static final int SLAVE_ID = 0x08;
    public static final int FUNC_READ_HOLDING = 0x03;
    public static final int BASE_ADDRESS = 999;
    public static final int FIELDS_PER_TANK = 6;
    public static final int REGISTERS_PER_TANK = FIELDS_PER_TANK * 2;
    /** 标准 Modbus 03 单次最多 125 个寄存器 */
    public static final int MAX_QUANTITY = 125;

    private static final int REQUEST_LENGTH = 8;
    private static final int EXCEPTION_ILLEGAL_ADDRESS = 0x02;
    private static final int EXCEPTION_ILLEGAL_VALUE = 0x03;

    private ZhongChengModbusSlave() {
    }

    public static final class Request {
        public final int start;
        public final int quantity;

        Request(int start, int quantity) {
            this.start = start;
            this.quantity = quantity;
        }
    }

    /**
     * 在收到的字节流里找最后一个 "08 03 ..." 且 CRC 正确的 8 字节请求帧，找不到返回 null。
     * 串口一次读到的可能是多帧粘连或夹杂噪声，所以逐字节扫描而不是只看开头。
     */
    public static Request findRequest(byte[] buf) {
        if (buf == null) {
            return null;
        }
        Request found = null;
        for (int i = 0; i + REQUEST_LENGTH <= buf.length; i++) {
            if ((buf[i] & 0xFF) != SLAVE_ID || (buf[i + 1] & 0xFF) != FUNC_READ_HOLDING) {
                continue;
            }
            byte[] body = new byte[REQUEST_LENGTH - 2];
            System.arraycopy(buf, i, body, 0, body.length);
            int crc = CRCheck.crc16Modbus(body);
            if ((buf[i + 6] & 0xFF) != (crc & 0xFF) || (buf[i + 7] & 0xFF) != ((crc >> 8) & 0xFF)) {
                continue;
            }
            int start = ((buf[i + 2] & 0xFF) << 8) | (buf[i + 3] & 0xFF);
            int quantity = ((buf[i + 4] & 0xFF) << 8) | (buf[i + 5] & 0xFF);
            found = new Request(start, quantity);
            i += REQUEST_LENGTH - 1;
        }
        return found;
    }

    /**
     * 按请求从寄存器映像中切片并组成完整应答帧（含 CRC）。
     *
     * @param tanks   罐号(1 起) → 6 个字段值，顺序同协议；未出现的罐寄存器全为 0
     * @param maxTank 寄存器映像覆盖到第几号罐，超出范围的请求回异常码 02
     */
    public static byte[] buildResponse(Request req, Map<Integer, float[]> tanks, int maxTank) {
        if (req.quantity < 1 || req.quantity > MAX_QUANTITY) {
            return exception(EXCEPTION_ILLEGAL_VALUE);
        }
        int offset = req.start - BASE_ADDRESS;
        int total = maxTank * REGISTERS_PER_TANK;
        if (offset < 0 || offset + req.quantity > total) {
            return exception(EXCEPTION_ILLEGAL_ADDRESS);
        }

        byte[] image = new byte[total * 2];
        for (Map.Entry<Integer, float[]> e : tanks.entrySet()) {
            int pos = e.getKey();
            if (pos < 1 || pos > maxTank) {
                continue;
            }
            float[] fields = e.getValue();
            int base = (pos - 1) * REGISTERS_PER_TANK * 2;
            for (int f = 0; f < FIELDS_PER_TANK && f < fields.length; f++) {
                int bits = Float.floatToIntBits(fields[f]);
                int p = base + f * 4;
                image[p] = (byte) (bits >> 24);
                image[p + 1] = (byte) (bits >> 16);
                image[p + 2] = (byte) (bits >> 8);
                image[p + 3] = (byte) bits;
            }
        }

        int byteCount = req.quantity * 2;
        byte[] frame = new byte[3 + byteCount + 2];
        frame[0] = (byte) SLAVE_ID;
        frame[1] = (byte) FUNC_READ_HOLDING;
        frame[2] = (byte) byteCount;
        System.arraycopy(image, offset * 2, frame, 3, byteCount);
        appendCrc(frame);
        return frame;
    }

    private static byte[] exception(int code) {
        byte[] frame = new byte[5];
        frame[0] = (byte) SLAVE_ID;
        frame[1] = (byte) (FUNC_READ_HOLDING | 0x80);
        frame[2] = (byte) code;
        appendCrc(frame);
        return frame;
    }

    /** 对 frame 除最后 2 字节以外的部分计算 CRC，低字节在前写入最后 2 字节。 */
    private static void appendCrc(byte[] frame) {
        byte[] body = new byte[frame.length - 2];
        System.arraycopy(frame, 0, body, 0, body.length);
        int crc = CRCheck.crc16Modbus(body);
        frame[frame.length - 2] = (byte) crc;
        frame[frame.length - 1] = (byte) (crc >> 8);
    }
}
