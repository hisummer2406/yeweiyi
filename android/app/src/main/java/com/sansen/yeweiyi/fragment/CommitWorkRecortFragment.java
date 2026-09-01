package com.sansen.yeweiyi.fragment;

import android.app.AlertDialog;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.OilCommitWorkAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.listener.ExcelListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.repository.ShiftRepository;
import com.sansen.yeweiyi.repository.ShiftRepositoryImpl;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.FileSizeUtil;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.lzy.okgo.utils.HttpUtils.runOnUiThread;

public class CommitWorkRecortFragment extends BaseFragment implements OilCommitWorkAdapter.OnItemClickListener{

	private RecyclerView commitwork_list_info;

	private OilCommitWorkAdapter myAdapter;

	private LinearLayoutManager mLinearLayoutManager;

	private final ShiftRepository shiftRepository = ShiftRepositoryImpl.getInstance();
	private boolean isSort = true;
	private int datasize; // 模拟数据集的条数
	private List<CommitWork> lists = null;// 存放当前的所有数据
	private List<CommitWork> temps;
	LinearLayout ly_startJiaoBan,ly_endJiaoBan,ly_jiaoBanData;
	@Override
	protected View initView(LayoutInflater inflater) {

		return inflater.inflate(R.layout.fragment_recode_commitwork, null);
	}
	@Override
	protected void processClick(View v) {
		if (v.getId()==R.id.ly_jiaoBanData){
//			runOnUiThread(() -> {
//				// 主线程操作
//				ly_jiaoBanData.setVisibility(View.GONE);
//			});
//			SaveOutOIl(Environment.getExternalStorageDirectory().getAbsolutePath());
			// 按 id 倒序取数（与 findAllSorted 已有排序口径一致），与界面列表（isSort=true）顺序保持一致
			lists = shiftRepository.findAllSorted(true);
			//String gsonInfo=lists.get(0).getInfo();
			for (int i=0;i<lists.size();i++){
				List<OilWork> oilWorks=dbManager.stringToArray(lists.get(i).getInfo(), OilWork[].class);
				for (OilWork oilwork :oilWorks) {
					oilwork.setTime(lists.get(i).getTime());
					oilwork.setEndTime(lists.get(i).getEndTime());
					oilwork.setJieZhi(Const.getOilType(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilwork.getOilId()).getOilType()));
				}
				dataset.addAll(oilWorks);
			}
			for (CommitWork work:lists) {
				dataset.addAll(dbManager.stringToArray(work.getInfo(), OilWork[].class));
			}
			SaveCommitOIl(Environment.getExternalStorageDirectory().getAbsolutePath());


		}

	}
	List<OilWork> dataset=new ArrayList<>();
	private void SaveCommitOIl(String path) {
		Log.e("TAG 路径",path);
		ExportExcel<OilWork> exportExcel = new ExportExcel<OilWork>();
		String headers[] = { "介质",SystemUtils.getRsStr(R.string.tank_no),"开始时间", "结束时间",SystemUtils.getRsStr(R.string.beginHeight), SystemUtils.getRsStr(R.string.endHeight), "前量", "后量","出库量l","出库量kg"};
		// = MyApplication.getInstance().getmDBManager().findAllObject(OilWork.class);
		File file = new File(path);
		if (!file.exists())
			file.mkdir();
		OutputStream out = null;
		try {
			file = new File(path + "/" + "Oil_Shilt_record" + ".xls");
			out = new FileOutputStream(file);
			exportExcel.exportExcel(headers, dataset,
					new OilWork().getParams(), out, new ExcelListener() {
						@Override
						public void Success(boolean isTrue) {
							runOnUiThread(() -> {
								// 主线程操作
								ly_jiaoBanData.setVisibility(View.VISIBLE);
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
	protected void initData(Bundle savedInstanceState) {
		findView();
		initializeAdapter(isSort);
	}
	@Override
	public void findView() {
		ly_startJiaoBan=rootView.findViewById(R.id.ly_startJiaoBan);
		ly_endJiaoBan=rootView.findViewById(R.id.ly_endJiaoBan);
		ly_jiaoBanData=rootView.findViewById(R.id.ly_jiaoBanData);
		commitwork_list_info = (RecyclerView) rootView
				.findViewById(R.id.commitwork_list_info);
		mLinearLayoutManager = new RecyclerViewNoBugLinearLayoutManager(ct);
		commitwork_list_info.setLayoutManager(mLinearLayoutManager);
		commitwork_list_info.addItemDecoration(new MyDecoration(ct, MyDecoration.VERTICAL_LIST));
		myAdapter = new OilCommitWorkAdapter();
		commitwork_list_info.setAdapter(myAdapter);
		myAdapter.setOnItemClickListener(this);
		View headerView = rootView.findViewById(R.id.title_bar);
// 再从该 View 中 findViewById 获取内部的 TextView
		tvTitle = headerView.findViewById(R.id.tv_title);
		tv_time=headerView.findViewById(R.id.main_time);
		tvTitle.setText("交班记录");
		SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
		ly_jiaoBanData.setOnClickListener(this);
		ly_startJiaoBan.setOnClickListener(v -> main.mainActions.but_startJiaoBan(v));
		ly_endJiaoBan.setOnClickListener(v -> main.mainActions.but_endJiaoBan(v));
		rootView.findViewById(R.id.ly_backHome).setOnClickListener(v -> main.main_but_main(v));
		if (!Singleton.getInstance().IsJiaoBan){
			ly_jiaoBanData.setVisibility(View.GONE);
		}
		if (!Singleton.getInstance().IsCreatShift){
			ly_startJiaoBan.setVisibility(View.GONE);
			ly_endJiaoBan.setVisibility(View.GONE);
		}else {
			setUIShow(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsStartJiaoBan,0)==0?false:true);
		}

	}
	public void setUIShow(boolean isJiaoBan){

		Log.e("方法","是否开始交班"+isJiaoBan);
		if (!isJiaoBan){
			ly_startJiaoBan.setVisibility(View.VISIBLE);
			ly_endJiaoBan.setVisibility(View.GONE);

		}
		else {
			isJiaoBan=false;
			ly_startJiaoBan.setVisibility(View.GONE);
			ly_endJiaoBan.setVisibility(View.VISIBLE);

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
	TextView tvTitle,tv_time;
	@Override
	public void onDestroy() {
		// Fragment 实例即将销毁
		super.onDestroy();
		SerialPortEntrity.time.removeOnPropertyChangedCallback(callback);
	}

	public void initTitle() {
		if (main != null) {
		}

	}

	private void initializeAdapter(final boolean sort) {
		main.showProgressDialog();
		Const.isOpen = false;
		ThreadManager.getShortPool().execute(new Runnable() {

			@Override
			public void run() {
				setListener();
				temps = findStorageObject(CommitWork.class, 0, Const.recordNum, sort);
				if (myAdapter == null) {
					myAdapter = new OilCommitWorkAdapter();
				}
				myAdapter.setData(temps);
				temps.clear();
				handler.sendEmptyMessage(0);
			}

		});

	}

	/**
	 * FindAll
	 *
	 * @param clazz
	 * @return List<T>
	 */
	public List<CommitWork> findStorageObject(final Class clazz, int pageIndex,
											  int pageSize, boolean des) {

		temps = new ArrayList<CommitWork>();
		if (temps != null) {

			lists = shiftRepository.findAllSorted(des);
			datasize = lists.size();

			// 读取前20记录数据
			for (int i = pageIndex * pageSize; i < (pageIndex + 1) * pageSize; i++) {

				if (i >= lists.size()) {
					break;
				}
				temps.add(lists.get(i));
			}
		}
		return temps;
	}

	void setListener() {
		commitwork_list_info.setOnScrollListener(new EndLessOnScrollListener(mLinearLayoutManager) {
			@Override
			public void onLoadMore(int currentPage) {
				//Log.i("底部数据", "onLoadMore: 加载到底部 " + currentPage);
				loadMoreData(currentPage);
			}
		});

	}

	/**
	 * 加载更多数据
	 */
	private void loadMoreData(int curPage) {

		List<CommitWork> newCommitWork = findObject(curPage, Const.recordNum);

		myAdapter.addNewsItem(newCommitWork);
		handler.sendEmptyMessage(0);
	}


	Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case 0:
				if (commitwork_list_info.getAdapter() == null) {
					commitwork_list_info.setAdapter(myAdapter);
				}
				myAdapter.notifyDataSetChanged();

				main.closeProgressDialog();
				Const.setIsOpen(true, main);
				break;
			default:
				break;
			}

		};
	};



	private List<CommitWork> findObject(int pageIndex, int pageSize) {
		return shiftRepository.findPage(pageSize,pageIndex,isSort);
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
		if (!Singleton.getInstance().IsCreatShift){
			ly_startJiaoBan.setVisibility(View.GONE);
			ly_startJiaoBan.setVisibility(View.GONE);
		}else {
			setUIShow(SharePrefUtil.getInt(MyApplication.getInstance().getApplicationContext(), Constans.IsStartJiaoBan,0)==0?false:true);
		}

	}



	@Override
	public void onClick(int position) {
		String gsonInfo = myAdapter.getItem(position);

		List<OilWork> stringToListByGson = dbManager.stringToArray(gsonInfo,
				OilWork[].class);

		DetailCommitWorkFragment detailCommitWorkFragment = new DetailCommitWorkFragment(
			   lists.get(position).getId(),stringToListByGson);
		main.switchContent(main.mContent, detailCommitWorkFragment);
	}

	@Override
	public void onLongClick(int position) {
		if (Singleton.getInstance().IsDelShift) {
			initDialog(position);
		}
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
				CommitWork oilWork = (CommitWork)myAdapter
						.getItems(position);
				shiftRepository.delete(oilWork);
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
}
