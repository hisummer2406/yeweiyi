package serialport.manager;

import asynmsg.intf.IAsyData;

class QuDongBan implements IAsyData {
    private int pcAddress;
    private int deviceAddress;
    private int chinaNo;
    private byte [] data;
    private byte commd;

    @Override
    public void setData(byte[] bytes) {
        this.data = bytes;
    }

    @Override
    public byte[] getData() {
        return this.data;
    }

    @Override
    public int getChainNo() {
        return this.chinaNo;
    }

    @Override
    public void setChainNo(int chainNo) {
        this.chinaNo = chainNo;
    }

    @Override
    public byte getCommd() {
        return this.commd;
    }

    @Override
    public void setCommd(byte commd) {
        this.commd = commd;
    }

    public int getPcAddress() {
        return this.pcAddress;
    }

    public void setPcAddress(int pcAddress) {
        this.pcAddress = pcAddress;
    }

    public int getDeviceAddress() {
        return this.deviceAddress;
    }

    public void setDeviceAddress(int deviceAddress) {
        this.deviceAddress = deviceAddress;
    }

}

