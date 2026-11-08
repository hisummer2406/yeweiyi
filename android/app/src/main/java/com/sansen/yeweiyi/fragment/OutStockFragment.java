package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
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
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.OutStockAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;
import com.sansen.yeweiyi.logic.export.RecordExportColumns;
import com.sansen.yeweiyi.utils.RecordExportUi;
import com.sansen.yeweiyi.utils.CurrentTankLookup;

import java.util.ArrayList;
import java.util.List;

public class OutStockFragment extends BaseFragment implements OutStockAdapter.OnItemClickListener{

	private static final String TAG ="InOilFragment";
	private final StockRepository stockRepository = StockRepositoryImpl.getInstance();
	private final OilTankRepository oilTankRepository = OilTankRepositoryImpl.getInstance();
	private RecyclerView inout_list_info;
	private OutStockAdapter myAdapter;
	private LinearLayoutManager mLinearLayoutManager;
	private List<OutStock> lists = null;// 存放当前的所有数据
	private List<OutStock> temps;
	private boolean isSort = true;
	private List<OilCanMapper> mapper;
	private int account=0;
	private LinearLayout ly_ruKu;
	@Override
	protected View initView(LayoutInflater inflater) {
		return inflater.inflate(R.layout.fragment_recode_out, null);
	}

	@Override
	protected void initData(Bundle savedInstanceState) {
		findView();
		initializeAdapter(isSort);
	}
	TextView tvTitle,tv_time;
	@Override
	public void findView() {
		mapper = oilTankRepository.findAllOilCanMapper();
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
		tvTitle.setText(R.string.chukujilu);
		SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
		ly_ruKu=rootView.findViewById(R.id.ly_ruKu);
		ly_ruKu.setOnClickListener(this);
		rootView.findViewById(R.id.ly_backHome).setOnClickListener(v -> main.main_but_main(v));
		if (!Singleton.getInstance().IsChuKu){
			ly_ruKu.setVisibility(View.GONE);
		}
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
				OutStock oilInOut = (OutStock) myAdapter
						.getItem(position);
				stockRepository.deleteOut(oilInOut);
				initializeAdapter(isSort);
				dialog.dismiss();
			}
		});

		dialog.show();

		Window window = dialog.getWindow();
		if (window != null) {
			int width = (int) (540 + 2 * getResources().getDisplayMetrics().density);   // 300dp
			// 高度不再写死——弹窗根布局本来就是 wrap_content，交给系统按实际内容（含英文下可能
			// 换行的提示文案）自适应，避免内容比写死的高度还高时把底部按钮裁掉。
			window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
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
		temps = new ArrayList<OutStock>();
		if (oilCanId != 0) {
			temps = stockRepository.findOutPageByOil(oilCanId,pageSize,pageIndex,des);
		} else {
			temps = stockRepository.findOutPage(pageSize,pageIndex,des);
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
		if (v.getId()==R.id.ly_ruKu){
			// 列定义见 RecordExportColumns（与列表表头一致，RecordExportHeadersTest 核对）；按 time 倒序取数，与界面列表（isSort=true）一致
			RecordExportUi.export(main, "Oil_out_record", RecordExportColumns.outStock(CurrentTankLookup.INSTANCE),
					() -> stockRepository.findAllOutSorted(true));
		}

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
		if (!Singleton.getInstance().IsChuKu){
			ly_ruKu.setVisibility(View.GONE);
		}
		else {
			ly_ruKu.setVisibility(View.VISIBLE);
		}

	}



	private List<OutStock> findObject(int pageIndex, int pageSize) {
		return stockRepository.findOutPage(pageSize,pageIndex,isSort);
	}

	boolean isUnload;
	@Override
	public void onClick(int position) {

	}

	@Override
	public void onLongClick(int position) {
		//Log.i(TAG, "onLongClick: 长按"+position);
		if (Singleton.getInstance().IsDelOut) {
			initDialog(position);
		}
		else {
			//SystemUtils.showRsToast(R.string.authorization_toast_noauth);
		}
	}
}
