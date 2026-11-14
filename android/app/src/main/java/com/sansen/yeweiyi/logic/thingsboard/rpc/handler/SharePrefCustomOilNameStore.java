package com.sansen.yeweiyi.logic.thingsboard.rpc.handler;

import android.content.Context;
import android.content.SharedPreferences;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.ArrayList;
import java.util.List;

/** 写入 {@link com.sansen.yeweiyi.utils.DataUtils#getOilType()} 读取的两份名称表，重启后保留。 */
public final class SharePrefCustomOilNameStore implements CustomOilNameStore {

    private static final int FIRST_INDEX = 32;
    private static final int SIZE = 8;
    private static final String EMPTY = "[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]";

    private final Context context;

    public SharePrefCustomOilNameStore(Context context) {
        this.context = context;
    }

    @Override
    public synchronized String[] save(int index, String zh, String en) {
        int position = index - FIRST_INDEX;
        List<String> cn = read(SharePrefUtil.CN_OIlTYPE);
        List<String> english = read(SharePrefUtil.EN_OIlTYPE);
        String[] previous = {cn.get(position), english.get(position)};
        cn.set(position, zh);
        english.set(position, en);
        // commit 而不是 apply：回成功前必须已经落盘
        SharedPreferences.Editor editor = SharePrefUtil.preferences(context).edit();
        editor.putString(SharePrefUtil.CN_OIlTYPE, JSONObject.toJSONString(cn));
        editor.putString(SharePrefUtil.EN_OIlTYPE, JSONObject.toJSONString(english));
        if (!editor.commit()) {
            throw new IllegalStateException("介质名称写入失败");
        }
        return previous;
    }

    private List<String> read(String key) {
        List<String> stored = JSONObject.parseArray(SharePrefUtil.getString(context, key, EMPTY), String.class);
        List<String> names = new ArrayList<>(stored == null ? new ArrayList<>() : stored);
        while (names.size() < SIZE) {
            names.add("");
        }
        return names;
    }
}
