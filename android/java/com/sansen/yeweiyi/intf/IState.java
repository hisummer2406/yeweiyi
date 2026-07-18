package com.sansen.yeweiyi.intf;

import asynmsg.manage.ObservableManager;

public interface IState {

    void success(boolean state);
    void fail(String msg);
}
