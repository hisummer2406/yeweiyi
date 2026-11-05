package com.sansen.yeweiyi.bean;

import java.io.Serializable;

/**
 * Created by PC on 2018-11-23.
 */
public class NetOilCan implements Serializable {
    //油罐id
    private int id;
    //油品
    private String oiltype;
    //油品颜色
    private int oilColor;
    //油位
    private double oilLevel;
    //水位
    private double waterLevel;
    //水位
    private double temp;
    //油体积
    private double oilVolume;
    //水体积
    private double waterVolume;
    //油重量
    private double oilWeight;
    //空油量
    private double nullVolume;
    //油比例
    private int oilRadio;
    //油比例
    private int waterRadio;
    //报警状态
    private String alarmState;
    //探针地址
    private String probeAddress;
    //通信状态
    private boolean isConnect;
    //探针类型
    private String probeType="";
    //探针地址
    private String address="";


    //新增字段，记录总共有多少个探针，作用是在线液位仪不用每次输入数量
    private int fatherAllProbe;
    //新增字段，预留备用。
    private Object object;
    //设备名称（ID）
    private String deviceName;

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getOiltype() {
        return oiltype;
    }

    public void setOiltype(String oiltype) {
        this.oiltype = oiltype;
    }

    public double getOilLevel() {
        return oilLevel;
    }

    public void setOilLevel(double oilLevel) {
        this.oilLevel = oilLevel;
    }

    public double getWaterLevel() {
        return waterLevel;
    }

    public void setWaterLevel(double waterLevel) {
        this.waterLevel = waterLevel;
    }

    public double getOilVolume() {
        return oilVolume;
    }

    public void setOilVolume(double oilVolume) {
        this.oilVolume = oilVolume;
    }

    public double getWaterVolume() {
        return waterVolume;
    }

    public void setWaterVolume(double waterVolume) {
        this.waterVolume = waterVolume;
    }

    public double getNullVolume() {
        return nullVolume;
    }

    public void setNullVolume(double nullVolume) {
        this.nullVolume = nullVolume;
    }

    public int getOilRadio() {
        return oilRadio;
    }

    public void setOilRadio(int oilRadio) {
        this.oilRadio = oilRadio;
    }

    /**
     * 液位占罐高的百分比（0–100，一位小数），遥测 {@code tank{n}.oilRatioPct} 用。跟本机罐体图
     * 同一含义（{@code OilCan.oilRatio} = 液位÷罐高×100），只是不截成整数。null = 罐高未配置，不上报。
     */
    private Double oilRatioPct;

    public Double getOilRatioPct() {
        return oilRatioPct;
    }

    public void setOilRatioPct(Double oilRatioPct) {
        this.oilRatioPct = oilRatioPct;
    }

    public int getWaterRadio() {
        return waterRadio;
    }

    public void setWaterRadio(int waterRadio) {
        this.waterRadio = waterRadio;
    }

    public String getAlarmState() {
        return alarmState;
    }

    public void setAlarmState(String alarmState) {
        this.alarmState = alarmState;
    }

    public boolean isConnect() {
        return isConnect;
    }

    public void setConnect(boolean connect) {
        isConnect = connect;
    }

    public int getOilColor() {
        return oilColor;
    }

    public void setOilColor(int oilColor) {
        this.oilColor = oilColor;
    }

    public String getProbeType() {
        return probeType;
    }

    public void setProbeType(String probeType) {
        this.probeType = probeType;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public double getTemp() {
        return temp;
    }

    public void setTemp(double temp) {
        this.temp = temp;
    }

    public double getOilWeight() {
        return oilWeight;
    }

    public void setOilWeight(double oilWeight) {
        this.oilWeight = oilWeight;
    }

    public String getProbeAddress() {
        return probeAddress;
    }

    public void setProbeAddress(String probeAddress) {
        this.probeAddress = probeAddress;
    }

    public int getFatherAllProbe() {
        return fatherAllProbe;
    }

    public void setFatherAllProbe(int fatherAllProbe) {
        this.fatherAllProbe = fatherAllProbe;
    }

    public Object getObject() {
        return object;
    }

    public void setObject(Object object) {
        this.object = object;
    }

    @Override
    public String toString() {
        return "NetOilCan{" +
                "id=" + id +
                ", oiltype='" + oiltype + '\'' +
                ", oilColor=" + oilColor +
                ", oilLevel=" + oilLevel +
                ", waterLevel=" + waterLevel +
                ", temp=" + temp +
                ", oilVolume=" + oilVolume +
                ", waterVolume=" + waterVolume +
                ", nullVolume=" + nullVolume +
                ", oilRadio=" + oilRadio +
                ", waterRadio=" + waterRadio +
                ", alarmState='" + alarmState + '\'' +
                ", isConnect=" + isConnect +
                ", probeType='" + probeType + '\'' +
                ", address='" + address + '\'' +
                '}';
    }
}
