package com.sansen.yeweiyi.manager;

import android.os.Build;
import android.util.Log;

import com.sansen.yeweiyi.logic.thingsboard.BrokerTls;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 在每个新建的 SSLSocket 上补两件 Paho 0.4.0 不会做的事：设置 SNI，以及把协议收敛到 TLS 1.2+。
 * <p>
 * Paho 那版的 {@code TCPNetworkModule.start()} 调的是<b>无参</b>的 {@code createSocket()}，
 * 之后才 {@code socket.connect(addr)}——socket 从头到尾不知道目标主机名，平台那套"按主机名
 * 自动带 SNI"因此完全不生效。云端 {@code internal/devicesim} 踩的是同一个坑（"paho does not
 * derive it from the broker URL"），那边显式设 {@code tls.Config.ServerName} 解决，这边只能
 * 在工厂里挨个 socket 设。
 * <p>
 * 主机名校验<b>不</b>在这里做——那件事在 {@link BrokerTls} 的 TrustManager 里，握手过程中就完成，
 * 早于任何应用数据发出。放到这里（握手完成回调）会留出 CONNECT 报文连同 access token 已经发给
 * 冒名服务端的窗口。
 */
final class SniSocketFactory extends SSLSocketFactory {

    private static final String TAG = "TBSniSocketFactory";

    private final SSLSocketFactory delegate;
    private final String host;

    SniSocketFactory(SSLSocketFactory delegate, String host) {
        this.delegate = delegate;
        this.host = host;
    }

    private Socket configure(Socket socket) {
        if (!(socket instanceof SSLSocket)) {
            return socket;
        }
        SSLSocket sslSocket = (SSLSocket) socket;
        BrokerTls.restrictProtocols(sslSocket);

        // IP 字面量不能进 SNI（RFC 6066 明确禁止），塞进去部分服务端会直接拒绝握手。
        // 而这个项目的 broker 地址就是现场手填的，填 IP 是常态，所以这个判断不是可选项。
        if (BrokerTls.isIpLiteral(host)) {
            return sslSocket;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(Collections.singletonList(new SNIHostName(host)));
                sslSocket.setSSLParameters(params);
            } catch (RuntimeException e) {
                // 设不上 SNI 不该让连接直接断：ThingsBoard 是单证书部署，服务端不靠 SNI 选证书，
                // 少了它握手照样能成，安全性也不受影响（校验在 TrustManager 里）。
                Log.w(TAG, "设置 SNI 失败，继续不带 SNI 握手: " + host, e);
            }
        }
        // API 21–23 没有 SNIHostName/setServerNames。同上：单证书部署下这只是少了一个提示字段。
        return sslSocket;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    /** Paho 走的就是这个重载。 */
    @Override
    public Socket createSocket() throws IOException {
        return configure(delegate.createSocket());
    }

    // 下面这些重载 Paho 用不到，但 SSLSocketFactory 的契约要求都实现。参数名一律带 peer 前缀：
    // SNI 用的始终是构造时传进来的 broker 主机名（this.host），不是这里传进来的地址，
    // 两者叫同一个名字很容易让人读反。
    @Override
    public Socket createSocket(Socket peerSocket, String peerHost, int peerPort, boolean autoClose)
            throws IOException {
        return configure(delegate.createSocket(peerSocket, peerHost, peerPort, autoClose));
    }

    @Override
    public Socket createSocket(String peerHost, int peerPort) throws IOException {
        return configure(delegate.createSocket(peerHost, peerPort));
    }

    @Override
    public Socket createSocket(String peerHost, int peerPort, InetAddress localHost, int localPort)
            throws IOException {
        return configure(delegate.createSocket(peerHost, peerPort, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress peerAddress, int peerPort) throws IOException {
        return configure(delegate.createSocket(peerAddress, peerPort));
    }

    @Override
    public Socket createSocket(InetAddress peerAddress, int peerPort, InetAddress localAddress, int localPort)
            throws IOException {
        return configure(delegate.createSocket(peerAddress, peerPort, localAddress, localPort));
    }
}
