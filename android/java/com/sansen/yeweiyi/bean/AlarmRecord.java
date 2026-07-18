package com.sansen.yeweiyi.bean;

public class AlarmRecord {

    private int oliTank;//油罐编号
    private String equipmentStatus;//设备报警状态
    private String equipmentNews;//设备报警信息
    private long uploadDate;//报警时间

    public int getOliTank() {
        return oliTank;
    }

    public void setOliTank(int oliTank) {
        this.oliTank = oliTank;
    }

    public String getEquipmentStatus() {
        return equipmentStatus;
    }

    public void setEquipmentStatus(String equipmentStatus) {
        this.equipmentStatus = equipmentStatus;
    }

    public String getEquipmentNews() {
        return equipmentNews;
    }

    public void setEquipmentNews(String equipmentNews) {
        this.equipmentNews = equipmentNews;
    }

    public long getUploadDate() {
        return uploadDate;
    }

    public void setUploadDate(long uploadDate) {
        this.uploadDate = uploadDate;
    }

    public AlarmRecord(int oliTank, String equipmentStatus, String equipmentNews, long uploadDate) {
        this.oliTank = oliTank;
        this.equipmentStatus = equipmentStatus;
        this.equipmentNews = equipmentNews;
        this.uploadDate = uploadDate;
    }
}
