package com.sansen.yeweiyi.structbean;

import com.alibaba.fastjson.JSONObject;

/**
 * Created by PC on 2018-07-26.
 */

public class TestMain{
    public static void main(String [] arg){
    	ProbeCanIn correctionIn = new ProbeCanIn();
        correctionIn.uCount=2;
        ProbeCanIn.ProbeCanData correctionData;
        ProbeCanIn.ProbeCanData  data[] =new ProbeCanIn.ProbeCanData[2];
        for (int i=0;i<2;i++){
        	correctionData = correctionIn.new ProbeCanData();
            correctionData.strDeviceModel="三绅科技";
            correctionData.strOilName="68#";
            data[i]=correctionData;
        }
        correctionIn.pProbeCanData = data;
        System.out.println(JSONObject.toJSONString(correctionIn));
    }
}
