package com.sansen.yeweiyi.manager;

import com.sansen.yeweiyi.bean.OilCan;

/**
 * 探头数据刷新回调。由 UI 层（{@code base.BaseActivity}）实现并注册给 {@link SerialPortManager}，
 * 取代编排层直接 {@code import BaseActivity} 并引用其 {@code DATA_REFRESH} 常量的做法。
 * 见 openspec/changes/archive/2026-11-02-layer-data-and-hardware-boundaries §8。
 */
public interface DataRefreshCallback {
    void onDataRefresh(OilCan oilCan);
}
