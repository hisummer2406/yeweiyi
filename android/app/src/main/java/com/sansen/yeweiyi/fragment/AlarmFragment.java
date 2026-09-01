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
import com.sansen.yeweiyi.adapter.AlarmAdapter;
import com.sansen.yeweiyi.adapter.OutStockAdapter;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.listener.ExcelListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.repository.AlarmRepository;
import com.sansen.yeweiyi.repository.AlarmRepositoryImpl;
import com.sansen.yeweiyi.utils.FileSizeUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

import static com.lzy.okgo.utils.HttpUtils.runOnUiThread;

public class AlarmFragment extends BaseFragment implements OutStockAdapter.OnItemClickListener {
    private String TAG = "AlarmFragment";
    private final AlarmRepository alarmRepository = AlarmRepositoryImpl.getInstance();
    /**
     * 下拉刷新思路
     * 1、显示前20条记录数据，倒序序 按时间
     * 2、当滚动到底部时，直接在加载20条记录到List中刷新ui
     */
    private RecyclerView alarm_list_info;


    private LinearLayoutManager mLinearLayoutManager;
    private AlarmAdapter myAdapter;
    private boolean isSort = true;

    private int datasize; // 模拟数据集的条数
    private List<Alarm> lists = null;// 存放当前的所有数据
    private List<Alarm> temps;
    private LinearLayout ly_baoJing;

    @Override
    protected View initView(LayoutInflater inflater) {

        return inflater.inflate(R.layout.fragment_recode_alarm, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
        initializeAdapter(isSort);
    }

    @Override
    public void findView() {

        alarm_list_info = (RecyclerView) rootView.findViewById(R.id.alarm_list_info);
        mLinearLayoutManager = new RecyclerViewNoBugLinearLayoutManager(ct);
        alarm_list_info.setLayoutManager(mLinearLayoutManager);
        alarm_list_info.addItemDecoration(new MyDecoration(ct, MyDecoration.VERTICAL_LIST));
        myAdapter = new AlarmAdapter();
        myAdapter.setOnItemClickListener(this);
        alarm_list_info.setAdapter(myAdapter);
        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText("报警记录");
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
        ly_baoJing=rootView.findViewById(R.id.ly_baoJing);
        ly_baoJing.setOnClickListener(this);
        rootView.findViewById(R.id.ly_backHome).setOnClickListener(v -> main.main_but_main(v));
        if (!Singleton.getInstance().IsBaoJing){
            ly_baoJing.setVisibility(View.GONE);
        }
    }
    OnPropertyChangedCallback callback = new OnPropertyChangedCallback() {
        @Override
        public void onPropertyChanged(Observable sender, int propertyId) {
            // 如果需要区分是哪个字段，可通过 propertyId 判断
            // 直接获取当前值
            String newValue = ((ObservableField<String>) sender).get();
           // Log.d("监听", "新值：" + newValue);
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

    void setListener() {
        alarm_list_info.setOnScrollListener(new EndLessOnScrollListener(mLinearLayoutManager) {
            @Override
            public void onLoadMore(int currentPage) {
                //Log.i("底部数据", "onLoadMore: 加载到底部 " + currentPage);
                loadMoreData(currentPage);
            }
        });

    }

    public void initTitle() {
        if (main != null) {

        }
    }


    /**
     * 初始化ListView的适配器
     */
    private void initializeAdapter(final boolean sort) {
        Const.isOpen = false;
        ThreadManager.getShortPool().execute(new Runnable() {

            @Override
            public void run() {
                setListener();

                temps = findAllObject(Alarm.class, 0, Const.recordNum, sort);

                if (myAdapter == null) {
                    myAdapter = new AlarmAdapter();
                }
                myAdapter.setData(temps);

                temps.clear();
                handler.sendEmptyMessage(0);

            }
        });

    }

    Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    if (alarm_list_info.getAdapter() == null) {
                        alarm_list_info.setAdapter(myAdapter);
                    }
                    //Log.i(TAG, "handleMessage: 加载数据");
                    myAdapter.notifyDataSetChanged();
                    Const.setIsOpen(true, main);

                    break;
                default:
                    break;
            }

        }

    };

    /**
     * FindAll
     *
     * @return List<T>
     */
    public List<Alarm> findAllObject(final Class clazz, int pageIndex,
                                     int pageSize, boolean des) {
        return alarmRepository.findPage(pageSize,pageIndex,des);
    }

    /**
     * 加载更多数据
     */
    private void loadMoreData(int curPage) {
        List<Alarm> newAlarm =findAllObject(Alarm.class, curPage,Const.recordNum,isSort);
        myAdapter.addNewsItem(newAlarm);
        handler.sendEmptyMessage(0);

    }

    @Override
    protected void processClick(View v) {

        if (v.getId()==R.id.ly_baoJing){
            runOnUiThread(() -> {
                // 主线程操作
                ly_baoJing.setVisibility(View.GONE);
            });
            SaveAlarmOIl(Environment.getExternalStorageDirectory().getAbsolutePath());
        }

    }
    private void SaveAlarmOIl(String path) {

        //path=path+"/" + "Oil_in_record";
        Log.e("TAG 路径",path);
        ExportExcel<Alarm> exportExcel = new ExportExcel<Alarm>();
        String headers[] = { "",SystemUtils.getRsStr(R.string.time),"报警信息", "介质","罐号"};
        // 按 time 倒序取数，与界面列表（isSort=true）顺序保持一致
        List<Alarm> dataset = alarmRepository.findAllSorted(true);
        File file = new File(path);
        if (!file.exists())
            file.mkdir();
        OutputStream out = null;
        try {
            file = new File(path + "/" + "Oil_alarm_record" + ".xls");
            out = new FileOutputStream(file);
            exportExcel.exportExcel(headers, dataset,
                    new Alarm().getParams(), out, new ExcelListener() {
                        @Override
                        public void Success(boolean isTrue) {
                            runOnUiThread(() -> {
                                // 主线程操作
                                ly_baoJing.setVisibility(View.VISIBLE);
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
            onReflushData();
            //main.tv_right.setVisibility(View.VISIBLE);
        } else {
            //main.tv_right.setVisibility(View.INVISIBLE);
        }
    }

    public void onReflushData() {
        initializeAdapter(isSort);
    }


    @Override
    public void onClick(int position) {

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
                Alarm alarm = (Alarm) myAdapter
                        .getItem(position);
                alarmRepository.delete(alarm);
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
    @Override
    public void onLongClick(int position) {
//Log.i(TAG, "onLongClick: 长按"+position);
        if (Singleton.getInstance().IsDelAlarm) {
            initDialog(position);
        }
        else {
            //SystemUtils.showRsToast(R.string.authorization_toast_noauth);
        }
    }
}
