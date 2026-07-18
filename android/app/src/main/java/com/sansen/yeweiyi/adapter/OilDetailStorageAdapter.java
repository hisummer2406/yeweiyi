package com.sansen.yeweiyi.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.bean.Oil;

import java.util.List;

public class OilDetailStorageAdapter extends BaseAdapter {
	private Context ct;

	public void setData(List<Oil> oils) {
		this.lists = oils;
	}

	private List<Oil> lists;

	public OilDetailStorageAdapter(Context ct) {
		this.ct = ct;
	}

	@Override
	public int getCount() {
		// TODO Auto-generated method stub
		return lists.size();
	}

	@Override
	public Object getItem(int position) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public long getItemId(int position) {
		// TODO Auto-generated method stub
		return position;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {

		ViewHolder holder = null;

		if (convertView == null) {
			holder = new ViewHolder();
			LayoutInflater inflater = LayoutInflater.from(ct);
			convertView = inflater.inflate(R.layout.item_recode_detailstorage,
					null);
			holder.detailstorage_tv_id = (TextView) convertView
					.findViewById(R.id.detailstorage_tv_id);
			holder.detailstorage_tv_oilheight = (TextView) convertView
					.findViewById(R.id.detailstorage_tv_oilheight);
			holder.detailstorage_tv_waterheight = (TextView) convertView
					.findViewById(R.id.detailstorage_tv_waterheight);
			holder.detailstorage_tv_oilvolume = (TextView) convertView
					.findViewById(R.id.detailstorage_tv_oilvolume);
			holder.detailstorage_tv_oilweight = (TextView) convertView
					.findViewById(R.id.detailstorage_tv_oilweight);
			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}
		Oil oil = lists.get(position);
		holder.detailstorage_tv_id.setText(oil.getOilId() + "");
		holder.detailstorage_tv_oilheight.setText(oil.getOilHeight() + "");
		holder.detailstorage_tv_waterheight.setText(oil.getWaterHeight() + "");
		holder.detailstorage_tv_oilvolume.setText(oil.getOilVolum() + "");
		holder.detailstorage_tv_oilweight.setText(oil.getOilWeigh() + "");

		return convertView;
	}

	class ViewHolder {
		TextView detailstorage_tv_id;
		TextView detailstorage_tv_oilheight;
		TextView detailstorage_tv_waterheight;
		TextView detailstorage_tv_oilvolume;
		TextView detailstorage_tv_oilweight;
	}

}
