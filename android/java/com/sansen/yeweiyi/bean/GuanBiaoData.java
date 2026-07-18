package com.sansen.yeweiyi.bean;

public class GuanBiaoData {
    private   int id;
    private  float hight;
    private  double rongJi;//容积

    public double getRongJi() {
        return rongJi;
    }

    public void setRongJi(double rongJi) {
        this.rongJi = rongJi;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public float getHight() {
        return hight;
    }

    public void setHight(float hight) {
        this.hight = hight;
    }

    public GuanBiaoData(int id, float hight, double rongJi) {
        this.id = id;
        this.hight = hight;
        this.rongJi = rongJi;
    }


}
