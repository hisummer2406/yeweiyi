package com.sansen.yeweiyi.bean;

public class OutOilRecord {
    private double beginHeigth;//开始高度
    private double endHeigth;//结束高度
    private double inVolum;//进油体积
    private  double inVolumvt;// V20
    private  double inWeight;//进油重量
    private  long uploadTime;//时间
    private int tankNumber;//罐号

    public double getBeginHeigth() {
        return beginHeigth;
    }

    public double getEndHeigth() {
        return endHeigth;
    }

    public double getInVolum() {
        return inVolum;
    }

    public double getInVolumvt() {
        return inVolumvt;
    }


    public long getUploadTime() {
        return uploadTime;
    }

    public void setBeginHeigth(double beginHeigth) {
        this.beginHeigth = beginHeigth;
    }

    public void setEndHeigth(double endHeigth) {
        this.endHeigth = endHeigth;
    }

    public void setInVolum(double inVolum) {
        this.inVolum = inVolum;
    }

    public void setInVolumvt(double inVolumvt) {
        this.inVolumvt = inVolumvt;
    }


    public void setUploadTime(long uploadTime) {
        this.uploadTime = uploadTime;
    }

    public double getInWeight() {
        return inWeight;
    }

    public void setInWeight(double inWeight) {
        this.inWeight = inWeight;
    }

    public int getTankNumber() {
        return tankNumber;
    }

    public void setTankNumber(int tankNumber) {
        this.tankNumber = tankNumber;
    }

    public OutOilRecord(double beginHeigth, double endHeigth, double inVolum, double inVolumvt, double inWeight, long uploadTime, int tankNumber) {
        this.beginHeigth = beginHeigth;
        this.endHeigth = endHeigth;
        this.inVolum = inVolum;
        this.inVolumvt = inVolumvt;
        this.inWeight = inWeight;
        this.uploadTime = uploadTime;
        this.tankNumber = tankNumber;
    }
}
