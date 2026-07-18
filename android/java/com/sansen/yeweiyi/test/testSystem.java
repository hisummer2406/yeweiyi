package com.sansen.yeweiyi.test;

import android.test.AndroidTestCase;

import com.sansen.yeweiyi.utils.SystemUtils;

public class testSystem extends AndroidTestCase {
	public void testMemory() {
		SystemUtils.showMemory();
		System.out.println("当前可用内存" + SystemUtils.getAvailMemory(getContext())
				+ "总共内存" + SystemUtils.getTotalMemory(getContext()));
	}
}
