package com.sansen.yeweiyi.activity;

import android.content.Intent;
import android.databinding.ObservableField;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseActivity;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.OilCanMapper;
import com.sansen.yeweiyi.db.OilTest;
import com.sansen.yeweiyi.fragment.AlarmFragment;
import com.sansen.yeweiyi.fragment.BaoBiaoFragment;
import com.sansen.yeweiyi.fragment.BuLuFragment;
import com.sansen.yeweiyi.fragment.ChuanKouFragment;
import com.sansen.yeweiyi.fragment.ColorFragment;
import com.sansen.yeweiyi.fragment.CommitWorkRecortFragment;
import com.sansen.yeweiyi.fragment.InOilFragment;
import com.sansen.yeweiyi.fragment.JiLuFragment;
import com.sansen.yeweiyi.fragment.LoadingCreateTableFragment;
import com.sansen.yeweiyi.fragment.MainFragment;
import com.sansen.yeweiyi.fragment.OilCanFragment;
import com.sansen.yeweiyi.fragment.OilCanSetFragment;
import com.sansen.yeweiyi.fragment.OutStockFragment;
import com.sansen.yeweiyi.fragment.SetBaseFragment;
import com.sansen.yeweiyi.fragment.SetFragment;
import com.sansen.yeweiyi.fragment.SetPawFragment;
import com.sansen.yeweiyi.fragment.SetSeniorFragment;
import com.sansen.yeweiyi.fragment.StockFragment;
import com.sansen.yeweiyi.fragment.ThingsBoardSetFragment;
import com.sansen.yeweiyi.listener.SimpleWinCallback;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.PrintRunable;
import com.sansen.yeweiyi.manager.AlarmManager;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.InOilManager;
import com.sansen.yeweiyi.manager.SocketManager.DataResolveListener;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.AndroidAdjustResizeBugFix;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DateUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.ToastUtils;
import com.sansen.yeweiyi.web.handler.ServerManager;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends BaseActivity implements DataResolveListener {
    public static ObservableField<String> title = new ObservableField<>();
    private static final String TAG = "MainActivity";
    private final int REFLASH_TIME = 2;
    public MainNavigator navigator;
    public MainActions mainActions = new MainActions(this);
    public AlarmCoordinator alarmCoordinator;

    private TextView bar_but_alarm;
    private TextView bar_but_inoil;
    private TextView bar_but_saveoil;
    private TextView bar_but_commitwork;
    public BaseFragment mContent;
    public MainFragment mainFragment;
    public SetFragment setFragment;
    public JiLuFragment jiLuFragment;
    public InOilFragment inOilFragment;
    public OutStockFragment outStockFragment;
    public OilCanSetFragment oilCanSetFragment;
    public LoadingCreateTableFragment loadingCreateTableFragment;
    public SetPawFragment setPawFragment;
    public BaoBiaoFragment baoBiaoFragment;
    public ChuanKouFragment chuanKouFragment;
    public BuLuFragment buLuFragment;
    public OilCanFragment oilCanFragment;
    public StockFragment stockFragment;
    public SetBaseFragment setBaseFragment;
    public ColorFragment colorFragment;
    public SetSeniorFragment setSeniorFragment;
    public ThingsBoardSetFragment thingsBoardSetFragment;
    public CommitWorkRecortFragment commitWorkRecortFragment;
    public TextView tempButton;
    private ServerManager mServerManager;
    public PollingCoordinator pollingCoordinator;
    private RelativeLayout main_root;
    private TextView mbar_tv_title;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE);
        setContentView(R.layout.activity_main);
        initView();
        initData();
        SharePreManager.saveAlarm(false);
        AndroidAdjustResizeBugFix.assistActivity(this);
        main_root.getViewTreeObserver().addOnGlobalLayoutListener(
                mOnGlobalLayoutListener);
        //架起来个本地服务器
        mServerManager = new ServerManager(this);
        mServerManager.register();
        mServerManager.startService();
        updateTestOil();
        Singleton.getInstance().getSetData("");
    }

    Handler handler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case REFLASH_TIME:
                    SerialPortEntrity.time.set(DateUtils.getYYYMMddHHmm());
                    handler.sendEmptyMessageDelayed(REFLASH_TIME, 1000);
                    if (Singleton.getInstance().isTestStatus){
                        setTestModel();
                    }
                    break;
            }

        }

        ;
    };

    private void findView() {

        View subLayout1 = (View) findViewById(R.id.title_bar);
        main_root = (RelativeLayout) findViewById(R.id.main_main_main);
        mbar_tv_title = subLayout1.findViewById(R.id.bar_tv_title);
        // 报警显示
        alarmCoordinator = new AlarmCoordinator(this, (TextView) findViewById(R.id.bar_main_alram));

        FrameLayout fragment_container = findViewById(R.id.fragment_container);

        // 进出油记录
        bar_but_inoil = findViewById(R.id.bar_but_inoil);

        // 存油记录
        bar_but_saveoil = findViewById(R.id.bar_but_saveoil);
        // 交班记录
        bar_but_commitwork = findViewById(R.id.bar_but_commitwork);
        // 报警记录
        bar_but_alarm = findViewById(R.id.bar_but_alarm);
        // 临时按钮 表示当前主页处于哪个页面，并且该按钮不可被点击；初始无高亮（首页无对应可视按钮）
        tempButton = null;

        // 底部导航栏点击绑定（原为 android:onClick 反射绑定，见 openspec/changes/split-main-activity §1）
        findViewById(R.id.btn_jieJing).setOnClickListener(alarmCoordinator::btn_jieJing);
        findViewById(R.id.ly_bar_but_alarm).setOnClickListener(this::bar_but_alarm);
        findViewById(R.id.ly_bar_but_saveoil).setOnClickListener(this::bar_but_saveoil);
        findViewById(R.id.ly_bar_but_inoil).setOnClickListener(this::bar_but_inoil);
        findViewById(R.id.ly_bar_but_commitwork).setOnClickListener(this::bar_but_commitwork);
        findViewById(R.id.btn_kuCunRecord).setOnClickListener(this::btn_kuCunRecord);
        findViewById(R.id.ly_main_but_set).setOnClickListener(mainActions::main_but_set);

        LinearLayout radio_bar = findViewById(R.id.radio_bar);
        pollingCoordinator = new PollingCoordinator();
        pollingCoordinator.start();

        navigator = new MainNavigator(getSupportFragmentManager(), fragment_container, radio_bar);
        dbManager = myApplication.getmDBManager();


    }
    public DBDataManager dbManager;

    private SparseArray<OilCan> map;
    OilCan oilCan;
    private double oilVolume,canAddOilWeight;
    int oilRoate;
    private List<OilTest>oilTests=new ArrayList<>();
    private void setTestModel(){
        oilTests = dbManager.findAllObject(OilTest.class);
        for (OilTest test:oilTests){
                setTest(test.getPk_oilCanId(),test);
        }
    }
    private void setTest(int address, OilTest oilTest) {
        map = MyApplication.getInstance().getmOilcanDataManager().getData();
        if (map != null) {
            for (int i = 0; i < map.size(); i++) {
                oilCan = map.valueAt(i);
                Log.e("TAG","----"+oilCan.getOilCanPos()+"---"+address);
                if (oilCan.getOilCanPos()==address) {
                    oilCan.setOilLevel(BigDecimal.valueOf(oilTest.getHeight()).add(BigDecimal.valueOf(oilCan.getLevelCorrection())).doubleValue());

                    if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.SHOW_VOLUME, true)) {
                        if (oilCan.getHightArray().length > 1) {
                            oilVolume = DataUtils.getLitre(
                                    oilCan.getOilLevel(), oilCan.getHightArray(),
                                    oilCan.getLitreArray());
                        }
                        if (oilCan.getMaxVolume() != 0) {
                            canAddOilWeight = (oilCan.getMaxVolume() * oilCan.getDensity()) - DataUtils.getLitre(oilCan.getOilLevel(), oilCan.getHightArray(), oilCan.getLitreArray()) * oilCan.getDensity();
                            oilCan.setSurPlus(DataUtils.getOilcanSurplus(oilCan));
                            oilCan.setCanAddOilWeight(DataUtils.doubleFoment(oilCan.getSurPlus() * oilCan.getDensity()));
                        }
                    } else {
                        oilVolume = 0.0;
                        oilCan.setCanAddOilWeight(0.0);
                    }
                    oilCan.setOilVolume(DataUtils.doubleFoment(oilVolume));
                    oilCan.setOilWeight(DataUtils.doubleFoment(oilCan
                            .getDensity() * oilCan.getOilVolume()));
                    oilCan.setTemper(BigDecimal.valueOf(oilTest.getTemp()).add(BigDecimal.valueOf(oilCan.getTempCorrection())).doubleValue());
                    oilRoate = (int) ((oilCan.getOilLevel() / oilCan
                            .getMaxHeight()) * 100);

                    oilCan.setOilRatio(oilRoate > 100 ? 100 : oilRoate);
                    oilCan.setLastTime(System.currentTimeMillis());
                   // return true;
                }
            }

        }
    }

    private void setDefaultFragment() {
        if (mainFragment == null) {
            mainFragment = new MainFragment();
            navigator.setDefaultFragment(mainFragment);
            mContent = mainFragment;
        }
        mainFragment.initTitle();
        navigator.setMainButton(true);

    }

    public void switchContent(BaseFragment from, BaseFragment to) {
        if (navigator.switchContent(from, to)) {
            // 部分页面（如 OilCanSetFragment）会在显示期间临时把 Window 的 softInputMode
            // 改成 adjustPan 以避免被软键盘遮挡；这里在切换到任何新页面前统一复位成系统默认值，
            // 不依赖离开页面自己在 onHiddenChanged 里记得还原，避免遗留状态泄漏到下一个页面。
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED);
            mContent = to;
        }
    }

    @Override
    protected void initView() {
        findView();
        initTitleBar();
        setDefaultFragment();
    }

    public void intitConst() {
        Const.recordNum = SharePrefUtil.getInt(ct, "recodNum", 20);
    }

    protected void initData() {
        Window win = this.getWindow();
        win.setCallback(new SimpleWinCallback(win.getCallback()) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    Const.lastDownTime = System.currentTimeMillis();
                    if (!SharePrefUtil.getBoolean(MainActivity.this, SharePrefUtil.LOCK_SCREEN, false)) {
                        //判断是否在休眠中
                        if (SystemUtils.getSystemBrightness(MainActivity.this) == 0) {
                            SystemUtils.saveBrightness(ct.getContentResolver(), SharePrefUtil.getInt(ct, SharePrefUtil.LIGHT_VOLUME, 255));
                            return false;
                        }
                    }
                }
                return super.dispatchTouchEvent(event);
            }
        });
        alarmCoordinator.initAlarm();
        initLocation();

        handler.sendEmptyMessageDelayed(REFLASH_TIME, 1000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, this.REQUEST_CODE);
            } else {
                // 已有权限，可以显示悬浮窗
                ToastUtils.showToast("获取权限成功",3);
            }
        }

    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // [已屏蔽-2026-09-23] 全仓库没有任何地方以 requestCode=1000 发起 startActivityForResult，
        // 这个分支进不来。先注释掉而不是删掉——万一以后要接回打印功能，逻辑还在。
