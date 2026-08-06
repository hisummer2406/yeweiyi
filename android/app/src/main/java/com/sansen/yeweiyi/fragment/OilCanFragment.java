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
            oilcan_tv_zhiliang.setText(DataUtils.doubleFoment(oilCan.getOilVolume() * oilCan.getDensity()) + " 千克");

            oilcan_tv_midu.setText(oilCan.getDensity() + " kg/m³"
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
            oilcan_tv_oiltype.setText(" "+oilCan.getOilCanPos() + "号  " + DataUtils.getOilType()[oilCan.getOilType()]);
            oilcan_tv_oillevel.setText(("毫米".equals(YeWeiUnit) ? DataUtils.doubleFomentStr(oilCan.getOilLevel() ): DataUtils.doubleFomentStr2(oilCan.getOilLevel() / 1000)) + "  " + YeWeiUnit);
            oilcan_tv_oilvolume.setText(("升".equals(RongJiUnit) ? DataUtils.doubleFomentStr(oilCan.getMaxVolume()) : DataUtils.doubleFomentStr2(oilCan.getMaxVolume() / 1000)) + "  " + RongJiUnit);
            oilcan_tv_oilweight.setText(("升".equals(RongJiUnit) ? DataUtils.doubleFomentStr(oilCan.getOilVolume()) : DataUtils.doubleFomentStr2(oilCan.getOilVolume() / 1000)) + "  " + RongJiUnit);
            oilcan_tv_oiladdible.setText(("升".equals(RongJiUnit) ?DataUtils.doubleFomentStr(oilCan.getSurPlus() ): DataUtils.doubleFomentStr2(DataUtils.doubleFoment(oilCan.getSurPlus() ) / 1000)) + "  " + RongJiUnit);
            oilcan_tv_waterlevel.setText(("毫米".equals(YeWeiUnit) ? DataUtils.doubleFomentStr(oilCan.getWaterLevel()) : DataUtils.doubleFomentStr2(oilCan.getWaterLevel() / 1000)) + "  " + YeWeiUnit);
            oilcan_tv_temperature.setText(DataUtils.doubleFomentStr(oilCan.getTemper() )+ " ℃");
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
    }

}
