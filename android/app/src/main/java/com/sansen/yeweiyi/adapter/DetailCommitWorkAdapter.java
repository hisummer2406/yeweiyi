package com.sansen.yeweiyi.adapter;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.utils.DataUtils;

import java.util.List;

public class DetailCommitWorkAdapter extends BaseAdapter {
	private Context ct;

	public void setData(List<OilWork> oilWorks) {
		this.oilWorks = oilWorks;
	}

	private List<OilWork> oilWorks;
	private double density=1;

	public DetailCommitWorkAdapter(Context ct) {
		this.ct = ct;
		List<OilCanSet> os = DBDataManager.getInstance().findAllObject(OilCanSet.class);
		if (os != null && os.size() > 0) {
			density = os.get(0).getDensity();
			Log.e("TAG","密度"+density);
		}
	}

	@Override
	public int getCount() {
		// TODO Auto-generated method stub
		return oilWorks.size();
	}

	@Override
	public OilWork getItem(int position) {
		return oilWorks.get(position);
	}

	@Override
	public long getItemId(int position) {
		// TODO Auto-generated method stub
		return position;
	}

	String jieZhi;
	@Override
	public View getView(int position, View convertView, ViewGroup parent) {

		ViewHolder holder = null;

		if (convertView == null) {
			holder = new ViewHolder();
			LayoutInflater inflater = LayoutInflater.from(ct);
			convertView = inflater.inflate(R.layout.item_recode_detailcommitwork,
					null);
			holder.detailcommmitwork_tv_id = (TextView) convertView
					.findViewById(R.id.detailcommitwork_tv_id);
			holder.detailcommitwork_tv_beginheight = (TextView) convertView
					.findViewById(R.id.detailcommitwork_tv_beginheight);
			holder.detailcommitwork_tv_endheight = (TextView) convertView
					.findViewById(R.id.detailcommitwork_tv_endheight);
			holder.detailcommitwork_tv_shellvolume = (TextView) convertView
					.findViewById(R.id.detailcommitwork_tv_shellvolume);
			holder.detailcommitwork_tv_shellweight = (TextView) convertView
					.findViewById(R.id.detailcommitwork_tv_shellweight);
			holder.detailcommitwork_tv_jiezhi=(TextView)convertView.findViewById(R.id.detailcommitwork_tv_jiezhi);
			holder.detailcommitwork_tv_beginvolume=(TextView)convertView.findViewById(R.id.detailcommitwork_tv_beginvolume);
			holder.detailcommitwork_tv_endvolume=(TextView)convertView.findViewById(R.id.detailcommitwork_tv_endvolume);
			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}
		OilWork oil = oilWorks.get(position);
		holder.detailcommmitwork_tv_id.setText(oil.getOilId() + "");
		holder.detailcommitwork_tv_beginheight.setText(DataUtils.doubleFoment(oil.getBeginHeight()) + "");
		holder.detailcommitwork_tv_endheight.setText(DataUtils.doubleFoment(oil.getEndHeight()) + "");
		holder.detailcommitwork_tv_shellvolume.setText(DataUtils.doubleFoment(oil.getShellVolume()) + "");
		holder.detailcommitwork_tv_shellweight.setText(DataUtils.doubleFoment(oil.getShellWeight()) + "");

		if (OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oil.getOilId())==null){
			jieZhi=Const.getOilType(0);
		}
		else {
			jieZhi=Const.getOilType(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oil.getOilId()).getOilType());
		}
		holder.detailcommitwork_tv_jiezhi.setText(jieZhi);
		holder.detailcommitwork_tv_beginvolume.setText(DataUtils.doubleFoment(oil.getBeginVolume())+"");
		holder.detailcommitwork_tv_endvolume.setText(DataUtils.doubleFoment(oil.getEndVolume())+"");
		return convertView;
	}

	class ViewHolder {
		TextView detailcommmitwork_tv_id;
		TextView detailcommitwork_tv_jiezhi;
		TextView detailcommitwork_tv_beginheight;
		TextView detailcommitwork_tv_beginvolume;
		TextView detailcommitwork_tv_endheight;
		TextView detailcommitwork_tv_endvolume;
		TextView detailcommitwork_tv_shellvolume;
		TextView detailcommitwork_tv_shellweight;
	}

}
