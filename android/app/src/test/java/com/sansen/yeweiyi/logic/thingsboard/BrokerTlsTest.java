package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Arrays;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 覆盖设备侧 TLS 的三件事：broker URI 解析、主机名匹配规则、以及一次**真实的 TLS 握手**。
 * <p>
 * 握手测试用 {@link SSLServerSocket} 在 127.0.0.1 上起一个临时服务端，客户端严格按
 * Paho 0.4.0 的调用顺序来（无参 {@code createSocket()} → {@code connect(addr)} →
 * {@code startHandshake()}）。这一点是有意为之：主机名校验之所以要塞进 TrustManager，
 * 正是因为这个顺序下 socket 自己不知道目标主机名，用别的写法测就测不到真实行为。
 * <p>
 * 证书夹具见 {@code tools/generate-tls-test-fixtures.sh}（20 年有效期，到期重跑）。
 */
public class BrokerTlsTest {

    private static final char[] KEYSTORE_PASSWORD = "test-only".toCharArray();

    // ---------- broker URI 解析 ----------

    @Test
    public void hostFromBrokerUriHandlesTheFormsThisAppActuallyProduces() {
        // ThingsBoardManager 拼的是 scheme + 用户填的 IP/域名 + ":" + 端口。
        assertEquals("10.0.0.10", BrokerTls.hostFromBrokerUri("ssl://10.0.0.10:8883"));
        assertEquals("tb.example.test", BrokerTls.hostFromBrokerUri("tcp://tb.example.test:1883"));
        assertEquals("::1", BrokerTls.hostFromBrokerUri("ssl://[::1]:8883"));
        assertEquals("没有端口也要能解析", "tb.example.test",
                BrokerTls.hostFromBrokerUri("ssl://tb.example.test"));
        assertEquals("没有 scheme 也要能解析", "10.0.0.10",
                BrokerTls.hostFromBrokerUri("10.0.0.10:8883"));
    }

    @Test
    public void hostFromBrokerUriReturnsNullInsteadOfThrowing() {
        // 设备上这个值是人工填的，填坏了应该走"连不上"的正常失败路径，不能抛异常炸掉调用方。
        assertNull(BrokerTls.hostFromBrokerUri(null));
        assertNull(BrokerTls.hostFromBrokerUri("ssl://"));
        assertNull(BrokerTls.hostFromBrokerUri("ssl://[::1"));
    }

    @Test
    public void ipLiteralDetectionDrivesTheSniDecision() {
        assertTrue(BrokerTls.isIpLiteral("10.0.0.10"));
        assertTrue(BrokerTls.isIpLiteral("127.0.0.1"));
        assertTrue(BrokerTls.isIpLiteral("::1"));
        assertFalse(BrokerTls.isIpLiteral("tb.example.test"));
        assertFalse("超出 255 的段不是合法 IPv4", BrokerTls.isIpLiteral("999.0.0.1"));
        assertFalse("段数不对不是 IPv4", BrokerTls.isIpLiteral("10.0.0"));
        assertFalse(BrokerTls.isIpLiteral(""));
        assertFalse(BrokerTls.isIpLiteral(null));
    }

    // ---------- 主机名匹配规则 ----------

    @Test
    public void hostnameMatchesDnsIpAndWildcardSans() throws Exception {
        X509Certificate cert = certificate("server-cert.pem");

        assertNull("精确域名", BrokerTls.hostnameMismatchReason("tb.example.test", cert));
        assertNull("大小写不敏感", BrokerTls.hostnameMismatchReason("TB.Example.Test", cert));
        assertNull("IP SAN", BrokerTls.hostnameMismatchReason("127.0.0.1", cert));
        assertNull("通配符匹配一段", BrokerTls.hostnameMismatchReason("a.wild.example.test", cert));
    }

