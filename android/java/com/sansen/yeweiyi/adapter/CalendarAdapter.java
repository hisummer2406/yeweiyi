package com.sansen.yeweiyi.adapter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

public class CalendarAdapter extends android.widget.BaseAdapter {
    private final Context mContext;
    private final List<String> dayList;
    private int selectedIndex = -1;
    private int todayYear;
    private int todayMonth;
    private int todayDay;
    public Calendar currentShowMonth;
    public CalendarAdapter(Context context, List<String> days) {
        mContext = context;
        dayList = days;
    }
    public CalendarAdapter(Context context, List<String> days, int ty, int tm, int td) {
        mContext = context;
        dayList = days;
        todayYear = ty;
        todayMonth = tm;
        todayDay = td;
    }
    @Override
    public int getCount() {
        return dayList.size();
    }

    @Override
    public Object getItem(int position) {
        return dayList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tvDay = new TextView(mContext);
        tvDay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        tvDay.setGravity(android.view.Gravity.CENTER);
        tvDay.setPadding(0, 4, 0, 4);
        tvDay.setTextSize(18);
        String dayText = dayList.get(position);
        tvDay.setText(dayText);

        // 标记是否是未来日期（超过今天）
        boolean isFutureDay = false;
        // 拿到当前格子对应的年月日
        int gridYear = currentShowMonth.get(Calendar.YEAR);
        int gridMonth = currentShowMonth.get(Calendar.MONTH);
        int gridDay = "".equals(dayText) ? -1 : Integer.parseInt(dayText);

        if(gridDay != -1){
            // 对比判断是否大于今天
            if(gridYear > todayYear){
                isFutureDay = true;
            }else if(gridYear == todayYear && gridMonth > todayMonth){
                isFutureDay = true;
            }else if(gridYear == todayYear && gridMonth == todayMonth && gridDay > todayDay){
                isFutureDay = true;
            }
        }

        // 样式区分
        if ("".equals(dayText)) {
            // 跨月空白占位
            tvDay.setTextColor(0xFFCCCCCC);
            tvDay.setEnabled(false);
        }else if(isFutureDay){
            // 未来日期：灰色文字、禁止点击
            tvDay.setTextColor(0xFFBBBBBB);
            tvDay.setBackground(null);
            tvDay.setEnabled(false);
        }else if (position == selectedIndex) {
            // 选中日期高亮
            tvDay.setBackgroundColor(0xFFCCE0FF);
            tvDay.setTextColor(0xFF0055BB);
            tvDay.setEnabled(true);
        }else{
            // 正常可点击日期
            tvDay.setBackground(null);
            tvDay.setTextColor(0xFF222222);
            tvDay.setEnabled(true);
        }
        return tvDay;
    }

    // 设置选中日期下标
    public void setSelectedIndex(int pos) {
        selectedIndex = pos;
        notifyDataSetChanged();
    }
}

