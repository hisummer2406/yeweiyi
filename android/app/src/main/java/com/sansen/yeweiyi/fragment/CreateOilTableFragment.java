package com.sansen.yeweiyi.fragment;


import android.annotation.SuppressLint;
import android.content.Intent;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.os.Bundle;
import android.os.Environment;
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
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.GuanBiaoData;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilTable;
import com.sansen.yeweiyi.factory.EllipseTable;
import com.sansen.yeweiyi.factory.ICreateTable;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.listener.ExcelListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.FileSizeUtil;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.ToastUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.lzy.okgo.utils.HttpUtils.runOnUiThread;

@SuppressLint("ValidFragment")
public class CreateOilTableFragment extends BaseFragment {

    private ImageView createtable_iv_icon;
    //罐长
    private EditText createtable_ed_length;
    //直径（罐高）
    private EditText createtable_ed_diameter;
    private TextView createtable_tv_diameter;

    private EditText createtable_ed_headA;

    private LinearLayout createtable_bt_build, createtable_bt_saveoilcan, ly_upper_page, createtable_bt_copyoilcan;

    private LinearLayout createtable_bt_writeoilcan;
    private LinearLayout mly_UprightOil;
    private LinearLayout createtable_bt_chaKan;
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
        createtable_bt_copyoilcan = rootView.findViewById(R.id.createtable_bt_copyoilcan);
        ly_upper_page = rootView.findViewById(R.id.ly_upper_page);
        createtable_bt_writeoilcan = (LinearLayout) rootView.findViewById(R.id.createtable_bt_writeoilcan);
        createtable_bt_chaKan = rootView.findViewById(R.id.createtable_bt_chaKan);
        guanbiao_list_info = rootView.findViewById(R.id.guanbiao_list_info);

        if (tableType == TableType.THETANK) {
            createtable_iv_icon.setImageDrawable(ContextCompat.getDrawable(main, R.drawable.guanbiao_liguan));
            create_iv_guanchang.setText("H:");
            createtable_tv_diameter.setText("R:");
            mly_UprightOil.setVisibility(View.GONE);

        } else if (tableType == TableType.HORIZONTALTANK) {
            createtable_iv_icon.setImageDrawable(ContextCompat.getDrawable(main, R.drawable.guanbiao_woguan));
            create_iv_guanchang.setText(SystemUtils.getRsStr(R.string.oilcan_length));

        } else {
            createtable_iv_icon.setImageDrawable(ContextCompat.getDrawable(main, R.drawable.guanbiao_lifangti));
            createtable_tv_diameter.setText("R:");
            dished_head_a.setText(SystemUtils.getRsStr(R.string.oilcan_height));
        }

        createtable_bt_build.setOnClickListener(this);
        createtable_bt_saveoilcan.setOnClickListener(this);
        createtable_bt_copyoilcan.setOnClickListener(this);
        createtable_bt_writeoilcan.setOnClickListener(this);
        createtable_bt_chaKan.setOnClickListener(this);
        ly_upper_page.setOnClickListener(this);