    @Test
    public void hostnameRejectsWhatTheCertificateDoesNotCover() throws Exception {
        X509Certificate cert = certificate("server-cert.pem");

        assertNotNull("别的域名", BrokerTls.hostnameMismatchReason("evil.example.test", cert));
        assertNotNull("别的 IP", BrokerTls.hostnameMismatchReason("10.0.0.10", cert));
        assertNotNull("通配符不跨段", BrokerTls.hostnameMismatchReason("a.b.wild.example.test", cert));
        assertNotNull("通配符不匹配母域本身", BrokerTls.hostnameMismatchReason("wild.example.test", cert));
        assertNotNull("后缀相同但不是子域", BrokerTls.hostnameMismatchReason("nottb.example.test", cert));
        assertNotNull("空主机名不能放行", BrokerTls.hostnameMismatchReason("", cert));
        assertNotNull("null 主机名不能放行", BrokerTls.hostnameMismatchReason(null, cert));
    }

    @Test
    public void certificateWithoutSanIsRejectedInsteadOfFallingBackToCn() throws Exception {
        // nosan-cert.pem 的 CN 就是 tb.example.test。回退到 CN 会让它通过——RFC 6125 已废弃这种用法，
        // 而且云端 gen-certs.sh 一定会写 SAN，所以"没有 SAN"只可能是证书配错了。
        X509Certificate cert = certificate("nosan-cert.pem");
        String reason = BrokerTls.hostnameMismatchReason("tb.example.test", cert);
        assertNotNull("不能因为 CN 对上了就放行", reason);
        assertTrue(reason, reason.contains("SAN"));
    }

    // ---------- 协议版本收敛 ----------

