package com.sansen.yeweiyi.logic.thingsboard;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 设备到 broker 这一段 TLS 的全部判断逻辑：CA 信任链 + 主机名校验 + broker URI 解析。
 * 纯 Java（不碰 {@code Context}/Android 单例），跟 {@code logic/inout}、{@code logic/thingsboard}
 * 其余部分一样可以在 JVM 上直接单测；Android 那一侧只负责"CA 文件从哪来"和 SNI
 * （见 {@code manager/ThingsBoardCaStore}、{@code manager/SniSocketFactory}）。
 *
 * <h3>为什么主机名校验要自己做</h3>
 * 项目里的 {@code libs/mqtt-client-0.4.0.jar}（Paho 2013 年版）压根没有主机名校验：
 * {@code TCPNetworkModule.start()} 调的是无参 {@code SocketFactory.createSocket()} 再
 * {@code socket.connect(addr)}——socket 自己不知道要连的是哪个名字，平台那套自动校验
 * （以及自动 SNI）都用不上。所以校验只能塞进 {@link X509TrustManager}：它在握手过程中、
 * 应用数据发出去之前执行，比握手完成后再回调检查安全——后者存在"CONNECT 报文（里面带着
 * access token）已经发给冒名服务端"的窗口。
 *
 * <h3>信任范围</h3>
 * 有私有 CA 就<b>只</b>信这一个 CA，不再叠加系统根证书：离线部署里服务端证书只可能来自这个
 * CA，多信一批公网 CA 只会扩大受攻击面而没有任何好处。这跟云端 {@code internal/devicesim}
 * 的 {@code tls.Config{RootCAs: pool}} 是同一个取舍。
 * <p>
 * 任何情况下都<b>不提供</b>"跳过证书校验"的开关。设备要从这条连接上接收的是启泵、开阀这类
 * 命令，不校验服务端身份的 TLS 只是对被动监听者加了密，挡不住中间人冒充云端下发命令。
 */
public final class BrokerTls {

    /** 与云端 {@code tls.Config.MinVersion = tls.VersionTLS12} 对齐。 */
    private static final String[] ALLOWED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    /** @return 允许的协议，强的在前。返回副本，避免这份清单被调用方就地改掉。 */
    public static String[] allowedProtocols() {
        return ALLOWED_PROTOCOLS.clone();
    }

    private BrokerTls() {
    }

    /**
     * 从 {@code ssl://host:8883} 这样的 broker URI 里取出主机部分。
     * <p>
     * 不用 {@code java.net.URI}：{@code ssl://} 不是它认识的 scheme，遇到下划线主机名之类
     * 也会直接抛异常，而这里的输入是设备上人工填的 IP/域名，解析失败就连不上，不值得为此
     * 引入一个更挑剔的解析器。
     *
     * @return 主机名或 IP；解析不出来返回 null
     */
    public static String hostFromBrokerUri(String brokerUri) {
        if (brokerUri == null) {
            return null;
        }
        String rest = brokerUri;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        if (rest.startsWith("[")) {
            // IPv6 字面量：[::1]:8883
            int close = rest.indexOf(']');
            if (close < 0) {
                return null;
            }
            String host = rest.substring(1, close);
            return host.isEmpty() ? null : host;
        }
        int colon = rest.lastIndexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest.isEmpty() ? null : rest;
    }

