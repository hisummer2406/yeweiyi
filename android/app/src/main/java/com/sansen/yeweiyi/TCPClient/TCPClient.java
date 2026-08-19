package com.sansen.yeweiyi.TCPClient;

import android.content.Context;
import android.content.Intent;
import android.os.HandlerThread;
import android.util.Log;
import android.util.SparseArray;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.intf.IState;
import com.sansen.yeweiyi.manager.DebugManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.SerialPortManager;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import serialport.help.SerialPortHelper;

public class TCPClient implements Runnable {

    private PrintWriter pw;
    private InputStream is;
    private BufferedReader dis;
    private boolean isRun = true;
    private Socket socket = null;
    InetAddress address = null;
    private String IP = "192.168.0.157";
    private int port = 8888;
    private IState iState;

    public TCPClient(String ip, int port, IState iState) {
        this.IP = ip;
        this.port = port;
        this.iState = iState;
    }


    public void sendData(String msg) {
        pw.println(msg);
        pw.flush();
    }

    public void breakSocket() {
        try {
            if (pw != null)
                pw.close();
            if (is != null)
                is.close();
            if (dis != null)
                dis.close();
            if (socket != null)
                socket.close();
            iState.fail("Socket关闭成功");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            if (socket == null) {
                socket = new Socket(IP, port);
                address = InetAddress.getLocalHost();
                pw = new PrintWriter(socket.getOutputStream(), true);
                is = socket.getInputStream();
                //dis = new DataInputStream(is);
                dis = new BufferedReader(new InputStreamReader(is));
                String info = null;
                iState.success(socket.isConnected());
                while ((info = dis.readLine()) != null) {

                    System.out.println("我是客户端,服务器端说:" + info);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            iState.fail(e.getMessage());
        } finally {
            try {
                if (pw != null)
                    pw.close();
                if (is != null)
                    is.close();
                if (dis != null)
                    dis.close();
                if (socket != null)
                    socket.close();
                iState.fail("Socket连接失败");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
