package com.sansen.yeweiyi.fragment;


import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.ActivityDialog;
import com.sansen.yeweiyi.adapter.GuanBiaoAdapter;
import com.sansen.yeweiyi.applog.AppLog;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.GuanBiaoData;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.factory.EllipseTable;
import com.sansen.yeweiyi.factory.ICreateTable;
import com.sansen.yeweiyi.listener.EndLessOnScrollListener;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.applog.LogModule;
import com.sansen.yeweiyi.logic.export.RecordExportColumns;
import com.sansen.yeweiyi.logic.oiltable.MultiSheetAssignment;
import com.sansen.yeweiyi.logic.oiltable.OilTableSheet;
import com.sansen.yeweiyi.logic.oiltable.OilTableSheetReader;
import com.sansen.yeweiyi.manager.RecyclerViewNoBugLinearLayoutManager;
import com.sansen.yeweiyi.repository.OilTankRepository;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.KeyboardAdjustHelper;
import com.sansen.yeweiyi.utils.RecordExportUi;
import com.sansen.yeweiyi.utils.StoragePermission;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.ToastUtils;
import com.sansen.yeweiyi.view.MyDecoration;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import kotlin.Pair;

import static com.lzy.okgo.utils.HttpUtils.runOnUiThread;

@SuppressLint("ValidFragment")
public class CreateOilTableFragment extends BaseFragment {

    private final OilTankRepository oilTankRepository = OilTankRepositoryImpl.getInstance();
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
        KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible(rootView,
                createtable_bt_build, createtable_bt_saveoilcan, createtable_bt_copyoilcan,
                createtable_bt_writeoilcan, createtable_bt_chaKan, ly_upper_page);


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
        tvTitle.setText(R.string.oilcantableset);
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
                if (temps == null || temps.isEmpty()) {
                    SystemUtils.showRsToast(R.string.createtable_createbable);
                    break;
                }
                // 同记录页导出：loading + 后台写 /sdcard/Oil_Table_record.xls + 结果提示（权限在 RecordExportUi 内申请）。
                // temps 会被 setMyAdapter 在主线程清空重填，先拷一份给后台线程
                List<GuanBiaoData> rows = new ArrayList<>(temps);
                RecordExportUi.export(main, "Oil_Table_record", RecordExportColumns.OIL_TABLE, () -> rows);
                break;
            case R.id.createtable_bt_build:
                createtable_bt_build(v);
                break;

            case R.id.createtable_bt_saveoilcan:
               // SaveInOIl(Environment.getExternalStorageDirectory().getAbsolutePath());
                Log.e("TAG", "导入罐表");
                // 没有存储权限时先申请，授权后自动接着读 U 盘；授权回来时页面可能已切走
                StoragePermission.ensure(main, () -> {
                    if (isAdded()) readLocation();
                });
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
            oilTankRepository.exportOilCanTable(path);
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

