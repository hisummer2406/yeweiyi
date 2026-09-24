package com.sansen.yeweiyi.adapter;

import android.content.Context;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.manager.AlarmManager;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.GoodProgressView;

public class GasExplorationAdapter extends BaseAdapter {

    private SparseArray<Integer> maps;

    private Context ct;

    public GasExplorationAdapter(Context ct) {
        this.ct = ct;
    }

    public void setData(SparseArray<Integer> maps) {
        this.maps = maps;

    }

    // [不可达-2026-09-21] MainFragment.gasExplor 字段从来没被真正赋值过（见 MainFragment.java 里的
    // 注释），传进来的 maps 恒为 null，这里恒返回 0（西语翻译覆盖率核查时发现）。Android 框架在
    // getCount()==0 时不会调用下面的 getView()，item_gas_exploration.xml 的字符串资源不可达。
    @Override
    public int getCount() {
        if (maps == null) {
            return 0;
        }
        return maps.size();
    }

    @Override
    public Object getItem(int position) {
        return maps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }


    private ViewHolder holder;

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(ct).inflate(
                    R.layout.item_gas_exploration, null);
            holder = new ViewHolder();
            holder.id = (TextView) convertView
                    .findViewById(R.id.good_progress_id);
            holder.progressView = (GoodProgressView) convertView
                    .findViewById(R.id.good_progress_view);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        if (maps.get(position) > MyApplication.getInstance().getmOilcanDataManager().getOilExploration().get(position).getValue()) {
            OilMessage oilMessage = new OilMessage();
            oilMessage.setOilCanId(position+1);
            oilMessage.setType(OilMessage.OIl_EXPLORATION);
            AlarmManager.getInstance().addAlarmState(oilMessage);
        }
        //Log.i("油气", "getView: "+position);
        //Log.i("油气", "getView: "+maps.get(position));
        holder.progressView.setProgressValue(maps.get(position));

        // [已屏蔽-2026-09-23] concentration 已删除（getView() 不可达，见上方 getCount() 处注释）。
        // holder.id.setText((position+1) + SystemUtils.getRsStr(R.string.concentration));
        holder.progressView.postInvalidate();
        return convertView;
    }

    class ViewHolder {
        TextView id;
        GoodProgressView progressView;
    }

}
