package com.sansen.yeweiyi.utils;

import java.io.UnsupportedEncodingException;
import java.util.List;

public class StringUtils {
	public static String getStringFList(List<String> list) {
		StringBuffer stringBuffer = new StringBuffer();
		for (String string : list) {
			stringBuffer.append(string);
		}

		return stringBuffer.toString();

	}

	/**
	 * 根据给的字符串 数字 按要求的长度前边补0
	 * 
	 * @param menuCode
	 *            原来的字符串
	 * @param len
	 *            要求的长度
	 * @return 补零后的结果
	 * 
	 */
	public static String getFillUpCode(String menuCode, int len) {
		String str = menuCode;
		int lenth = menuCode.length();
		for (int x = 0; x < (len - lenth); x++) {
			str = "0" + str;
		}
		return str.toUpperCase();
	}

	/*
	 * 根据给的字符串 数字 按要求的长度前边补0
	 * 
	 * @param menuCode 原来的字符串
	 * 
	 * @param len 要求的长度
	 * 
	 * @return 补零后的结果
	 */
	public static String getFillUpCode(int code, int len) {

		return getFillUpCode(code + "", len);
	}

	public static String getPosControlHex(String string) {

		return getFillUpCode(string, 2).toUpperCase();
	}

	public static String getSCode(int menuNum) {
		return getFillUpCode(String.valueOf(menuNum), 4);
	}

	public static String getSCode(int menuNum, int len) {
		return getFillUpCode(String.valueOf(menuNum), len);
	}

	/**
	 * @param menuCode
	 * @return
	 */
	public static String getSCode(String menuCode) {
		return getFillUpCode(menuCode, 4);
	}

	/**
	 * 
	 * 去除String的所有空格
	 * 
	 * @param code
	 * @return
	 */
	public static String getNoBlankString(String code) {
		return code.replaceAll(" ", "");
	}

	/**
	 * 转换hex为汉字
	 * 
	 * @param hexString
	 * @return
	 */
	public static String hexStringToStr(String hexString) {
		if (hexString == null || hexString.equals("")) {
			return null;
		}
		hexString = hexString.toUpperCase();
		int length = hexString.length() / 2;
		char[] hexChars = hexString.toCharArray();
		byte[] d = new byte[length];
		for (int i = 0; i < length; i++) {
			int pos = i * 2;
			d[i] = (byte) (charToByte(hexChars[pos]) << 4 | charToByte(hexChars[pos + 1]));
		}
		String string = null;
		try {
			string = new String(d, "gb2312");
		} catch (UnsupportedEncodingException e) {
			//e.printStackTrace();
		}

		return string;
	}

	private static byte charToByte(char c) {
		return (byte) "0123456789ABCDEF".indexOf(c);
	}

	public static String getOrderCodeHex(int menuCode) {

		return getFillUpCode(Integer.toHexString(menuCode), 2);
	}

	/**
	 * 获取四位的数据包的长度 2Hex
	 * 
	 * @param data
	 *            数据包长度
	 * @return
	 */
	public static String getStringLenHex(String data) {

		return getFillUpCode(Integer.toHexString(data.length() / 2), 4);
	}

	public static String getOriginHex(String data) {
		StringBuffer sb = new StringBuffer();
		char[] charArray = data.toCharArray();
		for (int i = 0; i < charArray.length; i++) {
			sb.append(charArray[i]);
			if (i % 2 != 0) {
				sb.append(" ");
			}
		}
		return sb.toString();
	}

	public static String str2ASC(String str) {// 字符串转换为ASCII码
		char[] chars = str.toCharArray(); // 把字符中转换为字符数组
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < chars.length; i++) {// 输出结果
			buffer.append((int) chars[i]);
		}
		return buffer.toString();
	}
}
