package com.sansen.yeweiyi.sensor;


import android.databinding.ObservableField;

public class SenSorInfo {

    public ObservableField<String> pressure = new ObservableField("");
    private static SenSorInfo instance;

    public static SenSorInfo getInstance(){
        if (instance == null){
            synchronized (SenSorInfo.class){
                if (instance==null){
                    instance = new SenSorInfo();
                }
            }
        }
        return instance;
    }

    private SenSorInfo(){

    }
}