//        if (requestCode == 1000) {
//            if (resultCode != 0) {
//                resultCode -= 100;
//                PrintRunable printRunable = new PrintRunable(resultCode);
//                ThreadManager.getShortPool().execute(printRunable);
//            }
//        }
//        else
        if (requestCode == REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    // 权限已授予
                    ToastUtils.showToast("获取权限成功",3000);
                } else {
                    // 用户拒绝，可提示或禁止功能
                    ToastUtils.showToast("用户拒绝",3000);
                }
            }
        }
    }


    private final int REQUEST_CODE=101;

    int how = 0;

    protected void processClick(View v) {

        if (v.getId() == R.id.bar_iv_icon) {
            how++;
            if (how >= 5) {
                int Logo = SharePrefUtil.getInt(this, SharePrefUtil.VERSIONHOWL, 0);
                if (Logo >= 3) {
                    SharePrefUtil.saveInt(this, SharePrefUtil.VERSIONHOWL, 0);
                } else {
                    SharePrefUtil.saveInt(this, SharePrefUtil.VERSIONHOWL, Logo + 1);
                }
                switch (SharePrefUtil.getInt(this, SharePrefUtil.VERSIONHOWL, 0)) {
                    case 0:
                        v.setBackgroundResource(R.drawable.sansen_logo);
                        how = 0;
                        break;
                    case 1:
                        v.setBackgroundResource(R.drawable.space_image);
                        how = 0;
                        break;
                    case 2:
                        v.setBackgroundResource(R.drawable.zhengxing_logo);
                        how = 0;
                        break;
                }

            }
        }
    }

    private long startTime, currenTime = 0;

    @Override
    public void refreshData(OilCan oilCan, int errorTimesAtParse, double oilLevelAtParse) {
        if (oilCan != null) {
            inOutOilDection(oilCan, errorTimesAtParse, oilLevelAtParse);
            if (Singleton.getInstance().StockTime > 0) {
                currenTime = System.currentTimeMillis() / 1000;
                if (currenTime - startTime > Singleton.getInstance().StockTime) {
                    if (startTime != 0) {
                        saveStock();
                    }
                    startTime = currenTime;

                }

            }
        }
    }


    private void saveStock() {
        myApplication.getmSaveOilManager().SaveOilDB();
    }

    private void inOutOilDection(OilCan oilCan, int errorTimesAtParse, double oilLevelAtParse) {
        // 用解析那一刻的值判断：失败计数不为 0 的帧在断线/重连途中，液位可能是界面清成的 0，
        // 送进去会被判成一次出/入库。中诚每 10 轮问一次长度会让紧跟的一帧被跳过，少一个采样点无妨。
        if (errorTimesAtParse == 0) {
            InOilManager.getInstance().setOil(oilCan.getOilCanPos(), oilLevelAtParse);
        }
        AlarmManager.getInstance().reshOilCan(oilCan);
    }

    public void main_but_main(View v) {
        // 有点多，需要优化
        if (mainFragment == null) {
            mainFragment = new MainFragment();
        }
        switchContent(mContent, mainFragment);
        clearButtonState();
        navigator.setMainButton(true);
    }

    public void btn_kuCunRecord(View v) {
        if (stockFragment == null) {
            stockFragment = new StockFragment();

        } else {
            stockFragment.onReflushData();
        }
        switchContent(mContent, stockFragment);
        switchButtonState(bar_but_saveoil);
        navigator.setMainButton(false);
    }

    public void bar_but_alarm(View v) {
        if (Const.isOpen) {
            AlarmFragment alarmFragment = new AlarmFragment();
            switchContent(mContent, alarmFragment);
        }
        switchButtonState(bar_but_alarm);
        navigator.setMainButton(false);
    }

    public void bar_but_inoil(View v) {
        if (inOilFragment == null) {
            inOilFragment = new InOilFragment();

        } else {
            inOilFragment.onReflushData();
        }
        switchContent(mContent, inOilFragment);
        switchButtonState(bar_but_inoil);
        navigator.setMainButton(false);
    }

    public void bar_but_saveoil(View v) {
        if (outStockFragment == null) {
            outStockFragment = new OutStockFragment();

        } else {
            outStockFragment.onReflushData();
        }
        switchContent(mContent, outStockFragment);
        switchButtonState(bar_but_saveoil);
        navigator.setMainButton(false);
    }

    public void switchButtonState(TextView from) {
        clearButtonState();
        from.setEnabled(false);
        tempButton = from;
    }

    /**
     * 取消当前高亮态但不设新的高亮目标（用于返回主页/设置——这两处没有可见的
     * 底部栏按钮与之对应，见 openspec/changes/split-main-activity §1）。
     * 非 private：MainActions（§4）在密码校验通过后导航到设置页时需要调用。
     */
    public void clearButtonState() {
        if (tempButton != null) {
            tempButton.setEnabled(true);
            tempButton = null;
        }
    }

    public void bar_but_commitwork(View v) {
        commitWorkRecortFragment = new CommitWorkRecortFragment();
        switchContent(mContent, commitWorkRecortFragment);
        switchButtonState(bar_but_commitwork);
        navigator.setMainButton(false);
    }


    @Override
    public void oilcanDismiss(OilCan obj) {

    }

    private boolean mBackEnable = false;
    private boolean mIsBtnBack = false;
    private int rootBottom = Integer.MIN_VALUE;

    private OnGlobalLayoutListener mOnGlobalLayoutListener = new OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            Rect r = new Rect();
            main_root.getGlobalVisibleRect(r);
            // 进入Activity时会布局，第一次调用onGlobalLayout，先记录开始软键盘没有弹出时底部的位置
            if (rootBottom == Integer.MIN_VALUE) {
                rootBottom = r.bottom;
                return;
            }
            // adjustResize，软键盘弹出后高度会变小
            if (r.bottom < rootBottom) {
                if (mBackEnable == true) {
                }
                mBackEnable = false;
            } else {

                if (mBackEnable == false) {
                }
                mBackEnable = true;

                if (mIsBtnBack) {
                    finish();
                }
            }
        }
    };


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mServerManager.unRegister();
        pollingCoordinator.stop();
    }

    private List<String> mAddressList;

    /**
     * Error notify.
     */
    public void serverError(String message) {
    }

    /**
     * Started notify.
     */
    public void serverHasStarted(String ip) {
    }

    /**
     * Stop notify.
     */
    public void serverStop() {
    }

    File file;

    // 出/入库事件的判定与落库已经整体搬到 InOilManager（胶水层）+
    // logic.inout 包（纯状态机）里，方向只在状态机里判定一次；
    // 这里不再需要实现回调接口来重新猜测方向。
    // 详见 openspec/changes/rewrite-stock-inout-detection。

    @Override
    protected void onResume() {
        super.onResume();
        boolean checked = SharePrefUtil.getBoolean(ct, SharePrefUtil.ENABLE_KEY, false);
        if (checked) {
            SystemUtils.showNavigation(ct);
        } else {
            SystemUtils.hideNavigation(ct);
        }
    }

    private static Map<Integer, Integer> testOil = new HashMap<>();

    public static void updateTestOil() {
        List<OilCanMapper> mapper = OilTankRepositoryImpl.getInstance().findAllOilCanMapper();
        if (mapper != null && mapper.size() > 0) {
            for (OilCanMapper oilCanMapper : mapper) {
                testOil.put(oilCanMapper.getProbeId(), oilCanMapper.getOilCanTableId());
            }
        }
    }

    public static int getTestOil(int how) {
        return testOil.get(how) == null ? 0 : testOil.get(Integer.valueOf(how));
    }

}
