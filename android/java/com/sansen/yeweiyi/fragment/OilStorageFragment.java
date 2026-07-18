package com.sansen.yeweiyi.fragment;

import android.content.Intent;
import android.databinding.Observable;
import android.databinding.Observable.OnPropertyChangedCallback;
import android.databinding.ObservableField;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.OilStorageAdapter;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import java.util.List;

public class OilStorageFragment extends BaseFragment implements
        OilStorageAdapter.OnItemClickListener{

    private RecyclerView storage_list_info;
    private boolean isSort = true;
    private int datasize; // 模拟数据集的条数
    private List<OilSaveInfo> lists = null;// 存放当前的所有数据
    private List<OilSaveInfo> temps;

    private OilStorageAdapter myAdapter;

    private LinearLayoutManager mLinearLayoutManager;


    @Override
    protected View initView(LayoutInflater inflater) {

        return inflater.inflate(R.layout.fragment_recode_storage, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
        initializeAdapter(isSort);
    }

    @Override
    public void findView() {
        storage_list_info = (RecyclerView) rootView
                .findViewById(R.id.storage_list_info);
        mLinearLayoutManager = new RecyclerViewNoBugLinearLayoutManager(ct);
        storage_list_info.setLayoutManager(mLinearLayoutManager);
        storage_list_info.addItemDecoration(new MyDecoration(ct, MyDecoration.VERTICAL_LIST));
        myAdapter = new OilStorageAdapter();
        storage_list_info.setAdapter(myAdapter);
        myAdapter.setOnItemClickListener(this);
        View headerView = rootView.findViewById(R.id.title_bar);

// 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText("出库记录");
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
    }
   OnPropertyChangedCallback callback = new OnPropertyChangedCallback() {
       @Override
       public void onPropertyChanged(Observable sender, int propertyId) {
           // 如果需要区分是哪个字段，可通过 propertyId 判断
           // 直接获取当前值
           String newValue = ((ObservableField<String>) sender).get();
         //  Log.d("监听", "新值：" + newValue);
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
//            main.tv_right.setVisibility(View.VISIBLE);
//            main.tv_filter.setVisibility(View.VISIBLE);
//            main.tv_filter.setText(SystemUtils.getRsStr(R.string.save));
//            main.tv_right.setText(SystemUtils.getRsStr(R.string.record_sort));
//
//            main.iv_right.setVisibility(View.GONE);
//            main.tv_unlogin.setVisibility(View.GONE);
//            main.tv_left.setVisibility(View.GONE);
//
//            main.tv_right.setOnClickListener(new OnClickListener() {
//
//                @Override
//                public void onClick(View v) {
//                    isSort = !isSort;
//                    initializeAdapter(isSort);
//                    mLinearLayoutManager.removeAllViews();
//                }
//            });
//            main.tv_filter.setOnClickListener(new OnClickListener() {
//
//                @Override
//                public void onClick(View v) {
//                    Intent intent = new Intent(main, InputDialogActivity.class);
//                    Bundle bundle = new Bundle();
//                    bundle.putString("title", "保存存油记录");
//                    bundle.putString("msg", "请输入备注信息");
//                    bundle.putBoolean("ispsw", false);
//                    intent.putExtra("inputDialog", bundle);
//                    startActivityForResult(intent, 0);
//                }
//            });
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data != null) {
            String remork = data.getStringExtra("value");
            myApplication.getmSaveOilManager().SaveOilDB(remork);
            SystemUtils.showRsToast(R.string.main_toast_savesuccess);
        } else {
            myApplication.getmSaveOilManager().SaveOilDB("");
            SystemUtils.showRsToast(R.string.main_toast_savesuccess);
        }
        this.onReflushData();
    }

    private void initializeAdapter(final boolean sort) {
        Const.isOpen = false;
        ThreadManager.getShortPool().execute(new Runnable() {

            @Override
            public void run() {
                setListener();
                temps = findStorageObject(OilSaveInfo.class, 0, Const.recordNum, sort);
                if (myAdapter == null) {
                    myAdapter = new OilStorageAdapter();
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
    public List<OilSaveInfo> findStorageObject(final Class clazz,
                                               int pageIndex, int pageSize, boolean des) {
        return dbManager.findLimit(OilSaveInfo.class,pageSize,pageIndex,des);
    }

    void setListener() {
        storage_list_info.setOnScrollListener(new EndLessOnScrollListener(mLinearLayoutManager) {
            @Override
            public void onLoadMore(int currentPage) {
                ////Log.i("底部数据", "onLoadMore: 加载到底部 " + currentPage);
                loadMoreData(currentPage);
            }
        });
    }
    /**
     * 加载更多数据
     */
    private void loadMoreData(int currentPage) {

        List<OilSaveInfo> newOilSaveInfo = findObject(currentPage,
                Const.recordNum);
        myAdapter.addNewsItem(newOilSaveInfo);
        handler.sendEmptyMessage(0);
    }


    Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:

                    if (storage_list_info.getAdapter() == null) {
                        storage_list_info.setAdapter(myAdapter);
                    }
                    myAdapter.notifyDataSetChanged();

//                    storage_list_info
//                            .setOnItemClickListener(OilStorageFragment.this);
                    Const.setIsOpen(true, main);
                    break;
                default:
                    break;
            }

        }

        ;
    };

    @Override
    protected void processClick(View v) {

    }
//
//    @Override
//    public void onItemClick(AdapterView<?> parent, View view, int position,
//                            long id) {
//        MainActivity main = (MainActivity) getActivity();
//        String gsonInfo = myAdapter.getItem(position);
//
//        List<Oil> stringToListByGson = dbManager.stringToArray(gsonInfo,
//                Oil[].class);
//
//        DetailStrageOilFragment detailStrageOilFragment = new DetailStrageOilFragment(
//                stringToListByGson);
//        main.switchContent(main.mContent, detailStrageOilFragment);
//    }


    private List<OilSaveInfo> findObject(int pageIndex, int pageSize) {
        return dbManager.findLimit(OilSaveInfo.class,pageSize,pageIndex,isSort);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        // TODO Auto-generated method stub
        super.onHiddenChanged(hidden);
        if (!hidden) {
            initializeAdapter(isSort);
//            main.tv_filter.setVisibility(View.VISIBLE);
//            main.tv_right.setVisibility(View.VISIBLE);
        } else {
//            main.tv_filter.setVisibility(View.INVISIBLE);
//            main.tv_right.setVisibility(View.INVISIBLE);
        }
    }

    public void onReflushData() {
        initializeAdapter(isSort);
    }

    @Override
    public void onClick(int position) {

    }

    @Override
    public void onLongClick(int position) {

    }
}
