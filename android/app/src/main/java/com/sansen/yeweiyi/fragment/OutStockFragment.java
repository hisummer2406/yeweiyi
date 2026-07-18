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
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.OutStockAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.InOilManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DoubleUtil;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;

public class OutStockFragment extends BaseFragment implements OutStockAdapter.OnItemClickListener{

	private static final String TAG ="InOilFragment";
	private RecyclerView inout_list_info;
	private OutStockAdapter myAdapter;
	private LinearLayoutManager mLinearLayoutManager;
	private int datasize; // 模拟数据集的条数
	private List<OutStock> lists = null;// 存放当前的所有数据
	private List<OutStock> temps;
	private boolean isSort = true;
	private int oilCanId; //筛选油罐号

	AlertDialog.Builder builder;
	private List<OilCanMapper> mapper;
	private int account=0;
	@Override
	protected View initView(LayoutInflater inflater) {
		return inflater.inflate(R.layout.fragment_recode_inout, null);
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
		myAdapter = new OutStockAdapter();
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
		//	Log.d("监听", "新值：" + newValue);
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
						OutStock oilInOut = (OutStock) myAdapter
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
				temps = removeOilId(findAllObject(resultCode + 1,OutStock.class, 0, Const.recordNum, isSort));
			}

			myAdapter.setData(temps);
			temps.clear();
			handler.sendEmptyMessage(0);
			mLinearLayoutManager.removeAllViews();
		}
	}

	private List<OutStock> removeOilId(List<OutStock>temp){
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

				temps =removeOilId( findAllObject(0,OutStock.class, 0, Const.recordNum, sort));
				//Log.i("进出油数据", "run: "+temps.size());
				if (myAdapter == null) {
					myAdapter = new OutStockAdapter();
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
	public List<OutStock> findAllObject(int oilCanId, final Class clazz, int pageIndex,
										int pageSize, boolean des) {
		Log.i("查询记录","++++++++");
		temps = new ArrayList<OutStock>();
		if (dbManager != null) {
//			if (SharePrefUtil.getBoolean(ct,SharePrefUtil.TEST_DATA,false)){
//				lists =new ArrayList<OutStock>();
//				List<OilCanMapper> mappers= dbManager.findAllObject(OilCanMapper.class);
//				for (OilCanMapper map:mappers) {
//					Log.i("查询记录","-------------"+map.getProbeId());
//					if (map.isshow()){
//						lists.addAll(dbManager.<OutStock>findLimit(OutStock.class,oilCanId,pageSize,pageIndex,des));
//					}
//				}
//			}else {
//				if (oilCanId != 0) {
//					temps = dbManager.findLimit(OutStock.class,oilCanId,pageSize,pageIndex,des);
//				} else {
//					temps = dbManager.findLimit(OutStock.class,pageSize,pageIndex,des);
//				}
//			}
			if (oilCanId != 0) {
				temps = dbManager.findLimit(OutStock.class,oilCanId,pageSize,pageIndex,des);
			} else {
				temps = dbManager.findLimit(OutStock.class,pageSize,pageIndex,des);
			}
		}
		return removeOilId(temps);
	}

	/**
	 * 加载更多数据
	 */
	private void loadMoreData(int curPage) {
		//Log.i(TAG, "loadMoreData: 记载更多时"+lists.size());
		List<OutStock> newAlarm = removeOilId(findObject(curPage, Const.recordNum));
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

//	@Override
//	public boolean onItemLongClick(AdapterView<?> parent, View view,
//			final int position, long id) {
//		if (myApplication.getAuthorization().getDelteInOutOil() == 1) {
//			initDialog(position);
//			builder.show();
//		} else {
//			SystemUtils.showRsToast(R.string.authorization_toast_noauth);
//		}
//
//		return true;
//	}


	private List<OutStock> findObject(int pageIndex, int pageSize) {
		return dbManager.findLimit(OutStock.class,pageSize,pageIndex,isSort);
	}

	boolean isUnload;
	@Override
	public void onClick(int position) {
		final OutStock oilInOut = (OutStock) myAdapter
				.getItem(position);
		final DetailInoutRecord detailInoutRecord = (DetailInoutRecord) DBDataManager.getInstance().findObjectByID(DetailInoutRecord.class, oilInOut.getId());
		if (detailInoutRecord != null) {
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			View v = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_detail_inout, null);

			TextView inout_detail_oil_id = (TextView) v.findViewById(R.id.inout_detail_oil_id);
			TextView inout_detail_oil_product = (TextView) v.findViewById(R.id.inout_detail_oil_product);

			TextView begin_date = (TextView) v.findViewById(R.id.begin_date);
			TextView end_date = (TextView) v.findViewById(R.id.end_date);

			final TextView detail_begin_height = (TextView) v.findViewById(R.id.detail_begin_height);
			final TextView detail_end_height = (TextView) v.findViewById(R.id.detail_end_height);

			TextView detail_begin_volume = (TextView) v.findViewById(R.id.detail_begin_volume);
			final TextView detail_end_volume = (TextView) v.findViewById(R.id.detail_end_volume);

			TextView detail_begin_tc_volume = (TextView) v.findViewById(R.id.detail_begin_tc_volume);
			final TextView detail_end_tc_volume = (TextView) v.findViewById(R.id.detail_end_tc_volume);

			TextView detail_begin_water = (TextView) v.findViewById(R.id.detail_begin_water);
			final TextView detail_end_water = (TextView) v.findViewById(R.id.detail_end_water);

			TextView detail_begin_water_volume = (TextView) v.findViewById(R.id.detail_begin_water_volume);
			final TextView detail_end_water_volume = (TextView) v.findViewById(R.id.detail_end_water_volume);

			TextView detail_begin_weight = (TextView) v.findViewById(R.id.detail_begin_weight);
			final TextView detail_end_weight = (TextView) v.findViewById(R.id.detail_end_weight);

			TextView detail_begin_temp = (TextView) v.findViewById(R.id.detail_begin_temp);
			final TextView detail_end_temp = (TextView) v.findViewById(R.id.detail_end_temp);
			final TextView inout_toal_delivery = (TextView) v.findViewById(R.id.inout_toal_delivery);
			final EditText inout_sale_volume = (EditText) v.findViewById(R.id.inout_sale_volume);
			Button inout_quit = (Button) v.findViewById(R.id.inout_quit);
			final Button inout_save = (Button) v.findViewById(R.id.inout_save);
			final Button inout_continue = (Button) v.findViewById(R.id.inout_continue);
			final Button inout_stop = (Button) v.findViewById(R.id.inout_stop);
			final TextView inout_detail_oil_state =(TextView) v.findViewById(R.id.inout_detail_oil_state);
			inout_detail_oil_product.setText(detailInoutRecord.getOilType());
			inout_detail_oil_id.setText(String.valueOf(detailInoutRecord.getOilcanId()));
			inout_detail_oil_state.setText(R.string.inoil_unload_stop);
			begin_date.setText(detailInoutRecord.getBeginTime());
			end_date.setText(detailInoutRecord.getEndTime());

			detail_begin_height.setText(detailInoutRecord.getBeginHeith() + " mm");
			detail_begin_volume.setText(detailInoutRecord.getBeginVolume() + " L");
			detail_begin_tc_volume.setText(detailInoutRecord.getBeginTcVolume() + " L");
			detail_begin_water.setText(detailInoutRecord.getBeginWater() + " mm");
			detail_begin_water_volume.setText(detailInoutRecord.getBeginWaterVolume() + " L");
			detail_begin_weight.setText(detailInoutRecord.getBeginOilWeight() + " kg");
			detail_begin_temp.setText(detailInoutRecord.getBeginTemp() + " ℃");

			detail_end_height.setText(detailInoutRecord.getEndHeight() + " mm");
			detail_end_volume.setText(detailInoutRecord.getEndVolume() + " L");
			detail_end_tc_volume.setText(detailInoutRecord.getEndTcVolume() + " L");
			detail_end_water.setText(detailInoutRecord.getEndWater() + " mm");
			detail_end_water_volume.setText(detailInoutRecord.getEndWaterVolume() + " L");
			detail_end_weight.setText(detailInoutRecord.getEndOilWeight() + " kg");
			detail_end_temp.setText(detailInoutRecord.getEndTemp() + " ℃");
			inout_toal_delivery.setText(detailInoutRecord.getSaleVolume() + " L");
			inout_sale_volume.setHint(String.valueOf(detailInoutRecord.getDuringDelivery()));

			builder.setView(v);
			final AlertDialog dialog = builder.create();
			dialog.setCancelable(false);
			final CompositeDisposable mCompositeDisposable = new CompositeDisposable();
			inout_stop.setEnabled(false);


			final DisposableObserver observer = new DisposableObserver<Long>() {
				@Override
				public void onNext(@NonNull Long aLong) {
					Log.i(TAG, "循环执行" + aLong);
					detailInoutRecord.setOilType(Const.getOilType(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getOilType()));
					detailInoutRecord.setEndHeight((OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getOilLevel()));
					detailInoutRecord.setEndVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getOilVolume());
					detailInoutRecord.setEndWater(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getWaterLevel());
					detailInoutRecord.setEndWaterVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getWaterVolume());
					detailInoutRecord.setEndTcVolume(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getSurPlus());
					detailInoutRecord.setEndOilWeight(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getOilWeight());
					detailInoutRecord.setEndTemp(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getTemper());
					detailInoutRecord.setEndTime(DateUtils.getYYYMMddHHmmss());
					detailInoutRecord.setSaleVolume(DoubleUtil.sub(detailInoutRecord.getEndVolume(), detailInoutRecord.getBeginVolume()));


					detail_end_height.setText(detailInoutRecord.getEndHeight() + " mm");
					detail_end_volume.setText(detailInoutRecord.getEndVolume() + " L");
					detail_end_tc_volume.setText(detailInoutRecord.getEndTcVolume() + " L");
					detail_end_water.setText(detailInoutRecord.getEndWater() + " mm");
					detail_end_water_volume.setText(detailInoutRecord.getEndWaterVolume() + " L");
					detail_end_weight.setText(detailInoutRecord.getEndOilWeight() + " kg");
					detail_end_temp.setText(detailInoutRecord.getEndTemp() + " ℃");
					inout_toal_delivery.setText(detailInoutRecord.getSaleVolume() + " L");
					inout_sale_volume.setHint(String.valueOf(detailInoutRecord.getDuringDelivery()));
				}

				@Override
				public void onError(@NonNull Throwable e) {
					//e.printStackTrace();
				}

				@Override
				public void onComplete() {

				}
			};

			inout_quit.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View view) {
					if (!isUnload) {
						InOilManager.setEnable(oilInOut.getOilId(), true);
						mCompositeDisposable.remove(observer);
						dialog.dismiss();
					}else{
						SystemUtils.showRsToast("Please stop.");
					}
				}
			});

			inout_save.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View view) {
					String salVolume = inout_sale_volume.getText().toString();
					double value = 0;
					if (StringUtils.isNotEmpty(salVolume)) {
						value = Double.parseDouble(salVolume);
					}
					detailInoutRecord.salesDuring(value);
					Log.i("计算体积", "开始的体积" + detailInoutRecord.getBeginVolume() + "结束的体积" + detailInoutRecord.getEndVolume() + " SaleVolume" + detailInoutRecord.getSaleVolume());
					detailInoutRecord.setDuringDelivery(DoubleUtil.sub(detailInoutRecord.getSaleVolume(), DoubleUtil.sub(detailInoutRecord.getEndVolume(), detailInoutRecord.getBeginVolume())));
					DBDataManager.getInstance().UpdateObject(detailInoutRecord);
					OutStock oilInOut = (OutStock) DBDataManager.getInstance().findObjectByID(OutStock.class, detailInoutRecord.getId());
					oilInOut.setEndHeigth(detailInoutRecord.getEndHeight());
					oilInOut.setBeginVolum(detailInoutRecord.getSaleVolume());
					oilInOut.setEndWeight(DoubleUtil.mul(detailInoutRecord.getSaleVolume(),OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getDensity()));
					DBDataManager.getInstance().UpdateObject(oilInOut);
					inout_toal_delivery.setText(detailInoutRecord.getSaleVolume() + " L");
					inout_sale_volume.setText("");
					inout_sale_volume.setHint(String.valueOf(detailInoutRecord.getDuringDelivery()));
					SystemUtils.showRsToast("Save Success.");
				}
			});


			inout_continue.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View view) {
					isUnload = true;
					inout_detail_oil_state.setText(R.string.inoil_unloading);
					inout_save.setEnabled(false);
					inout_continue.setEnabled(false);
					inout_stop.setEnabled(true);
					InOilManager.setEnable(oilInOut.getOilId(), false);
					mCompositeDisposable.add(io.reactivex.Observable.interval(1, 1, TimeUnit.SECONDS)
							.subscribeOn(Schedulers.io())
							.observeOn(AndroidSchedulers.mainThread())
							.subscribeWith(observer));
				}
			});
			inout_stop.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View view) {
					inout_detail_oil_state.setText(R.string.inoil_unload_stop);
					inout_save.setEnabled(true);
					isUnload = false;
					InOilManager.setEnable(oilInOut.getOilId(), true);
					mCompositeDisposable.remove(observer);
				}
			});

			Window window = dialog.getWindow();

			window.setGravity(Gravity.CENTER);//此处可以设置dialog显示的位置
			dialog.show();
			//设置dialog的宽度和手机宽度一样
			WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
			lp.width = (int) (window.getWindowManager().getDefaultDisplay().getWidth() * 0.8);
			dialog.getWindow().setAttributes(lp);//设置宽度
		}
	}

	@Override
	public void onLongClick(int position) {
		//Log.i(TAG, "onLongClick: 长按"+position);
		if (Singleton.getInstance().IsDelOut) {
			initDialog(position);
			builder.show();
		}
		else {
			//SystemUtils.showRsToast(R.string.authorization_toast_noauth);
		}
	}
}
