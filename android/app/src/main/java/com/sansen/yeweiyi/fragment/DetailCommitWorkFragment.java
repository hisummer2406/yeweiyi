package com.sansen.yeweiyi.fragment;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.DetailCommitWorkAdapter;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.Authorization;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.repository.ShiftRepository;
import com.sansen.yeweiyi.repository.ShiftRepositoryImpl;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.UpdateCommitDialog;

import java.util.List;

@SuppressLint("ValidFragment")
public class DetailCommitWorkFragment extends BaseFragment {

	ListView detailcommitwork_list_info;
	private final ShiftRepository shiftRepository = ShiftRepositoryImpl.getInstance();
	private DetailCommitWorkAdapter myAdapter;
	private List<OilWork> oilWorks;
	private int oilCanId;
	private LinearLayout ly_return;

	@Override
	protected View initView(LayoutInflater inflater) {
		// TODO Auto-generated method stub
		return inflater.inflate(R.layout.fragment_recode_detailcommitwork,
				null, false);
	}

	@SuppressLint("ValidFragment")
	public DetailCommitWorkFragment(int id, List<OilWork> oilWorks) {

		super();
		this.oilCanId = id;
		this.oilWorks = oilWorks;

	}

	@Override
	protected void initData(Bundle savedInstanceState) {
		findView();
		myAdapter = new DetailCommitWorkAdapter(ct);
		myAdapter.setData(oilWorks);
		detailcommitwork_list_info.setAdapter(myAdapter);
		detailcommitwork_list_info
				.setOnItemLongClickListener(new OnItemLongClickListener() {

					@Override
					public boolean onItemLongClick(AdapterView<?> parent,
												   View view, int position, long id) {
						Authorization authorization= myApplication.getAuthorization();
						if (authorization.getUpdateCommitInfo()==1) {
							Intent intent = new Intent(main,
									UpdateCommitDialog.class);
							Bundle bundle = new Bundle();
							OilWork oilWork = myAdapter.getItem(position);
							bundle.putInt("id",oilCanId);
							bundle.putSerializable("obj", oilWork);
							intent.putExtra("bundle", bundle);
							startActivityForResult(intent, 0);
						}else {
							SystemUtils.showRsToast(R.string.authorization_toast_noauth);
						}
						
						return false;
					}
				});
	}

	@Override
	public void findView() {

		detailcommitwork_list_info = (ListView) rootView
				.findViewById(R.id.detailcommitwork_list_info);
		ly_return=rootView.findViewById(R.id.ly_return);
		ly_return.setOnClickListener(this);
	}

	public void initTitle() {
		if (main != null) {
//			main.iv_right.setImageResource(R.drawable.laststep);
//			main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
//			main.iv_right.setVisibility(View.VISIBLE);
//			main.tv_left.setVisibility(View.VISIBLE);
			OnClickListener onClickListener= new OnClickListener() {
				@Override
				public void onClick(View v) {
					CommitWorkRecortFragment commitWorkRecortFragment = new CommitWorkRecortFragment();
					main.switchContent(main.mContent, commitWorkRecortFragment);
				}
			};
//			main.iv_right.setOnClickListener(onClickListener);
//			main.tv_left.setOnClickListener(onClickListener);
		}
	}

	@Override
	protected void processClick(View v) {

		if (v.getId()==R.id.ly_return){
			CommitWorkRecortFragment commitWorkRecortFragment = new CommitWorkRecortFragment();
			main.switchContent(main.mContent, commitWorkRecortFragment);
		}
	}
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		// TODO Auto-generated method stub
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode==100) {
			CommitWork work = shiftRepository.findById(oilCanId);
			List<OilWork> oilWorks= dbManager.stringToArray(work.getInfo(), OilWork[].class);
			myAdapter.setData(oilWorks);
			myAdapter.notifyDataSetChanged();
		}
	}
	
	
	@Override
	public void onHiddenChanged(boolean hidden) {
		if (!hidden) {
			SystemUtils.showRsToast(R.string.long_touch_update_commitwork);
		}
	}
}