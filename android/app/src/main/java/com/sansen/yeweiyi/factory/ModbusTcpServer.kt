package com.sansen.yeweiyi.factory

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对外 Modbus 的 TCP 通道：同一端口同时支持 RTU over TCP 和标准 Modbus TCP（MBAP），按收到的格式应答
 * （见 [ModbusTcpFraming]）。寄存器与组帧复用 [ZhongChengModbusSlave]，与串口 5 口径一致、并行互不影响。
 * 不依赖 android.*，端口和日志可注入，便于 JVM 单测起真实 socket。
 */
class ModbusTcpServer(
    private val port: Int,
    private val responder: Responder,
    private val logger: Logger
) {

    /** 由调用方按当前罐数据组出完整应答帧 */
    fun interface Responder {
        fun respond(request: ZhongChengModbusSlave.Request): ByteArray
    }

    /** 日志出口，生产实现是 applog/ExternalLog；级别由实现决定 */
    interface Logger {
        fun onListening(port: Int)
        fun onBindFailed(port: Int, e: IOException)
        fun onAcceptFailed(e: IOException)
        fun onRejected(remote: String, maxClients: Int)
        fun onConnected(remote: String)
        fun onIdleTimeout(remote: String)
        fun onError(remote: String, e: Exception)
        fun onDisconnected(remote: String)
        /** 每次轮询都会调用，实现里要按级别过滤后再格式化报文。[framing] 为 rtu 或 mbap；非读请求时 [request] 为 null */
        fun onRequest(remote: String, framing: String, request: ZhongChengModbusSlave.Request?, response: ByteArray)
    }

    private val clientCount = AtomicInteger()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    /** 后台线程里绑定端口并开始接受连接；绑定失败只记日志，不抛出 */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        Thread(::acceptLoop, "ModbusTcp-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        running = false
        closeQuietly(serverSocket)
    }

    /** 实际监听的端口；未绑定成功时返回 -1。测试用 port=0 时靠它拿到系统分配的端口 */
    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    private fun acceptLoop() {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            // 普通应用在 Android 上绑定 1024 以下端口会 EACCES，需要系统签名/root
            logger.onBindFailed(port, e)
            running = false
            return
        }
        serverSocket = server
        logger.onListening(server.localPort)
        var errorDelay = 0L
        while (running) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!running) break
                logger.onAcceptFailed(e)
                errorDelay = if (errorDelay == 0L) INITIAL_ERROR_DELAY_MS else minOf(errorDelay * 2, MAX_ERROR_DELAY_MS)
                try {
                    Thread.sleep(errorDelay)
                } catch (ie: InterruptedException) {
                    break
                }
                continue
            }
            errorDelay = 0L
            if (clientCount.incrementAndGet() > MAX_CLIENTS) {
                clientCount.decrementAndGet()
                logger.onRejected(client.remoteSocketAddress.toString(), MAX_CLIENTS)
                closeQuietly(client)
                continue
            }
            Thread({ serve(client) }, "ModbusTcp-client").apply { isDaemon = true }.start()
        }
        closeQuietly(server)
    }

    private fun serve(client: Socket) {
        val remote = client.remoteSocketAddress.toString()
        logger.onConnected(remote)
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()
            var pending = ByteArray(0)
            val chunk = ByteArray(256)
            while (running) {
                val n = input.read(chunk)
                if (n == -1) break
                pending += chunk.copyOf(n)
                // 标准 Modbus TCP 主站可能一次发多帧，逐帧处理；RTU 帧沿用串口逻辑，一次清空整个缓冲
                while (true) {
                    val frame = ModbusTcpFraming.parse(pending) ?: break
                    val response = when (frame) {
                        is ModbusTcpFraming.Rtu -> responder.respond(frame.request)
                        is ModbusTcpFraming.Mbap -> frame.request
                            ?.let { ModbusTcpFraming.mbapResponse(frame, responder.respond(it)) }
                            ?: ModbusTcpFraming.mbapException(frame)
                    }
                    output.write(response)
                    output.flush()
                    val request = (frame as? ModbusTcpFraming.Rtu)?.request ?: (frame as ModbusTcpFraming.Mbap).request
                    logger.onRequest(remote, if (frame is ModbusTcpFraming.Rtu) "rtu" else "mbap", request, response)
                    pending = pending.copyOfRange(frame.consumed, pending.size)
                }
                if (pending.size > MAX_PENDING_BYTES || ModbusTcpFraming.isUnrecognizable(pending)) {
                    pending = ByteArray(0)
                }
            }
        } catch (e: SocketTimeoutException) {
            logger.onIdleTimeout(remote)
        } catch (e: Exception) {
            logger.onError(remote, e)
        } finally {
            closeQuietly(client)
            clientCount.decrementAndGet()
            logger.onDisconnected(remote)
        }
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (ignored: IOException) {
            // 关闭失败无可补救
        }
    }

    companion object {
        /** 用 5020 而不是标准 502：普通应用在 Android 上绑定 1024 以下端口会被拒（EACCES） */
        const val DEFAULT_PORT = 5020

        /** 同时在线的主站数上限，超出的连接直接关闭，防止线程无限增长 */
        internal const val MAX_CLIENTS = 4

        /** 主站这么久没发任何数据就断开，回收死连接（Modbus Poll 默认 1 s 轮询一次） */
        internal const val IDLE_TIMEOUT_MS = 60_000

        /** 缓冲区里攒这么多字节仍找不到合法请求，丢弃重来；须大于最长的 MBAP 帧（6 + 254 = 260 字节） */
        private const val MAX_PENDING_BYTES = 512

        /** accept 连续失败（如文件句柄耗尽）时的退避，从 50ms 翻倍到 1.6s，防止空转刷日志（同 Tomcat Acceptor） */
        private const val INITIAL_ERROR_DELAY_MS = 50L
        private const val MAX_ERROR_DELAY_MS = 1600L
    }
}
