package com.sansen.yeweiyi.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.UnitPref;
import com.sansen.yeweiyi.view.CustomProgressView;

@SuppressLint("ValidFragment")
public class OilCanFragment extends BaseFragment {

    private CustomProgressView oilcan_view_progress;

    private TextView oilcan_tv_tankno;

    private TextView oilcan_tv_oiltype;

    private TextView oilcan_tv_oillevel;

    private TextView oilcan_tv_oilvolume;

    private TextView oilcan_tv_oilweight;

    private TextView oilcan_tv_oiladdible;

    private TextView oilcan_tv_waterlevel;


    private TextView oilcan_tv_temperature;

    private TextView oilcan_tv_zhiliang;

    private TextView oilcan_tv_midu;

    // 信息列的 label（不是数值），中英文长度差异大，findView() 里会统一量宽度，见 unifyWeightedLabelWidth。
    // xxx_wrap_xxx 是 label 外层那个 0dp+weight 的 LinearLayout——宽度要改在它身上，改 TextView 自己没用
    // （它是被 weight 分配空间的子 View，自身宽度不会让外层多分空间，见 BaseFragment 里的注释）。
//    private TextView oilcan_label_type;
    private View oilcan_label_wrap_type;
    private TextView oilcan_label_capacity;
    private View oilcan_label_wrap_capacity;
    private TextView oilcan_label_level;
    private View oilcan_label_wrap_level;
    private TextView oilcan_label_volume;
    private View oilcan_label_wrap_volume;
    private TextView oilcan_label_empty;
    private View oilcan_label_wrap_empty;
    private TextView oilcan_label_density;
    private View oilcan_label_wrap_density;
    private TextView oilcan_label_weight;
    private View oilcan_label_wrap_weight;
    private TextView oilcan_label_waterlevel;
    private View oilcan_label_wrap_waterlevel;
    private TextView oilcan_label_temp;
    private View oilcan_label_wrap_temp;

    private int oilCanPos;
    private TextView tv_main_return;
    private LinearLayout iv_back;

    private LayoutInflater inflater;

