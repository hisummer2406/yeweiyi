package com.sansen.yeweiyi.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.utils.Constans;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.UnitPref;


public class MinOilCanView extends View {
    private Context ct;
    private OilCan oilCan;
    private Bitmap src;
    private Paint mBitPaint;
    private Paint mTextPaint;
    private Resources mResource;
    private int mTotalWidth;
    private int mTotalHeight;
    private Rect mSrcRect, mDestRect;
    private int mBitWidth;
    private int mBitHeight;
    private String TAG = "MinOilCanView";
    private Rect mOilProRect;
    private Rect mWaterProRect;
    private int defualtColor;
    private int tagColor;

    private long temp = Const.customOilcan;

    float proLeftRoait = (float) 0.13538212;//进度条 左
    float proRightRoait = (float) 0.24023305;//进度条 右
    //float proTopRoait = (float) 0.262340426;//进度条 上
    volatile float proTopRoait = (float) 0.222340426;
    float proBootomRoait = (float) 0.94829786;//(float) 0.94829786;//进度条 下
    float textLeft = (float) 0.50471493;//具体数据的值
    float textID = (float) 0;//油罐id

    // [不可达-2026-09-23] content[0]（oil_type_language）被算出来放进数组，但 onDraw() 里
    // i==0 那一轮画的是罐号徽标（oilcan_short_label_format），画完直接 continue，不会走到下面
    // 画 content[i] 的通用逻辑——content[0] 因此从未被绘制到屏幕上（用户凭印象指出、这里代码复核
    // 确认属实）。oil_type_language 不用优先翻。
    // [已屏蔽-2026-09-23] oil_type_language 已删除（content[0] 不可达，见上方注释），用空串占位保持编译。
    String[] content = {/* SystemUtils.getRsStr(R.string.oil_type_language) */ "", SystemUtils.getRsStr(R.string.oil_level_language),
            SystemUtils.getRsStr(R.string.oil_volume_language),SystemUtils.getRsStr(R.string.water_weight_language),
            SystemUtils.getRsStr(R.string.temperature_language), SystemUtils.getRsStr(R.string.oil_weight_language)};
    private int top;
    private int right;
    private int bootm;

    int [] arrIndex;
    public MinOilCanView(Context context) {
        super(context);
    }

    private void initPant() {
        mBitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBitPaint.setFilterBitmap(true);
        mBitPaint.setDither(true);
        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setFilterBitmap(true);
        mTextPaint.setColor(tagColor);
    }
    private int GunType=0;

    private void initBitmap() {
        src = BitmapFactory.decodeResource(mResource, R.drawable.sansen_oilcan);


        mBitWidth = src.getWidth();
        mBitHeight = src.getHeight();

    }