        // String path = "/storage/usbhost1";
        File file = new File(path + "/" + "Oil_Table_record" + ".xls");
        try {
            if (file.exists()) {

                Log.e("TAG","导入罐表");
                List<OilTableSheet> sheets = OilTableSheetReader.read(new FileInputStream(file));
                // 多 sheet：sheet 名称即罐号，确认后批量写入
                if (OilTableSheetReader.isMultiSheet(sheets)) {
                    MultiSheetAssignment assignment = OilTableSheetReader.assignByName(sheets);
                    runOnUiThread(() -> onMultiSheetRead(assignment));
                    return;
                }
                // 单 sheet（或多个 sheet 但全是空的）：原流程，预览后再"复制到"某个罐；取值同原实现（最后一个 sheet）
                createOilCanTalbe = sheets.isEmpty() ? new OilCanTable()
                        : sheets.get(sheets.size() - 1).toOilCanTable(sheets.size());

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setMyAdapter();
                    }
                });

                ToastUtils.showToastLong("Read Success");
            } else {
                ToastUtils.showToastLong(R.string.datamanager_chek_out);
            }

        } catch (Exception e) {
            ToastUtils.showToastLong(e.getMessage());
            //e.printStackTrace();
        }

    }
    /** 多 sheet 导入：有 sheet 名称不合规则整份不导入；否则弹框确认后按罐号逐个写入（写法同"复制到"）。 */
    private void onMultiSheetRead(MultiSheetAssignment assignment) {
        // 读文件在后台线程，回到主线程时页面可能已离开，此时 getResources() 会抛异常
        if (!isAdded()) return;
        if (assignment instanceof MultiSheetAssignment.InvalidName) {
            String sheetName = ((MultiSheetAssignment.InvalidName) assignment).getSheetName();
            ToastUtils.showToastLong(SystemUtils.getRsStr(R.string.createtable_sheet_name_invalid,
                    sheetName, OilTableSheetReader.MAX_TANK_NO));
            return;
        }
        List<Pair<Integer, OilTableSheet>> tanks = ((MultiSheetAssignment.Ok) assignment).getTanks();
        // 调用方已用 isMultiSheet 保证至少一个 sheet 有数据，这里再兜底：空列表时后面的 get(0) 会越界
        if (tanks.isEmpty()) return;
        String tankNos = OilTableSheetReader.joinTankNos(tanks);

        View customView = LayoutInflater.from(main).inflate(R.layout.dialog_custom_alert, null);
        TextView title = customView.findViewById(R.id.tv_alert_title);
        TextView message = customView.findViewById(R.id.tv_alert_message);
        title.setText(R.string.usb_import);
        message.setText(SystemUtils.getRsStr(R.string.createtable_multi_import_confirm, tankNos));
        AlertDialog dialog = new AlertDialog.Builder(main)
                .setView(customView)
                .create();
        customView.findViewById(R.id.btn_cancel).setOnClickListener(view -> dialog.dismiss());
        customView.findViewById(R.id.btn_confirm).setOnClickListener(view -> {
            dialog.dismiss();
            writeTankTables(tanks, tankNos);
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            // 同 AlarmFragment 的确认框：宽度固定、高度随内容
            int width = (int) (540 + 2 * getResources().getDisplayMetrics().density);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void writeTankTables(List<Pair<Integer, OilTableSheet>> tanks, String tankNos) {
        for (Pair<Integer, OilTableSheet> tank : tanks) {
            OilCanTable table = tank.getSecond().toOilCanTable(tank.getFirst());
            table.setGmt_modified(DateUtils.getYYYMMddHHmmss());
            oilTankRepository.updateOilCanTable(table);
        }
        AppLog.w(LogModule.SYSTEM, "罐表多sheet导入", "file", "Oil_Table_record.xls",
                "points", OilTableSheetReader.describePoints(tanks));
        SystemUtils.showRsToast(SystemUtils.getRsStr(R.string.createtable_multi_import_success, tankNos));
        // 列表预览第一个导入的罐，便于核对
        createOilCanTalbe = tanks.get(0).getSecond().toOilCanTable(tanks.get(0).getFirst());
        setMyAdapter();
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
        } else if (tableType == TableType.HORIZONTALTANK & StringUtils.isEmpty(createtable_ed_headA.getText().toString())) {
            // R（封头半径）允许填 0——平封头（没有弧度）是真实存在的罐型，不是"太小"，
            // 之前用 length()<3 卡最小值，跟罐长/罐高的"不能小于10厘米"混在一起是错的。
            // createtable_a_nonull 这个资源之前就存在（设计稿留的），但全仓库零引用，这次接上。
            SystemUtils.showRsToast(R.string.createtable_a_nonull);
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
                oilTankRepository.updateOilCanTable(createOilCanTalbe);

                // 罐号在句子里的位置由 createtable_import_success 资源自己决定，不按语言分支拼字符串。
                SystemUtils.showRsToast(SystemUtils.getRsStr(R.string.createtable_import_success, resultCode + 1));
            }
        } else if (requestCode == 1) {
            if (resultCode != 0) {
                resultCode -= 100;
                createOilCanTalbe = null;
                createOilCanTalbe = oilTankRepository.findAllOilCanTable().get(resultCode);
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