    Handler handler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            setData();
            handler.sendEmptyMessageDelayed(0, 1000);
        }

        ;
    };

    public OilCanFragment(int oilCanPos) {
        this.oilCanPos = oilCanPos;

    }

    @Override
    protected View initView(LayoutInflater inflater) {
        // TODO Auto-generated method stub
        return inflater.inflate(R.layout.fragment_oil_can, null, false);

    }

    @Override
    protected void processClick(View v) {
        if (v.getId() == R.id.tv_main_return) {
            if (main.mainFragment == null) {
                main.mainFragment = new MainFragment();
            }
            main.switchContent(main.mContent, main.mainFragment);
        }

    }

    @Override
    protected void initData(Bundle savedInstanceState) {

        findView();
        setData();
        handler.sendEmptyMessageDelayed(0, 1000);

    }

    @Override
    public void findView() {

        oilcan_view_progress = (CustomProgressView) rootView
                .findViewById(R.id.oilcan_view_progress);
        oilcan_tv_tankno = (TextView) rootView
                .findViewById(R.id.oilcan_tv_tankno);
        oilcan_tv_oiltype = (TextView) rootView
                .findViewById(R.id.oilcan_tv_oiltype);
        oilcan_tv_oillevel = (TextView) rootView
                .findViewById(R.id.oilcan_tv_oillevel);
        oilcan_tv_oilvolume = (TextView) rootView
                .findViewById(R.id.oilcan_tv_oilvolume);
        oilcan_tv_oilweight = (TextView) rootView
                .findViewById(R.id.oilcan_tv_oilweight);
        oilcan_tv_oiladdible = (TextView) rootView
                .findViewById(R.id.oilcan_tv_oiladdible);
        oilcan_tv_waterlevel = (TextView) rootView
                .findViewById(R.id.oilcan_tv_waterlevel);
        oilcan_tv_temperature = (TextView) rootView
                .findViewById(R.id.oilcan_tv_temperature);
        oilcan_tv_zhiliang = (TextView) rootView
                .findViewById(R.id.oilcan_tv_zhiliang);
        oilcan_tv_midu = (TextView) rootView
                .findViewById(R.id.oilcan_tv_midu);
        tv_main_return = rootView.findViewById(R.id.tv_main_return);
        tv_main_return.setOnClickListener(this);
        iv_back = rootView.findViewById(R.id.iv_back);

//        oilcan_label_type = rootView.findViewById(R.id.oilcan_label_type);
        oilcan_label_wrap_type = rootView.findViewById(R.id.oilcan_label_wrap_type);
        oilcan_label_capacity = rootView.findViewById(R.id.oilcan_label_capacity);
        oilcan_label_wrap_capacity = rootView.findViewById(R.id.oilcan_label_wrap_capacity);
        oilcan_label_level = rootView.findViewById(R.id.oilcan_label_level);
        oilcan_label_wrap_level = rootView.findViewById(R.id.oilcan_label_wrap_level);
        oilcan_label_volume = rootView.findViewById(R.id.oilcan_label_volume);
        oilcan_label_wrap_volume = rootView.findViewById(R.id.oilcan_label_wrap_volume);
        oilcan_label_empty = rootView.findViewById(R.id.oilcan_label_empty);
        oilcan_label_wrap_empty = rootView.findViewById(R.id.oilcan_label_wrap_empty);
        oilcan_label_density = rootView.findViewById(R.id.oilcan_label_density);
        oilcan_label_wrap_density = rootView.findViewById(R.id.oilcan_label_wrap_density);
        oilcan_label_weight = rootView.findViewById(R.id.oilcan_label_weight);
        oilcan_label_wrap_weight = rootView.findViewById(R.id.oilcan_label_wrap_weight);
        oilcan_label_waterlevel = rootView.findViewById(R.id.oilcan_label_waterlevel);
        oilcan_label_wrap_waterlevel = rootView.findViewById(R.id.oilcan_label_wrap_waterlevel);
        oilcan_label_temp = rootView.findViewById(R.id.oilcan_label_temp);
        oilcan_label_wrap_temp = rootView.findViewById(R.id.oilcan_label_wrap_temp);
        // label 文案是静态的（只是标签，不随数据刷新），按当前语言实际渲染宽度统一量一次即可。
        // 宽度要设置在 wrap（label 外层 0dp+weight 的 LinearLayout）上，不是 TextView 自己身上。
        //
        // 注意：wrapperViews 里不能带 oilcan_label_wrap_type——fragment_oil_can.xml 里这一行
        // （罐类型行）本身 visibility="gone"，压根不显示，配套的 label TextView 字段在上面也是
        // 注释掉的死代码（// private TextView oilcan_label_type;），从没 findViewById 过。之前
        // 这里把它也塞进了 wrapperViews，导致 wrapperViews 比 labelTextViews 多一个（9 vs 8），
        // 而 unifyWeightedLabelWidth 一开始就有 size 不一致直接 return 的保护判断——这个方法因此
        // 从没真正跑起来过，之前调大 paddingDp 在真机上完全没有效果的根因就是这个，不是数值本身
        // 的问题。
        unifyWeightedLabelWidth(
                java.util.Arrays.asList(oilcan_label_wrap_capacity, oilcan_label_wrap_level,
                        oilcan_label_wrap_volume, oilcan_label_wrap_empty, oilcan_label_wrap_density,
                        oilcan_label_wrap_weight, oilcan_label_wrap_waterlevel, oilcan_label_wrap_temp),
                java.util.Arrays.asList(oilcan_label_capacity, oilcan_label_level,
                        oilcan_label_volume, oilcan_label_empty, oilcan_label_density, oilcan_label_weight,
                        oilcan_label_waterlevel, oilcan_label_temp),
                // 不设宽度上限：maxLines="1"+ellipsize="end" 已经是防止极端文案撑爆布局的兜底，
                // 没必要再叠加一个拍脑袋的固定值——上次就是这个 160dp 的上限比"Water Level"实际
                // 需要的宽度还小，才会被截断/挤压。宽度完全按当前语言的真实测量结果来。
                //
                // paddingDp=4：这里只是给测量出来的列宽留一点缓冲，防止文字贴着列边界；label 和
                // 右边数值列之间看得见的间距不靠这个，是数值那边的 setText 自己在文本前加了一个
                // 空格前缀（见 setData()），跟这个宽度测量机制无关。
                4, 0);

    }

    private String YeWeiUnit, RongJiUnit, ZhiLiangUnit;

    public void setData() {
        OilCan oilCan;
        YeWeiUnit = SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit, "毫米");
        RongJiUnit = SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit, "升");
        ZhiLiangUnit = SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit, "千克");
        if (SharePrefUtil.getBoolean(ct, SharePrefUtil.TEST_DATA, false)) {
            oilCan = mOilcanDataManager.getTestData(oilCanPos);
        } else {
            oilCan = mOilcanDataManager.getData(oilCanPos);
        }

        if (oilCan != null) {
            // 每个数值前面这个空格前缀，是跟左边 label 列之间唯一的视觉间距来源——label 右对齐、
            // 数值左对齐，两列中间没有别的 margin/padding，不加这个空格两列文字会直接贴在一起
            // （尤其英文下更明显）。
            oilcan_tv_zhiliang.setText(" " + DataUtils.doubleFoment(oilCan.getOilVolume() * oilCan.getDensity()) + " " + SystemUtils.getRsStr(R.string.unit_kg));

            oilcan_tv_midu.setText(" " + oilCan.getDensity() + " kg/m³"
            );//+ " 地址：" + oilCan.getProbeAdr()

            if (oilCan.getErrorTimes() < 4) {

                oilcan_view_progress.setCoord(oilCan.getOilRatio(),
                        oilCan.getWaterRatio(), oilCan.getOilColor());
//			oilCan.setErrorTimes(0);
            } else {
                oilcan_view_progress.setCoord(0, 0, oilCan.getOilColor());
                oilCan.setOilLevel(0.0);
                oilCan.setWaterLevel(0.0);
                oilCan.setOilVolume(0.0);
                oilCan.setWaterVolume(0.0);
                oilCan.setOilWeight(0.0);
                oilCan.setCanAddOilWeight(0.0);
                oilCan.setTemper(0.0);
            }
          //  Drawable drawable;
            if (oilCan.getGunType() == 0) {
                //drawable = ContextCompat.getDrawable(main, R.drawable.max_oilcan);
                iv_back.setBackgroundResource(R.drawable.max_oilcan);

            } else {
               // drawable = ContextCompat.getDrawable(main, R.drawable.min_oilcan);
                iv_back.setBackgroundResource(R.drawable.min_oilcan);
            }
            //iv_back.setImageDrawable(drawable);

            oilcan_view_progress.invalidate();
            oilcan_tv_tankno.setText(oilCan.getOilCanPos() + "#");
            oilcan_tv_oiltype.setText(SystemUtils.getRsStr(R.string.oilcan_type_format, oilCan.getOilCanPos(), DataUtils.getOilType()[oilCan.getOilType()]));
            oilcan_tv_oillevel.setText(" " + (UnitPref.Length.parse(YeWeiUnit) == UnitPref.Length.MM ? DataUtils.doubleFomentStr(oilCan.getOilLevel() ): DataUtils.doubleFomentStr2(oilCan.getOilLevel() / 1000)) + "  " + SystemUtils.localizedUnit(YeWeiUnit));
            oilcan_tv_oilvolume.setText(" " + UnitPref.Volume.parse(RongJiUnit).format(oilCan.getMaxVolume()) + "  " + SystemUtils.localizedUnit(RongJiUnit));
            oilcan_tv_oilweight.setText(" " + UnitPref.Volume.parse(RongJiUnit).format(oilCan.getOilVolume()) + "  " + SystemUtils.localizedUnit(RongJiUnit));
            // 提前用 doubleFoment 预处理一次是延续原来 CUBIC 分支就有的写法（对 L/GALLON 分支是
            // 幂等操作，不改变显示结果——doubleFoment 跟 doubleFomentStr 用的是同一套保留1位小数
            // 的 DecimalFormat，只是返回 double 不是 String）。
            oilcan_tv_oiladdible.setText(" " + UnitPref.Volume.parse(RongJiUnit).format(DataUtils.doubleFoment(oilCan.getSurPlus())) + "  " + SystemUtils.localizedUnit(RongJiUnit));
            oilcan_tv_waterlevel.setText(" " + (UnitPref.Length.parse(YeWeiUnit) == UnitPref.Length.MM ? DataUtils.doubleFomentStr(oilCan.getWaterLevel()) : DataUtils.doubleFomentStr2(oilCan.getWaterLevel() / 1000)) + "  " + SystemUtils.localizedUnit(YeWeiUnit));
            oilcan_tv_temperature.setText(" " + DataUtils.doubleFomentStr(oilCan.getTemper() )+ " ℃");
        } else {
            // 罐配置被移除时清空复用页面，避免继续显示上一罐的数据。
            for (TextView value : new TextView[]{oilcan_tv_tankno, oilcan_tv_oiltype,
                    oilcan_tv_oillevel, oilcan_tv_oilvolume, oilcan_tv_oilweight,
                    oilcan_tv_oiladdible, oilcan_tv_waterlevel, oilcan_tv_temperature,
                    oilcan_tv_zhiliang, oilcan_tv_midu}) {
                value.setText("");
            }
            oilcan_view_progress.setCoord(0, 0, 0);
            oilcan_view_progress.invalidate();
        }
    }

    @Override
    public void initTitle() {
        if (main != null) {
            OnClickListener backListener = new OnClickListener() {

                @Override
                public void onClick(View v) {

                    if (main.mainFragment == null) {
                        main.mainFragment = new MainFragment();
                    }
                    main.switchContent(main.mContent, main.mainFragment);

                }
            };

        }
    }

    public void UpdatePostion(int position) {

        oilCanPos = position;
        if (oilcan_tv_tankno != null) {
            setData();
        }
    }

}
