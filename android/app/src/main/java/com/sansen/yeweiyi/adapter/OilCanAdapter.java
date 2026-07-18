package com.sansen.yeweiyi.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.view.MinOilCanView;

public class OilCanAdapter extends  RecyclerView.Adapter<RecyclerView.ViewHolder> {

	private static final String TAG ="OilCanAdapter" ;
	SparseArray<OilCan> maps;
	Context context;
	private OnItemClickListener mOnItemClickListener;

	public OilCanAdapter(Context context) {
		this.context = context;
	}

	public void setData(SparseArray<OilCan> maps) {
		this.maps = maps;
	}

	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View v= LayoutInflater.from(parent.getContext()).inflate(R.layout.item_oil,parent,false);
		OilCanAdapter.ViewHolder viewHolder = new OilCanAdapter.ViewHolder(v);
		viewHolder.min_oil= (MinOilCanView) v.findViewById(R.id.min_oil);
		return viewHolder;
	}

	@Override
	public void onBindViewHolder(RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") final int position) {
		OilCanAdapter.ViewHolder viewHolder = (OilCanAdapter.ViewHolder) holder;
//		for (int i = 0; i < maps.size(); i++) {
//			int key = maps.keyAt(i);
//			Log.e("TAG",key+"");
//			// 处理每个 key 和 value
//		}
			viewHolder.min_oil.setOilCan(maps.get(maps.keyAt(position)));
			viewHolder.min_oil.invalidate();
//		Log.i("刷新中的",position+"  "+maps.get(position+1));
			////Log.i(TAG, "onBindViewHolder: 刷新页面");
			if (mOnItemClickListener != null) {
				viewHolder.min_oil.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						mOnItemClickListener.onClick(position);
					}
				});
			}



	}

	@Override
	public long getItemId(int arg0) {
		return arg0;
	}

	@Override
	public int getItemCount() {
		return maps.size();
	}

	public static class ViewHolder extends RecyclerView.ViewHolder{
		MinOilCanView min_oil;

		public ViewHolder(View itemView) {
			super(itemView);
		}
	}
	public void setOnItemClickListener(OilCanAdapter.OnItemClickListener onItemClickListener) {
		this.mOnItemClickListener = onItemClickListener;
		//Log.i(TAG, "setOnItemClickListener: ");

	}

	public interface OnItemClickListener {
		void onClick(int position);

		void onLongClick(int position);
	}
}