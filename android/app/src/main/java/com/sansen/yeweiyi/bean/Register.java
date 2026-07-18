package com.sansen.yeweiyi.bean;

public class Register {
    long sumTime;//使用时间
    boolean isRegister;
    long registerTime;

    public long getSumTime() {
        return sumTime;
    }

    public void setSumTime(long sumTime) {
        this.sumTime = sumTime;
    }

    public boolean isRegister() {
        return isRegister;
    }

    public void setRegister(boolean register) {
        isRegister = register;
    }

    public long getRegisterTime() {
        return registerTime;
    }

    public void setRegisterTime(long registerTime) {
        this.registerTime = registerTime;
    }

    @Override
    public String toString() {
        return "Register{" +
                "sumTime=" + sumTime +
                ", isRegister=" + isRegister +
                ", registerTime=" + registerTime +
                '}';
    }
}
