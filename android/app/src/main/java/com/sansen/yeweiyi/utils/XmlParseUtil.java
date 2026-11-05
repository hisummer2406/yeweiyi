package com.sansen.yeweiyi.utils;

import android.util.Log;
import android.util.Xml;

import com.sansen.yeweiyi.bean.BaseInfo;
import com.sansen.yeweiyi.bean.UpdataBean;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

public class XmlParseUtil {

	/**
	 * 解析xml
	 * 
	 * @param inputStream
	 * @return
	 */
	public static UpdataBean getUpdataInfo(InputStream inputStream) {
		// 拿到解析器 初始化 拿到事件类型 如果是制定内容就放到bean中
		XmlPullParser parser = Xml.newPullParser();
		UpdataBean bean = new UpdataBean();
		try {
			parser.setInput(inputStream, "UTF-8");
			int type = parser.getEventType();
			while (type != XmlPullParser.END_DOCUMENT) {
				switch (type) {
				case XmlPullParser.START_TAG:
					if ("version".equals(parser.getName())) {
						bean.setVersiion(parser.nextText());
					} else if ("description".equals(parser.getName())) {
						bean.setDes(parser.nextText());
					} else if ("apkurl".equals(parser.getName())) {
						bean.setApkUrl(parser.nextText());
					}
					break;
				}
				type = parser.next();
			}
		} catch (Exception e) {
		    Log.e("XmlParseUtil", "getUpdataInfo 执行失败", e);
		}
		return bean;
	}

	/**
	 * 解析baseInfo xml
	 * 
	 * @param inputStream
	 * @return
	 */
	public static BaseInfo getBaseInfo(InputStream inputStream) {
		// 拿到解析器 初始化 拿到事件类型 如果是制定内容就放到bean中
		Map<String, String> infoMap = new TreeMap<String, String>();
		XmlPullParser parser = Xml.newPullParser();
		BaseInfo bean = new BaseInfo();
		try {
			parser.setInput(inputStream, "UTF-8");
			int type = parser.getEventType();
			while (type != XmlPullParser.END_DOCUMENT) {
				switch (type) {
				case XmlPullParser.START_TAG:

					if ("companyaddress".equals(parser.getName())) {
						bean.setCompanyaddress(parser.nextText());
					} else if ("telephonenumber".equals(parser.getName())) {
						bean.setTelephonenumber(parser.nextText());
					} else if ("androidphone".equals(parser.getName())) {
						bean.setAndroidphone(parser.nextText());
					}
					break;
				}
				type = parser.next();
			}
		} catch (Exception e) {
		    Log.e("XmlParseUtil", "getBaseInfo 执行失败", e);
		}
		return bean;
	}

	/**
	 * 将String转换成InputStream
	 * 
	 * @param in
	 * @return
	 * @throws Exception
	 */
	public static InputStream StringTOInputStream(String in) throws Exception {

		ByteArrayInputStream is = new ByteArrayInputStream(in.getBytes("UTF-8"));
		return is;
	}

}
