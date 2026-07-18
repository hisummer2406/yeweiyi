package com.sansen.yeweiyi.bean;

import java.io.Serializable;

public class BaudrateBean implements Serializable {
    int baudrate;
    int databit;
    int stopbit;
    int parity;

    public BaudrateBean(int baudrate, int databit, int stopbit, int parity) {
        this.baudrate = baudrate;
        this.databit = databit;
        this.stopbit = stopbit;
        this.parity = parity;
    }

    public int getBaudrate() {
        return baudrate;
    }

    public void setBaudrate(int baudrate) {
        this.baudrate = baudrate;
    }

    public int getDatabit() {
        return databit;
    }

    public void setDatabit(int databit) {
        this.databit = databit;
    }

    public int getStopbit() {
        return stopbit;
    }

    public void setStopbit(int stopbit) {
        this.stopbit = stopbit;
    }

    public int getParity() {
        return parity;
    }

    public void setParity(int parity) {
        this.parity = parity;
    }
}
