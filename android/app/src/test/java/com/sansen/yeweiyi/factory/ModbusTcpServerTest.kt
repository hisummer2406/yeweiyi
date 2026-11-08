package com.sansen.yeweiyi.factory

import com.sansen.yeweiyi.utils.CRCheck
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

/**
 * [ModbusTcpServer] 起真实 socket（端口 0 由系统分配），用文档 2 号罐举例报文核对 TCP 通道应答：RTU over TCP 与标准 Modbus TCP（MBAP）两种格式。
 */
class ModbusTcpServerTest {

    private lateinit var server: ModbusTcpServer

    private fun hex(s: String): ByteArray = CRCheck.hexToBytes(s.replace(" ", ""))

    private fun tank2(): Map<Int, FloatArray> {
        val f = listOf("3FBABB71", "3DEB08B4", "41DC8000", "462C98F0", "462744F4", "43AA7F71")
        return mapOf(2 to FloatArray(f.size) { java.lang.Float.intBitsToFloat(f[it].toLong(16).toInt()) })
    }

    private object NoopLogger : ModbusTcpServer.Logger {
        override fun onListening(port: Int) {}
        override fun onBindFailed(port: Int, e: IOException) {}
        override fun onAcceptFailed(e: IOException) {}
        override fun onRejected(remote: String, maxClients: Int) {}
        override fun onConnected(remote: String) {}
        override fun onIdleTimeout(remote: String) {}
        override fun onError(remote: String, e: Exception) {}
        override fun onDisconnected(remote: String) {}
        override fun onRequest(remote: String, framing: String, request: ZhongChengModbusSlave.Request?, response: ByteArray) {}
    }

