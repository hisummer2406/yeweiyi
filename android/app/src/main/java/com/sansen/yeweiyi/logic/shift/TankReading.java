package com.sansen.yeweiyi.logic.shift;

/** 交班开始/结束时一个罐的读数（容积已由罐表换算好）。纯值对象，不依赖 Android。 */
public final class TankReading {
    public final int tank;
    public final double levelMm;
    public final double volumeL;
    public final double density;
    /** 当时的介质显示名（{@code OilCan.getJieZhi()}，随界面语言变化），可为 null。 */
    public final String oilType;

    public TankReading(int tank, double levelMm, double volumeL, double density, String oilType) {
        this.tank = tank;
        this.levelMm = levelMm;
        this.volumeL = volumeL;
        this.density = density;
        this.oilType = oilType;
    }
}