    @Test
    public void restrictProtocolsKeepsOnlyTls12AndAbove() throws Exception {
        SSLSocketFactory factory = BrokerTls.privateCaSocketFactory(
                FixtureLoader.openStream("tls/ca.pem"), "tb.example.test");
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            String[] enabled = BrokerTls.restrictProtocols(socket);

            assertTrue("这个 JDK 至少要支持 TLSv1.2", enabled.length > 0);
            for (String protocol : socket.getEnabledProtocols()) {
                assertTrue("不应该还开着 " + protocol,
                        Arrays.asList(BrokerTls.allowedProtocols()).contains(protocol));
            }
        }
    }

    // ---------- 真实握手 ----------

    @Test
    public void handshakeSucceedsAgainstCertificateSignedByThePrivateCa() throws Exception {
        try (TlsTestServer server = TlsTestServer.start("server-cert.pem", "server-key.pem")) {
            SSLSocketFactory factory = BrokerTls.privateCaSocketFactory(
                    FixtureLoader.openStream("tls/ca.pem"), "127.0.0.1");
            handshake(factory, server.port());
        }
    }

    @Test
    public void handshakeFailsWhenCertificateComesFromAnotherCa() throws Exception {
        // rogue-cert.pem 的主机名是对的（127.0.0.1），只是签发方不是我们信任的 CA。
        try (TlsTestServer server = TlsTestServer.start("rogue-cert.pem", "rogue-key.pem")) {
            SSLSocketFactory factory = BrokerTls.privateCaSocketFactory(
                    FixtureLoader.openStream("tls/ca.pem"), "127.0.0.1");
            assertHandshakeFails(factory, server.port(), "签发方不受信任时必须握手失败");
        }
    }

    @Test
    public void theRogueCertificateItselfIsValid() throws Exception {
        // 对照组：换成签发它的那个 CA 就能握手成功。这条排除掉"上一个测试其实是因为证书本身
        // 有问题（过期、字段坏了）才失败"的可能——失败的原因确实只是信任锚不对。
        try (TlsTestServer server = TlsTestServer.start("rogue-cert.pem", "rogue-key.pem")) {
            SSLSocketFactory factory = BrokerTls.privateCaSocketFactory(
                    FixtureLoader.openStream("tls/other-ca.pem"), "127.0.0.1");
            handshake(factory, server.port());
        }
    }

    @Test
    public void handshakeFailsWhenHostnameDoesNotMatch() throws Exception {
        // 证书是我们的 CA 签的、链完全合法，只是没签给 10.0.0.10 这个地址。
        // 这一条是 Paho 0.4.0 自己完全不管的部分——没有 TrustManager 里那道校验就会连上。
        try (TlsTestServer server = TlsTestServer.start("server-cert.pem", "server-key.pem")) {
            SSLSocketFactory factory = BrokerTls.privateCaSocketFactory(
                    FixtureLoader.openStream("tls/ca.pem"), "10.0.0.10");
            assertHandshakeFails(factory, server.port(), "主机名对不上时必须握手失败");
        }
    }

    @Test
    public void systemTrustRejectsThePrivateCaCertificate() throws Exception {
        // 私有 CA 不在系统信任库里——这正是 issue #40 §4 里"设备现在接私有化环境的 8883
        // 会直接握手失败"的那个现象，用真实握手把它钉住。
        try (TlsTestServer server = TlsTestServer.start("server-cert.pem", "server-key.pem")) {
            SSLSocketFactory factory = BrokerTls.systemTrustSocketFactory("127.0.0.1");
            assertHandshakeFails(factory, server.port(), "系统信任库不该认识我们的自签 CA");
        }
    }

    @Test
    public void emptyCaFileIsRejectedUpFront() {
        // 部署时 ca.pem 拷成了空文件/拷错了内容：应该在建工厂时就报错，
        // 而不是留一个"看着建好了、连上去才失败"的工厂。
        try {
            BrokerTls.privateCaSocketFactory(
                    new java.io.ByteArrayInputStream(new byte[0]), "tb.example.test");
            fail("空 CA 文件应该抛异常");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("没有可用的 PEM 证书"));
        }
    }

    // ---------- 工具 ----------

    private static void handshake(SSLSocketFactory factory, int port) throws Exception {
        // 严格复刻 Paho TCPNetworkModule.start() 的调用顺序。
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            BrokerTls.restrictProtocols(socket);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
            socket.setSoTimeout(5000);
            socket.startHandshake();
        }
    }

    private static void assertHandshakeFails(SSLSocketFactory factory, int port, String message) {
        try {
            handshake(factory, port);
            fail(message);
        } catch (Exception expected) {
            // SSLHandshakeException 或它在不同 JDK 上的包装形态，都算通过。
        }
    }

    private static X509Certificate certificate(String fixtureName) throws Exception {
        try (InputStream in = FixtureLoader.openStream("tls/" + fixtureName)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static PrivateKey privateKey(String fixtureName) throws Exception {
        String pem = FixtureLoader.loadText("tls/" + fixtureName)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** 临时 TLS 服务端：接一个连接、握完手就退，只为让客户端那边的校验真的跑起来。 */
    private static final class TlsTestServer implements AutoCloseable {

        private final SSLServerSocket serverSocket;
        private final Thread thread;

        static TlsTestServer start(String certFixture, String keyFixture) throws Exception {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setKeyEntry("server", privateKey(keyFixture), KEYSTORE_PASSWORD,
                    new Certificate[]{certificate(certFixture)});

            KeyManagerFactory keyManagers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, KEYSTORE_PASSWORD);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);

            SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
            socket.setSoTimeout(10_000);
            return new TlsTestServer(socket);
        }

        private TlsTestServer(SSLServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                    accepted.setSoTimeout(5000);
                    accepted.startHandshake();
                    accepted.getInputStream().read(); // 等客户端关闭
                } catch (Exception ignored) {
                    // 客户端拒绝证书时服务端这边必然也抛——测试关心的是客户端的行为。
                }
            }, "tls-test-server");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            thread.join(2000);
        }
    }

    @Test
    public void allowedProtocolListIsOrderedStrongestFirst() {
        assertEquals(Arrays.asList("TLSv1.3", "TLSv1.2"), Arrays.asList(BrokerTls.allowedProtocols()));
        BrokerTls.allowedProtocols()[0] = "SSLv3";
        assertEquals("返回的应该是副本，改不动内部那份", "TLSv1.3", BrokerTls.allowedProtocols()[0]);
    }
}
