package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.databinding.Observable;
import android.databinding.Observable.OnPropertyChangedCallback;
import android.databinding.ObservableField;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.StockSaveAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import java.util.ArrayList;
import java.util.List;

public class StockFragment extends BaseFragment implements StockSaveAdapter.OnItemClickListener{

	private static final String TAG ="InOilFragment";
	private RecyclerView inout_list_info;
	private StockSaveAdapter myAdapter;
	private LinearLayoutManager mLinearLayoutManager;
	private int datasize; // 模拟数据集的条数
	private List<StockSave> lists = null;// 存放当前的所有数据
	private List<StockSave> temps;
	private boolean isSort = true;
	private int oilCanId; //筛选油罐号

	AlertDialog.Builder builder;
	private List<OilCanMapper> mapper;
	private int account=0;
	@Override
	protected View initView(LayoutInflater inflater) {
		return inflater.inflate(R.layout.fragment_recode_stock, null);
	}

	@Override
	protected void initData(Bundle savedInstanceState) {
		findView();
		initializeAdapter(isSort);
	}
	TextView tvTitle,tv_time;
	@Override
	public void findView() {
		mapper = MyApplication.getInstance().getmDBManager().findAllObject(OilCanMapper.class);
		for (int i = 0; i <mapper.size() ; i++) {
			if (mapper.get(i).isshow()){
				account++;
			}
		}
		inout_list_info = (RecyclerView) rootView
				.findViewById(R.id.inout_list_info);
		mLinearLayoutManager = new RecyclerViewNoBugLinearLayoutManager(ct);
		inout_list_info.setLayoutManager(mLinearLayoutManager);
		inout_list_info.addItemDecoration(new MyDecoration(ct, MyDecoration.VERTICAL_LIST));
		myAdapter = new StockSaveAdapter();
		myAdapter.setOnItemClickListener(this);
		inout_list_info.setAdapter(myAdapter);
		View headerView = rootView.findViewById(R.id.title_bar);

// 再从该 View 中 findViewById 获取内部的 TextView
		tvTitle = headerView.findViewById(R.id.tv_title);
		tv_time=headerView.findViewById(R.id.main_time);
		tvTitle.setText("设置");
		SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
	}
	OnPropertyChangedCallback callback = new OnPropertyChangedCallback() {

		@Override
		public void onPropertyChanged(Observable sender, int propertyId) {
			// 如果需要区分是哪个字段，可通过 propertyId 判断
			// 直接获取当前值
			String newValue = ((ObservableField<String>) sender).get();
			//Log.d("监听", "新值：" + newValue);
			tv_time.setText(newValue);
		}
	};
	@Override
	public void onDestroy() {
		// Fragment 实例即将销毁
		super.onDestroy();
		SerialPortEntrity.time.removeOnPropertyChangedCallback(callback);
	}
	private void initDialog(final int position) {
		builder = new AlertDialog.Builder(ct);
		builder.setTitle(SystemUtils.getRsStr(R.string.isDeleteRecord));
		builder.create();
		builder.setPositiveButton(SystemUtils.getRsStr(R.string.confirm),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						StockSave oilInOut = (StockSave) myAdapter
								.getItem(position);
						dbManager.delteRecord(oilInOut);
						initializeAdapter(isSort);

					}
				});

		builder.setNegativeButton(SystemUtils.getRsStr(R.string.cancle),
				new DialogInterface.OnClickListener() {

					@Override
					public void onClick(DialogInterface dialog, int which) {
						// TODO Auto-generated method stub

					}
				});

	}

	public void initTitle() {

	
		if (main != null) {

//			main.tv_filter.setVisibility(View.VISIBLE);
//			main.tv_right.setVisibility(View.VISIBLE);
//			main.tv_right.setText(SystemUtils.getRsStr(R.string.record_sort));
//			main.tv_filter.setText(SystemUtils.getRsStr(R.string.filter));
//
//			main.iv_right.setVisibility(View.GONE);
//			main.tv_unlogin.setVisibility(View.GONE);
//			main.tv_left.setVisibility(View.GONE);
//
//			main.tv_filter.setOnClickListener(new OnClickListener() {
//
//				@Override
//				public void onClick(View v) {
//					Intent intent = new Intent(main,ActivityDialog.class);
//					startActivityForResult(intent, 0);
//				}
//			});
//
//			main.tv_right.setOnClickListener(new OnClickListener() {
//
//				@Override
//				public void onClick(View v) {
//					isSort = !isSort;
//					initializeAdapter(isSort);
//					mLinearLayoutManager.removeAllViews();
//				}
//			});
		}
	}
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
//		 TODO Auto-generated method stub
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode!=0) {
			resultCode-=100;
			setListener();
			if (SharePrefUtil.getBoolean(MyApplication.getInstance().getApplicationContext(),SharePrefUtil.TEST_DATA,false)){
				temps = removeOilId(findAllObject(resultCode + 1,StockSave.class, 0, Const.recordNum, isSort));
			}

			myAdapter.setData(temps);
			temps.clear();
			handler.sendEmptyMessage(0);
			mLinearLayoutManager.removeAllViews();
		}
	}

	private List<StockSave> removeOilId(List<StockSave>temp){
		for (int i = 0; i <temp.size() ; i++) {
			if (account!=0&&temp.get(i).getOilId()>account){
				temp.remove(i);
			}
		}
		return temp;
	}

	/**
	 * 初始化ListView的适配器
	 */
	private void initializeAdapter(final boolean sort) {

		Const.isOpen=false;
		ThreadManager.getShortPool().execute(new Runnable() {

			@Override
			public void run() {
				setListener();
				Log.i("查询记录","==============");

				temps =removeOilId( findAllObject(0,StockSave.class, 0, Const.recordNum, sort));
				//Log.i("进出油数据", "run: "+temps.size());
				if (myAdapter == null) {
					myAdapter = new StockSaveAdapter();
				}

				myAdapter.setData(temps);
				temps.clear();
				handler.sendEmptyMessage(0);
			}
		});

	}

	void setListener() {
		inout_list_info.setOnScrollListener(new EndLessOnScrollListener(mLinearLayoutManager) {
			@Override
			public void onLoadMore(int currentPage) {
				//Log.i("底部数据", "onLoadMore: 加载到底部 " + currentPage);
				loadMoreData(currentPage);
			}
		});

	}

	Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 0:

				if (inout_list_info.getAdapter() == null) {
					inout_list_info.setAdapter(myAdapter);
				}
				myAdapter.notifyDataSetChanged();
				Const.setIsOpen(true, main);
				break;
			default:
				break;
			}

		};
	};

	/**
	 * FindAll
	 * 
	 * @param clazz
	 * @return List<T>
	 */
	public List<StockSave> findAllObject(int oilCanId, final Class clazz, int pageIndex,
										int pageSize, boolean des) {
		Log.i("查询记录","++++++++");
		temps = new ArrayList<StockSave>();
		if (dbManager != null) {
//			if (SharePrefUtil.getBoolean(ct,SharePrefUtil.TEST_DATA,false)){
//				lists =new ArrayList<StockSave>();
//				List<OilCanMapper> mappers= dbManager.findAllObject(OilCanMapper.class);
//				for (OilCanMapper map:mappers) {
//					Log.i("查询记录","-------------"+map.getProbeId());
//					if (map.isshow()){
//						lists.addAll(dbManager.<StockSave>findLimit(StockSave.class,oilCanId,pageSize,pageIndex,des));
//					}
//				}
//			}else {
//				if (oilCanId != 0) {
//					temps = dbManager.findLimit(StockSave.class,oilCanId,pageSize,pageIndex,des);
//				} else {
//					temps = dbManager.findLimit(StockSave.class,pageSize,pageIndex,des);
//				}
//			}
			if (oilCanId != 0) {
				temps = dbManager.findLimit(StockSave.class,oilCanId,pageSize,pageIndex,des);
			} else {
				temps = dbManager.findLimit(StockSave.class,pageSize,pageIndex,des);
			}
		}
		return removeOilId(temps);
	}

	/**
	 * 加载更多数据
	 */
	private void loadMoreData(int curPage) {
		//Log.i(TAG, "loadMoreData: 记载更多时"+lists.size());
		List<StockSave> newAlarm = removeOilId(findObject(curPage, Const.recordNum));
		myAdapter.addNewsItem(newAlarm);
		handler.sendEmptyMessage(0);

	}


	@Override
	protected void processClick(View v) {

	}





	@Override
	public void onHiddenChanged(boolean hidden) {
		// TODO Auto-generated method stub
		super.onHiddenChanged(hidden);
		if (!hidden) {
			initializeAdapter(isSort);
			//SystemUtils.showRsToast(R.string.long_touch_delte_inoil);
//			main.tv_filter.setVisibility(View.VISIBLE);
//			main.tv_right.setVisibility(View.VISIBLE);
		}else {
//			main.tv_filter.setVisibility(View.INVISIBLE);
//			main.tv_right.setVisibility(View.INVISIBLE);
		}
	}

	public void onReflushData() {
		initializeAdapter(isSort);
		mLinearLayoutManager.removeAllViews();

	}
	private List<StockSave> findObject(int pageIndex, int pageSize) {
		return dbManager.findLimit(StockSave.class,pageSize,pageIndex,isSort);
	}

	@Override
	public void onClick(int position) {

	}

	@Override
	public void onLongClick(int position) {
		//Log.i(TAG, "onLongClick: 长按"+position);
		if (Singleton.getInstance().IsDelStock) {
			initDialog(position);
			builder.show();
		} else {
			//SystemUtils.showRsToast(R.string.authorization_toast_noauth);
		}
	}
}
