package com.sansen.yeweiyi.adapter;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.bean.GuanBiaoData;

import java.util.ArrayList;
import java.util.List;

public class GuanBiaoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

	private List<GuanBiaoData> guanBiaoDatas;
	

	public GuanBiaoAdapter() {
		guanBiaoDatas=new ArrayList<GuanBiaoData>();
	}

	public void setData(List<GuanBiaoData> lists) {
		guanBiaoDatas.clear();
		if (lists!=null) {
			guanBiaoDatas.addAll(lists);
		}
	}


	/**
	 * 添加数据列表项
	 * 
	 */
	public void addNewsItem(List<GuanBiaoData> newAlarm) {
		guanBiaoDatas.addAll(newAlarm);
	}

	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recode_guan_biao, parent, false);
		GuanBiaoAdapter.ViewHolder holder = new GuanBiaoAdapter.ViewHolder(v);
		holder.alarm_tv_id = (TextView) v
					.findViewById(R.id.alarm_tv_guanhao);
			holder.biao_tv_hight = (TextView) v
					.findViewById(R.id.biao_tv_hight);
			holder.biao_tv_volum = (TextView) v
					.findViewById(R.id.biao_tv_volum);
		return holder;
	}

	@Override
	public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
		GuanBiaoAdapter.ViewHolder viewHolder=(GuanBiaoAdapter.ViewHolder)holder;
		if (guanBiaoDatas.size()>position) {
			GuanBiaoData alarm = guanBiaoDatas.get(position);
			viewHolder.alarm_tv_id.setText(String.valueOf(alarm.getId()));
			viewHolder.biao_tv_hight.setText(String.valueOf(alarm.getHight()));
			viewHolder.biao_tv_volum.setText(String.valueOf(alarm.getRongJi()));
		}
	}

	@Override
	public int getItemCount() {
		return guanBiaoDatas.size();
	}

	public static class ViewHolder extends RecyclerView.ViewHolder{
		TextView alarm_tv_id;
		TextView biao_tv_hight;
		TextView biao_tv_volum;

		public ViewHolder(View itemView) {
			super(itemView);
		}
	}
}