        guanbiao_list_info = (RecyclerView) rootView.findViewById(R.id.guanbiao_list_info);
        mLinearLayoutManager = new RecyclerViewNoBugLinearLayoutManager(ct);
        guanbiao_list_info.setLayoutManager(mLinearLayoutManager);
        guanbiao_list_info.addItemDecoration(new MyDecoration(ct, MyDecoration.VERTICAL_LIST));
        myAdapter = new GuanBiaoAdapter();
        guanbiao_list_info.setAdapter(myAdapter);
        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText("罐表设置");
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);

    }
    Observable.OnPropertyChangedCallback callback = new Observable.OnPropertyChangedCallback() {
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
    public void clearEdit() {
        createtable_ed_length.setText("");
        createtable_ed_diameter.setText("");
        createtable_ed_headA.setText("");
    }

    public void initTitle() {
        if (main != null) {

            OnClickListener backSetListener = new OnClickListener() {

                @Override
                public void onClick(View v) {

                    if (main.loadingCreateTableFragment == null) {
                        main.loadingCreateTableFragment = new LoadingCreateTableFragment();
                    }
                    main.switchContent(main.mContent, main.loadingCreateTableFragment);
                }
            };


        }

    }

    @Override
    protected void processClick(View v) {
        switch (v.getId()) {
            case R.id.createtable_bt_writeoilcan:

                if (temps!=null&&temps.size()>0){
                    for (int i=0;i<temps.size();i++){
                        OilTable oilTable=new OilTable();
                        oilTable.setHeight(temps.get(i).getHight());
                        oilTable.setVolum(temps.get(i).getRongJi());
                        dataset.add(oilTable);

                    }
                    SaveTable(Environment.getExternalStorageDirectory().getAbsolutePath());
                }


                break;
            case R.id.createtable_bt_build:
                createtable_bt_build(v);
                break;

            case R.id.createtable_bt_saveoilcan:
               // SaveInOIl(Environment.getExternalStorageDirectory().getAbsolutePath());
                Log.e("TAG", "导入罐表");
                readLocation();
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

            case R.id.createtable_bt_copyoilcan:
                if (CheckIsBuildTable()) {
                    createtable_bt_writeoilcan(v);
                }

                break;
        }
    }
    List<OilTable> dataset =new ArrayList<>();
    private void SaveTable(String path) {
        Log.e("TAG 路径", path);
        ExportExcel<OilTable> exportExcel = new ExportExcel<OilTable>();
        String headers[] = { "高度", "容积"};
        DBDataManager db = MyApplication.getInstance().getmDBManager();
      //  List<OilTable> dataset = db.findAllObject(OilInOut.class);
        File file = new File(path);
        if (!file.exists())
            file.mkdir();
        OutputStream out = null;
        try {
            file = new File(path + "/" + "Oil_Table_record" + ".xls");
            out = new FileOutputStream(file);
            exportExcel.exportExcel(headers, dataset,
                    new OilTable().getParams(), out, new ExcelListener() {
                        @Override
                        public void Success(boolean isTrue) {
                            runOnUiThread(() -> {
                                // 主线程操作
                               // ly_ruKu.setVisibility(View.VISIBLE);
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

    public void readLocation() {

        ThreadManager.getLongPool().execute(readRunnable);

    }

    Runnable readRunnable = new Runnable() {
        public void run() {
            String path = "/storage/usbhost1/zhongcheng";
            File file = new File(path);
            if (!SystemUtils.isUsbExit()) {
                path= Environment.getExternalStorageDirectory().getPath()+"/zhongcheng";
            }
            ReadOilCanTable(path);
        }
    };

    Runnable saveRunnable = new Runnable() {
        public void run() {
            String path = "/storage/usbhost1/SanSen";


            if (!SystemUtils.isUsbExit()) {
                path= Environment.getExternalStorageDirectory().getPath()+"/zhongcheng";
            }
            dbManager.SaveOilCanTable(path);
            System.out.println("罐表保存成功");


            // SaveCommitWork();
            // SaveOilStroage();
        }
    };
    /**
     * 从U盘中读取罐表
     */
    private void ReadOilCanTable(String path) {
        int age;
        age = 20;
        System.out.println(age);
        int score = 100;
        System.out.println(score);

        ExportExcel<Object> excel = new ExportExcel<Object>();
        // String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/" + "Oil_Table_record" + ".xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                Log.e("TAG","导入罐表");
                createOilCanTalbe = excel
                        .readExcelOilCanTableContent(is);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setMyAdapter();
                    }
                });

                is.close();
                ToastUtils.showToastLong("Read Success");
            } else {
                ToastUtils.showToastLong(R.string.datamanager_chek_out);
            }

        } catch (Exception e) {
            ToastUtils.showToastLong(e.getMessage());
            //e.printStackTrace();
        }

    }
    private void setMyAdapter (){
        showTemps.clear();
        temps.clear();
        step = 1;
        if (createOilCanTalbe != null) {
            if (createOilCanTalbe.getHeight().length >= 200) {
                Log.e("TAG", createOilCanTalbe.getHeight().length + "-----------");
                step = (int) Math.ceil(createOilCanTalbe.getHeight().length / 200f);
            }
            for (int i = 0; i < createOilCanTalbe.getHeight().length; i++) {

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
        } else if (createtable_ed_diameter.getText().length() < 3) {
            SystemUtils.showRsToast("灌高不能小于10厘米");
        } else if (tableType == TableType.HORIZONTALTANK & createtable_ed_headA.getText().length() < 3) {
            SystemUtils.showRsToast("罐头不能小于10厘米");
        } else {
            L = Double.parseDouble(createtable_ed_length.getText()
                    .toString()) / 1000;
            R1 = Double.parseDouble(createtable_ed_diameter.getText()
                    .toString()) / 2000;
            if (tableType == TableType.HORIZONTALTANK) {
                C1 = Double.parseDouble(createtable_ed_headA.getText()
                        .toString()) / 1000;
            } else if (tableType == TableType.CHANGFANGTI) {
                C1 = Double.parseDouble(createtable_ed_headA.getText()
                        .toString()) / 1000;
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
                createOilCanTalbe = createTable.craeteOilCanTalbe(L, R1, C1, 0);
            } else {
                createOilCanTalbe = createTable.craeteOilCanTalbe(R1 * 2, L);
            }
            createOilCanTalbe.ArrayToString();
            setMyAdapter();
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
                createOilCanTalbe.StringToArray();
                setMyAdapter();
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
