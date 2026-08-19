package com.sansen.yeweiyi.bean;


import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

public class OilRecord {
    private double oilLevel;//油位
    private double WaterLevel;//水位
    private double oilVolume;//油体积
    private double temp;//温度
    private int OilCanPos;
    private String oiltype;//油品
    private double nullVolume;;//可填油重量
    private double oilWeight;//存油重量
    private double waterVolume;//水体积
    private String probeaddress;//探针编号
    private String probeType;//探针类型
    private long uploadTime;

    public long getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(long uploadTime) {
        this.uploadTime = uploadTime;
    }

    public OilRecord(long uploadTime, double oilLevel, double waterLevel, double oilVolume, double temp, int oilCanPos, String oiltype, double nullVolume, double oilWeight, double waterVolume, String probeaddress, String probeType) {
        this.oilLevel = oilLevel;
        WaterLevel = waterLevel;
        this.uploadTime=uploadTime;
        this.oilVolume = oilVolume;
        this.temp = temp;
        OilCanPos = oilCanPos;
        this.oiltype = oiltype;
        this.nullVolume = nullVolume;
        this.oilWeight = oilWeight;
        this.waterVolume = waterVolume;
        this.probeaddress = probeaddress;
        this.probeType = probeType;
    }

    public double getOilLevel() {
        return oilLevel;
    }

    public void setOilLevel(double oilLevel) {
        this.oilLevel = oilLevel;
    }

    public double getWaterLevel() {
        return WaterLevel;
    }

    public void setWaterLevel(double waterLevel) {
        WaterLevel = waterLevel;
    }

    public double getOilVolume() {
        return oilVolume;
    }

    public void setOilVolume(double oilVolume) {
        this.oilVolume = oilVolume;
    }


    public int getOilCanPos() {
        return OilCanPos;
    }

    public void setOilCanPos(int oilCanPos) {
        OilCanPos = oilCanPos;
    }

    public double getTemp() {
        return temp;
    }

    public void setTemp(double temp) {
        this.temp = temp;
    }

    public String getOiltype() {
        return oiltype;
    }

    public void setOiltype(String oiltype) {
        this.oiltype = oiltype;
    }

    public double getNullVolume() {
        return nullVolume;
    }

    public void setNullVolume(double nullVolume) {
        this.nullVolume = nullVolume;
    }

    public double getOilWeight() {
        return oilWeight;
    }

    public void setOilWeight(double oilWeight) {
        this.oilWeight = oilWeight;
    }

    public double getWaterVolume() {
        return waterVolume;
    }

    public void setWaterVolume(double waterVolume) {
        this.waterVolume = waterVolume;
    }

    public String getProbeaddress() {
        return probeaddress;
    }

    public void setProbeaddress(String probeaddress) {
        this.probeaddress = probeaddress;
    }

    public String getProbeType() {
        return probeType;
    }

    public void setProbeType(String probeType) {
        this.probeType = probeType;
    }
}
