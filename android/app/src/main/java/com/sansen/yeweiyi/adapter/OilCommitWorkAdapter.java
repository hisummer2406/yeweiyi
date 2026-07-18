package com.sansen.yeweiyi.adapter;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.db.CommitWork;

import java.util.ArrayList;
import java.util.List;

public class OilCommitWorkAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private List<CommitWork> list;
    private OilCommitWorkAdapter.OnItemClickListener mOnItemClickListener;

    public OilCommitWorkAdapter() {
        list = new ArrayList<CommitWork>();
    }

    public void setData(List<CommitWork> lists) {
        list.clear();
        list.addAll(lists);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recode_commitwork, parent, false);
        OilCommitWorkAdapter.ViewHolder holder = new OilCommitWorkAdapter.ViewHolder(v);
        holder.commitwork_tv_id = (TextView) v
                .findViewById(R.id.commitwork_tv_id);
        holder.commitwork_tv_time = (TextView) v
                .findViewById(R.id.commitwork_tv_time);
        holder.commitwork_tv_userId = (TextView) v
                .findViewById(R.id.commitwork_tv_userId);
        return holder;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, final int position) {
        OilCommitWorkAdapter.ViewHolder viewHolder = (OilCommitWorkAdapter.ViewHolder) holder;
        CommitWork commitWork = list.get(position);
        if (commitWork != null) {
            viewHolder.commitwork_tv_id.setText(String.valueOf(commitWork.getId()));
            viewHolder.commitwork_tv_time.setText(String.valueOf(commitWork.getTime()));
            String userId = commitWork.getEndTime();
            viewHolder.commitwork_tv_userId.setText(userId);

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
                        return true;
                    }
                });
            }
        }
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    public String getItem(int position) {
        if (position < list.size()) {
            return list.get(position).getInfo();
        }
        return null;
    }
    public Object getItems(int position) {
        if (position < list.size()) {
            return list.get(position);
        }
        return null;
    }
    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView commitwork_tv_id;
        TextView commitwork_tv_time;
        TextView commitwork_tv_userId;

        public ViewHolder(View itemView) {
            super(itemView);
        }
    }

    /**
     * 添加数据列表项
     */
    public void addNewsItem(List<CommitWork> newAlarm) {
        list.addAll(newAlarm);
    }

    public void setOnItemClickListener(OilCommitWorkAdapter.OnItemClickListener onItemClickListener) {
        this.mOnItemClickListener = onItemClickListener;
    }

    public interface OnItemClickListener {
        void onClick(int position);

        void onLongClick(int position);
    }
}
