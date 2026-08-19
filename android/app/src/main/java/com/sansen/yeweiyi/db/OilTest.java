package com.sansen.yeweiyi.db;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;
import com.lidroid.xutils.db.annotation.NotNull;

public class OilTest {

    /**
     * 有罐id 1-9
     */

    @Id
    private int pk_oilCanId;

    /**
     * 高度
     */
    @Column(column = "height")
    private double height;

    /**
     * 温度
     */
    @Column(column = "temp")
    private int temp;

    @Column(column = "enable")
    @NotNull
    private int enable = 0;

    public OilTest() {
        super();
    }

    @Column(column = "gmt_modified")
    private String gmt_modified;

    @Override
    public String toString() {
        return "OilCanSet{" +
                "pk_oilCanId=" + pk_oilCanId +
                ", height=" + height +
                ", enable=" + enable +
                ", gmt_modified=" + gmt_modified +
                ", enable='" + enable + '\''
                +
                '}';
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public int getTemp() {
        return temp;
    }

    public void setTemp(int temp) {
        this.temp = temp;
    }

    public String[] getParams() {
        String[] params = {"pk_oilCanId", "height",  "temp", "enable","gmt_modified"};
        return params;
    }

    public int getEnable() {
        return enable;
    }

    public void setEnable(int enable) {
        this.enable = enable;
    }


    public int getPk_oilCanId() {
        return pk_oilCanId;
    }

    public void setPk_oilCanId(int pk_oilCanId) {
        this.pk_oilCanId = pk_oilCanId;
    }

    public String getGmt_modified() {
        return gmt_modified;
    }

    public void setGmt_modified(String gmt_modified) {
        this.gmt_modified = gmt_modified;
    }
}
