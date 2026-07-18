package com.sansen.yeweiyi.manager;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.AlarmRecord;
import com.sansen.yeweiyi.bean.AlarmState;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.Map;

import bean.Bean;

public class AlarmDetection {

    private static final String TAG = "报警";
    private static AlarmDetection mInstance;
    private AlarmManager alarmManager;
    private MyApplication myApplication;
    private DBDataManager dbDataManager;
    AlarmRecord record;
    Map<Integer, AlarmState> alarmStates;
    public static final String NoAlarm = SystemUtils.getRsStr(R.string.no_alarm);
    public static final String ConnectError = SystemUtils.getRsStr(R.string.communication_interrupted);
    public static final String HeightOilError = SystemUtils.getRsStr(R.string.height_oil_alarm);
    public static final String HHeightOilError = SystemUtils.getRsStr(R.string.hheight_oil_alarm);
    public static final String LowOilError = SystemUtils.getRsStr(R.string.low_oil_alarm);
    public static final String LLowOilError = SystemUtils.getRsStr(R.string.llow_oil_alarm);
    public static final String HeightWaterError = SystemUtils.getRsStr(R.string.height_water_alarm);
    public static final String HeightTempError = SystemUtils.getRsStr(R.string.height_temp_alarm);
    public static final String LowTempError = SystemUtils.getRsStr(R.string.low_temp_alarm);

    private AlarmDetection(AlarmManager alarmManager) {
        this.alarmManager = alarmManager;
        myApplication = MyApplication.getInstance();
        dbDataManager = myApplication.getmDBManager();
        alarmStates = MyApplication.alarmStates;
    }

    public static AlarmDetection getInstance(AlarmManager alarmManager) {

        if (mInstance == null) {
            mInstance = new AlarmDetection(alarmManager);
        }
        return mInstance;
    }


