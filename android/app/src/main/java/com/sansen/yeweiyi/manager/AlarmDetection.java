package com.sansen.yeweiyi.manager;

import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.applog.AlarmLog;
import com.sansen.yeweiyi.applog.SoundLightLog;
import com.sansen.yeweiyi.bean.AlarmRecord;
import com.sansen.yeweiyi.bean.AlarmState;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.config.ApiConfig;
import com.sansen.yeweiyi.logic.alarm.HighTempConfirmer;
import com.sansen.yeweiyi.repository.AlarmRepository;
import com.sansen.yeweiyi.repository.AlarmRepositoryImpl;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.Map;

import bean.Bean;

public class AlarmDetection {

    private static final String TAG = "报警";
    private static AlarmDetection mInstance;
    private AlarmManager alarmManager;
    private MyApplication myApplication;
    private AlarmRepository alarmRepository;
    AlarmRecord record;
    Map<Integer, AlarmState> alarmStates;
    private final HighTempConfirmer highTempConfirmer = new HighTempConfirmer();
    /** 低温同样要连续低于阈值满 5 秒才报（同一套确认逻辑，#65）。 */
    private final HighTempConfirmer lowTempConfirmer = new HighTempConfirmer();

    private AlarmDetection(AlarmManager alarmManager) {
        this.alarmManager = alarmManager;
        myApplication = MyApplication.getInstance();
        alarmRepository = AlarmRepositoryImpl.getInstance();
        alarmStates = MyApplication.alarmStates;
    }

    public static AlarmDetection getInstance(AlarmManager alarmManager) {

        if (mInstance == null) {
            mInstance = new AlarmDetection(alarmManager);
        }
        return mInstance;
    }


