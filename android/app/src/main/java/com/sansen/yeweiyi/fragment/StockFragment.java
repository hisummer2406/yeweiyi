package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.logic.thingsboard.StockRecordMapper;
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
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;
import com.sansen.yeweiyi.logic.export.RecordExportColumns;
import com.sansen.yeweiyi.utils.RecordExportUi;
import com.sansen.yeweiyi.utils.CurrentTankLookup;

import java.util.ArrayList;
import java.util.List;

public class StockFragment extends BaseFragment implements StockSaveAdapter.OnItemClickListener{

	private static final String TAG ="InOilFragment";
	private final StockRepository stockRepository = StockRepositoryImpl.getInstance();
	private final OilTankRepository oilTankRepository = OilTankRepositoryImpl.getInstance();
	private RecyclerView inout_list_info;
	private StockSaveAdapter myAdapter;
	private LinearLayoutManager mLinearLayoutManager;
	private List<StockSave> lists = null;// 存放当前的所有数据
	private List<StockSave> temps;
	private boolean isSort = true;
	private List<OilCanMapper> mapper;
	private int account=0;
	private LinearLayout ly_saveStock,ly_ruKu;
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
		myAdapter = new StockSaveAdapter();
		myAdapter.setOnItemClickListener(this);
		inout_list_info.setAdapter(myAdapter);
		View headerView = rootView.findViewById(R.id.title_bar);

// 再从该 View 中 findViewById 获取内部的 TextView
		tvTitle = headerView.findViewById(R.id.tv_title);
		tv_time=headerView.findViewById(R.id.main_time);
		tvTitle.setText(R.string.inventory);
		SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
		ly_saveStock=rootView.findViewById(R.id.ly_saveStock);
		ly_ruKu=rootView.findViewById(R.id.ly_ruKu);
		ly_saveStock.setOnClickListener(this);
		ly_ruKu.setOnClickListener(this);
		rootView.findViewById(R.id.ly_backHome).setOnClickListener(v -> main.main_but_main(v));
		if (!Singleton.getInstance().IsKuCun){
			ly_ruKu.setVisibility(View.GONE);
		}

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
		btn_cancel.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				dialog.dismiss();
			}
		});
		btn_confirm.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				StockSave oilInOut = (StockSave) myAdapter
						.getItem(position);
				stockRepository.deleteStock(oilInOut);
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
	@Override
	// [可达-2026-09-23] R.string.main_toast_savesuccess 之前被误判不可达——它同时也在
	// BaseFragment.getSaveListener()（已确认死代码）里出现过，之前的核查只看到那个死用法就把
	// 整个 key 判死了，没查到这里：ly_saveStock 真实绑定了 setOnClickListener(this)，点击后无条件
	// 走到这行 toast，是可达的。
	protected void processClick(View v) {
		if (v.getId()==R.id.ly_saveStock){
			myApplication.getmSaveOilManager().saveStock(StockRecordMapper.TRIGGER_MANUAL);
			SystemUtils.showRsToast(R.string.main_toast_savesuccess);

		}
		else if (v.getId()==R.id.ly_ruKu){
			// 列定义见 RecordExportColumns（与列表表头一致，RecordExportHeadersTest 核对）；按 time 倒序取数，与界面列表（isSort=true）一致
			RecordExportUi.export(main, "Oil_Stock_record", RecordExportColumns.stock(CurrentTankLookup.INSTANCE),
					() -> stockRepository.findAllStockSorted(true));
		}

	}

	public void initTitle() {

	
		if (main != null) {

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

				temps =findAllObject(0,StockSave.class, 0, Const.recordNum, sort);
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
		if (oilCanId != 0) {
			temps = stockRepository.findStockPageByOil(oilCanId,pageSize,pageIndex,des);
		} else {
			temps = stockRepository.findStockPage(pageSize,pageIndex,des);
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
	public void onHiddenChanged(boolean hidden) {
		// TODO Auto-generated method stub
		super.onHiddenChanged(hidden);
		if (!hidden) {
			initializeAdapter(isSort);
		}
	}

	public void onReflushData() {
		initializeAdapter(isSort);
		mLinearLayoutManager.removeAllViews();
		if (!Singleton.getInstance().IsKuCun){
			ly_ruKu.setVisibility(View.GONE);
		}
		else {
			ly_ruKu.setVisibility(View.VISIBLE);
		}

	}
	private List<StockSave> findObject(int pageIndex, int pageSize) {
		return stockRepository.findStockPage(pageSize,pageIndex,isSort);
	}

	@Override
	public void onClick(int position) {

	}

	@Override
	public void onLongClick(int position) {
		//Log.i(TAG, "onLongClick: 长按"+position);
		if (Singleton.getInstance().IsDelStock) {
			initDialog(position);
		} else {
			//SystemUtils.showRsToast(R.string.authorization_toast_noauth);
		}
	}
}
