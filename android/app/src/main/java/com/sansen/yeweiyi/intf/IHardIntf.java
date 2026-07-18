package com.sansen.yeweiyi.intf;

import asynmsg.manage.ObservableManager;

public interface IHardIntf {

    void openOne(int relayNo,byte isAutoClose,ObservableManager.ReciveListener reciveListener);
    void openOne(int relayNo,int isAutoClose,ObservableManager.ReciveListener reciveListener);
    void closeOne(int relayNo,ObservableManager.ReciveListener reciveListener);

    void openAll(ObservableManager.ReciveListener reciveListener);
    void closeAll(ObservableManager.ReciveListener reciveListener);

    void queryVersion(ObservableManager.ReciveListener reciveListener);
    void queryStatus(ObservableManager.ReciveListener reciveListener);
}
