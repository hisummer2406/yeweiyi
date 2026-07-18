package com.sansen.yeweiyi.adapter;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;

/**
 * Created by PC on 2018-06-25.
 */

public class MinOilcanItemAdapter  extends RecyclerView.Adapter {

    String [] arry;
    OnItemClickListener onItemClickListener;
    public MinOilcanItemAdapter(String [] arry){
        this.arry = arry;
    }
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view= LayoutInflater.from(parent.getContext()).inflate(R.layout.item_select_minoilcan,parent,false);
        MinOilcanItemAdapter.ViewHolder viewHolder = new MinOilcanItemAdapter.ViewHolder(view);
        viewHolder.select_tv_content= (TextView) view.findViewById(R.id.select_tv_content);

        return viewHolder;
    }

    public void setOnViewItemClickListener(OnItemClickListener onItemClickListener){
        this.onItemClickListener = onItemClickListener;
    }

    public void setData(String[] tempData) {
        this.arry =tempData;
        notifyDataSetChanged();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView select_tv_content;

        public ViewHolder(View itemView) {
            super(itemView);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, final int position) {
        final MinOilcanItemAdapter.ViewHolder viewHolder  = (MinOilcanItemAdapter.ViewHolder)holder;
        viewHolder.select_tv_content.setText(arry[position]);
        viewHolder.select_tv_content.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onItemClickListener.onItemClick(view,position);
            }
        });
        viewHolder.select_tv_content.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                onItemClickListener.onItemLongClick(view,position);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return arry.length;
    }

    public static interface OnItemClickListener {
        void onItemClick(View v,int position);
        void onItemLongClick(View v,int position);
    }

    public String getItemObj(int position){
          return arry[position];
    }
}
