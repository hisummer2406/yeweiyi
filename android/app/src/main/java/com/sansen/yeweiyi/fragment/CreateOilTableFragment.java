package com.sansen.yeweiyi.fragment;


import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.ActivityDialog;
import com.sansen.yeweiyi.adapter.GuanBiaoAdapter;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.GuanBiaoData;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.factory.EllipseTable;
import com.sansen.yeweiyi.factory.ICreateTable;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("ValidFragment")
public class CreateOilTableFragment extends BaseFragment {

    private ImageView createtable_iv_icon;
    //罐长
    private EditText createtable_ed_length;
    //直径（罐高）
    private EditText createtable_ed_diameter;
    private TextView createtable_tv_diameter;

    private EditText createtable_ed_headA;

    private LinearLayout createtable_bt_build, createtable_bt_saveoilcan, ly_upper_page;

    private LinearLayout createtable_bt_writeoilcan;
    private LinearLayout mly_UprightOil;
    private LinearLayout createtable_bt_chaKan;

//	private Button createtable_bt_writeu;

    //private android.widget.TextView createtable_tv_main;

    private OilCanTable createOilCanTalbe;
    private TableType tableType;
    private RecyclerView guanbiao_list_info;
    private LinearLayoutManager mLinearLayoutManager;
    private GuanBiaoAdapter myAdapter;

    public enum TableType {
        HORIZONTALTANK, THETANK, CHANGFANGTI;
    }

    public CreateOilTableFragment(TableType tableType) {
        this.tableType = tableType;
    }

    PopupWindow popupWindow = null;
    private View TextView;
    private android.widget.TextView create_iv_guanchang;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_createtable, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();
//		createtable_tv_main.setMovementMethod(ScrollingMovementMethod
//				.getInstance());

