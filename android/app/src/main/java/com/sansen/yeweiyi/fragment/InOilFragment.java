package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.databinding.Observable;
import android.databinding.Observable.OnPropertyChangedCallback;
import android.databinding.ObservableField;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.InOutAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.listener.ExcelListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.InOilManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DoubleUtil;
import com.sansen.yeweiyi.utils.FileSizeUtil;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;

import static com.lzy.okgo.utils.HttpUtils.runOnUiThread;

public class InOilFragment extends BaseFragment implements InOutAdapter.OnItemClickListener{

	private static final String TAG ="InOilFragment";
	private RecyclerView inout_list_info;
	private InOutAdapter myAdapter;
	private LinearLayoutManager mLinearLayoutManager;
	private List<OilInOut> lists = null;// 存放当前的所有数据
	private List<OilInOut> temps;
	private boolean isSort = true;
	private List<OilCanMapper> mapper;
	private int account=0;
	private LinearLayout ly_ruKu;
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
		myAdapter = new InOutAdapter();
		myAdapter.setOnItemClickListener(this);
		inout_list_info.setAdapter(myAdapter);
		View headerView = rootView.findViewById(R.id.title_bar);
		ly_ruKu=rootView.findViewById(R.id.ly_ruKu);
		ly_ruKu.setOnClickListener(this);
		if (!Singleton.getInstance().IsRuKu){
			ly_ruKu.setVisibility(View.GONE);
		}

// 再从该 View 中 findViewById 获取内部的 TextView
		tvTitle = headerView.findViewById(R.id.tv_title);
		tv_time=headerView.findViewById(R.id.main_time);
		tvTitle.setText("入库记录");
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
		View customView = LayoutInflater.from(main).inflate(R.layout.dialog_custom_alert, null);
		Button btn_confirm = customView.findViewById(R.id.btn_confirm);
		Button btn_cancel = customView.findViewById(R.id.btn_cancel);
		AlertDialog dialog = new AlertDialog.Builder(main)
				.setView(customView)
				.create();
		btn_cancel.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				dialog.dismiss();
			}
		});
		btn_confirm.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				OilInOut oilInOut = (OilInOut) myAdapter
						.getItem(position);
				dbManager.delteRecord(oilInOut);
				initializeAdapter(isSort);
				dialog.dismiss();
			}
		});

		dialog.show();

		Window window = dialog.getWindow();
		if (window != null) {
			int width = (int) (540 + 2 * getResources().getDisplayMetrics().density);   // 300dp
			int height = (int) (230*1.3  * getResources().getDisplayMetrics().density);  // 400dp
			window.setLayout(width, height);
		}
	}

	public void initTitle() {

	
		if (main != null) {

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
				temps = removeOilId(findAllObject(resultCode + 1,OilInOut.class, 0, Const.recordNum, isSort));
			}

			myAdapter.setData(temps);
			temps.clear();
			handler.sendEmptyMessage(0);
			mLinearLayoutManager.removeAllViews();
		}
	}

	private List<OilInOut> removeOilId(List<OilInOut>temp){
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
				//Log.i("查询记录","==============");

				temps =removeOilId( findAllObject(0,OilInOut.class, 0, Const.recordNum, sort));
				//Log.i("进出油数据", "run: "+temps.size());
				if (myAdapter == null) {
					myAdapter = new InOutAdapter();
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
	public List<OilInOut> findAllObject(int oilCanId, final Class clazz, int pageIndex,
										int pageSize, boolean des) {
		//Log.i("查询记录","++++++++");
		temps = new ArrayList<OilInOut>();
		if (dbManager != null) {

			if (oilCanId != 0) {
				temps = dbManager.findLimit(OilInOut.class,oilCanId,pageSize,pageIndex,des);
			} else {
				temps = dbManager.findLimit(OilInOut.class,pageSize,pageIndex,des);
			}
		}
		return removeOilId(temps);
	}

	/**
	 * 加载更多数据
	 */
	private void loadMoreData(int curPage) {
		//Log.i(TAG, "loadMoreData: 记载更多时"+lists.size());
		List<OilInOut> newAlarm = removeOilId(findObject(curPage, Const.recordNum));
		myAdapter.addNewsItem(newAlarm);
		handler.sendEmptyMessage(0);

	}


	@Override
	protected void processClick(View v) {

		if (v.getId()==R.id.ly_ruKu){
			runOnUiThread(() -> {
				// 主线程操作
				ly_ruKu.setVisibility(View.GONE);
			});
			SaveInOIl(Environment.getExternalStorageDirectory().getAbsolutePath());
		}
	}
	private void SaveInOIl(String path) {

		//path=path+"/" + "Oil_in_record";
		Log.e("TAG 路径",path);
		ExportExcel<OilInOut> exportExcel = new ExportExcel<OilInOut>();
		String headers[] = { "",SystemUtils.getRsStr(R.string.tank_no),SystemUtils.getRsStr(R.string.time), SystemUtils.getRsStr(R.string.beginHeight), SystemUtils.getRsStr(R.string.endHeight), "前量", "后量","入库量l","入库量kg", "介质"};
		DBDataManager db = MyApplication.getInstance().getmDBManager();
		List<OilInOut> dataset = db.findAllObject(OilInOut.class);
		File file = new File(path);
		if (!file.exists())
			file.mkdir();
		OutputStream out = null;
		try {
			file = new File(path + "/" + "Oil_in_record" + ".xls");
			out = new FileOutputStream(file);
			exportExcel.exportExcel(headers, dataset,
					new OilInOut().getParams(), out, new ExcelListener() {
						@Override
						public void Success(boolean isTrue) {
							runOnUiThread(() -> {
								// 主线程操作
								ly_ruKu.setVisibility(View.VISIBLE);
							});
						}
					});
			out.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
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
		if (!Singleton.getInstance().IsRuKu){
			ly_ruKu.setVisibility(View.GONE);
		}
		else {
			ly_ruKu.setVisibility(View.VISIBLE);
		}

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


	private List<OilInOut> findObject(int pageIndex, int pageSize) {
		return dbManager.findLimit(OilInOut.class,pageSize,pageIndex,isSort);
	}

	boolean isUnload;
	@Override
	public void onClick(int position) {
		final OilInOut oilInOut = (OilInOut) myAdapter
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
					//Log.i(TAG, "循环执行" + aLong);
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
						InOilManager.getInstance().setEnable(oilInOut.getOilId(), true);
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
					OilInOut oilInOut = (OilInOut) DBDataManager.getInstance().findObjectByID(OilInOut.class, detailInoutRecord.getId());
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
					InOilManager.getInstance().setEnable(oilInOut.getOilId(), false);
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
					InOilManager.getInstance().setEnable(oilInOut.getOilId(), true);
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
		if (Singleton.getInstance().IsDelIn) {
			initDialog(position);
		} else {
			//SystemUtils.showRsToast(R.string.authorization_toast_noauth);
		}
	}
}
