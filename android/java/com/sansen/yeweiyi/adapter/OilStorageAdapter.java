package com.sansen.yeweiyi.adapter;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.db.OilSaveInfo;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class OilStorageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private Context ct;
    private List<OilSaveInfo> list;
    private OnItemClickListener mOnItemClickListener;

    public OilStorageAdapter() {
        list = new ArrayList<OilSaveInfo>();
    }

    public void setData(List<OilSaveInfo> lists) {
        list.clear();
        list.addAll(lists);
    }

    /**
     * 添加数据列表项
     */
    public void addNewsItem(List<OilSaveInfo> newAlarm) {
        list.addAll(newAlarm);
    }


    public String getItem(int position) {

        return list.get(position).getOilInfoGosn();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recode_storage, parent, false);

        OilStorageAdapter.ViewHolder holder = new OilStorageAdapter.ViewHolder(v);
        holder.storage_tv_id = (TextView) v
                .findViewById(R.id.storage_tv_id);
        holder.storage_tv_time = (TextView) v
                .findViewById(R.id.storage_tv_time);
        holder.storage_tv_remark = (TextView) v
                .findViewById(R.id.storage_tv_remark);
        return holder;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, final int position) {
        if (position < list.size()) {
            OilStorageAdapter.ViewHolder viewHolder = (ViewHolder) holder;
            OilSaveInfo oilSaveInfo = (OilSaveInfo) list.get(position);
            viewHolder.storage_tv_id.setText(String.valueOf(oilSaveInfo.getId()));
            viewHolder.storage_tv_time.setText(String.valueOf(oilSaveInfo.getTime()));
            String remark = oilSaveInfo.getRemark();
            if (StringUtils.isNotEmpty(remark)) {
                viewHolder.storage_tv_remark.setText(remark);
            } else {
                viewHolder.storage_tv_remark.setText(oilSaveInfo.getOilInfoGosn());
            }

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
        return list.size();
    }
//
//	@Override
//	public long getItemId(int position) {
//		// TODO Auto-generated method stub
//		return 0;
//	}
//
//	@Override
//	public View getView(int position, View convertView, ViewGroup parent) {
//
//		ViewHolder holder = null;
//
//		if (convertView == null) {
//			holder = new ViewHolder();
//			LayoutInflater inflater = LayoutInflater.from(ct);
//			convertView = inflater.inflate(R.layout.item_recode_storage, null);
//			holder.storage_tv_id = (TextView) convertView
//					.findViewById(R.id.storage_tv_id);
//			holder.storage_tv_time = (TextView) convertView
//					.findViewById(R.id.storage_tv_time);
//			holder.storage_tv_remark = (TextView) convertView
//					.findViewById(R.id.storage_tv_remark);
//			convertView.setTag(holder);
//		} else {
//			holder = (ViewHolder) convertView.getTag();
//		}
//		if (position<list.size()) {
//			OilSaveInfo oilSaveInfo = (OilSaveInfo) list.get(position);
//			holder.storage_tv_id.setText(oilSaveInfo.getId() + "");
//			holder.storage_tv_time.setText(oilSaveInfo.getTime() + "");
//			String remark = oilSaveInfo.getRemark();
//			if (StringUtils.isNotEmpty(remark)) {
//				holder.storage_tv_remark.setText(remark);
//			} else {
//				holder.storage_tv_remark.setText(oilSaveInfo.getOilInfoGosn());
//			}
//		}
//
//
//
//		return convertView;
//	}

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView storage_tv_id;
        TextView storage_tv_time;
        TextView storage_tv_remark;

        public ViewHolder(View itemView) {
            super(itemView);
        }
    }

    public void setOnItemClickListener(OilStorageAdapter.OnItemClickListener onItemClickListener) {
        this.mOnItemClickListener = onItemClickListener;
    }

    public interface OnItemClickListener {
        void onClick(int position);

        void onLongClick(int position);
    }
}