    @Before
    fun setUp() {
        server = ModbusTcpServer(0,
            ModbusTcpServer.Responder { ZhongChengModbusSlave.buildResponse(it, tank2(), 12) },
            NoopLogger)
        server.start()
        val deadline = System.currentTimeMillis() + 3000
        while (server.localPort <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("server 未能绑定端口", server.localPort > 0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun readN(s: Socket, n: Int): ByteArray {
        val buf = ByteArray(n)
        DataInputStream(s.getInputStream()).readFully(buf)
        return buf
    }

    private fun connect() = Socket("127.0.0.1", server.localPort).apply { soTimeout = 3000 }

    @Test
    fun `RTU帧原样经TCP应答_含CRC无MBAP头`() {
        connect().use { s ->
            s.getOutputStream().write(hex(REQUEST))
            assertArrayEquals(hex(TANK2_RESPONSE), readN(s, TANK2_RESPONSE.length / 2))
        }
    }

    @Test
    fun `请求被拆成两段发送_拼齐后应答`() {
        connect().use { s ->
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            out.write(hex("08 03 03"))
            out.flush()
            Thread.sleep(100)
            out.write(hex("f3 00 0c b5 21"))
            out.flush()
            assertArrayEquals(hex(TANK2_RESPONSE), readN(s, TANK2_RESPONSE.length / 2))
        }
    }

    @Test
    fun `同一连接连续轮询_每次都应答`() {
        connect().use { s ->
            repeat(3) {
                s.getOutputStream().write(hex(REQUEST))
                assertArrayEquals(hex(TANK2_RESPONSE), readN(s, TANK2_RESPONSE.length / 2))
            }
        }
    }

    private fun tank2Data() = TANK2_RESPONSE.substring(6, TANK2_RESPONSE.length - 4)

    @Test
    fun `标准ModbusTCP_带MBAP头无CRC_原样带回事务号和单元号`() {
        connect().use { s ->
            s.getOutputStream().write(hex("12 34 00 00 00 06 08 03 03 f3 00 0c"))
            val expected = hex("12 34 00 00 00 1b 08 03 18" + tank2Data())
            assertArrayEquals(expected, readN(s, expected.size))
        }
    }

    @Test
    fun `标准ModbusTCP_单元号不是8也应答`() {
        connect().use { s ->
            s.getOutputStream().write(hex("00 01 00 00 00 06 01 03 03 f3 00 0c"))
            val expected = hex("00 01 00 00 00 1b 01 03 18" + tank2Data())
            assertArrayEquals(expected, readN(s, expected.size))
        }
    }

    @Test
    fun `标准ModbusTCP_一次发两帧_逐帧应答`() {
        connect().use { s ->
            s.getOutputStream().write(hex("00 01 00 00 00 06 08 03 03 f3 00 0c" + "00 02 00 00 00 06 08 03 03 f3 00 0c"))
            for (tid in listOf("00 01", "00 02")) {
                val expected = hex("$tid 00 00 00 1b 08 03 18" + tank2Data())
                assertArrayEquals(expected, readN(s, expected.size))
            }
        }
    }

    @Test
    fun `标准ModbusTCP_不支持的功能码回异常01`() {
        connect().use { s ->
            s.getOutputStream().write(hex("00 07 00 00 00 06 08 04 03 f3 00 0c"))
            val expected = hex("00 07 00 00 00 03 08 84 01")
            assertArrayEquals(expected, readN(s, expected.size))
        }
    }

    @Test
    fun `标准ModbusTCP_地址越界回异常02`() {
        connect().use { s ->
            s.getOutputStream().write(hex("00 08 00 00 00 06 08 03 00 00 00 02"))
            val expected = hex("00 08 00 00 00 03 08 83 02")
            assertArrayEquals(expected, readN(s, expected.size))
        }
    }

    @Test
    fun `RTU请求拆成7加1字节_前半截不被当成乱数据丢弃`() {
        connect().use { s ->
            val out = s.getOutputStream()
            val req = hex(REQUEST)
            out.write(req, 0, 7)
            out.flush()
            Thread.sleep(100)
            out.write(req, 7, 1)
            out.flush()
            assertArrayEquals(hex(TANK2_RESPONSE), readN(s, TANK2_RESPONSE.length / 2))
        }
    }

    @Test
    fun `先收到乱数据_随后第一个标准ModbusTCP请求就应答`() {
        connect().use { s ->
            val out = s.getOutputStream()
            out.write("hello".toByteArray())
            out.flush()
            Thread.sleep(100)
            out.write(hex("00 09 00 00 00 06 08 03 03 f3 00 0c"))
            val expected = hex("00 09 00 00 00 1b 08 03 18" + tank2Data())
            assertArrayEquals(expected, readN(s, expected.size))
        }
    }

    @Test
    fun `260字节最长MBAP帧分段到达_不被缓冲上限截断`() {
        connect().use { s ->
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            // 功能码 16、MBAP 长度 254：凑满 257~259 字节时还没收完，旧上限 256 会把它丢掉
            val frame = hex("00 0a 00 00 00 fe 08 10") + ByteArray(252)
            out.write(frame, 0, 100)
            out.flush()
            Thread.sleep(100)
            out.write(frame, 100, 158)
            out.flush()
            Thread.sleep(100)
            out.write(frame, 258, 2)
            out.flush()
            val expected = hex("00 0a 00 00 00 03 08 90 01")
            assertArrayEquals(expected, readN(s, expected.size))
            out.write(hex("00 0b 00 00 00 06 08 03 03 f3 00 0c"))
            val next = hex("00 0b 00 00 00 1b 08 03 18" + tank2Data())
            assertArrayEquals(next, readN(s, next.size))
        }
    }

    @Test
    fun `乱数据判定_RTU前缀和合法MBAP头不算乱数据`() {
        assertTrue(!ModbusTcpFraming.isUnrecognizable(hex("08")))
        assertTrue(!ModbusTcpFraming.isUnrecognizable(hex("08 03 03 f3 00 0c b5")))
        assertTrue(!ModbusTcpFraming.isUnrecognizable(hex("00 01 00 00 00 06 08")))
        assertTrue(ModbusTcpFraming.isUnrecognizable("hello".toByteArray()))
        assertTrue(ModbusTcpFraming.isUnrecognizable(hex("00 01 00 00 01 00")))
    }

    @Test
    fun `地址为0的RTU请求按RTU识别_不误判为MBAP`() {
        val body = hex("08 03 00 00 00 02")
        val crc = CRCheck.crc16Modbus(body)
        val frame = ModbusTcpFraming.parse(body + byteArrayOf(crc.toByte(), (crc shr 8).toByte()))
        assertTrue(frame is ModbusTcpFraming.Rtu)
    }

    private companion object {
        const val REQUEST = "08 03 03 f3 00 0c b5 21"
        const val TANK2_RESPONSE =
            "080318" + "3FBABB71" + "3DEB08B4" + "41DC8000" + "462C98F0" + "462744F4" + "43AA7F71" + "64B2"
    }
}