        //temps = findAllObject(TableType.class, 0, Const.recordNum, true);
        //dbManager.findLimit(OilCanTable.class, 0, 20, true);


    }

    @Override
    public void findView() {
        mly_UprightOil = rootView.findViewById(R.id.ly_UprightOil);
        createtable_iv_icon = (ImageView) rootView.findViewById(R.id.createtable_iv_icon);
        createtable_ed_length = (EditText) rootView.findViewById(R.id.createtable_ed_length);
        createtable_tv_diameter = rootView.findViewById(R.id.createtable_tv_diameter);
        createtable_ed_diameter = (EditText) rootView.findViewById(R.id.createtable_ed_diameter);
        create_iv_guanchang = (android.widget.TextView) rootView.findViewById(R.id.create_table_guanchang);
        TextView dished_head_a = (android.widget.TextView) rootView.findViewById(R.id.dished_head_a);
        createtable_ed_headA = (EditText) rootView.findViewById(R.id.createtable_ed_headA);
        createtable_bt_build = (LinearLayout) rootView.findViewById(R.id.createtable_bt_build);
        createtable_bt_saveoilcan = (LinearLayout) rootView.findViewById(R.id.createtable_bt_saveoilcan);
        ly_upper_page = rootView.findViewById(R.id.ly_upper_page);
        createtable_bt_writeoilcan = (LinearLayout) rootView.findViewById(R.id.createtable_bt_writeoilcan);
        createtable_bt_chaKan = rootView.findViewById(R.id.createtable_bt_chaKan);
//		createtable_bt_writeu = (Button) rootView.findViewById(R.id.createtable_bt_writeu);
        //	createtable_tv_main =  (android.widget.TextView) rootView.findViewById(R.id.createtable_tv_main);
        guanbiao_list_info = rootView.findViewById(R.id.guanbiao_list_info);

        if (tableType == TableType.THETANK) {
            //mly_UprightOil.setVisibility(View.GONE);
//			createtable_ed_headA.setVisibility(View.GONE);
//			createtable_ed_headB.setVisibility(View.GONE);
//			dished_head_a.setVisibility(View.GONE);
//			dished_head_b.setVisibility(View.GONE);
            createtable_iv_icon.setImageDrawable(ContextCompat.getDrawable(main, R.drawable.guanbiao_liguan));
            create_iv_guanchang.setText(SystemUtils.getRsStr(R.string.oilcan_height));
            mly_UprightOil.setVisibility(View.GONE);

        } else if (tableType == TableType.HORIZONTALTANK) {
            createtable_iv_icon.setImageDrawable(ContextCompat.getDrawable(main, R.drawable.guanbiao_woguan));
            create_iv_guanchang.setText(SystemUtils.getRsStr(R.string.oilcan_length));
        } else {
            createtable_iv_icon.setImageDrawable(ContextCompat.getDrawable(main, R.drawable.guanbiao_lifangti));
            createtable_tv_diameter.setText("罐宽R");
            dished_head_a.setText(SystemUtils.getRsStr(R.string.oilcan_height));
        }

        createtable_bt_build.setOnClickListener(this);
        createtable_bt_saveoilcan.setOnClickListener(this);
        createtable_bt_writeoilcan.setOnClickListener(this);
        createtable_bt_chaKan.setOnClickListener(this);
        ly_upper_page.setOnClickListener(this);


        guanbiao_list_info = (RecyclerView) rootView.findViewById(R.id.guanbiao_list_info);
        mLinearLayoutManager = new RecyclerViewNoBugLinearLayoutManager(ct);
        guanbiao_list_info.setLayoutManager(mLinearLayoutManager);
        guanbiao_list_info.addItemDecoration(new MyDecoration(ct, MyDecoration.VERTICAL_LIST));
        myAdapter = new GuanBiaoAdapter();
        guanbiao_list_info.setAdapter(myAdapter);

    }

    public void clearEdit() {
        createtable_ed_length.setText("");
        createtable_ed_diameter.setText("");
        createtable_ed_headA.setText("");
    }

    public void initTitle() {
        if (main != null) {


//			main.iv_right.setImageResource(R.drawable.laststep);
            OnClickListener backSetListener = new OnClickListener() {

                @Override
                public void onClick(View v) {

                    if (main.loadingCreateTableFragment == null) {
                        main.loadingCreateTableFragment = new LoadingCreateTableFragment();
                    }
                    main.switchContent(main.mContent, main.loadingCreateTableFragment);
                }
            };
//			main.tv_left.setOnClickListener(backSetListener);
//			main.iv_right.setOnClickListener(backSetListener);

        }

    }

    @Override
    protected void processClick(View v) {
        switch (v.getId()) {
            case R.id.createtable_bt_writeoilcan:
                if (CheckIsBuildTable()) {
                    createtable_bt_writeoilcan(v);
                }
                break;
//		case R.id.createtable_bt_writeu:
//			if (CheckIsBuildTable()) {
//				createtable_bt_writeu(v);
//			}
//			break;
            case R.id.createtable_bt_build:
                createtable_bt_build(v);
                break;

            case R.id.createtable_bt_saveoilcan:
                Log.e("TAG", "导出罐表");
                break;
            case R.id.ly_upper_page:
                if (main.loadingCreateTableFragment == null) {
                    main.loadingCreateTableFragment = new LoadingCreateTableFragment();
                }
                main.switchContent(main.mContent, main.loadingCreateTableFragment);
                break;

            case R.id.createtable_bt_chaKan:

                Intent intent = new Intent(main, ActivityDialog.class);
                startActivityForResult(intent, 1);
                break;
        }
    }


    boolean CheckIsBuildTable() {
        if (createOilCanTalbe != null) {
            return true;
        }
        SystemUtils.showRsToast(R.string.createtable_createbable);
        return false;
    }

    public void createtable_bt_writeoilcan(View v) {
        Intent intent = new Intent(main, ActivityDialog.class);
        startActivityForResult(intent, 0);
    }

    public void createtable_bt_writeu(View v) {

        //dbManager.SaveOilCanTable();
    }

    public void createtable_bt_build(View v) {
        double L = 0, R1 = 0, C1 = 0;
        if (StringUtils.isEmpty(createtable_ed_length.getText().toString())) {
            SystemUtils.showRsToast(R.string.createtable_oillengtg_nonull);
        } else if (createtable_ed_length.getText().length() < 3) {
            SystemUtils.showRsToast("灌长不能小于10厘米");
        } else if (StringUtils.isEmpty(createtable_ed_diameter.getText().toString())) {
            SystemUtils.showRsToast(R.string.createtable__nodiameternull);
        } else if (createtable_ed_diameter.getText().length()<3) {
            SystemUtils.showRsToast("灌高不能小于10厘米");
        } else if (tableType == TableType.HORIZONTALTANK & createtable_ed_headA.getText().length()<3) {
            SystemUtils.showRsToast("罐头不能小于10厘米");
        } else {
            L = Double.parseDouble(createtable_ed_length.getText()
                    .toString())/1000;
            R1 = Double.parseDouble(createtable_ed_diameter.getText()
                    .toString()) /2000;
            if (tableType == TableType.HORIZONTALTANK) {
                C1 = Double.parseDouble(createtable_ed_headA.getText()
                        .toString())/1000;
            } else if (tableType == TableType.CHANGFANGTI) {
                C1 = Double.parseDouble(createtable_ed_headA.getText()
                        .toString())/1000;
            }


            ICreateTable createTable;
            if (tableType == TableType.HORIZONTALTANK) {
                createTable = new EllipseTable();
            } else {
                createTable = new EllipseTable();
            }
            if (tableType == TableType.CHANGFANGTI) {
                createOilCanTalbe = createTable.craeteOilCanTalbe(L, R1 * 2, C1);
            } else if (tableType == TableType.HORIZONTALTANK) {
                createOilCanTalbe = createTable.craeteOilCanTalbe(L,  R1,C1, 0);
            } else {
                createOilCanTalbe = createTable.craeteOilCanTalbe( R1*2,L);
            }


            //createtable_tv_main.setText(createOilCanTalbe.toString());
            createOilCanTalbe.ArrayToString();
            showTemps.clear();
            temps.clear();
            step = 1;
            if (createOilCanTalbe != null) {
                if (createOilCanTalbe.getHeight().length >= 200) {
                    Log.e("TAG", createOilCanTalbe.getHeight().length + "-----------");
                    step = (int) Math.ceil(createOilCanTalbe.getHeight().length / 200f);
                }
                for (int i = 0; i < createOilCanTalbe.getHeight().length; i++) {
                    Log.e("TAG", "ID : " + i + "高度 : " + createOilCanTalbe.getHeight()[i] + " 容积 : " + createOilCanTalbe.getVolum()[i]);
                    temps.add(new GuanBiaoData(i, createOilCanTalbe.getHeight()[i], createOilCanTalbe.getVolum()[i]));

                    if (step == 1) {
                        showTemps.add(new GuanBiaoData(i, createOilCanTalbe.getHeight()[i], createOilCanTalbe.getVolum()[i]));
                    } else {
                        if (step != 0 && i % step == 1) {
                            showTemps.add(new GuanBiaoData(i / step, createOilCanTalbe.getHeight()[i], createOilCanTalbe.getVolum()[i]));

                        }
                    }

                }
            }
            //showTemps=sampleFixedSize(temps,200);
            myAdapter.notifyDataSetChanged();
            initializeAdapter(showTemps);
            //清空已输入的数据
            //clearEdit();
        }
    }

    private int step;

    public static <T> List<T> sampleFixedSize(List<T> source, int targetSize) {
        if (source == null || source.isEmpty()) return new ArrayList<>();
        if (source.size() <= targetSize) return new ArrayList<>(source);

        List<T> result = new ArrayList<>(targetSize);
        double step = (double) source.size() / targetSize;
        for (int i = 0; i < targetSize; i++) {
            int index = (int) (i * step);
            result.add(source.get(index));
        }
        return result;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0) {
            if (resultCode != 0 && createOilCanTalbe != null) {
                resultCode -= 100;
                createOilCanTalbe.setPk_oilCanId(resultCode + 1);
                createOilCanTalbe.setGmt_modified(DateUtils.getYYYMMddHHmmss());
                dbManager.UpdateObject(createOilCanTalbe);

                if ("en".equals(SharePrefUtil.getString(ct, SharePrefUtil.LANGUAGE, "zh"))) {
                    SystemUtils.showRsToast("No." + SystemUtils.getRsStr(R.string.createtable_daorudao) + (resultCode + 1) + SystemUtils.getRsStr(R.string.createtable_tanksucess));
                } else if ("zh".equals(SharePrefUtil.getString(ct, SharePrefUtil.LANGUAGE, "zh"))) {
                    SystemUtils.showRsToast(SystemUtils.getRsStr(R.string.createtable_daorudao) + (resultCode + 1) + SystemUtils.getRsStr(R.string.createtable_tanksucess));
                } else if ("th".equals(SharePrefUtil.getString(ct, SharePrefUtil.LANGUAGE, "zh"))) {
                    SystemUtils.showRsToast(SystemUtils.getRsStr(R.string.createtable_daorudao) + (resultCode + 1) + SystemUtils.getRsStr(R.string.createtable_tanksucess));
                }
            }
        } else if (requestCode == 1) {
            if (resultCode != 0) {
                resultCode -= 100;
                createOilCanTalbe = null;
                createOilCanTalbe = (OilCanTable) dbManager.findAllObject(OilCanTable.class).get(resultCode);
                //   OilCan oil = MyApplication.getInstance().getmOilcanDataManager().getData(1);
                createOilCanTalbe.StringToArray();
                temps.clear();
                step = 1;
                showTemps.clear();
                if (createOilCanTalbe != null) {
                    if (createOilCanTalbe.getHeight().length >= 200) {
                        Log.e("TAG", createOilCanTalbe.getHeight().length + "-----------");
                        step = (int) Math.ceil(createOilCanTalbe.getHeight().length / 200f);
                    }
                    for (int i = 0; i < createOilCanTalbe.getHeight().length; i++) {
                        Log.e("TAG", "ID : " + i + "高度 : " + createOilCanTalbe.getHeight()[i] + " 容积 : " + createOilCanTalbe.getVolum()[i]);
                        temps.add(new GuanBiaoData(i, createOilCanTalbe.getHeight()[i], createOilCanTalbe.getVolum()[i]));

                        if (step == 1) {
                            showTemps.add(new GuanBiaoData(i, createOilCanTalbe.getHeight()[i], createOilCanTalbe.getVolum()[i]));
                        } else {
                            if (step != 0 && i % step == 1) {
                                showTemps.add(new GuanBiaoData(i / step, createOilCanTalbe.getHeight()[i], createOilCanTalbe.getVolum()[i]));

                            }
                        }

                    }
                    myAdapter.notifyDataSetChanged();
                    myAdapter.setData(showTemps);
                }
            }
        }

    }

    void setListener() {
        guanbiao_list_info.setOnScrollListener(new EndLessOnScrollListener(mLinearLayoutManager) {
            @Override
            public void onLoadMore(int currentPage) {
                Log.e("底部数据", "onLoadMore: 加载到底部 " + currentPage);
                //loadMoreData(currentPage);
            }
        });

    }

    /**
     * 加载更多数据
     */
    private void loadMoreData(int curPage) {
        List<GuanBiaoData> newAlarm = findAllObject(GuanBiaoData.class, curPage, Const.recordNum, true);
        myAdapter.addNewsItem(newAlarm);
        handler.sendEmptyMessage(0);

    }

    Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    if (guanbiao_list_info.getAdapter() == null) {
                        guanbiao_list_info.setAdapter(myAdapter);
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
    private List<GuanBiaoData> temps = new ArrayList<>();
    private List<GuanBiaoData> showTemps = new ArrayList<>();

    /**
     * FindAll
     *
     * @return List<T>
     */
    public List<GuanBiaoData> findAllObject(final Class clazz, int pageIndex,
                                            int pageSize, boolean des) {
        List<GuanBiaoData> list = new ArrayList<>();
        return list;
    }

    /**
     * 初始化ListView的适配器
     */
    private void initializeAdapter(List<GuanBiaoData> list) {
        Const.isOpen = false;
        ThreadManager.getShortPool().execute(new Runnable() {

            @Override
            public void run() {
                //	temps = findAllObject(Alarm.class, 0, Const.recordNum, sort);
                setListener();
                if (myAdapter == null) {
                    myAdapter = new GuanBiaoAdapter();
                }
                myAdapter.setData(list);

                list.clear();
                handler.sendEmptyMessage(0);

            }
        });

    }

    public void onReflushData() {
        //	initializeAdapter();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        // TODO Auto-generated method stub
        super.onHiddenChanged(hidden);

        if (hidden) {
            updateData();
        }
    }
}
