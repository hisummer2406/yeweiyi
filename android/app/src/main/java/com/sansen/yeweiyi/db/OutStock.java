package com.sansen.yeweiyi.db;


import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

//入库记录
public class OutStock {

    // 记录id
    @Id
    private int id;

    /**
     * 油罐id
     */
    @Column(column = "oilId")
    private int oilId;
    /**
     * 介质
     */
    @Column(column = "jieZhi")
    private String jieZhi;
    /**
     * 时间
     */
    @Column(column = "time")
    private String time;
    /**
     * 开始高度
     */
    @Column(column = "beginheigth")
    private double beginHeigth;

    /**
     * 结束高度
     */
    @Column(column = "endheigth")
    private double endHeigth;
    /**
     * 开始体积
     */
    @Column(column = "begivolum")
    private double beginVolum;

    /**
     * 结束体积
     */
    @Column(column = "endvolum")
    private double endVolum;


    /**
     * 进油 开始重量
     */
    @Column(column = "beginweight")
    private double beginWeight;
    /**
     * 进油 开始重量
     */
    @Column(column = "endweight")
    private double endWeight;

    /**
     * "AUTO"（InOilManager 自动检测，默认值）或 "MANUAL"（BuLuFragment 手动补录）——
     * 见 StockEventMapper.SOURCE_AUTO/SOURCE_MANUAL。zhongchengyeweiyi#42 待办2 新增列，
     * 老设备升级需要走 DaoHelp 的 ALTER TABLE 迁移（dbVersion 6→7），不是新建表就有的。
     */
    @Column(column = "source")
    private String source = "AUTO";

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getOilId() {
        return oilId;
    }

    public void setOilId(int oilId) {
        this.oilId = oilId;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public double getBeginHeigth() {
        return beginHeigth;
    }

    public void setBeginHeigth(double beginHeigth) {
        this.beginHeigth = beginHeigth;
    }

    public double getEndHeigth() {
        return endHeigth;
    }

    public void setEndHeigth(double endHeigth) {
        this.endHeigth = endHeigth;
    }

    public double getBeginVolum() {
        return beginVolum;
    }

    public void setBeginVolum(double beginVolum) {
        this.beginVolum = beginVolum;
    }

    public double getEndVolum() {
        return endVolum;
    }

    public void setEndVolum(double endVolum) {
        this.endVolum = endVolum;
    }

    public double getBeginWeight() {
        return beginWeight;
    }

    public void setBeginWeight(double beginWeight) {
        this.beginWeight = beginWeight;
    }

    public double getEndWeight() {
        return endWeight;
    }

    public void setEndWeight(double endWeight) {
        this.endWeight = endWeight;
    }

    public String getJieZhi() {
        return jieZhi;
    }

    public void setJieZhi(String jieZhi) {
        this.jieZhi = jieZhi;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public String toString() {//
        return "OilInOut [id=" + id + ", oilId=" + oilId + ", time=" + time + ", jieZhi=" + jieZhi
                + ", beginHeigth=" + beginHeigth + ", endHeigth=" + endHeigth
                + ", beginVolum=" + beginVolum + ", beginWeight=" + beginWeight + ", endWeight=" + endWeight + ", endVolum=" + endVolum + "]";
    }

    public String[] getParams() {
        String[] params = {"id", "oilId", "time", "beginHeigth", "endHeigth", "beginVolum", "beginWeight", "endWeight", "endVolum", "jieZhi"};
        return params;
    }

}
