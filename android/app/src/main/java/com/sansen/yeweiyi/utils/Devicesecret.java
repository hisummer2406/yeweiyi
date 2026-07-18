package com.sansen.yeweiyi.utils;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;

import org.apache.commons.lang3.StringUtils;

public class Devicesecret {

    public static String getDevice(){
        if (!StringUtils.isNotEmpty(SharePreManager.getIpBean().getAddr())) {
            String deviceName = SharePrefUtil.getString(MyApplication.getInstance(), SharePrefUtil.DEVICESECRET, "");
            if (StringUtils.isNotEmpty(deviceName)) {
                return deviceName;
            }

        }
        return SharePreManager.getIpBean().getAddr();

    }
}
