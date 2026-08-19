package com.sansen.yeweiyi.okGoUntils;

import com.lzy.okgo.model.Progress;

public interface RequestCallBack {
    public void Success(String message);
    public void Error(String failing);
    public void Progress(Progress progress);
}
