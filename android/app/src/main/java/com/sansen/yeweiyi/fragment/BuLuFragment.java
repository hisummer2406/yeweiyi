package com.sansen.yeweiyi.fragment;

import android.annotation.SuppressLint;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.bigkoo.pickerview.adapter.ArrayWheelAdapter;
import com.contrarywind.view.WheelView;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.CalendarAdapter;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OutStock;
import com.sansen.yeweiyi.logic.thingsboard.StockEventMapper;
import com.sansen.yeweiyi.manager.SocketManager;
import com.sansen.yeweiyi.repository.StockRepository;
import com.sansen.yeweiyi.repository.StockRepositoryImpl;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.DecimalDigitsInputFilter;
import com.sansen.yeweiyi.utils.GsonUtil;
import com.sansen.yeweiyi.utils.KeyboardAdjustHelper;
import com.sansen.yeweiyi.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;


/**
 * 权限管理
 */
@SuppressLint("ValidFragment")
public class BuLuFragment extends BaseFragment implements TextWatcher, AdapterView.OnItemSelectedListener {


    private Spinner sp_in_tank, sp_out_tank;
    private EditText et_in_height, et_in_liter, et_out_height, et_out_liter;
    private LinearLayout ly_saveData,ly_upper_page;
    private TextView tv_out_time, tv_in_time;

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
    private Calendar selectCal = Calendar.getInstance();
    private Calendar currentShowMonth = Calendar.getInstance();
    private List<String> dayDataList = new ArrayList<>();
    private CalendarAdapter calendarAdapter;

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_bu_lu, null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        findView();

    }

    @Override
    public void findView() {
        ly_saveData = rootView.findViewById(R.id.ly_saveData);
        ly_upper_page=rootView.findViewById(R.id.ly_upper_page);
        tv_in_time = rootView.findViewById(R.id.tv_in_time);
        tv_out_time = rootView.findViewById(R.id.tv_out_time);
        sp_in_tank = rootView.findViewById(R.id.sp_in_tank);
        sp_out_tank = rootView.findViewById(R.id.sp_out_tank);
        et_in_height = rootView.findViewById(R.id.et_in_height);
        et_in_liter = rootView.findViewById(R.id.et_in_liter);
        et_out_height = rootView.findViewById(R.id.et_out_height);
        et_out_liter = rootView.findViewById(R.id.et_out_liter);
        InputFilter[] oneDecimalFilter = new InputFilter[]{new DecimalDigitsInputFilter(1)};
        et_in_height.setFilters(oneDecimalFilter);
        et_in_liter.setFilters(oneDecimalFilter);
        et_out_height.setFilters(oneDecimalFilter);
        et_out_liter.setFilters(oneDecimalFilter);
        ly_saveData.setOnClickListener(this);
        ly_upper_page.setOnClickListener(this);
        KeyboardAdjustHelper.hideActionBarWhileKeyboardVisible(rootView, ly_saveData, ly_upper_page);
        tv_out_time.setOnClickListener(this);
        tv_in_time.setOnClickListener(this);
        et_in_height.addTextChangedListener(this);
        et_in_liter.addTextChangedListener(this);
        et_out_height.addTextChangedListener(this);
        et_out_liter.addTextChangedListener(this);
        sp_in_tank.setOnItemSelectedListener(this);
        sp_out_tank.setOnItemSelectedListener(this);
        oilcanArray = getResources().getStringArray(R.array.oilcanArray);
        View headerView = rootView.findViewById(R.id.title_bar);
        // 再从该 View 中 findViewById 获取内部的 TextView
        tvTitle = headerView.findViewById(R.id.tv_title);
        tv_time=headerView.findViewById(R.id.main_time);
        tvTitle.setText("补录记录");
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

    OilCan oilCan = new OilCan();

    private Double getVolume(double hight,int oilId) {
        double oilVolume = 0;
        oilCan = MyApplication.getInstance().getmOilcanDataManager().getData(oilId);

        oilVolume = DataUtils.getLitre(
                        hight, oilCan.getHightArray(),
                        oilCan.getLitreArray());

        return oilVolume;
    }

    private Double getHiget(double volume,int oilId) {
        double hight = 0;
        oilCan = MyApplication.getInstance().getmOilcanDataManager().getData(oilId);

        hight = DataUtils.getHight(
                volume, oilCan.getHightArray(),
                oilCan.getLitreArray());

        return hight;
    }


    public void showPickerDialog(boolean isRuKu) {
        View dialogView = LayoutInflater.from(main).inflate(R.layout.dialog_calendar_time, null);
        // 设置宽高（例如：宽度 MATCH_PARENT，高度 600dp）
        int width = (int) (327 * main.getResources().getDisplayMetrics().density);// ViewGroup.LayoutParams.MATCH_PARENT;
        int height = (int) (439 * main.getResources().getDisplayMetrics().density); // 600dp 转 px
        //dialogView.setLayoutParams(new ViewGroup.LayoutParams(width, height));
        // 控件绑定
        ImageView ivPrev = dialogView.findViewById(R.id.iv_prev_month);
        ImageView ivNext = dialogView.findViewById(R.id.iv_next_month);
        TextView tvMonthTitle = dialogView.findViewById(R.id.tv_month_title);
        GridView gvCalendar = dialogView.findViewById(R.id.gv_calendar);
        WheelView wheelHour = dialogView.findViewById(R.id.wheel_hour);
        WheelView wheelMinute = dialogView.findViewById(R.id.wheel_minute);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        Calendar todayCal = Calendar.getInstance();
        int todayY = todayCal.get(Calendar.YEAR);
        int todayM = todayCal.get(Calendar.MONTH);
        int todayD = todayCal.get(Calendar.DAY_OF_MONTH);
        // 1.初始化日历网格
        calendarAdapter = new CalendarAdapter(main, dayDataList, todayY, todayM, todayD);
// 绑定当前展示月份给适配器，用于日期对比
        calendarAdapter.currentShowMonth = currentShowMonth;
        gvCalendar.setAdapter(calendarAdapter);
        refreshMonthData(tvMonthTitle);
        // 默认选中当前日期
        int defaultDayPos = getDayIndexInGrid(currentShowMonth, selectCal.get(Calendar.DAY_OF_MONTH));
        calendarAdapter.setSelectedIndex(defaultDayPos);

        // 月份左右切换
        // 上个月
        ivPrev.setOnClickListener(v -> {
            currentShowMonth.add(Calendar.MONTH, -1);
            // 同步给适配器
            calendarAdapter.currentShowMonth = currentShowMonth;
            refreshMonthData(tvMonthTitle);
        });
// 下个月
        ivNext.setOnClickListener(v -> {
            currentShowMonth.add(Calendar.MONTH, 1);
            calendarAdapter.currentShowMonth = currentShowMonth;
            refreshMonthData(tvMonthTitle);
        });

        // 日历格子点击事件
        gvCalendar.setOnItemClickListener((parent, view, position, id) -> {
            String dayStr = dayDataList.get(position);
            if ("".equals(dayStr)) return;
            int day = Integer.parseInt(dayStr);

            // 拿到当前格子日期
            int gridY = currentShowMonth.get(Calendar.YEAR);
            int gridM = currentShowMonth.get(Calendar.MONTH);
            // 今日
            Calendar today = Calendar.getInstance();
            int tY = today.get(Calendar.YEAR);
            int tM = today.get(Calendar.MONTH);
            int tD = today.get(Calendar.DAY_OF_MONTH);

            // 判断是否是未来日期，是则直接return，不执行选中逻辑
            boolean isFuture = false;
            if (gridY > tY) isFuture = true;
            else if (gridY == tY && gridM > tM) isFuture = true;
            else if (gridY == tY && gridM == tM && day > tD) isFuture = true;

            if (isFuture) return;

            // 正常日期才赋值选中
            selectCal.set(gridY, gridM, day);
            calendarAdapter.setSelectedIndex(position);
        });


        // 2.初始化小时滚轮 00~23
        ArrayList<String> hourList = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            hourList.add(String.format("%02d", i));
        }
        wheelHour.setAdapter(new ArrayWheelAdapter<>(hourList));
        wheelHour.setCyclic(true);
        wheelHour.setCurrentItem(selectCal.get(Calendar.HOUR_OF_DAY));

        // 3.初始化分钟滚轮 00~59
        ArrayList<String> minList = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            minList.add(String.format("%02d", i));
        }
        wheelMinute.setAdapter(new ArrayWheelAdapter<>(minList));
        wheelMinute.setCyclic(true);
        wheelMinute.setCurrentItem(selectCal.get(Calendar.MINUTE));

        // 弹窗构建
        AlertDialog dialog = new AlertDialog.Builder(main)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            int hour = wheelHour.getCurrentItem();
            int minute = wheelMinute.getCurrentItem();
            selectCal.set(Calendar.HOUR_OF_DAY, hour);
            selectCal.set(Calendar.MINUTE, minute);
            String resultTime = sdf.format(selectCal.getTime());
            // 此处回调完整时间 resultTime
            //Log.i("TAG", "时间:" + resultTime);
            if (isRuKu) {
                tv_in_time.setText(resultTime);
                InTime = resultTime;
            } else {
                tv_out_time.setText(resultTime);
                OutTime = resultTime;
            }
            dialog.dismiss();
        });
        dialog.show();
        // ---------- 再设置窗口大小 ----------
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (327 * main.getResources().getDisplayMetrics().density),   // 宽度
                    (int) (439 * main.getResources().getDisplayMetrics().density)    // 高度
            );
            // 可选：设置背景透明（若需去除默认白色圆角背景）
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /**
     * 刷新当月日期数据+更新顶部年月标题
     */
    private void refreshMonthData(TextView titleTv) {
        dayDataList.clear();
        Calendar cal = (Calendar) currentShowMonth.clone();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH);
        // 当月第一天
        Calendar firstDay = Calendar.getInstance();
        firstDay.set(year, month, 1);
        int firstWeek = firstDay.get(Calendar.DAY_OF_WEEK) - 1;
        // 当月最大天数
        int maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        // 前置空白格子（上月日期占位，灰色）
        for (int i = 0; i < firstWeek; i++) dayDataList.add("");
        // 填充当月日期
        for (int i = 1; i <= maxDay; i++) dayDataList.add(String.valueOf(i));
        // 补齐7列一行
        int remain = 7 - (dayDataList.size() % 7);
        if (remain != 7) {
            for (int i = 0; i < remain; i++) dayDataList.add("");
        }
        calendarAdapter.notifyDataSetChanged();
        // 更新标题
        titleTv.setText(year + "年" + (month + 1) + "月");
    }

    /**
     * 根据日期计算在GridView中的下标
     */
    private int getDayIndexInGrid(Calendar monthCal, int targetDay) {
        Calendar firstDay = Calendar.getInstance();
        firstDay.set(monthCal.get(Calendar.YEAR), monthCal.get(Calendar.MONTH), 1);
        int weekOffset = firstDay.get(Calendar.DAY_OF_WEEK) - 1;
        return weekOffset + targetDay - 1;
    }


    String[] oilcanArray;

    @Override
    public void initTitle() {

        if (main != null) {
            OnClickListener backSetListener = new OnClickListener() {

                @Override
                public void onClick(View v) {

                    if (main.setFragment == null) {
                        main.setFragment = new SetFragment();
                    }
                    main.switchContent(main.mContent, main.setFragment);

                }
            };
        }
    }

    @Override
    public void onReflushData() {


    }


    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }

    private double beginInHight, endInVoulme;
    // 原来只有 OutTime 有 "" 默认值，InTime 保持 null——如果用户填了入库高度/升数
    // 但没点开时间选择器就点保存，下面 !InTime.isEmpty() 会直接空指针崩溃。
    private String InTime = "", OutTime = "";
    private double beginOutHight, endOutVoulme;

    @Override
    public void afterTextChanged(Editable editable) {
        String text = editable.toString();
        // 允许一位小数后，"." 或 "-" 单独输入时还不是合法数字，先不解析，等用户继续输入
        if (!text.isEmpty() && !".".equals(text) && !"-".equals(text)) {
            try {
                if (et_in_height.hasFocus()) {

                    beginInHight = Double.parseDouble(text);
                } else if (et_in_liter.hasFocus()) {
                    endInVoulme = Double.parseDouble(text);

                } else if (et_out_height.hasFocus()) {

                    beginOutHight = Double.parseDouble(text);
                } else if (et_out_liter.hasFocus()) {

                    endOutVoulme = Double.parseDouble(text);
                }
            } catch (NumberFormatException e) {
                Log.e("BuLuFragment", "afterTextChanged 解析失败: " + text, e);
            }

        }


    }

    private int inOilId, outOilId = -1;

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        switch (adapterView.getId()) {
            case R.id.sp_in_tank:

                inOilId = i + 1;
                break;
            case R.id.sp_out_tank:
                outOilId = i + 1;
                break;


        }
    }

    OilInOut oilIn = new OilInOut();
    OutStock outStock = new OutStock();

    @Override
    protected void processClick(View v) {
        switch (v.getId()) {
            case R.id.ly_saveData:
                v.setEnabled(false);
                // 原来是 if/else if：两个区块都填了的话点一次保存，出库那份数据会被静默丢弃，
                // 没有任何提示。改成两个独立 if，两个区块只要各自填完整就都落库，互不影响。
                boolean savedAny = false;
                if (inOilId != -1 && beginInHight != 0 && endInVoulme != 0 && !InTime.isEmpty()) {
                    //Log.i("TAG", "点击了保存数据");
                    if (MyApplication.getInstance().getmOilcanDataManager().getData(inOilId)==null){
                        ToastUtils.showToast("请设置罐表",3);

                    }
                    else {
                        oilIn.setBeginHeigth(beginInHight);
                        oilIn.setBeginVolum(getVolume(beginInHight,inOilId));
                        oilIn.setEndVolum((oilIn.getBeginVolum() + endInVoulme));
                        oilIn.setEndHeigth(getHiget(oilIn.getEndVolum(),inOilId));
                        oilIn.setTime(InTime);
                        oilIn.setOilId(inOilId);
                        oilIn.setSource(StockEventMapper.SOURCE_MANUAL);
                        saveInOil(oilIn);
                        reportManualStockEvent(true, inOilId, InTime, beginInHight, oilIn.getEndHeigth(),
                                oilIn.getBeginVolum(), oilIn.getEndVolum());
                        savedAny = true;
                    }

                }
                if (outOilId != -1 && beginOutHight != 0 && endOutVoulme != 0 && !OutTime.isEmpty()) {
                    //Log.i("TAG", "点击了保存数据");
                    if (MyApplication.getInstance().getmOilcanDataManager().getData(outOilId)==null){
                        ToastUtils.showToast("请设置罐表",3);
                    }
                    else {
                        outStock.setBeginHeigth(beginOutHight);
                        outStock.setBeginVolum(getVolume(beginOutHight,outOilId));
                        outStock.setEndVolum((outStock.getBeginVolum() - endOutVoulme));
                        outStock.setEndHeigth(getHiget(outStock.getEndVolum(),outOilId));
                        outStock.setTime(OutTime);
                        outStock.setOilId(outOilId);
                        outStock.setSource(StockEventMapper.SOURCE_MANUAL);
                        saveOutOil(outStock);
                        reportManualStockEvent(false, outOilId, OutTime, beginOutHight, outStock.getEndHeigth(),
                                outStock.getBeginVolum(), outStock.getEndVolum());
                        savedAny = true;
                    }

                }
                v.postDelayed(() -> v.setEnabled(true), 1000);
                if (savedAny) {
                    showWindDialog(3000);
                }

                break;

            case R.id.tv_in_time:
                showPickerDialog(true);
                //Log.i("TAG", "选择时间");
                break;
            case R.id.tv_out_time:
                showPickerDialog(false);
                //Log.i("TAG", "选择时间");
                break;
            case R.id.ly_upper_page:
                if (main.setSeniorFragment == null) {
                    main.setSeniorFragment = new SetSeniorFragment();
                }
                main.switchContent(main.mContent, main.setSeniorFragment);
                break;

        }

    }

    private final StockRepository stockRepository = StockRepositoryImpl.getInstance();

    void saveInOil(OilInOut oilInOut) {

        OilInOut inOut = stockRepository.findLastIn();
        stockRepository.saveInRecord(oilInOut);
    }
    void saveOutOil(OutStock outStock) {

        OutStock Out = stockRepository.findLastOut();
        stockRepository.saveOutRecord(outStock);
    }

    /**
     * 补录事件也复用 InOilManager.handleEvent 那条 stockEvent 上报通道，不然对云端/小程序永远
     * 不可见（zhongchengyeweiyi#42 待办2）。跟自动检测不同，补录时设备处于断电/故障状态，没有
     * 水位/温度/介质/重量这些实时快照——DetailInoutRecord 对应字段留 Java 默认值（0/null），
     * 不伪造数据；beginTime/endTime 都用同一个用户填的事件时间，因为补录只有一个时刻，不像
     * 自动检测那样有"开始变化"到"结束变化"的过程。
     */
    private void reportManualStockEvent(boolean isIn, int tankId, String time,
                                         double beginHeightMm, double endHeightMm,
                                         double beginVolumeL, double endVolumeL) {
        DetailInoutRecord record = new DetailInoutRecord();
        record.setOilcanId(tankId);
        record.setBeginTime(time);
        record.setEndTime(time);
        record.setBeginHeith(beginHeightMm);
        record.setEndHeight(endHeightMm);
        record.setBeginVolume(beginVolumeL);
        record.setEndVolume(endVolumeL);
        record.setSaleVolume(endVolumeL - beginVolumeL);
        record.setSource(StockEventMapper.SOURCE_MANUAL);
        String stockEventJson = GsonUtil.toJsonStr(StockEventMapper.build(record, isIn));
        SocketManager.getInstance(MyApplication.getInstance()).sendUnloadOil(stockEventJson);
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }
}
