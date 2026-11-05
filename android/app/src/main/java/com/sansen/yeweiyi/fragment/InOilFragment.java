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
import android.text.InputFilter;
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
import com.sansen.yeweiyi.manager.InOilManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.DecimalDigitsInputFilter;
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
	private final StockRepository stockRepository = StockRepositoryImpl.getInstance();
	private final OilTankRepository oilTankRepository = OilTankRepositoryImpl.getInstance();
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
		rootView.findViewById(R.id.ly_backHome).setOnClickListener(v -> main.main_but_main(v));
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
				stockRepository.deleteIn(oilInOut);
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
		if (oilCanId != 0) {
			temps = stockRepository.findInPageByOil(oilCanId,pageSize,pageIndex,des);
		} else {
			temps = stockRepository.findInPage(pageSize,pageIndex,des);
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
		// 按 time 倒序取数，与界面列表（isSort=true）顺序保持一致
		List<OilInOut> dataset = stockRepository.findAllInSorted(true);
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
		return stockRepository.findInPage(pageSize,pageIndex,isSort);
	}

	/**
	 * 废弃：这个方法（连同它打开的 {@code dialog_detail_inout.xml}"边卸边卖"人工核对
	 * 详情框——查看快照、继续/停止卸油、录入售液补偿并重发 stockEvent）从来没有被真正
	 * 触发过，也不打算再修：{@link InOutAdapter} 给列表行装的点击监听器
	 * （{@code onBindViewHolder}）本身就是空实现，只有长按才会调
	 * {@link InOutAdapter.OnItemClickListener#onLongClick}，那条路走的是
	 * {@link #onLongClick} 里完全不同的删除确认框。2026-09 复核确认这不是接错线的 bug、
	 * 是已经废弃不再使用的功能，因此把原本 ~190 行的详情/核对逻辑清空，只保留方法体
	 * 以满足 {@link InOutAdapter.OnItemClickListener} 接口——不能删掉整个方法，
	 * {@link #onLongClick} 这个真正在用的入口需要同一个接口。
	 * <p>
	 * 连带影响：{@link DetailInoutRecord#getDuringDelivery()} 因此永远不会被写成非零值
	 * （唯一的写入点 {@code salesDuring}/{@code setDuringDelivery} 调用就在这段被清空的
	 * 代码里）——{@code StockEventMapper} 对它的 omitempty 判断永远为 false，
	 * {@code duringDeliveryVolumeL} 不会出现在真实上报的 stockEvent 里。这是这个功能被
	 * 废弃后的自然结果，不是留待修复的缺陷，也不需要另外处理。
	 */
	@Override
	public void onClick(int position) {
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
