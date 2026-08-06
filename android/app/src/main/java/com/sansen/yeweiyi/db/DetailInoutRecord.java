package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.NoAutoIncrement;
import com.lidroid.xutils.db.annotation.Table;
import com.sansen.yeweiyi.utils.DoubleUtil;

/**
 * Created by PC on 2019-03-04.
 */
@Table(name = "detail_inout_record")
public class DetailInoutRecord {
    @Id
    @NoAutoIncrement
    int id;
    @Column
    int oilcanId;
    @Column
    String oilType;
    @Column
    double beginHeith;

    @Column
    double endHeight;
    @Column
    double beginVolume;
    @Column
    double endVolume;

    @Column
    String beginTime;
    @Column
    String endTime;

    @Column
    double beginTcVolume;
    @Column
    double endTcVolume;
    @Column
    double beginWater;
    @Column
    double endWater;
    @Column
    double beginWaterVolume;
    @Column
    double endWaterVolume;
    @Column
    double beginOilWeight;
    @Column
    double endOilWeight;
    @Column
    double beginTemp;
    @Column
    double endTemp;
    @Column
    double saleVolume;

    @Column
    double duringDelivery;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getOilcanId() {
        return oilcanId;
    }

    public void setOilcanId(int oilcanId) {
        this.oilcanId = oilcanId;
    }

    public double getBeginHeith() {
        return beginHeith;
    }

    public void setBeginHeith(double beginHeith) {
        this.beginHeith = beginHeith;
    }

    public double getEndHeight() {
        return endHeight;
    }

    public void setEndHeight(double endHeight) {
        this.endHeight = endHeight;
    }

    public String getBeginTime() {
        return beginTime;
    }

    public void setBeginTime(String beginTime) {
        this.beginTime = beginTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public double getBeginTcVolume() {
        return beginTcVolume;
    }

    public void setBeginTcVolume(double beginTcVolume) {
        this.beginTcVolume = beginTcVolume;
    }

    public double getEndTcVolume() {
        return endTcVolume;
    }

    public void setEndTcVolume(double endTcVolume) {
        this.endTcVolume = endTcVolume;
    }

    public double getBeginWater() {
        return beginWater;
    }

    public void setBeginWater(double beginWater) {
        this.beginWater = beginWater;
    }

    public double getEndWater() {
        return endWater;
    }

    public void setEndWater(double endWater) {
        this.endWater = endWater;
    }

    public double getBeginTemp() {
        return beginTemp;
    }

    public void setBeginTemp(double beginTemp) {
        this.beginTemp = beginTemp;
    }

    public double getEndTemp() {
        return endTemp;
    }

    public void setEndTemp(double endTemp) {
        this.endTemp = endTemp;
    }

    public void salesDuring(double volume){
        this.saleVolume=DoubleUtil.add(volume,this.saleVolume);
    }

    public double getBeginVolume() {
        return beginVolume;
    }

    public void setBeginVolume(double beginVolume) {
        this.beginVolume = beginVolume;
    }

    public double getEndVolume() {
        return endVolume;
    }

    public void setEndVolume(double endVolume) {
        this.endVolume = endVolume;
    }

    public double getBeginWaterVolume() {
        return beginWaterVolume;
    }

    public void setBeginWaterVolume(double beginWaterVolume) {
        this.beginWaterVolume = beginWaterVolume;
    }

    public double getEndWaterVolume() {
        return endWaterVolume;
    }

    public void setEndWaterVolume(double endWaterVolume) {
        this.endWaterVolume = endWaterVolume;
    }

    public double getBeginOilWeight() {
        return beginOilWeight;
    }

    public void setBeginOilWeight(double beginOilWeight) {
        this.beginOilWeight = beginOilWeight;
    }

    public double getEndOilWeight() {
        return endOilWeight;
    }

    public void setEndOilWeight(double endOilWeight) {
        this.endOilWeight = endOilWeight;
    }

    public String getOilType() {
        return oilType;
    }

    public void setOilType(String oilType) {
        this.oilType = oilType;
    }

    public double getSaleVolume() {
        return saleVolume;
    }

    public void setSaleVolume(double saleVolume) {
        this.saleVolume = saleVolume;
    }

    public double getDuringDelivery() {
        return duringDelivery;
    }

    public void setDuringDelivery(double duringDelivery) {
        this.duringDelivery = duringDelivery;
    }

    @Override
    public String toString() {
        return "DetailInoutRecord{" +
                "oilcanId=" + oilcanId +
                ", beginHeith=" + beginHeith +
                ", endHeight=" + endHeight +
                ", beginVolume=" + beginVolume +
                ", endVolume=" + endVolume +
                ", beginTime='" + beginTime + '\'' +
                ", endTime='" + endTime + '\'' +
                ", beginTcVolume=" + beginTcVolume +
                ", endTcVolume=" + endTcVolume +
                ", beginWater=" + beginWater +
                ", endWater=" + endWater +
                ", beginTemp=" + beginTemp +
                ", endTemp=" + endTemp +
                '}';
    }
}