    public void beginDection(final OilCan oilCan) {
        if (oilCan != null) {
            if (oilCan.getHighLevelAlarm() != 0 && oilCan.getOilLevel() > (oilCan.getHighLevelAlarm())) {
                oilHeightDection(oilCan);
            } else if (oilCan.getLowLevelAlarm() != 0 && oilCan.getOilLevel() < oilCan.getLowLevelAlarm()) {
                oilLowDection(oilCan);
//                if (MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isSoundLightAlarm()) {
//                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setSoundLightAlarm(false);
//
//                }
            } else {
                if (!MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isSoundLightAlarm()) {//如果当前的所有油罐为非报警
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setSoundLightAlarm(true);
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                }
                if (!MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isWaterAlarm()) {
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setSoundLightAlarm(false);
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                } else if (!MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isHHightAlarm()) {
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setHHightAlarm(true);
                }


            }
            if (oilCan.getHighWaterLevelAlarm() != 0 && oilCan.getWaterLevel() > oilCan.getHighWaterLevelAlarm()) {
                if (MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isWaterAlarm()) {
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setWaterAlarm(false);
                    waterHeightDection(oilCan);
                }
            } else {
                if (!MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isWaterAlarm()) {
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setWaterAlarm(true);
                }
            }
            if (oilCan.getHighTempAlarm() != 0 && oilCan.getTemper() > oilCan.getHighTempAlarm()) {
                if (MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isTemperAlarm()) {
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setTemperAlarm(false);
                    tempHeightDection(oilCan);
                }

            } else {
                if (!MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isTemperAlarm()) {
                    SoundLightManager.getInstance().closeAlarm(oilCan.getOilCanPos());
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setTemperAlarm(true);
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
        if (oilCan.getOilLevel() > oilCan.getHhighLevelAlarm()
                & oilCan.getHhighLevelAlarm() != 0) {
            if (MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isHHightAlarm()) {
                isHHeightAlarm = true;
                MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setHHightAlarm(false);
                SoundLightManager.getInstance().FYF(oilCan.getOilCanPos(), isHHeightAlarm);
                //Log.i("TAG","高高油位--------- "+oilCan.getHhighLevelAlarm()+"----"+oilCan.getOilLevel() );
                oilMessage = new OilMessage();
                oilMessage.setOilCanId(oilCan.getOilCanPos());
                oilMessage.setType(OilMessage.HHEIGHT_OIL_ALARM);
                alarmManager.addAlarmState(oilMessage);
                oilCan.setAlarmState(1);
                oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.hheight_oil_alarm);
                if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                    alarmState = alarmStates.get(oilCan.getOilCanPos());
                    if (alarmState.getHheightOilError() == 0) {
                        oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                        dbDataManager.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                        alarmState.setHheightOilError(1);
                        alarmState.setHeightOilError(0);
                    }
                }
            }

        } else {
            MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setHHightAlarm(true);
            SoundLightManager.getInstance().FYF(oilCan.getOilCanPos(), false);
            if (MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isSoundLightAlarm()) {
                MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setSoundLightAlarm(false);

                isHHeightAlarm = false;
                SoundLightManager.getInstance().openHeightAlarm(oilCan.getOilCanPos(), isHHeightAlarm);

                oilMessage = new OilMessage();
                oilMessage.setOilCanId(oilCan.getOilCanPos());

                oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                oilMessage.setType(OilMessage.HEIGHT_OIL_ALARM);
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
                        dbDataManager.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                        alarmState.setHeightOilError(1);
                        alarmState.setHheightOilError(0);

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
                if (MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isLLLowAlarm()) {
                    isLLowAlarm = true;
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setLLLowAlarm(false);
                    oilMessage = new OilMessage();
                    oilMessage.setOilCanId(oilCan.getOilCanPos());
                    oilMessage.setType(OilMessage.LLOW_OIL_ALARM);
                    alarmManager.addAlarmState(oilMessage);
                    oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.llow_oil_alarm);
                    SoundLightManager.getInstance().FYFL(oilCan.getOilCanPos(), isLLowAlarm);

                    oilCan.setAlarmState(2);

                    if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                        alarmState = alarmStates.get(oilCan.getOilCanPos());
                        if (alarmState.getLlowOilError() == 0) {
                            oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                            dbDataManager.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                            alarmState.setLlowOilError(1);
                            alarmState.setLowOilError(0);
                        }
                    }
                }
            } else {
                MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setLLLowAlarm(true);
                SoundLightManager.getInstance().FYFL(oilCan.getOilCanPos(), false);
                if (MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).isSoundLightAlarm()) {
                    MyApplication.getInstance().getmOilcanDataManager().getData(oilCan.getOilCanPos()).setSoundLightAlarm(false);
                    isLLowAlarm = false;
                    oilMessage = new OilMessage();
                    oilMessage.setOilCanId(oilCan.getOilCanPos());
                    oilMessage.setType(OilMessage.LOW_OIL_ALARM);
                    alarmManager.addAlarmState(oilMessage);
                    oilCan.setAlarmState(2);
                    oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.low_oil_alarm);

                    if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                        alarmState = alarmStates.get(oilCan.getOilCanPos());
                        if (alarmState.getLowOilError() == 0) {
                            oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                            dbDataManager.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                            if (SharePrefUtil.getBoolean(myApplication.getApplicationContext(), SharePrefUtil.ISUPLOADING, false)) {
                                UploadRecord("低油位报警", oilCan);
                            }
                            alarmState.setLowOilError(1);
                            alarmState.setLlowOilError(0);
                        }
                    }
                }
                SoundLightManager.getInstance().openLowOilAlarm(oilCan.getOilCanPos(), isLLowAlarm);
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
        bean.setProductId("fQxNBq6eCx3");
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
        alarmManager.addAlarmState(oilMessage);
        oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.height_water_alarm);

        oilCan.setAlarmState(3);

        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            if (alarmState.getHeightWaterError() == 0) {
                oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                dbDataManager.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                alarmState.setHeightWaterError(1);
            }
        }
        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            alarmState.setHeightWaterError(0);
        }
        SoundLightManager.getInstance().openLowOilAlarm(oilCan.getOilCanPos(), false);
    }

    /**
     * 检测高温度报警 如果当前温度高于设定的高温度报警则为ture
     *
     * @return true or false
     */
    private void tempHeightDection(OilCan oilCan) {

        AlarmState alarmState;
        oilMessage = new OilMessage();
        oilMessage.setOilCanId(oilCan.getOilCanPos());
        oilMessage.setType(OilMessage.HEIGHT_TEMPER);
        alarmManager.addAlarmState(oilMessage);
        oilCan.setAlarmState(4);
        oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.height_temp_alarm);
        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            if (alarmState.getHeightTempError() == 0) {
                oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                dbDataManager.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                alarmState.setHeightTempError(1);
            }
        }
        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            alarmState.setHeightTempError(0);
        }
        SoundLightManager.getInstance().openLowOilAlarm(oilCan.getOilCanPos(), false);
    }

    /**
     * 检测低温度报警 如果当前温度低于设定的低温度报警则为ture
     *
     * @return true or false
     */
    private boolean tempLowDection(OilCan oilCan) {

        AlarmState alarmState;

        if (oilCan.getTemper() < oilCan.getLowTempAlarm()) {
            oilMessage = new OilMessage();
            oilMessage.setOilCanId(oilCan.getOilCanPos());
            oilMessage.setType(OilMessage.LOW_TEMPER);
            alarmManager.addAlarmState(oilMessage);
            oilCan.setAlarmState(5);
            oilCan.setStateInfo(oilCan.getOilCanPos(), R.string.low_temp_alarm);

            if (alarmStates.containsKey(oilCan.getOilCanPos())) {
                alarmState = alarmStates.get(oilCan.getOilCanPos());
                if (alarmState.getLowTempError() == 0) {
                    oilMessage.setJieZhi(DataUtils.getOilType()[oilCan.getOilType()]);
                    dbDataManager.saveAlarm(oilCan.getOilCanPos(), oilMessage);
                    alarmState.setLowTempError(1);
                }
            }
            return false;
        }

        if (alarmStates.containsKey(oilCan.getOilCanPos())) {
            alarmState = alarmStates.get(oilCan.getOilCanPos());
            alarmState.setLowTempError(0);
        }
        return true;
    }


}