    /**
     * 云端报警上报：下面各个"条件不再成立"的 else 分支里调 {@link CloudAlarmReporter#onRecovered}，
     * 跟本地标志位复位是同一个判断条件；没有对应的 ACTIVE 时是空操作。本地判断、声光、继电器
     * 行为不受影响。产生在 {@link AlarmManager#addAlarmState} 里上报。
     */
    public void beginDection(final OilCan oilCan) {
        if (oilCan != null) {
            CloudAlarmReporter cloud = CloudAlarmReporter.getInstance();
            int pos = oilCan.getOilCanPos();
            if (oilCan.getHighLevelAlarm() != 0 && oilCan.getOilLevel() > (oilCan.getHighLevelAlarm())) {
                oilHeightDection(oilCan);
            } else {
                cloud.onRecovered(pos, OilMessage.HEIGHT_OIL_ALARM, oilCan.getOilLevel());
                cloud.onRecovered(pos, OilMessage.HHEIGHT_OIL_ALARM, oilCan.getOilLevel());
                if (!Singleton.getInstance().SoundLightAlarm[oilCan.getOilCanPos()]) {//如果当前的所有油罐为非报警
                    Singleton.getInstance().SoundLightAlarm[oilCan.getOilCanPos()]=true;
                    SoundLightLog.onClose(oilCan.getOilCanPos(), "高液位");
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                    oilCan.setAlarmState(0);
                } else if (!Singleton.getInstance().isHHightAlarm[oilCan.getOilCanPos()]) {
                    Singleton.getInstance().isHHightAlarm[oilCan.getOilCanPos()]=true;
                }
            }
            if (oilCan.getLowLevelAlarm() != 0 && oilCan.getOilLevel() < oilCan.getLowLevelAlarm()) {
                oilLowDection(oilCan);

            } else {
                cloud.onRecovered(pos, OilMessage.LOW_OIL_ALARM, oilCan.getOilLevel());
                cloud.onRecovered(pos, OilMessage.LLOW_OIL_ALARM, oilCan.getOilLevel());

                if (!Singleton.getInstance().isLowAlarms[oilCan.getOilCanPos()]) {
                    Log.e("TAG", "打开报警oilLowDection======"+"----------"+oilCan.getOilLevel()+"----"+oilCan.getOilCanPos());
                    Singleton.getInstance().isLowAlarms[oilCan.getOilCanPos()]=true;
                    SoundLightLog.onClose(oilCan.getOilCanPos(), "低液位");
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                    oilCan.setAlarmState(0);
                }
                else if (!Singleton.getInstance().isLLLowAlarm[oilCan.getOilCanPos()]) {
                    Singleton.getInstance().isLowAlarms[oilCan.getOilCanPos()]=true;
                }

            }
            if (oilCan.getHighWaterLevelAlarm() != 0 && oilCan.getWaterLevel() > oilCan.getHighWaterLevelAlarm()) {
                if (Singleton.getInstance().isWaterAlarm[oilCan.getOilCanPos()]) {
                    Singleton.getInstance().isWaterAlarm[oilCan.getOilCanPos()]=false;
                    waterHeightDection(oilCan);
                }
            } else {
                cloud.onRecovered(pos, OilMessage.HEIGHT_WATER, oilCan.getWaterLevel());
                if (!Singleton.getInstance().isWaterAlarm[oilCan.getOilCanPos()]) {
                    Log.e("关闭警报灯", "警报灯03");
                    SoundLightLog.onClose(oilCan.getOilCanPos(), "高水位");
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                    oilCan.setAlarmState(0);
                    Singleton.getInstance().isWaterAlarm[oilCan.getOilCanPos()]=true;
                }
            }
            boolean overTemp = oilCan.getHighTempAlarm() != 0 && oilCan.getTemper() > oilCan.getHighTempAlarm();
            HighTempConfirmer.Result tempCheck = highTempConfirmer.update(pos, overTemp, oilCan.getTemperReadAt());
            if (tempCheck.getState() == HighTempConfirmer.State.DISMISSED) {
                AlarmLog.onHighTempDismissed(oilCan, tempCheck.getOverMs());
            }
            if (overTemp) {
                // 没持续满确认时长时什么都不做，也不走下面的恢复分支
                if (tempCheck.getState() == HighTempConfirmer.State.CONFIRMED
                        && Singleton.getInstance().isTemperAlarm[oilCan.getOilCanPos()]) {
                    Singleton.getInstance().isTemperAlarm[oilCan.getOilCanPos()]=false;
                    tempHeightDection(oilCan);
                }

            } else {
                cloud.onRecovered(pos, OilMessage.HEIGHT_TEMPER, oilCan.getTemper());
                if (!Singleton.getInstance().isTemperAlarm[oilCan.getOilCanPos()]) {
                    Log.e("关闭警报灯", "警报灯04");
                    AlarmLog.onHighTempRecovered(oilCan);
                    Singleton.getInstance().isTemperAlarm[oilCan.getOilCanPos()]=true;
                    SoundLightLog.onClose(oilCan.getOilCanPos(), "高温");
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                    oilCan.setAlarmState(0);
              }
            }
            // 只认真实温度读数（temperReadAt > 0）：LD 系列探针不读温度，显示的是补偿值
            boolean underTemp = oilCan.getLowTempAlarm() != 0 && oilCan.getTemperReadAt() > 0
                    && oilCan.getTemper() < oilCan.getLowTempAlarm();
            HighTempConfirmer.Result lowCheck = lowTempConfirmer.update(pos, underTemp, oilCan.getTemperReadAt());
            if (underTemp) {
                if (lowCheck.getState() == HighTempConfirmer.State.CONFIRMED
                        && Singleton.getInstance().isLowTemperAlarm[pos]) {
                    Singleton.getInstance().isLowTemperAlarm[pos] = false;
                    tempLowDection(oilCan);
                }
            } else {
                cloud.onRecovered(pos, OilMessage.LOW_TEMPER, oilCan.getTemper());
                if (!Singleton.getInstance().isLowTemperAlarm[pos]) {
                    Singleton.getInstance().isLowTemperAlarm[pos] = true;
                    SoundLightLog.onClose(pos, "低温");
                    SoundLightManager.getInstance().closeAlarm(pos);
                    oilCan.setAlarmState(0);
                }
            }
        }
    }


    OilMessage oilMessage;

