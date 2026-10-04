package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.GasExplorationAdapter;
import com.sansen.yeweiyi.adapter.OilCanAdapter;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.utils.SharePrefUtil;

public class MainFragment extends BaseFragment implements OilCanAdapter.OnItemClickListener {

    private RecyclerView main_gridview_oilcan;//油罐

    private GridView main_gridview_gas_exploration;//油气探测

    private OilCanAdapter oilAdapter;
//432  854
    private GasExplorationAdapter gasExplorationAdapter;

    public static SparseArray<OilCan> oilCanMaps;

    // [不可达-2026-09-21] 这个字段声明后从没被真正赋值过——唯一赋值代码在下面被注释掉了
    // （西语翻译覆盖率核查时发现）。传给 GasExplorationAdapter.setData() 的永远是 null，
    // 导致 getCount() 恒为 0，Android 框架不会调用 getView()，item_gas_exploration.xml 里的
    // R.string.concentration 因此不可达，不用优先翻。如果油气探测这块要真的接上，需要先找到
    // 数据源填充这个字段（并取消下面 76-77 行注释）。
    private SparseArray gasExplor;

    int curSpCount = 0;

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

//            for (int i = 0, nsize = mOilcanDataManager.getTestData().size(); i < nsize; i++) {
//                int key = mOilcanDataManager.getTestData().keyAt(i);
////                            Log.i("主键", key + "");
//                OilCan oilCan2 = mOilcanDataManager.getTestData().get(key);
//                if (oilCan2 != null) {
//                    OilCanMapper mapper = (OilCanMapper) MyApplication.getInstance().getmDBManager().findObjectByID(
//                            OilCanMapper.class, oilCan2.getOilCanPos());
//                    if (!mapper.isshow()) {
//                        mOilcanDataManager.getTestData().remove(key);
//                    }
//                } else {
//                    mOilcanDataManager.getTestData().remove(key);
//                }
//            }

//            if (SharePrefUtil.getBoolean(ct, SharePrefUtil.TEST_DATA, false)) {
//                oilCanMaps = mOilcanDataManager.getTestData();
//            } else {
//                oilCanMaps = mOilcanDataManager.getData();
//            }
            oilCanMaps = mOilcanDataManager.getData();
//            Log.i("索引大小", oilCanMaps.size() + "");
            oilAdapter.setData(oilCanMaps);

            int targetSpanCount = computeSpanCount(oilCanMaps.size());
            if (targetSpanCount != curSpCount) {
                layoutManager = new GridLayoutManager(ct, targetSpanCount);
                main_gridview_oilcan.setLayoutManager(layoutManager);
                curSpCount = targetSpanCount;
                Log.i("curSpCount", curSpCount + "");
            }

            oilAdapter.notifyDataSetChanged();

//			gasExplorationAdapter.setData(gasExplor);
//			gasExplorationAdapter.notifyDataSetChanged();

            handler.sendEmptyMessageDelayed(1, 3000);
        }
    };

    private GridLayoutManager layoutManager;

    /**
     * 罐列表每行展示个数：
     * 数量 &gt; 4：一行3个；
     * 数量 &lt;= 4：1个居中(1列)，2个一行2个，3个一行3个，4个一行2个(2行)。
     */
    private int computeSpanCount(int count) {
        if (count <= 1) {
            return 1;
        } else if (count == 2) {
            return 2;
        } else if (count == 3) {
            return 3;
        } else if (count == 4) {
            return 2;
        } else {
            return 3;
        }
    }


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

        curSpCount = computeSpanCount(oilCanMaps.size());
        layoutManager = new GridLayoutManager(ct, curSpCount);
        main_gridview_oilcan.setLayoutManager(layoutManager);

        oilAdapter.setOnItemClickListener(this);
        main_gridview_gas_exploration.setAdapter(gasExplorationAdapter);
        main_gridview_oilcan.setAdapter(oilAdapter);
        //main_gridview_oilcan.setOnItemClickListener(this);

        handler.sendEmptyMessageDelayed(1, 1000);

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