package com.sansen.yeweiyi.bean;

import java.io.Serializable;

/**
 * Created by Administrator on 2019-08-20.
 */

public class BrandBean implements Serializable{
    int baudrate;
    int stopbits;
    int paritys;
    int databits;

    public int getBaudrate() {
        return baudrate;
    }

    public void setBaudrate(int baudrate) {
        this.baudrate = baudrate;
    }

    public int getStopbits() {
        return stopbits;
    }

    public void setStopbits(int stopbits) {
        this.stopbits = stopbits;
    }

    public int getParitys() {
        return paritys;
    }

    public void setParitys(int paritys) {
        this.paritys = paritys;
    }

    public int getDatabits() {return databits;
    }

    public void setDatabits(int databits) {
        this.databits = databits;
    }

    public BrandBean(int baudrate, int databits, int stopbits, int paritys) {
        this.baudrate = baudrate;
        this.stopbits = stopbits;
        this.paritys = paritys;
        this.databits = databits;
    }

    @Override
    public String toString() {
        return "BrandBean{" +
                "baudrate=" + baudrate +
                ", stopbits=" + stopbits +
                ", paritys=" + paritys +
                ", databits=" + databits +
                '}';
    }
}