    /**
     * 检测高油位报警 如果当前油高大于设定的油位报警则为ture
     *
     * @param oilCan
     * @return true or false
     */
    boolean isHHeightAlarm = false;

    private void oilHeightDection(OilCan oilCan) {
        AlarmState alarmState;
        //Log.i("TAG","高高油位 "+oilCan.getHhighLevelAlarm()+"----"+oilCan.getOilLevel() );
        if (oilCan.getOilLevel() > oilCan.getHhighLevelAlarm() & oilCan.getHhighLevelAlarm() != 0) {
            if (Singleton.getInstance().isHHightAlarm[oilCan.getOilCanPos()]) {
                isHHeightAlarm = true;//
                Singleton.getInstance().isHHightAlarm[oilCan.getOilCanPos()]=false;
                SoundLightManager.getInstance().FYF(oilCan.getOilCanPos(), isHHeightAlarm);
                //Log.i("TAG","高高油位--------- "+oilCan.getHhighLevelAlarm()+"----"+oilCan.getOilLevel() );
                oilMessage = new OilMessage();
                oilMessage.setOilCanId(oilCan.getOilCanPos());
                oilMessage.setType(OilMessage.HHEIGHT_OIL_ALARM);
                // 触发这次报警的实际液位读数——之前这里从没设置过，上报到云端的报警事件
                // value 字段永远是 double 默认值 0，跟真实的 0 液位读数无法区分
                // （PR #38 Codex 第六轮审核 P2）。
                oilMessage.setValue(oilCan.getOilLevel());
                alarmManager.addAlarmState(oilMessage);
                oilCan.setAlarmState(1);
                oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.hheight_oil_alarm);
                if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                    alarmState = alarmStates.get(oilCan.getOilCanPos());
                    if (alarmState.getHheightOilError() == 0) {
                        oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                        alarmRepository.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                        alarmState.setHheightOilError(1);

                    }
                }
            }

        } else {
            // 液位 ≤ 高高阈值（或高高未启用）：高高液位条件不再成立
            CloudAlarmReporter.getInstance().onRecovered(oilCan.getOilCanPos(), OilMessage.HHEIGHT_OIL_ALARM,
                    oilCan.getOilLevel());
            Singleton.getInstance().isHHightAlarm[oilCan.getOilCanPos()]=true;
            SoundLightManager.getInstance().FYF(oilCan.getOilCanPos(), false);
            if ( Singleton.getInstance().SoundLightAlarm[oilCan.getOilCanPos()]) {
                Singleton.getInstance().SoundLightAlarm[oilCan.getOilCanPos()]=false;
                isHHeightAlarm = false;
                SoundLightLog.onOpen(oilCan.getOilCanPos(), "高液位");
                SoundLightManager.getInstance().openHeightAlarm(oilCan.getOilCanPos(), isHHeightAlarm);

                oilMessage = new OilMessage();
                oilMessage.setOilCanId(oilCan.getOilCanPos());

                oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                oilMessage.setType(OilMessage.HEIGHT_OIL_ALARM);
                oilMessage.setValue(oilCan.getOilLevel());
                alarmManager.addAlarmState(oilMessage);
                oilCan.setAlarmState(1);
                oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.height_oil_alarm);
                if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                    alarmState = alarmStates.get(oilCan.getOilCanPos());
                    if (alarmState.getHeightOilError() == 0) {
                        if (SharePrefUtil.getBoolean(myApplication.getApplicationContext(), SharePrefUtil.ISUPLOADING, false)) {
                            UploadRecord("高油位报警", oilCan);
                        }
                        oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                        alarmRepository.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                        alarmState.setHeightOilError(1);


                    }
                }
            }

        }
        //  AlarmRecord record=new AlarmRecord(oilCan.getOilCanPos(),alarmState.getHeightOilError(),);
