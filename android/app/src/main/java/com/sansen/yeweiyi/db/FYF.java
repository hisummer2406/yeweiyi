package com.sansen.yeweiyi.db;

import android.content.Context;

import com.lidroid.xutils.db.annotation.Column;
import com.lidroid.xutils.db.annotation.Id;

import asynmsg.intf.IAsyData;
import asynmsg.manage.AsyManager;
import asynmsg.manage.ObservableManager;

/**
 * Created by PC on 2017-12-21.
 */

public class FYF {
    public static final int SAFETY_OPEN=0;  //正常打开
    public static final int COMPULSORY_OPEN=1; //强制打开
    public static final int TIME_ERROR=2; //时间内不允许打开
    public static final int TIMEOUT_ERROR=3;  //打开错误

    @Id
    private int id;
    private int way; //打开方式，0 正常打开 （防溢阀冷却过后打开）  p'    1 （未冷却） 强制打开
    private boolean state=true;//打开关闭  true 可以使用  false 不可使用
    @Column(column = "lastTime")
    private long lastTime ;//上次打开时间
    private boolean isOpen;//当前防溢阀处于打开还是关闭 与 isState的区别  isOpen是指 防溢阀打开关闭状态， isState是冷却状态

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean isState() {
        return (System.currentTimeMillis()-lastTime)>(1*60*1000)?true:false;
    }

    public void setState(boolean state) {
        this.state = state;
    }

    public Long getLastTime() {
        return lastTime;
    }

    public int getWay() {
        return way;
    }

    public void setWay(int way) {
        this.way = way;
    }

    public void setLastTime(long lastTime) {
        this.lastTime = lastTime;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void setOpen(boolean open) {
        isOpen = open;
    }

    @Override
    public String toString() {
        return "FYF{" +
                "id=" + id +
                ", way=" + way +
                ", state=" + state +
                ", lastTime=" + lastTime +
                ", isOpen=" + isOpen +
                '}';
    }

    /**
     * 需要正常打开
     * @return
     */
    public void openFyf(Context ct){
        //是否允许打开FYF
        AsyManager.getInstance().openOne(this.id,(byte)0x66, new ObservableManager.ReciveListener() {
            @Override
            public void recive(int id, IAsyData object) {

            }

            @Override
            public void error(int id) {

            }
        });
    }


}
