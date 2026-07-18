package com.sansen.yeweiyi.utils;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Arrays;
import java.util.List;

/*
 * 封装的GSON解析工具类，提供泛型参数
 */
public class GsonUtil {
    //将Json数据解析成相应的映射对象
    public static <T> T parseJsonWithGson(String jsonData, Class<T> type) {
        Gson gson = new Gson();
        T result = gson.fromJson(jsonData, type);
        return result;
    }


	public static String toJsonStr(Object obj){
		return new Gson().toJson(obj);
	}

    /**
	 * List to String by Gson
	 * 
	 * @param <T>
	 * @param oils
	 * @return oilGsonString
	 */
    public static <T> List<T> stringToArray(String s, Class<T[]> clazz) {
		T[] arr = new Gson().fromJson(s, clazz);
		return Arrays.asList(arr);
	}
    /**
	 * List to String by Gson
	 * 
	 * @param <T>
	 * @param oils
	 * @return oilGsonString
	 */
	public static <T> String getListToStringByGson(List<T> object) {
		GsonBuilder builder = new GsonBuilder();
		return builder.create().toJson(object);
	}
	public static <T> String parseObjectWithGson(Object object, Class<T> type) {
        Gson gson = new Gson();
        String result = gson.toJson(object, type);
        return result;
    }
}