//            String argu= JSONObject.toJSONString(record);
//            OkgoNet.getInstance().simplenessPost("http://192.168.0.133:8080/api/androidLiquidLevel/equipmentOilInAndOutRecord", argu, new RequestCallBack() {
//                @Override
//                public void Success(String message) {
//
//                    Log.e("AAAA",message);
//                }
//
//                @Override
//                public void Error(String failing) {
//                    Log.e("AAAA","failing");
//                }
//
//                @Override
//                public void Progress(Progress progress) {
//
//                }
//            });

        //Log.i(TAG, "oilHeightDection: openHeightAlarm");
        //  SoundLightManager.getInstance().openHeightAlarm(oilCan.getOilCanPos(),isHHeightAlarm);

//        }
        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            alarmState.setHeightOilError(0);
            alarmState.setHheightOilError(0);
        }

    }

    /**
     * 检测低油位报警 如果当前油高低于设定的油位报警则为ture
     *
     * @return true or false
     */
    boolean isLLowAlarm = false;

    private boolean oilLowDection(OilCan oilCan) {
        AlarmState alarmState;
        if (oilCan.getOilLevel() < oilCan.getLowLevelAlarm() & oilCan.getLowLevelAlarm() != 0) {
            if (oilCan.getOilLevel() < oilCan.getLlowLevelAlarm() & oilCan.getLlowLevelAlarm() != 0) {

                if (Singleton.getInstance().isLLLowAlarm[oilCan.getOilCanPos()] ) {
                    isLLowAlarm = true;
                    Singleton.getInstance().isLLLowAlarm[oilCan.getOilCanPos()]=false;
                    oilMessage = new OilMessage();
                    oilMessage.setOilCanId(oilCan.getOilCanPos());
                    oilMessage.setType(OilMessage.LLOW_OIL_ALARM);
                    oilMessage.setValue(oilCan.getOilLevel());
                    alarmManager.addAlarmState(oilMessage);
                    oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.llow_oil_alarm);
                    SoundLightManager.getInstance().FYFL(oilCan.getOilCanPos(), isLLowAlarm);

                    oilCan.setAlarmState(2);

                    if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                        alarmState = alarmStates.get(oilCan.getOilCanPos());
                        if (alarmState.getLlowOilError() == 0) {
                            oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                            alarmRepository.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                            alarmState.setLlowOilError(1);
                            alarmState.setLowOilError(0);
                        }
                    }
                }
            } else {
                // 液位 ≥ 低低阈值（或低低未启用）：低低液位条件不再成立
                CloudAlarmReporter.getInstance().onRecovered(oilCan.getOilCanPos(), OilMessage.LLOW_OIL_ALARM,
                        oilCan.getOilLevel());
                Singleton.getInstance().isLLLowAlarm[oilCan.getOilCanPos()]=true;
                SoundLightManager.getInstance().FYFL(oilCan.getOilCanPos(), false);
                if (Singleton.getInstance().isLowAlarms[oilCan.getOilCanPos()]) {
                    Singleton.getInstance().isLowAlarms[oilCan.getOilCanPos()]=false;
                    isLLowAlarm = false;
                    oilMessage = new OilMessage();
                    oilMessage.setOilCanId(oilCan.getOilCanPos());
                    oilMessage.setType(OilMessage.LOW_OIL_ALARM);
                    oilMessage.setValue(oilCan.getOilLevel());
                    alarmManager.addAlarmState(oilMessage);
                    oilCan.setAlarmState(2);
                    oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.low_oil_alarm);

                    SoundLightLog.onOpen(oilCan.getOilCanPos(), "低液位");
                    SoundLightManager.getInstance().openLowOilAlarm(oilCan.getOilCanPos(), isLLowAlarm);
                    if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                        alarmState = alarmStates.get(oilCan.getOilCanPos());
                        if (alarmState.getLowOilError() == 0) {
                            oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                            alarmRepository.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                            if (SharePrefUtil.getBoolean(myApplication.getApplicationContext(), SharePrefUtil.ISUPLOADING, false)) {
                                UploadRecord("低油位报警", oilCan);
                            }
                            alarmState.setLowOilError(1);
                            alarmState.setLlowOilError(0);
                        }
                    }
                }
            }
        }

        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());

            alarmState.setLowOilError(0);
            alarmState.setLlowOilError(0);
        }
        //   SoundLightManager.getInstance().closeLowOilAlarm(oilCan.getOilCanPos());

        return true;
    }

    private void UploadRecord(String AlarmContent, OilCan oilCan) {
        Bean bean = new Bean();
        record = new AlarmRecord(oilCan.getOilCanPos(), "2", AlarmContent, System.currentTimeMillis());
        bean.setDefaultName(SharePrefUtil.getString(MyApplication.getInstance().getApplicationContext(), SharePrefUtil.DEVICESECRET, null));
        bean.setProductId(ApiConfig.productKey);
        bean.setAlarmRecord(record);

        String argu = JSONObject.toJSONString(bean);
        //Log.i("AAAA"," 报警数据"+argu);
//        OkgoNet.getInstance().simplenessPost(Constans.URL+Constans.AlarmDatas, argu, new RequestCallBack() {
//            @Override
//            public void Success(String message) {
//                Log.e("AAAA","报警数据上传"+message);
//            }
//
//            @Override
//            public void Error(String failing) {
//
//                Log.e("AAAA","报警数据上传"+"fail");
//            }
//
//            @Override
//            public void Progress(Progress progress) {
//
//            }
//        });
    }

    /**
     * 检测高水位报警 如果当前水高高于设定的高水位报警则为ture
     *
     * @return true or false
     */
    private void waterHeightDection(OilCan oilCan) {

        AlarmState alarmState;
        oilMessage = new OilMessage();
        oilMessage.setOilCanId(oilCan.getOilCanPos());
        oilMessage.setType(OilMessage.HEIGHT_WATER);
        oilMessage.setValue(oilCan.getWaterLevel());
        alarmManager.addAlarmState(oilMessage);
        oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.height_water_alarm);

        oilCan.setAlarmState(3);

        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            if (alarmState.getHeightWaterError() == 0) {
                oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                alarmRepository.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                alarmState.setHeightWaterError(1);
            }
        }
        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            alarmState.setHeightWaterError(0);
        }
        Log.e("TAG", "打开报警waterHeightDection");
        SoundLightLog.onOpen(oilCan.getOilCanPos(), "高水位");
        SoundLightManager.getInstance().openLowOilAlarm(oilCan.getOilCanPos(), false);
    }

    /**
     * 检测高温度报警 如果当前温度高于设定的高温度报警则为ture
     *
     * @return true or false
     */
    private void tempHeightDection(OilCan oilCan) {

        AlarmLog.onHighTempRaised(oilCan);
        AlarmState alarmState;
        oilMessage = new OilMessage();
        oilMessage.setOilCanId(oilCan.getOilCanPos());
        oilMessage.setType(OilMessage.HEIGHT_TEMPER);
        oilMessage.setValue(oilCan.getTemper());
        alarmManager.addAlarmState(oilMessage);
        oilCan.setAlarmState(4);
        oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.height_temp_alarm);
        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            if (alarmState.getHeightTempError() == 0) {
                oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                alarmRepository.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                alarmState.setHeightTempError(1);
            }
        }
        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            alarmState.setHeightTempError(0);
        }
        Log.e("TAG", "打开报警tempHeightDection");
        SoundLightLog.onOpen(oilCan.getOilCanPos(), "高温");
        SoundLightManager.getInstance().openLowOilAlarm(oilCan.getOilCanPos(), false);
    }

    /** 进入低温报警：只在 beginDection 里低温确认且标志位允许时调用一次，与 tempHeightDection 对称。 */
    private void tempLowDection(OilCan oilCan) {
        oilMessage = new OilMessage();
        oilMessage.setOilCanId(oilCan.getOilCanPos());
        oilMessage.setType(OilMessage.LOW_TEMPER);
        oilMessage.setValue(oilCan.getTemper());
        alarmManager.addAlarmState(oilMessage);
        oilCan.setAlarmState(5);
        oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.low_temp_alarm);
        oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
        alarmRepository.saveAlarm(oilCan.getOilCanPos(), oilMessage);
        SoundLightLog.onOpen(oilCan.getOilCanPos(), "低温");
        SoundLightManager.getInstance().openLowOilAlarm(oilCan.getOilCanPos(), false);
    }
}