    public MinOilCanView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.ct = context;
        mResource = getResources();
        defualtColor = getResources().getColor(R.color.main_font_vlue);
        tagColor = getResources().getColor(R.color.main_font_tag);
        initBitmap();
        initPant();
        initData();
    }

    private void initData() {
        arrIndex = (int[]) SharePrefUtil.getObj(ct,SharePrefUtil.MIN_ITEM_SELECT);
        if (arrIndex!=null) {
            String[] all = ct.getResources().getStringArray(R.array.oilcanItem);
            for (int i = 0; i < arrIndex.length; i++) {
            content[i] = all[arrIndex[i]];
            }
        }else{
            arrIndex =new int[]{0,1,2,3,7,5};
        }
    }

    public MinOilCanView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        //Log.i(TAG, "MinOilCanView: 3");

    }

    public void setOilCan(OilCan oilCan) {
        this.oilCan = oilCan;
        if (oilCan!=null&&GunType!=oilCan.getGunType()){
            GunType=oilCan.getGunType();
            if (GunType==1){
               // proTopRoait = (float) 0.222340426;
               // proTopRoait = (float) 0.562340426;
                src = BitmapFactory.decodeResource(mResource, R.drawable.sansen_oilcan2);
            }
            else {
              //  proTopRoait = (float)0.422340426;
                src = BitmapFactory.decodeResource(mResource, R.drawable.sansen_oilcan);
            }

        }
    }

    int oilRatio;
    int waterRatio;
    float oilTopValue;
    float waterTopValue;
    float textSize;
    long beginTIme;
    int left;

    int account=0;
    @SuppressLint("ResourceAsColor")
    @Override
    protected void onDraw(Canvas canvas) {
        //Log.i(TAG, "onDraw: "+mBitWidth+"      "+mBitHeight+"   "+mTotalWidth+"   "+mTotalHeight);

        super.onDraw(canvas);

        if (temp<Const.customOilcan){
            initData();
            temp =Const.customOilcan;
            Log.i(TAG, temp+"");
        }

        try {
            beginTIme = System.currentTimeMillis();
            if (oilCan != null) {

//            Log.i("显示页面",oilCan.getOilCanPos()+"  "+(System.currentTimeMillis() - oilCan.getLastTime() < MyApplication.getInstance().getmOilcanDataManager()
//                    .getData().size() * 1000)+"");

                switch (oilCan.getProbeType()) {
                        case 0:
                        Error(4, 2000);
                        break;
                    case 1:
                        Error(4, 2000);
                        break;
                    case 2:
                        Error(4, 2000);
                        break;
                    case 3:
                        Error(4, 2000);
                        break;
                    case 5:
                        Error(20, 4000);
                        break;
                    case 6:
                        Error(4, 2000);
                        break;
                    default:
                        Error(4,200);
                        break;
                }
                mSrcRect = new Rect(0, 0, mBitWidth, mBitHeight);
                mDestRect = new Rect(0, 0, mTotalWidth, mTotalHeight);
                canvas.drawBitmap(src, mSrcRect, mDestRect, mBitPaint);
                mBitPaint.setColor(Color.BLUE);
                // //Log.i(TAG, "onDraw: 比例："+"左："+(float)374/mTotalWidth+" 右:"+(float)583/mTotalWidth+"上:"+(float)108/mTotalHeight+"下"+(float)(mTotalHeight-40)/mTotalHeight);
                //比例值
                oilRatio = oilCan.getOilRatio() > 100 ? 100 : oilCan.getOilRatio();

                waterRatio = oilCan.getWaterRatio() > 100 ? 100 : oilCan.getWaterRatio();
//        int oilRatio =60;
//        int waterRatio=20;
                //Log.i(TAG, "onDraw: 油比例"+oilRatio+"水比例"+waterRatio);


//        Log.i(TAG, "onDraw: oilTopValue ="+(bootm - top));

               // mOilProRect = new Rect(left, (int) ((int) top+ oilTopValue), right,bootm);
                if (GunType==1){
                    //立罐
                    oilTopValue = (bootm - top) * (1 - (float) oilRatio / 100);
                    mOilProRect = new Rect(left, (int) ((int) (mTotalHeight * 0.222340426f) + oilTopValue), right, bootm);

                }
                else {
                    oilTopValue = (bootm - (int) (mTotalHeight * 0.392340426f)) * (1 - (float) oilRatio / 100);

                    mOilProRect = new Rect(left, (int) ((int) (mTotalHeight * 0.392340426f) + oilTopValue), right, (int) (mTotalHeight * 0.94829786));
                }


                //mOilProRect = new Rect(78, 23, 113, 167);
                //Log.i(TAG, "onDraw: 比例："+"左："+(float)78/mTotalWidth+" 右:"+(float)113/mTotalWidth+"上:"+(float)23/mTotalHeight+"下"+(float)167/mTotalHeight);

                //Log.i(TAG, "onDraw: 进度左"+mOilProRect.left+"上"+mOilProRect.top+"右"+mOilProRect.right+"下"+mOilProRect.bottom);
                waterTopValue = (bootm - top) * (1 - (float) waterRatio / 100);
                mWaterProRect = new Rect(left, (int) (top + waterTopValue), right, bootm);
                if (oilCan.getOilColor() == 0) {
                    mBitPaint.setColor(Color.BLUE);
                } else {
                    mBitPaint.setColor(oilCan.getOilColor());
                }

                canvas.drawRect(mOilProRect, mBitPaint);

                mBitPaint.setColor(Color.rgb(193, 210, 240));
                canvas.drawRect(mWaterProRect, mBitPaint);

                //Log.i(TAG, "onDraw: 进度条总高度"+(proBootomRoait-proTopRoait)*mTotalHeight+"每个字体的高度"+(proBootomRoait-proTopRoait)*mTotalHeight/content.length);

                mTextPaint.setTextSize((float) (textSize * 1));

                // 除罐号那一行（i==0，单独画蓝色徽标）外，其余每一行都是 label/数值/单位 三段。
                // 三段都按默认字号（不做整屏统一定宽、不做缩放），每一行各自量自己的宽度：
                // label 画在固定起始 X，数值紧跟在该行 label 的实际宽度后面，单位再紧跟在该行
                // 数值的实际宽度后面——行与行之间不强制对齐成列，但也不会因为跟别的行比较最大
                // 宽度而被压缩/裁切。
                float originalTextSize = mTextPaint.getTextSize(); // 罐号徽标行用这个
                float colGap = 8f;
                float labelColX = right + 120;

                for (int i = 0; i < content.length; i++) {
                    // --- 为油品行绘制蓝色背景 ---
                    if (i == 0) {
                        mTextPaint.setTextSize(originalTextSize); // 罐号徽标行用原始字号，不受上面的缩放影响
                        // 加粗
                        boolean oldFakeBold = mTextPaint.isFakeBoldText();
                        mTextPaint.setColor(tagColor);
                        mTextPaint.setFakeBoldText(oldFakeBold);
                        // 1. 根据通信状态选择背景色
                      //  int bgColor = isCommunicationError() ? Color.rgb(255,0,0) : Color.BLUE;
                        int bgColor = isCommunicationError() ? Color.RED : Color.rgb(0,102,162);
                        // 计算背景区域
                        float leftBg = right + 120;  // 与标签起始X一致
                        float topBg = top + i * textSize;
                        // 右边界需要包含标签和数值文本的总宽度
                        String label = " " + SystemUtils.getRsStr(R.string.oilcan_short_label_format, oilCan.getOilCanPos());//content[i] + ":";
                        String value = getTextContent(arrIndex == null ? i : arrIndex[i]);
                        float totalWidth = 125 + mTextPaint.measureText(label) + mTextPaint.measureText(value);
                        // 背景框宽度按实际文案（label+value）测量结果撑开，不再写死；330 作为下限，
                        // 保证文案很短时框不会比中文场景下的原有效果还窄，英文/西语等更长文案时会跟着变宽，不再溢出。
                        float rightBg = Math.max(right + totalWidth, 330);
                        float bottomBg = top + (i + 1) * textSize;

                        // 保存当前画笔颜色，绘制蓝色矩形
                        int savedColor = mBitPaint.getColor();

                        mBitPaint.setColor(bgColor);
                        canvas.drawRect(leftBg, topBg-45, rightBg+40, bottomBg-32, mBitPaint);
                        mBitPaint.setColor(savedColor);  // 恢复原画笔颜色

                        // 将文字颜色设为白色（确保在蓝色背景上可见）
                        mTextPaint.setColor(Color.WHITE);
                        canvas.drawText(label, right + 120, bottomBg -40, mTextPaint);

                        // 绘制数值（白色）
                        canvas.drawText(value, right + 125 + mTextPaint.measureText(label),
                                bottomBg -40, mTextPaint);

                        // 背景绘制略...
                       // mTextPaint.setColor(Color.WHITE);
                       ;
                        continue;  // 跳过下方的通用绘制，避免重复
                    }

                    mTextPaint.setTextSize(originalTextSize);
                    mTextPaint.setColor(tagColor);
                    float rowY = top + (i*1.35f + 1.2f) * textSize - 38;
                    String labelText = content[i] + "";
                    canvas.drawText(labelText, labelColX, rowY, mTextPaint);
                    float valueColX = labelColX + mTextPaint.measureText(labelText) + colGap;
                    switch (oilCan.getProbeType()) {
                        case 0:
                            ErrorColor(4);
                            break;
                        case 1:
                            ErrorColor(4);
                            break;
                        case 2:
                            ErrorColor(4);
                            break;
                        case 3:
                            ErrorColor(4);
                            break;
                        case 5:
                            ErrorColor(20);
                            break;
                        case 6:
                            ErrorColor(4);
                            break;
                        default:
                            ErrorColor(4);
                            break;
                    }
                    String[] parts = getTextContentParts(arrIndex == null ? i : arrIndex[i]);
                    canvas.drawText(parts[0], valueColX, rowY, mTextPaint);
                    if (!parts[1].isEmpty()) {
                        float unitColX = valueColX + mTextPaint.measureText(parts[0]) + colGap;
                        canvas.drawText(parts[1], unitColX, rowY, mTextPaint);
                    }
                }

            }
        } catch (Exception e) {
            Log.e(TAG, "onDraw 执行失败", e);
        }
    }
    private boolean isCommunicationError() {
        if (oilCan == null) return true;  // 无数据视为中断

        // 根据探棒类型确定阈值
        int errTime = 4;
        int time = 2000;
        if (oilCan.getProbeType() == 5) {
            errTime = 20;
            time = 4000;
        }
        else if (oilCan.getAlarmState()!=0){
            Log.e("TAG报警状态"," "+oilCan.getAlarmState());
            return true;
        }

        // 正常条件：错误次数未超限 且 通讯时间差在允许范围内
        boolean normal = oilCan.getErrorTimes() < errTime
                && (System.currentTimeMillis() - oilCan.getLastTime())
                < MyApplication.getInstance().getmOilcanDataManager().getData().size() * time;

        return !normal;  // 不满足正常条件即为中断
    }
    private void ErrorColor(int error) {
        if (oilCan.getErrorTimes() < error) {
            mTextPaint.setColor(defualtColor);
        } else {
            mTextPaint.setColor(Color.RED);
        }
    }

    private void Error(int errTime, int time) {
        if (oilCan.getErrorTimes() < errTime && (System.currentTimeMillis() - oilCan.getLastTime() < MyApplication.getInstance().getmOilcanDataManager()
                .getData().size() * time)) {
            //Log.i(TAG, "onDraw: 默认颜色"+defualtColor);
            mTextPaint.setColor(tagColor);
        } else {
            mTextPaint.setColor(Color.RED);
            if (SharePrefUtil.getBoolean(ct, SharePrefUtil.TEST_DATA, false)) {
                oilCan.setOilLevel(oilCan.getLevelCorrection());
                oilCan.setWaterLevel(Math.max(0, oilCan.getWaterLevelCorrection()));
                oilCan.setTemper(oilCan.getTempCorrection());

                int oilRoate = (int) ((oilCan.getOilLevel() / oilCan
                        .getMaxHeight()) * 100);
                oilCan.setOilRatio(oilRoate > 100 ? 100 : oilRoate);
                // 水比例
                int waterRoate = (int) ((oilCan.getWaterLevel() / oilCan
                        .getMaxHeight()) * 100);
                oilCan.setWaterRatio(waterRoate > 100 ? 100 : waterRoate);
                // 水体积
                double waterVolume = DataUtils.getLitre(
                        oilCan.getWaterLevel(), oilCan.getHightArray(),
                        oilCan.getLitreArray());
                // 油体积 = 体积-水体积
                double oilVolume = DataUtils.getLitre(
                        oilCan.getOilLevel(), oilCan.getHightArray(),
                        oilCan.getLitreArray());

                double curVolume = oilVolume - waterVolume;

                // 可添油重量
                if (oilCan.getMaxVolume() != 0) {
                    double canAddOilWeight = (oilCan.getMaxVolume() * oilCan.getDensity()) - DataUtils.getLitre(oilCan.getOilLevel(), oilCan.getHightArray(), oilCan.getLitreArray()) * oilCan.getDensity();
                    //可添油体积
                    oilCan.setSurPlus(DataUtils.getOilcanSurplus(oilCan));
                    //可添油重量
                    oilCan.setCanAddOilWeight(DataUtils.doubleFoment(oilCan.getSurPlus() * oilCan.getDensity()));
                }

                oilCan.setWaterVolume(DataUtils.doubleFoment(waterVolume));
                oilCan.setOilVolume(DataUtils.doubleFoment(oilVolume));
                // 存油重量
                oilCan.setOilWeight(DataUtils.doubleFoment(oilCan
                        .getDensity() * oilCan.getOilVolume()));
            } else {
                oilCan.setOilLevel(0);
                oilCan.setTemper(0);
                oilCan.setOilRatio(0);
                oilCan.setWaterRatio(0);
            }
        }
    }

    private String getTextContent(int i) {
        String[] parts = getTextContentParts(i);
        return parts[1].isEmpty() ? parts[0] : parts[0] + "  " + parts[1];
    }

    /**
     * 数值和单位分开返回（{数值, 单位}，没有单位时单位为 ""），供按列对齐绘制用——数值列、单位列
     * 分别按各自当前最长的一条统一定宽，不能像之前那样把两者拼成一个字符串一起测量/绘制。
     * getTextContent() 仍然可用，内部就是把这两段拼回去，行为跟以前完全一样。
     */
    private String[] getTextContentParts(int i) {
        switch (i) {
            case 0:
                switch (oilCan.getProbeType()) {
                    case 0:
                        return new String[]{ErrorContent(4), ""};
                    case 1:
                        return new String[]{ErrorContent(4), ""};
                    case 2:
                        return new String[]{ErrorContent(4), ""};
                    case 3:
                        return new String[]{ErrorContent(4), ""};
                    case 4:
                        return new String[]{ErrorContent(4), ""};
                    case 5:
                        return new String[]{ErrorContent(20), ""};
                    case 6:
                        return new String[]{ErrorContent(4), ""};
                    default:
                        return new String[]{ErrorContent(4), ""};
                }

            case 1:
                Log.e("TAG油高","油高:  "+DataUtils.doubleFomentStr(oilCan.getOilLevel()));
                YeWeiUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit,"毫米");
                return new String[]{
                        UnitPref.Length.parse(YeWeiUnit) == UnitPref.Length.MM?DataUtils.doubleFomentStr(oilCan.getOilLevel()):DataUtils.doubleFomentStr2(oilCan.getOilLevel()/1000),
                        SystemUtils.localizedUnit(YeWeiUnit)};
            case 2:
                RongJiUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit,"升");
                return new String[]{
                        UnitPref.Volume.parse(RongJiUnit) == UnitPref.Volume.L?DataUtils.doubleFomentStr(oilCan.getOilVolume()):DataUtils.doubleFomentStr2(oilCan.getOilVolume()/1000),
                        SystemUtils.localizedUnit(RongJiUnit)};
            case 3:
                YeWeiUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.YeWeiUnit,"毫米");
                return new String[]{
                        UnitPref.Length.parse(YeWeiUnit) == UnitPref.Length.MM?DataUtils.doubleFomentStr(oilCan.getWaterLevel()):DataUtils.doubleFomentStr2(oilCan.getWaterLevel()/1000),
                        SystemUtils.localizedUnit(YeWeiUnit)};

            case 4:
                ZhiLiangUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit,"千克");
                return new String[]{
                        UnitPref.Mass.parse(ZhiLiangUnit) == UnitPref.Mass.KG?DataUtils.doubleFomentStr(oilCan.getCanAddOilWeight()) :DataUtils.doubleFomentStr2(oilCan.getCanAddOilWeight() /1000),
                        SystemUtils.localizedUnit(ZhiLiangUnit)};

            case 5:
                ZhiLiangUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.ZhiLiangUnit,"千克");
                return new String[]{
                        UnitPref.Mass.parse(ZhiLiangUnit) == UnitPref.Mass.KG?DataUtils.doubleFomentStr(oilCan.getOilWeight()) :DataUtils.doubleFomentStr2(oilCan.getOilWeight() /1000),
                        SystemUtils.localizedUnit(ZhiLiangUnit)};

            case 6:
                RongJiUnit=SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), Constans.RongJiUnit,"升");
                return new String[]{
                        UnitPref.Volume.parse(RongJiUnit) == UnitPref.Volume.L?DataUtils.doubleFomentStr(oilCan.getWaterVolume()):DataUtils.doubleFomentStr2(oilCan.getWaterVolume()/1000),
                        SystemUtils.localizedUnit(RongJiUnit)};
            case 7:
                return new String[]{String.valueOf(oilCan.getTemper()), "℃"};

            case 8:
                return new String[]{oilCan.getAlarmState()+"", ""};
            case 9:
                return new String[]{oilCan.getProbeType()+" "+oilCan.getProbeAdr(), ""};
            default:
                return new String[]{"   " + i, ""};
        }
    }
    private String YeWeiUnit,RongJiUnit,ZhiLiangUnit;

    // [可达-2026-09-23] 用户反馈"探针连不上时，首页小罐图标上的通信中断提示还是中文"，查出来是
    // R.string.oil_Communication_interrupt 之前被误判不可达——它同时也在 PrintRunable.java（已确认
    // 死）里出现过，之前的核查只看到那个死用法就把整个 key 判死了，没查到这条：oilCan.getErrorTimes()
    // 是真实的探针错误计数，超过阈值时这个分支会执行、显示在首页每个罐图标上，是可达的。
    private String ErrorContent(int error) {
        if (oilCan.getErrorTimes() < error) {
            return Const.getOilType(oilCan.getOilType());
        } else {
            return SystemUtils.getRsStr(R.string.oil_Communication_interrupt);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension((int) (mBitWidth *1.1f), (int) (mBitHeight*1.1f ));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        mTotalHeight = h;
        mTotalWidth = w;
        //Log.i(TAG, "onSizeChanged: 宽"+w+"高"+h);
        invalidate();
        left = (int) (mTotalWidth * proLeftRoait);
        top = (int) (mTotalHeight * proTopRoait);
        right = (int) (mTotalWidth * proRightRoait);
        bootm = (int) (mTotalHeight * proBootomRoait);
        textSize = (float) ((proBootomRoait - proTopRoait) * mTotalHeight / content.length);
    }
}