package com.sansen.yeweiyi.manager

import com.sansen.yeweiyi.logic.update.AppUpdateCandidate
import com.sansen.yeweiyi.logic.update.AppUpdateCheck
import com.sansen.yeweiyi.logic.update.AppUpdateContract
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class AppUpdateNetworkException(val code: String, val httpStatus: Int = 0) : IOException(code)

/** One immutable origin and credential snapshot per operation, with no token-bearing exception text. */
class AppUpdateHttpClient(baseUrl: String, deviceId: String, private val token: String, ca: InputStream? = null) {
    private val origin = requireNotNull(HttpUrl.parse(baseUrl))
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeCall: Call? = null
    private val client: OkHttpClient
    val scope: String

    init {
        require(origin.scheme() == "https" && origin.encodedUsername().isEmpty() && origin.encodedPassword().isEmpty())
        require(origin.encodedPath() == "/" && origin.query() == null && origin.fragment() == null)
        require(deviceId.isNotBlank() && token.isNotBlank())
        val builder = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()))
        ca?.use { input ->
            val certs = CertificateFactory.getInstance("X.509").generateCertificates(input)
            require(certs.isNotEmpty())
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            certs.forEachIndexed { index, cert -> store.setCertificateEntry("ca-$index", cert) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(trust), null) }
            builder.sslSocketFactory(ssl.socketFactory, trust)
        }
        client = builder.build()
        scope = digest("${origin}\u0000$deviceId\u0000$token".toByteArray(Charsets.UTF_8))
    }

    fun check(currentCode: Int): AppUpdateCheck {
        val url = deviceUrl("attributes").addQueryParameter("sharedKeys", AppUpdateContract.SHARED_KEYS).build()
        return AppUpdateContract.parse(String(get(url, AppUpdateContract.MAX_INFO_BYTES), Charsets.UTF_8), currentCode)
    }

    fun chunk(candidate: AppUpdateCandidate, size: Int, index: Int): ByteArray {
        require(size in 1..1024 * 1024 && index >= 0)
        val offset = index.toLong() * size
        require(offset < candidate.sizeBytes)
        val expected = minOf(size.toLong(), candidate.sizeBytes - offset).toInt()
        val url = deviceUrl("software").addQueryParameter("title", AppUpdateContract.TITLE)
            .addQueryParameter("version", candidate.versionCode.toString()).addQueryParameter("size", size.toString())
            .addQueryParameter("chunk", index.toString()).build()
        return get(url, expected).also { if (it.size != expected) throw AppUpdateNetworkException("CHUNK_LENGTH_MISMATCH") }
    }

    fun cancel() { cancelled.set(true); activeCall?.cancel() }
    fun isCancelled(): Boolean = cancelled.get()

    private fun deviceUrl(endpoint: String): HttpUrl.Builder = origin.newBuilder()
        .addPathSegments("api/v1").addPathSegment(token).addPathSegment(endpoint)

    // Serialize calls so a concurrent check cannot replace a download's cancellation handle.
    @Synchronized
    private fun get(url: HttpUrl, limit: Int): ByteArray {
        if (cancelled.get()) throw InterruptedIOException("UPDATE_CANCELLED")
        val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-store").build())
        activeCall = call
        try {
            if (cancelled.get()) { call.cancel(); throw InterruptedIOException("UPDATE_CANCELLED") }
            call.execute().use { response ->
                if (response.code() != 200) throw AppUpdateNetworkException("HTTP_ERROR", response.code())
                val body = response.body() ?: throw AppUpdateNetworkException("EMPTY_RESPONSE")
                if (body.contentLength() > limit) throw AppUpdateNetworkException("RESPONSE_TOO_LARGE")
                body.byteStream().use { stream ->
                    val out = ByteArrayOutputStream(minOf(limit, 8192))
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (cancelled.get()) throw InterruptedIOException("UPDATE_CANCELLED")
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (out.size().toLong() + count > limit) throw AppUpdateNetworkException("RESPONSE_TOO_LARGE")
                        out.write(buffer, 0, count)
                    }
                    return out.toByteArray()
                }
            }
        } catch (error: AppUpdateNetworkException) {
            throw error
        } catch (_: IOException) {
            // OkHttp exceptions contain request URLs; never retain their cause or message.
            if (cancelled.get()) throw InterruptedIOException("UPDATE_CANCELLED")
            throw AppUpdateNetworkException("TRANSPORT_ERROR")
        } finally { activeCall = null }
    }

    companion object {
        internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
