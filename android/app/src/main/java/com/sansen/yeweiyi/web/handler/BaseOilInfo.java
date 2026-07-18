package com.sansen.yeweiyi.web.handler;


import android.util.Log;
import android.util.SparseArray;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.yanzhenjie.andserver.RequestHandler;
import com.yanzhenjie.andserver.RequestMethod;
import com.yanzhenjie.andserver.annotation.RequestMapping;

import org.apache.httpcore.HttpException;
import org.apache.httpcore.HttpRequest;
import org.apache.httpcore.HttpResponse;
import org.apache.httpcore.entity.StringEntity;
import org.apache.httpcore.protocol.HttpContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by PC on 2018-01-08.
 */

public class BaseOilInfo implements RequestHandler {
    private String TAG ="BaseOilInfo";
    @RequestMapping(method = {RequestMethod.POST})
    @Override
    public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {
        SparseArray<OilCan> data= OilcanDataManager.getInstance(MyApplication.getInstance()).getData();
        ArrayList<BaseOilCan> array= new ArrayList<BaseOilCan>();
        HashMap<String,Object> data2=new HashMap<>();
        data2.put("pressure",0);
        BaseOilCan base;
        OilCan oilcan;
        for (int i=0;i<data.size();i++){
            oilcan = data.valueAt(i);
            base= new BaseOilCan();
            base.setId(oilcan.getOilCanPos());
            base.setOilType(Const.getOilType(oilcan.getOilType()));
            base.setLevel(oilcan.getOilLevel());
            base.setVolume(oilcan.getOilVolume());
            base.setWeight(oilcan.getOilWeight());
            base.setTemple(oilcan.getTemper());
            base.setConnet(oilcan.getErrorTimes() == 0 ? 1: 0);
            base.setOilRadio(oilcan.getOilRatio()/100f);
            base.setWaterRadio(oilcan.getWaterRatio()/100f);
            if (base.getConnet()==0){
                base.setStateInfo(SystemUtils.getRsStr(R.string.oil_Communication_interrupt));
            }else{
                base.setStateInfo(oilcan.getStateInfo());
            }
            array.add(base);
        }
        data2.put("data",array);

        String oilsStr=JSONObject.toJSONString(data2);
        Log.i(TAG, "handle: ="+oilsStr);
        httpResponse.setStatusCode(200);
        StringEntity stringEntity = new StringEntity(oilsStr, "utf-8");
        httpResponse.setEntity(stringEntity);
    }
    class BaseOilCan{
        int id;//罐号
        String oilType;//油品
        double level;//油位
        double volume;//体积
        double weight;//重量
        double temple;//温度
        double oilRadio;
        double waterRadio;
        int connet;//连接状态 0  油罐连接正常   1 油罐连接失败
        String stateInfo;

        public String getStateInfo() {
            return stateInfo;
        }

        public void setStateInfo(String stateInfo) {
            this.stateInfo = stateInfo;
        }

        public int getConnet() {
            return connet;
        }

        public void setConnet(int connet) {
            this.connet = connet;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getOilType() {
            return oilType;
        }

        public void setOilType(String oilType) {
            this.oilType = oilType;
        }

        public double getLevel() {
            return level;
        }

        public void setLevel(double level) {
            this.level = level;
        }

        public double getVolume() {
            return volume;
        }

        public void setVolume(double volume) {
            this.volume = volume;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public double getTemple() {
            return temple;
        }

        public void setTemple(double temple) {
            this.temple = temple;
        }

        public double getOilRadio() {
            return oilRadio;
        }

        public void setOilRadio(double oilRadio) {
            this.oilRadio = oilRadio;
        }

        public double getWaterRadio() {
            return waterRadio;
        }

        public void setWaterRadio(double waterRadio) {
            this.waterRadio = waterRadio;
        }
    }
}
