package com.sansen.yeweiyi.test;

import android.test.AndroidTestCase;

import com.sansen.yeweiyi.utils.NetworkState;

public class testNet extends AndroidTestCase {
	public void test(){
		System.out.println("net connection  "+NetworkState.checkNetworkConnection(getContext()));
		System.out.println("net isWifi"+NetworkState.isWifi(getContext()));
		System.out.println("net isMobile"+NetworkState.isMobileConnected(getContext()));
	}
}
