package com.sansen.yeweiyi.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;

import com.sansen.yeweiyi.utils.NetUtils;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.executor.ExecutorFilter;
import org.apache.mina.filter.logging.LoggingFilter;
import org.apache.mina.transport.socket.DatagramSessionConfig;
import org.apache.mina.transport.socket.nio.NioDatagramAcceptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Udp广播接受者，当有人发送udp广播的时候，返回当前的地址
 * Created by PC on 2018-01-11.
 */

public class UdpService extends Service {

    private static final int PORT = 25566;
    private String TAG = "UdpService";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //Log.i(TAG, "onCreate: 准备启动");
        NioDatagramAcceptor acceptor = new NioDatagramAcceptor();//创建一个UDP的接收器
        acceptor.setHandler(new YourHandler());//设置接收器的处理程序

        Executor threadPool = Executors.newFixedThreadPool(10);//建立线程池
        acceptor.getFilterChain().addLast("exector", new ExecutorFilter(threadPool));
        acceptor.getFilterChain().addLast("logger", new LoggingFilter());

        DatagramSessionConfig dcfg = acceptor.getSessionConfig();//建立连接的配置文件
        dcfg.setReadBufferSize(2048);//设置接收最大字节默认2048
        dcfg.setReceiveBufferSize(1024);//设置输入缓冲区的大小
        dcfg.setSendBufferSize(1024);//设置输出缓冲区的大小
        dcfg.setReuseAddress(true);//设置每一个非主监听连接的端口可以重用

        try {
            acceptor.bind(new InetSocketAddress(PORT));//绑定端口
        } catch (IOException e) {
            //e.printStackTrace();
        }
        //Log.i(TAG, "onCreate: 启动完成");

    }

    public class YourHandler extends IoHandlerAdapter {

//messageSent是Server响应给Clinet成功后触发的事件

        @Override
        public void messageSent(IoSession session, Object message) throws Exception {

        }

//抛出异常触发的事件

        @Override
        public void exceptionCaught(IoSession session, Throwable cause)
                throws Exception {
            cause.printStackTrace();//
            session.close(true);
        }

//Server接收到UDP请求触发的事件

        @Override
        public void messageReceived(IoSession session, Object message)
                throws Exception {
            System.out.println("messageReceived");
            if (message instanceof IoBuffer) {
                IoBuffer buffer = (IoBuffer) message;
                byte[] bb=new byte[buffer.remaining()];
                buffer.get(bb);
                //Log.i(TAG, "messageReceived: "+new String(bb,"gb2312"));
                IoBuffer buffer1 = IoBuffer.wrap(NetUtils.getLocalIPAddress().toString().getBytes());//返回信息给Clinet端
                session.write(buffer1);
                String clientIP = ((InetSocketAddress)session.getRemoteAddress()).getAddress().getHostAddress();
                //Log.i(TAG, "messageReceived:IP地址 "+clientIP);
//声明这里message必须为IoBuffer类型
            }

        }

//连接关闭触发的事件

        @Override
        public void sessionClosed(IoSession session) throws Exception {
            System.out.println("Session closed...");
        }

//建立连接触发的事件

        @Override
        public void sessionCreated(IoSession session) throws Exception {
            System.out.println("Session created...");
            SocketAddress remoteAddress = session.getRemoteAddress();
            System.out.println(remoteAddress);

        }

//会话空闲

        @Override
        public void sessionIdle(IoSession session, IdleStatus status)
                throws Exception {
            System.out.println("Session idle...");
        }

//打开连接触发的事件，它与sessionCreated的区别在于，一个连接地址（A）第一次请求Server会建立一个Session默认超时时间为1分钟，此时若未达到超时时间这个连接地址（A）再一次向Server发送请求即是sessionOpened（连接地址（A）第一次向Server发送请求或者连接超时后向Server发送请求时会同时触发sessionCreated和sessionOpened两个事件）

        @Override
        public void sessionOpened(IoSession session) throws Exception {
            System.out.println("Session Opened...");
            SocketAddress remoteAddress = session.getRemoteAddress();
            System.out.println(remoteAddress);
        }
    }
}