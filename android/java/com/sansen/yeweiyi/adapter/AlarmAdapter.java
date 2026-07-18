package com.sansen.yeweiyi.adapter;

import android.annotation.SuppressLint;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.db.Alarm;

import java.util.ArrayList;
import java.util.List;

public class AlarmAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

	private List<Alarm> alarms;
	private OutStockAdapter.OnItemClickListener mOnItemClickListener;

	public AlarmAdapter() {
		alarms=new ArrayList<Alarm>();
	}

	public void setData(List<Alarm> lists) {
		alarms.clear();
		if (lists!=null) {
			alarms.addAll(lists);
		}
	}

	public Object getItem(int position) {
		if (position < alarms.size()) {
			return alarms.get(position);
		}
		return null;
	}

	/**
	 * 添加数据列表项
	 * 
	 */
	public void addNewsItem(List<Alarm> newAlarm) {
		alarms.addAll(newAlarm);
	}

	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recode_alarm, parent, false);
		AlarmAdapter.ViewHolder holder = new AlarmAdapter.ViewHolder(v);
		holder.alarm_tv_id = (TextView) v
					.findViewById(R.id.alarm_tv_id);
			holder.alarm_tv_time = (TextView) v
					.findViewById(R.id.alarm_tv_time);
			holder.alarm_tv_alarminfo = (TextView) v
					.findViewById(R.id.alarm_tv_alarminfo);
			holder.alarm_tv_guanhao=v.findViewById(R.id.alarm_tv_guanhao);
			holder.alarm_tv_jiezhi=v.findViewById(R.id.alarm_tv_jiezhi);
		return holder;
	}

	@Override
	public void onBindViewHolder(RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") int position) {
		AlarmAdapter.ViewHolder viewHolder=(AlarmAdapter.ViewHolder)holder;
		if (alarms.size()>position) {
			Alarm alarm = alarms.get(position);
			viewHolder.alarm_tv_id.setText(String.valueOf(alarm.getId()));
			viewHolder.alarm_tv_time.setText(String.valueOf(alarm.getTime()));
			viewHolder.alarm_tv_alarminfo.setText(String.valueOf(alarm.getInfo()));
			viewHolder.alarm_tv_guanhao.setText(alarm.getGuanHao());
			viewHolder.alarm_tv_jiezhi.setText(alarm.getJieZhi());
			if (mOnItemClickListener != null) {
				viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						mOnItemClickListener.onClick(position);
					}
				});
				viewHolder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
					@Override
					public boolean onLongClick(View v) {
						mOnItemClickListener.onLongClick(position);
						return false;
					}
				});
			}
		}
	}

	@Override
	public int getItemCount() {
		return alarms.size();
	}

	public static class ViewHolder extends RecyclerView.ViewHolder{
		TextView alarm_tv_id;
		TextView alarm_tv_time;
		TextView alarm_tv_alarminfo;
		TextView alarm_tv_guanhao;
		TextView alarm_tv_jiezhi;

		public ViewHolder(View itemView) {
			super(itemView);
		}
	}

	public interface OnItemClickListener {
		void onClick(int position);

		void onLongClick(int position);
	}

	public void setOnItemClickListener(OutStockAdapter.OnItemClickListener onItemClickListener) {
		this.mOnItemClickListener = onItemClickListener;
	}
}
