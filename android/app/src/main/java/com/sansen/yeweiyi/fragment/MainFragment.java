package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.GasExplorationAdapter;
import com.sansen.yeweiyi.adapter.OilCanAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.utils.SharePrefUtil;

public class MainFragment extends BaseFragment implements OilCanAdapter.OnItemClickListener {

    private RecyclerView main_gridview_oilcan;//油罐

    private GridView main_gridview_gas_exploration;//油气探测

    private OilCanAdapter oilAdapter;
//432  854
    private GasExplorationAdapter gasExplorationAdapter;

    public static SparseArray<OilCan> oilCanMaps;

    private SparseArray gasExplor;

    int curSpCount = 0;

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            for (int i = 0, nsize = mOilcanDataManager.getTestData().size(); i < nsize; i++) {
                int key = mOilcanDataManager.getTestData().keyAt(i);
//                            Log.i("主键", key + "");
                OilCan oilCan2 = mOilcanDataManager.getTestData().get(key);
                if (oilCan2 != null) {
                    OilCanMapper mapper = (OilCanMapper) MyApplication.getInstance().getmDBManager().findObjectByID(
                            OilCanMapper.class, oilCan2.getOilCanPos());
                    if (!mapper.isshow()) {
                        mOilcanDataManager.getTestData().remove(key);
                    }
                } else {
                    mOilcanDataManager.getTestData().remove(key);
                }
            }

            if (SharePrefUtil.getBoolean(ct, SharePrefUtil.TEST_DATA, false)) {
                oilCanMaps = mOilcanDataManager.getTestData();
            } else {
                oilCanMaps = mOilcanDataManager.getData();
            }
//            Log.i("索引大小", oilCanMaps.size() + "");
            oilAdapter.setData(oilCanMaps);
            if (oilCanMaps.size() == 1 && curSpCount != 1) {
                layoutManager = new GridLayoutManager(ct, 1);
                main_gridview_oilcan.setLayoutManager(layoutManager);
                curSpCount = 1;
//                Log.i("curSpCount", curSpCount + "");
            } else if (oilCanMaps.size() <= 4 && oilCanMaps.size() != 1 && curSpCount != 2) {
                layoutManager = new GridLayoutManager(ct, 2);
                main_gridview_oilcan.setLayoutManager(layoutManager);
                curSpCount = 2;
//                Log.i("curSpCount", curSpCount + "");
            } else if (oilCanMaps.size() > 4 && curSpCount != 3) {
                layoutManager = new GridLayoutManager(ct, 3);
                main_gridview_oilcan.setLayoutManager(layoutManager);
                curSpCount = 3;
//                Log.i("curSpCount", curSpCount + "");
            }


            oilAdapter.notifyDataSetChanged();

//			gasExplorationAdapter.setData(gasExplor);
//			gasExplorationAdapter.notifyDataSetChanged();

            handler.sendEmptyMessageDelayed(1, 3000);
        }
    };

    private GridLayoutManager layoutManager;


    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_main, null, false);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
        if (SharePrefUtil.getBoolean(ct, SharePrefUtil.TEST_DATA, false)) {

            oilCanMaps = mOilcanDataManager.getTestData();
        } else {
            oilCanMaps = mOilcanDataManager.getData();
        }


        oilAdapter = new OilCanAdapter(ct);

        gasExplorationAdapter = new GasExplorationAdapter(ct);

        oilAdapter.setData(oilCanMaps);
        gasExplorationAdapter.setData(gasExplor);

        layoutManager = new GridLayoutManager(ct, 3);
        main_gridview_oilcan.setLayoutManager(layoutManager);

        oilAdapter.setOnItemClickListener(this);
        main_gridview_gas_exploration.setAdapter(gasExplorationAdapter);
        main_gridview_oilcan.setAdapter(oilAdapter);
        //main_gridview_oilcan.setOnItemClickListener(this);

        handler.sendEmptyMessageDelayed(1, 3000);

    }
    @Override
    public void findView() {
        main_gridview_oilcan = (RecyclerView) rootView
                .findViewById(R.id.main_gridview_oilcan);
        main_gridview_gas_exploration = (GridView) rootView
                .findViewById(R.id.main_gridview_gas_exploration);
    }

    @Override
    public void initTitle() {
        if (main != null) {

//            main.tv_filter.setVisibility(View.INVISIBLE);
//            main.tv_right.setVisibility(View.INVISIBLE);
//            main.tv_unlogin.setVisibility(View.VISIBLE);
//            main.iv_right.setVisibility(View.VISIBLE);
//            main.tv_left.setVisibility(View.VISIBLE);
//            main.iv_right.setImageResource(R.drawable.sansen_user_logo);
//
//            if (myApplication.getUser() != null) {
//                main.tv_left.setText(myApplication.getUser().getId());
//            }
//
//            main.tv_left.setOnClickListener(getUserListener());
//            main.iv_right.setOnClickListener(getUserListener());
   //         main.tv_unlogin.setOnClickListener(this);
        }
    }

    @Override
    protected void processClick(View v) {
        switch (v.getId()) {
             }
    }


    @Override
    public void onClick(int position) {
        if (main.oilCanFragment != null) {

            main.oilCanFragment.UpdatePostion(position + 1);
        } else {

            main.oilCanFragment = new OilCanFragment(position + 1);
        }

        main.switchContent(main.mContent, main.oilCanFragment);
    }

    @Override
    public void onLongClick(int position) {

    }
}