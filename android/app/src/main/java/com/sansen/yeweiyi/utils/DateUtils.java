package com.sansen.yeweiyi.utils;

import android.util.Log;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DateUtils {

	private static final int WEEKDAYS = 7;

	private static String[] WEEK = { "星期日", "星期一", "星期二", "星期三", "星期四", "星期五",
			"星期六" };

	/** 日期变量转成对应的星期字符串 */
	public static String getDayOfWeek(Date date) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		int dayIndex = calendar.get(Calendar.DAY_OF_WEEK);
		if (dayIndex < 1 || dayIndex > WEEKDAYS) {
			return null;
		}

		return WEEK[dayIndex - 1];
	}
	//计算时间差

	public static long getStringToDate(String dateString, String pattern) {
		SimpleDateFormat dateFormat = new SimpleDateFormat(pattern);
		Date date = new Date();
		try{
			date = dateFormat.parse(dateString);
		} catch(ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return date.getTime();
	}
	public static int equitTime(String time1,String time2){
		String[]time01=time1.split(" ")[0].split("-");
		String[]time02=time2.split(" ")[0].split("-");

		if ((Integer.parseInt(time01[1])-Integer.parseInt(time02[1]))>1){
			return 30;
		}
		else if (Integer.parseInt(time01[1]) - Integer.parseInt(time02[1]) == 1){
			return 30+Integer.parseInt(time01[2])-Integer.parseInt(time02[2]);
		}
		else {
			return Integer.parseInt(time01[2])-Integer.parseInt(time02[2]);
		}

	}
	/** 得到当前日期是星期几 */
	public static String getCurrentDayOfWeek() {
		return getDayOfWeek(new Date(System.currentTimeMillis()));
	}

	/** yyyy-MM-dd mm:ss */
	public static Date getFormatTime(String time) throws ParseException {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd mm:ss");
		Date date = dateFormat.parse(time);
		return date;
	}
	public static double getAverageTime(int time,int hight){
		//double hour=time*24*60;
		double hour=0.01*24*60;
		return hight/hour;
	}
	/** yyyy-MM-dd */
	public static Date getFormatDay(String time) {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Date date = null;
		try {
			date = dateFormat.parse(time);
		} catch (ParseException e) {
		    Log.e("DateUtils", "getFormatDay 执行失败", e);
		}
		return date;
	}

	/** yyyy-MM-dd HH:mm */
	public static String getTimeString(long time) {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(time));
	}

	/** 获取当前的day信息yyyy-MM-dd HH:mm */
	public static String getYYYMMddHHmmss() {
		//DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		Date date = null;
		date = new Date(System.currentTimeMillis());

		return dateFormat.format(date);
	}

	/** 获取当前的day信息 yyyyMMdd */

	/** get server date String */

	/** 获取两个时间的间隔天数 保证第二个时间一定大于第一个时间 */
	public static int getBetweenDays(String t1, String t2) {
		int betweenDays = 0;
		Date d1 = getFormatDay(t1);
		Date d2 = getFormatDay(t2);
		Calendar c1 = Calendar.getInstance();
		Calendar c2 = Calendar.getInstance();
		c1.setTime(d1);
		c2.setTime(d2);
		// 保证第二个时间一定大于第一个时间
		if (c1.after(c2)) {
			c1 = c2;
			c2.setTime(d1);
		}
		int betweenYears = c2.get(Calendar.YEAR) - c1.get(Calendar.YEAR);
		betweenDays = c2.get(Calendar.DAY_OF_YEAR)
				- c1.get(Calendar.DAY_OF_YEAR);
		for (int i = 0; i < betweenYears; i++) {
			c1.set(Calendar.YEAR, (c1.get(Calendar.YEAR) + 1));
			betweenDays += c1.getMaximum(Calendar.DAY_OF_YEAR);
		}
		return betweenDays;
	}

	/** 获取两个时间的间隔天数 保证第二个时间一定大于第一个时间 */

	/** 获取当前指定天数之后的日期 */
	public static String getLeftTimeByDays(Long serverTime, float days) {
		String leftTime = null;
		Date date = new Date((long) (serverTime + days * 24 * 60 * 60 * 1000));
		java.text.DateFormat format1 = new java.text.SimpleDateFormat(
				"yyyy-MM-dd");
		leftTime = format1.format(date);
		return leftTime;
	}

	public static void setCurTime(String time) {
		Calendar c = Calendar.getInstance();
		// c.set(Calendar.YEAR, year); // 年
		// c.set(Calendar.MONTH, month); // 月
		// c.set(Calendar.DAY_OF_MONTH, day); // 日
		// c.set(Calendar.HOUR_OF_DAY, hourOfDay); // 时
		// c.set(Calendar.MINUTE, minute); // 分
		// c.set(Calendar.SECOND, second); // 秒
		// long when = c.getTimeInMillis();
		// SystemClock.setCurrentTimeMillis(when);
	}

	/**
	 * 获取指定网站的日期时间
	 * 
	 * @param webUrl
	 * @return 时间 long
	 */
	private static String getWebsiteDatetime(String webUrl) {
		try {
			URL url = new URL(webUrl);// 取得资源对象
			URLConnection uc = url.openConnection();// 生成连接对象
			uc.connect();// 发出连接

			long ld = uc.getDate();// 读取网站日期时间
			System.out.println("网站时间" + ld);
			Date date = new Date(ld);// 转换为标准时间对象
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
					Locale.CHINA);// 输出北京时间
			return sdf.format(date);
		} catch (MalformedURLException e) {
		    Log.e("DateUtils", "getWebsiteDatetime 执行失败", e);
		} catch (IOException e) {
		    Log.e("DateUtils", "getWebsiteDatetime 执行失败", e);
		}
		return null;
	}
	
	/** 获取当前的day信息yyyy-MM-dd HH:mm */
	public static String getYYYMMddHHmmSSS() {
		DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");
		Date date = null;
		date = new Date(System.currentTimeMillis());

		return dateFormat.format(date);
	}

	/** 获取当前的day信息yyyy-MM-dd HH:mm */
	public static String getYYYMMddHHmmSSS2() {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
		Date date = null;
		date = new Date(System.currentTimeMillis());

		return dateFormat.format(date);
	}
	
	/** 获取当前的day信息yyyy-MM-dd HH:mm */
	public static String getYYYMMddHHmm() {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd   HH:mm");
		Date date = null;
		date = new Date(System.currentTimeMillis());

		return dateFormat.format(date);
	}
	/** 获取当前的day信息yyyy-MM-dd HH:mm */
	public static String getYYYMMdd() {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Date date = null;
		date = new Date(System.currentTimeMillis());

		return dateFormat.format(date);
	}

	public static String getYYYMMddHHmm2() {
		DateFormat dateFormat = new SimpleDateFormat("yyMMddHHmm");
		Date date = null;
		date = new Date(System.currentTimeMillis());

		return dateFormat.format(date);
	}
}
