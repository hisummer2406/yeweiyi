package com.sansen.yeweiyi.service;
interface IBackService{
	boolean sendAlarm(String message);
	boolean sendUnloadOil(String message);
	void reloadSocket();
	void stopSocket();
	boolean isConnect();
}