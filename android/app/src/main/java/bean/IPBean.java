package bean;

import java.io.Serializable;

public class IPBean implements Serializable {
    String ip;
    int port;
    String addr;

    public IPBean(String ip, int port,String addr) {
        this.ip = ip;
        this.port = port;
        this.addr = addr;
    }

    public String getAddr() {
        return addr;
    }

    public void setAddr(String addr) {
        this.addr = addr;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
