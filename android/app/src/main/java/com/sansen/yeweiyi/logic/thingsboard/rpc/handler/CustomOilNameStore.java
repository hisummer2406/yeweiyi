package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

/** 自定义介质名称（oilTypeIndex 32–39）的持久化，设备显示读同一份数据。 */
public interface CustomOilNameStore {
    /** 同步写入并返回该位置原来的 {中文名, 英文名}。 */
    String[] save(int index, String zh, String en);
}
