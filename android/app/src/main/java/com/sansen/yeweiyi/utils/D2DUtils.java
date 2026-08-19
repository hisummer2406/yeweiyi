package com.sansen.yeweiyi.utils;

public class D2DUtils {
	public static String getD2D(int id){
		int value= id*65536+65535-id;

		StringBuilder d2d=new StringBuilder(Integer.toHexString(value));
		
		while (d2d.length()<8) {
			d2d.insert(0, "0");
		}

		return d2d.toString().toUpperCase();
	
	}
	public static int getD2D(String d2d){
		int value= Integer.parseInt(d2d, 16);
		value= (value-65535)/(65536-1);

		return value;
	}
	
}
