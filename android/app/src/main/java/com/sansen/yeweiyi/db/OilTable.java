package com.sansen.yeweiyi.db;


//入库记录
public class OilTable {

    private double height;
    private double volum;


    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public double getVolum() {
        return volum;
    }

    public void setVolum(double volum) {
        this.volum = volum;
    }

    public String[] getParams() {
        String[] params = {"height", "volum"};
        return params;
    }


}
