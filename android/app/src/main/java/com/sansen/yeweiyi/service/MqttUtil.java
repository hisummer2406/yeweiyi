package com.sansen.yeweiyi.service;

public class MqttUtil {
    /**
     * 属性发布 post 上报 set 设置
     * @param productKey
     * @param deviceName
     * @return 上传数据
     */
    public static String getPropert(String productKey,String deviceName,String propertyIdentifer){
        return  "sys/"+productKey+"/"+deviceName+"/thing/event/property/post/"+propertyIdentifer;
    }

    /**
     * 服务发布
     * @param productKey
     * @param deviceName
     * @return 从目前代码上来看应该是监听服务器发过来的消息，在backService的mqtt消息接收处做出相应的逻辑判断。
     */
    public static String getService(String productKey,String deviceName,String serviceIdentifer){
        return  "sys/"+productKey+"/"+deviceName+"/thing/service/"+serviceIdentifer;
    }
    /**
     * 属性发布
     * @param productKey
     * @param deviceName
     * @return 目前代码来看，用于两个地方一个(isOline)是获取是否在线，另一个(msg)是报警信息
     */
    public static String getEvent(String productKey,String deviceName,String eventIdentifer){
        return  "sys/"+productKey+"/"+deviceName+"/thing/event/"+eventIdentifer+"/post";
    }

}
