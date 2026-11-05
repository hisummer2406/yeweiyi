package com.sansen.yeweiyi.utils;


import serialport.help.SerialPortHelper;

public class HexUtils {

	public static String ResolveInt(int value, int length) {
		String result = value + "";

		result = Integer.toHexString(Integer.parseInt(result));

		while (result.length() < length) {
			result = "0" + result;
		}

		return result.toUpperCase();
	}

	// 编码器
	public static String hexEnCoder(String type, String content) {
		StringBuffer sb = new StringBuffer();
		if (type.length() % 2 == 0 && content.length() % 2 == 0) {
			String dataLength = ResolveInt((content.length() + type.length() + 4) / 2, 4);
			sb.append(type);// 命令字
			sb.append(dataLength);// 数据长度
			sb.append(content);// 数据
			String crc = initCrC(sb.toString()).toUpperCase();
			sb.append(crc);// CRC
			sb.append("FFFFFF");
			sb.insert(0, "FAFAFA");
//			Toast.makeText(MyApplication.getInstance(), sb.toString(), Toast.LENGTH_LONG).show();
		}
		return sb.toString();
	}

	private static String initCrC(String content) {
		byte[] crcData = DataUtils.HexString2Bytes(content.toString());
		CRCheck crCheck = CRCheck.getInstance();
		byte crc = crCheck.calculate_crc8_maxim(crcData, crcData.length);
		return Integer.toHexString(crc & 0xFF).length()>1? Integer.toHexString(crc & 0xFF):0+ Integer.toHexString(crc & 0xFF);
	}

}