    /**
     * 是不是 IP 字面量。SNI 用得上：RFC 6066 明确规定 server_name 里不能放 IP 字面量，
     * 塞进去有的服务端会直接拒绝握手。
     */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 只可能是字面量，主机名里不会有冒号
        }
        // IPv4：四段纯数字。不用 InetAddress.getByName，那个对主机名会去做 DNS 解析。
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把 socket 的可用协议收敛到 {@link #allowedProtocols()}。
     * <p>
     * Paho 0.4.0 不碰协议版本，socket 用的是平台默认开启的那批；老 Android 上这批里可能还带着
     * TLSv1/TLSv1.1。这里跟云端的 {@code MinVersion: tls.VersionTLS12} 对齐，取"平台支持的"
     * 与"我们允许的"的交集——直接硬设 {@code setEnabledProtocols({"TLSv1.3","TLSv1.2"})} 会在
     * 不支持 1.3 的旧平台上抛 IllegalArgumentException。
     *
     * @return 实际启用的协议；交集为空时不改动 socket 并返回空数组（让握手自己去失败，
     *         而不是在这里把连接断在一个更难查的位置）
     */
    public static String[] restrictProtocols(SSLSocket socket) {
        List<String> supported = Arrays.asList(socket.getSupportedProtocols());
        List<String> enabled = new ArrayList<>();
        for (String protocol : ALLOWED_PROTOCOLS) {
            if (supported.contains(protocol)) {
                enabled.add(protocol);
            }
        }
        if (enabled.isEmpty()) {
            return new String[0];
        }
        String[] result = enabled.toArray(new String[0]);
        socket.setEnabledProtocols(result);
        return result;
    }

    /**
     * 只信任 {@code caPem} 里那些 CA 的 socket 工厂，附带对 {@code expectedHost} 的主机名校验。
     *
     * @param caPem       PEM 格式的 CA 证书流（可以是含多张证书的 bundle），由调用方负责关闭
     * @param expectedHost 期望的服务端主机名或 IP，即 broker URI 里的那个
     * @throws GeneralSecurityException PEM 里一张可用证书都没有，或者信任链初始化失败
     */
    public static SSLSocketFactory privateCaSocketFactory(InputStream caPem, String expectedHost)
            throws GeneralSecurityException {
        List<X509Certificate> cas = parseCertificates(caPem);
        if (cas.isEmpty()) {
            throw new CertificateException("CA 文件里没有可用的 PEM 证书");
        }
        KeyStore keyStore = emptyKeyStore();
        int index = 0;
        for (X509Certificate ca : cas) {
            keyStore.setCertificateEntry("ca-" + (index++), ca);
        }
        return socketFactory(trustManagersFor(keyStore), expectedHost);
    }

    /**
     * 系统根证书 + 主机名校验的 socket 工厂——公网 CA 签发证书的常规部署走这条。
     * <p>
     * 主机名校验在这条路径上同样开着：证书是给谁签的、设备连的是不是同一个地址，两件事都得对上。
     * broker 用裸 IP 填时，公网 CA 的证书通常没有 IP SAN，这里会连不上——这不是本方法的问题，
     * 而是那种部署本来就没有可信的服务端身份，需要改成域名或改用私有 CA。
     */
    public static SSLSocketFactory systemTrustSocketFactory(String expectedHost)
            throws GeneralSecurityException {
        return socketFactory(trustManagersFor(null), expectedHost);
    }

    private static SSLSocketFactory socketFactory(TrustManager[] platformTrustManagers, String expectedHost)
            throws GeneralSecurityException {
        X509TrustManager delegate = firstX509(platformTrustManagers);
        if (delegate == null) {
            throw new CertificateException("平台没有提供 X509TrustManager，无法建立 TLS 信任链");
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new HostCheckingTrustManager(delegate, expectedHost)}, null);
        return context.getSocketFactory();
    }

    private static TrustManager[] trustManagersFor(KeyStore keyStore) throws GeneralSecurityException {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore); // null = 平台默认信任库
        return factory.getTrustManagers();
    }

    private static X509TrustManager firstX509(TrustManager[] managers) {
        if (managers == null) {
            return null;
        }
        for (TrustManager manager : managers) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        return null;
    }

    private static List<X509Certificate> parseCertificates(InputStream pem) throws GeneralSecurityException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        // 必须 buffer：generateCertificates 读多张证书时要 mark/reset。
        Collection<? extends Certificate> parsed =
                factory.generateCertificates(new BufferedInputStream(pem));
        List<X509Certificate> result = new ArrayList<>();
        for (Certificate certificate : parsed) {
            if (certificate instanceof X509Certificate) {
                result.add((X509Certificate) certificate);
            }
        }
        return result;
    }

    private static KeyStore emptyKeyStore() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            keyStore.load(null, null); // null/null = 建一张空表，不是从文件加载
        } catch (IOException e) {
            throw new CertificateException("初始化空 KeyStore 失败", e);
        }
        return keyStore;
    }

    /**
     * 校验证书的 SAN 里有没有 {@code expectedHost}。
     * <p>
     * 只看 SAN，不回退到 CN：RFC 6125 已经把 CN 判为废弃用法，而云端 {@code deploy/gen-certs.sh}
     * 生成的证书本来就总是带 SAN（域名进 DNS、IP 进 IP）。回退到 CN 只会让一张配错的证书
     * 悄悄通过。
     *
     * @return null 表示通过；否则返回拒绝原因（给日志/异常用）
     */
    static String hostnameMismatchReason(String expectedHost, X509Certificate certificate) {
        if (expectedHost == null || expectedHost.isEmpty()) {
            return "没有可校验的目标主机名";
        }
        Collection<List<?>> sans;
        try {
            sans = certificate.getSubjectAlternativeNames();
        } catch (CertificateException e) {
            return "读取证书 SAN 失败: " + e.getMessage();
        }
        if (sans == null || sans.isEmpty()) {
            return "服务端证书没有 SAN 扩展，无法确认它是签给 " + expectedHost + " 的";
        }

        boolean expectIp = isIpLiteral(expectedHost);
        List<String> presented = new ArrayList<>();
        for (List<?> san : sans) {
            if (san == null || san.size() < 2) {
                continue;
            }
            Object type = san.get(0);
            Object value = san.get(1);
            if (!(type instanceof Integer) || !(value instanceof String)) {
                continue;
            }
            int tag = (Integer) type;
            String name = (String) value;
            if (expectIp && tag == 7) {
                presented.add("IP:" + name);
                if (ipEquals(expectedHost, name)) {
                    return null;
                }
            } else if (!expectIp && tag == 2) {
                presented.add("DNS:" + name);
                if (dnsMatches(expectedHost, name)) {
                    return null;
                }
            }
        }
        return "服务端证书不包含 " + expectedHost + "，证书里是 " + presented;
    }

    private static boolean dnsMatches(String host, String pattern) {
        String h = host.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        // 末尾的根点（a.example.com.）两边都去掉再比。
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (p.endsWith(".")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return false;
        }
        if (!p.startsWith("*.")) {
            return h.equals(p);
        }
        // 通配符只允许在最左一段，且只匹配一段：*.example.com 匹配 a.example.com，
        // 不匹配 a.b.example.com，也不匹配 example.com 本身。
        String suffix = p.substring(1); // ".example.com"
        if (!h.endsWith(suffix)) {
            return false;
        }
        String label = h.substring(0, h.length() - suffix.length());
        return !label.isEmpty() && label.indexOf('.') < 0;
    }

    private static boolean ipEquals(String expected, String presented) {
        if (expected.equalsIgnoreCase(presented)) {
            return true;
        }
        // IPv6 有多种等价写法（::1 / 0:0:0:0:0:0:0:1），归一化后再比。
        // 两边都是字面量，getByName 不会触发 DNS 查询。
        try {
            return InetAddress.getByName(expected).equals(InetAddress.getByName(presented));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 先让平台按 PKIX 验完证书链，再自己补一道主机名校验。
     * <p>
     * 顺序不能反：链都没验过的证书，里面的 SAN 是攻击者随便写的，先比主机名毫无意义。
     */
    private static final class HostCheckingTrustManager implements X509TrustManager {

        private final X509TrustManager delegate;
        private final String expectedHost;

        HostCheckingTrustManager(X509TrustManager delegate, String expectedHost) {
            this.delegate = delegate;
            this.expectedHost = expectedHost;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // 设备端不做双向认证（ThingsBoard 走 access token 认设备），这里不会被调用。
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            if (chain == null || chain.length == 0) {
                throw new CertificateException("服务端没有提供证书");
            }
            String reason = hostnameMismatchReason(expectedHost, chain[0]);
            if (reason != null) {
                throw new CertificateException("主机名校验失败: " + reason);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
