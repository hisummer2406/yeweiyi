package com.sansen.yeweiyi.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.NetOilCan;
import com.sansen.yeweiyi.bean.OilMessage;
import com.sansen.yeweiyi.bean.RequestRecord;
import com.sansen.yeweiyi.config.ApiConfig;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.listener.NetListener;
import com.sansen.yeweiyi.manager.AlarmManager;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.GsonUtil;
import com.sansen.yeweiyi.utils.NetUtils;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import bean.IPBean;
import io.reactivex.Observable;
import io.reactivex.functions.Consumer;
import rxjava.RxScheduler;
import rxjava.intf.IOTask;
public class BackService extends Service {

    private String productKey;
    private String deviceName;

    private boolean isSend = false;

    //服务0
    private String isOline = "isOline"; //实时状态
    private String requestOilcan = "requestOilcan"; //实时油罐数据
    private String requestUnloadOil = "requestUnloadOil";//拉取多条卸油记录
    private String requestAlarm = "requestAlarm";//拉取多天报警记录
    private String requestOilLeak = "requestOilLeak";
    private String msg = "msg";//实时报警记录
    private String requestOilStorage = "requestOilStorage";//实时进出油记录
    private String requestShift = "requestShift";
    private String requestTankTable = "requestTankTable";
    private String requestTankSet = "requestTankSet";
    private String requestProbeSet = "requestProbeSet";


    public static String HOST;
    private static String clientid;
    private static final String TAG = "BackService";

    private static MqttClient client;

    private MqttConnectOptions options;
    /**
     * MQTT 连接凭据，构建期由 local.properties 注入，见 {@link ApiConfig#MQTT_USERNAME}。
     * 未配置时为空字符串 —— 不回退到任何默认账号，连接会失败并在日志中留下记录（见 initMqttClient）。
     */
    private final String userName = ApiConfig.MQTT_USERNAME;
    private final String passWord = ApiConfig.MQTT_PASSWORD;


    /**
     * aidl 进程间通信的接口
     */
    private IBackService.Stub iBackService = new IBackService.Stub() {
        //发送消息
        @Override
        public boolean sendAlarm(String message) throws RemoteException {
            return sendAlarms(message);
        }

        @Override
        public boolean sendUnloadOil(String message) throws RemoteException {
            return sendUnloadOils(message);
        }

        //重启Socket
        @Override
        public void reloadSocket() throws RemoteException {
            RxScheduler.doOnIOThread(new IOTask<Object>() {
                                         @Override
                                         public void doOnIOThread() {
                                             onReloadMqtt();
                                         }
                                     }
            );
        }


        //停止Socket
        @Override
        public void stopSocket() throws RemoteException {
        }

        public boolean isConnect() {

            return isConnected();
        }

    };
    /**
     * 发布信息
     * @param msgInfo
     * @return
     */
    private boolean sendUnloadOils(String msgInfo) {
        Log.i(TAG, "发布主题: 发布消息" + msgInfo);
        if (client != null) {
            MqttMessage message = new MqttMessage();
            message.setQos(1);
            message.setRetained(false);
            HashMap<String, String> alarms = new HashMap<>();
            alarms.put("requestOilStorage", msgInfo);
            message.setPayload(GsonUtil.toJsonStr(alarms).getBytes());
            MqttTopic topic = client.getTopic(MqttUtil.getEvent(productKey, deviceName, requestOilStorage));
            try {
                publish(topic, message);
            } catch (MqttPersistenceException e) {
                Log.e(TAG, "sendUnloadOils MQTT操作失败(MqttPersistenceException)", e);
            } catch (MqttException e) {
                Log.e(TAG, "sendUnloadOils MQTT操作失败", e);
            }
            return true;
        }
        return false;
    }
    /**
     * 发布信息
     * @param msgInfo
     * @return
     */
    private boolean sendAlarms(String msgInfo) {
        Log.i(TAG, "发布主题: 发布消息" + msgInfo);
        if (client != null) {
            MqttMessage message = new MqttMessage();
            message.setQos(1);
            message.setRetained(false);
            HashMap<String, String> alarms = new HashMap<>();
            alarms.put("msg", msgInfo);
            message.setPayload(GsonUtil.toJsonStr(alarms).getBytes());
            MqttTopic topic = client.getTopic(MqttUtil.getEvent(productKey, deviceName, msg));
            try {
                publish(topic, message);
            } catch (MqttPersistenceException e) {
                Log.e(TAG, "sendAlarms MQTT操作失败(MqttPersistenceException)", e);
            } catch (MqttException e) {
                Log.e(TAG, "sendAlarms MQTT操作失败", e);
            }
            return true;
        }
        return false;
    }
    /**
     * 发布在线状态
     * @param b
     */
    private void notifyOiline(boolean b) {
        MqttMessage message = new MqttMessage();
        message.setQos(1);
        message.setRetained(true);
        message.setPayload(getOilneStateJson(b).getBytes());
        MqttTopic topic = client.getTopic(MqttUtil.getEvent(productKey, deviceName, isOline));
        try {
            publish(topic, message);
        } catch (MqttPersistenceException e) {
            Log.e(TAG, "notifyOiline MQTT操作失败(MqttPersistenceException)", e);
        } catch (MqttException e) {
            Log.e(TAG, "notifyOiline MQTT操作失败", e);
        }
    }
    private boolean isDisConnNet;

    private boolean onReloadMqtt() {
//        Log.i(TAG, "重启Mqtt");
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect(3000);
                }
                client.close();
            } catch (MqttException e) {
                Log.i(TAG, "onReloadMqtt: " + e.getMessage());
                //e.printStackTrace();
            }
            return initMqttClient();

        }
        return false;
    }

    public boolean initMqttClient() {
        try {
            if (StringUtils.isEmpty(userName) || StringUtils.isEmpty(passWord)) {
                // 构建期没注入凭据。不静默用默认账号顶上 —— 那正是本次要消除的东西。
                // 连接大概率会被 broker 拒绝，此处先留下可定位的日志，避免现场只看到"连不上"。
                Log.w(TAG, "MQTT 凭据未配置（local.properties 缺 sansen.mqttUsername/mqttPassword），"
                        + "本次连接将以空凭据发起，预期会被 broker 拒绝");
            }
            if (clientid != null) {
//                Log.i(TAG, clientid + " 222");
                client = new MqttClient(HOST, clientid, new MemoryPersistence());
                client.setCallback(new PushCallback());
//                Log.i(TAG, client.toString() + "333");

//                if (options == null) {
                options = new MqttConnectOptions();
                options.setCleanSession(true);
                options.setUserName(userName);
                options.setPassword(passWord.toCharArray());
                MqttTopic topic = client.getTopic(MqttUtil.getEvent(productKey, deviceName, isOline));
                options.setWill(topic, getOilneStateJson(false).getBytes(), 1, true);
                // 设置超时时间
                options.setConnectionTimeout(3);
                // 设置会话心跳时间
                options.setKeepAliveInterval(3);
//                }
//                Log.i(TAG, options.toString() + "444");

                client.connect(options);

//                Log.i(TAG, client.isConnected() + "555");
                if (client.isConnected()) {
                    subscribeService();
                    notifyOiline(true);

                }
            }
        } catch (Exception e) {
            //e.printStackTrace();
            if (!SharePreManager.getEnableNet()) {
                OilMessage oilMessage = new OilMessage();
                oilMessage.setType(OilMessage.NET_ERR_CONN);
                AlarmManager.getInstance().addAlarmState(oilMessage);
            }
        }
        return false;
    }

    private String getOilneStateJson(boolean b) {
        Map<String, Boolean> map = new HashMap<>();
        map.put(isOline, b);
        return JSONObject.toJSONString(map);
    }



    @Override
    public IBinder onBind(Intent arg0) {
        return (IBinder) iBackService;
    }

    NetListener netListener;

    @Override
    public void onCreate() {
        super.onCreate();
        IPBean ipBean = SharePreManager.getIpBean();
        HOST = "tcp://" + ipBean.getIp() + ":" + ipBean.getPort();
        Log.i(TAG, "onCreate: 主机名" + HOST);
        productKey = ApiConfig.productKey;
        if (StringUtils.isNotEmpty(SharePreManager.getIpBean().getAddr())) {
            deviceName = SharePreManager.getIpBean().getAddr();
        } else {
            deviceName = SharePrefUtil.getString(this, SharePrefUtil.DEVICESECRET, "");
        }
        Log.i(TAG, "onCreate: 设备名字" + deviceName);
        clientid = NetUtils.getMacAddr();
        // MemoryPersistence设置clientid的保存形式，默认为以内存保存
        RxScheduler.doOnIOThread(new IOTask<Object>() {
                                     @Override
                                     public void doOnIOThread() {
                                         initMqttClient();
                                     }
                                 }
        );
        netListener = new NetListener(this);
        netListener.begin(new NetListener.NetEvevt() {
            @Override
            public void onNetChange(int netMobile) {
                if (netMobile >= 0) {
                    try {
                        if (client != null) {
                            if (client.isConnected()) {
                                client.disconnect(3000);
                            }
                            client.close();
                            initMqttClient();
                        }
                    } catch (MqttException e) {
                        Log.e(TAG, "onNetChange MQTT操作失败", e);
                    }
                }
            }
        });

        Observable.interval(1, 1, TimeUnit.SECONDS).subscribe(new Consumer<Long>() {
            @Override
            public void accept(Long aLong) throws Exception {
                if (client != null && client.isConnected() && isSend) {
                    Log.i(TAG, "accept:发送中。。。");
                    sendOilcanData();
                    isSend = false;
                }
                if (client != null && aLong % (60) == 0) {
                    Log.i(TAG, "accept: 是否要重启mqtt" + client.isConnected());
                    if (!client.isConnected()) {
                        Log.i(TAG, "accept: 重启mqtt");
                        onReloadMqtt();
                    }
                }
            }
        });
    }


    public void subscribeService() {
        subscribeIsOilne();
        subscribeRequestOilcan();
        subscribeRequestAlarm();
        subscribeRequestOilLeak();
        subscribeRequestUnload();
        subscribeRequestOilStorage();
        subscribeRequestShift();
        subscribeRequestTankTable();
        subscribeRequestTankSet();
        subscribeRequestProbeSet();
    }

    private void subscribeRequestTankTable() {
        // 订阅消息
        int[] Qos = {1};
        String[] topic = {MqttUtil.getService(productKey, deviceName, requestTankTable)};
        try {
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestTankTable MQTT操作失败", e);
        }
    }

    private void subscribeRequestProbeSet() {
        // 订阅消息
        int[] Qos = {1};
        String[] topic = {MqttUtil.getService(productKey, deviceName, requestProbeSet)};
        try {
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestProbeSet MQTT操作失败", e);
        }
    }

    private void subscribeRequestTankSet() {
        // 订阅消息
        int[] Qos = {1};
        String[] topic = {MqttUtil.getService(productKey, deviceName, requestTankSet)};
        try {
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestTankSet MQTT操作失败", e);
        }
    }
//        subscribeRequest(
//                 MqttUtil.getService(productKey, deviceName, requestOilcan)
//                ,MqttUtil.getService(productKey, deviceName, requestAlarm)
//                ,MqttUtil.getService(productKey, deviceName, requestOilLeak)
//                ,MqttUtil.getService(productKey, deviceName, requestOilStorage)
//                ,MqttUtil.getService(productKey, deviceName, requestProbeSet)
//                ,MqttUtil.getService(productKey, deviceName, requestTankSet)
//                ,MqttUtil.getService(productKey, deviceName, requestTankTable)
//                ,MqttUtil.getService(productKey, deviceName, requestShift)
//                ,MqttUtil.getService(productKey, deviceName, requestUnloadOil)
//                ,MqttUtil.getService(productKey, deviceName, isOline));


//    private void subscribeRequest(String...strings) {
//        int[] Qos = {1};
//        String [] strs = new String[strings.length];
//        for (int i=0;i<strings.length;i++){
//            strs[i]=strings[i];
//        }
//        try {
//            client.subscribe(strs, Qos);
//        } catch (MqttException e) {
//            // TODO Auto-generated catch block
//            Log.i(TAG, "subscribeRequest: 异常");
//            //e.printStackTrace();
//        }
//    }

    private void subscribeRequestOilLeak() {
        // 订阅消息
            int[] Qos = {1};
            String[] topic = {MqttUtil.getService(productKey, deviceName, requestOilLeak)};
            try {
                client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestOilLeak MQTT操作失败", e);
        }
    }

    private void subscribeRequestUnload() {
        // 订阅消息
        int[] Qos = {1};
        String[] topic = {MqttUtil.getService(productKey, deviceName, requestUnloadOil)};
        try {
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestUnload MQTT操作失败", e);
        }
    }

    private void subscribeRequestOilStorage() {
        // 订阅消息
        int[] Qos = {1};
        String[] topic = {MqttUtil.getService(productKey, deviceName, requestOilStorage)};
        try {
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestOilStorage MQTT操作失败", e);
        }
    }


    private void subscribeIsOilne() {
        // 订阅消息
        int[] Qos = {1};
        String[] topic = {MqttUtil.getService(productKey, deviceName, isOline)};
        try {
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeIsOilne MQTT操作失败", e);
        }
    }

    private void subscribeRequestOilcan() {
        try {
            // 订阅消息
            int[] Qos = {1};
            String[] topic = {MqttUtil.getService(productKey, deviceName, requestOilcan)};
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestOilcan MQTT操作失败", e);
        }
    }

    private void subscribeRequestAlarm() {
        try {
            // 订阅消息
            int[] Qos = {1};
            String[] topic = {MqttUtil.getService(productKey, deviceName, requestAlarm)};
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestAlarm MQTT操作失败", e);
        }
    }

    private void subscribeRequestShift() {
        try {
            // 订阅消息
            int[] Qos = {1};
            String[] topic = {MqttUtil.getService(productKey, deviceName, requestShift)};
            client.subscribe(topic, Qos);
        } catch (MqttException e) {
            Log.e(TAG, "subscribeRequestShift MQTT操作失败", e);
        }
    }

    public void publish(MqttTopic topic, MqttMessage message)
            throws MqttPersistenceException, MqttException {
        Log.i(TAG, "发布主题:" + topic.getName() + "  内容:" + new String(message.getPayload()));
        if (client.isConnected()) {
            topic.publish(message);
        } else {
            // 断连时消息被直接丢弃：既不抛异常也不重试，调用方(sendAlarms/sendUnloadOils)
            // 仍会返回 true。此处补一条告警，使丢弃在现场日志中可见。
            // 是否改为抛异常或入队重发属行为变更，见 docs/重构方案.md 的遗留问题。
            Log.w(TAG, "MQTT 未连接，消息被丢弃: " + topic.getName());
        }
//		token.waitForCompletion();
        // System.out.println("message is published completely! " +
        // token.isComplete());
    }

    /**
     * 发布消息的回调类
     * <p>
     * 必须实现MqttCallback的接口并实现对应的相关接口方法CallBack 类将实现 MqttCallBack。
     * 每个客户机标识都`需要一个回调实例。在此示例中，构造函数传递客户机标识以另存为实例数据。 在回调中，将它用来标识已经启动了该回调的哪个实例。
     * 必须在回调类中实现三个方法：
     * <p>
     * public void messageArrived(MqttTopic topic, MqttMessage
     * message)接收已经预订的发布。
     * <p>
     * public void connectionLost(Throwable cause)在断开连接时调用。
     * <p>
     * public void deliveryComplete(MqttDeliveryToken token)) 接收到已经发布的 QoS 1 或
     * QoS 2 消息的传递令牌时调用。 由 MqttClient.connect 激活此回调。
     */
    class PushCallback implements MqttCallback {

        public void connectionLost(Throwable cause) {
            // 连接丢失后，一般在这里面进行重连
        }

        public void deliveryComplete(IMqttDeliveryToken token) {
            // System.out.println("deliveryComplete---------" +
            // token.isComplete());
        }

        public void messageArrived(String topic, MqttMessage message)
                throws Exception {

            Log.i(TAG, "回调方法: ");
            // subscribe后得到的消息会执行到这里面
            // System.out.println("接收消息主题 : " + topic);
            // System.out.println("接收消息Qos : " + message.getQos());
            Log.i(TAG, "messageArrived: " + topic + ":" + new String(message.getPayload()));
            String identifer = topic.substring(topic.lastIndexOf("/") + 1);
//            Log.i(TAG, "messageArrived: " + identifer);
            if (isOline.equals(identifer)) {
//                Log.i(TAG, "messageArrived: 判断是否在线");
            } else if (requestOilcan.equals(identifer)) {
//                Log.i(TAG, "messageArrived:油罐信息");
                Log.i(TAG, "发布主题=========：请求油罐信息");
                isSend = true;
            } else if (requestAlarm.equals(identifer)) {
//                Log.i(TAG, "messageArrived:报警记录信息");
                Log.i(TAG, "发布主题：请求报警");
                RequestRecord record = GsonUtil.parseJsonWithGson(new String(message.getPayload()), RequestRecord.class);
                sendAlarms(record.getLimit(), record.getOffset(), record.getBeginTime(), record.getEndTime(), record.isSort());
            } else if (requestUnloadOil.equals(identifer)) {
                Log.i(TAG, "发布主题：请求卸油记录");
//                Log.i(TAG, "messageArrived: 进出油记录");
                RequestRecord record = GsonUtil.parseJsonWithGson(new String(message.getPayload()), RequestRecord.class);
//                        Log.i(TAG,"进出油记录"+record.getLimit()+"  "+record.getOffset()+"  "+record.getBeginTime()+"  "+record.getEndTime()+"  "+record.isSort());
                if (record != null) {
                    sendUnloadOils(record.getLimit(), record.getOffset(), record.getBeginTime(), record.getEndTime(), record.isSort());
                }
            } else if (requestShift.equals(identifer)) {
                Log.i(TAG, "发布主题：请求卸油记录");
//                Log.i(TAG, "messageArrived: 进出油记录");
                RequestRecord record = GsonUtil.parseJsonWithGson(new String(message.getPayload()), RequestRecord.class);
//                        Log.i(TAG,"进出油记录"+record.getLimit()+"  "+record.getOffset()+"  "+record.getBeginTime()+"  "+record.getEndTime()+"  "+record.isSort());
                if (record != null) {
                    sendShifts(record.getLimit(), record.getOffset(), record.getBeginTime(), record.getEndTime(), record.isSort());
                }
            } else if (requestOilLeak.equals(identifer)) {
                Log.i(TAG, "发布主题：请求侧漏记录");
//                Log.i(TAG, "messageArrived: 进出油记录");
                RequestRecord record = GsonUtil.parseJsonWithGson(new String(message.getPayload()), RequestRecord.class);
//                        Log.i(TAG,"进出油记录"+record.getLimit()+"  "+record.getOffset()+"  "+record.getBeginTime()+"  "+record.getEndTime()+"  "+record.isSort());
                if (record != null) {
                    sendLeakOils(record.getLimit(), record.getOffset(), record.getBeginTime(), record.getEndTime(), record.isSort());
                }
            } else if (requestOilStorage.equals(identifer)) {
                Log.i(TAG, "发布主题：请求卸油记录");

//                Log.i(TAG, "messageArrived: 进出油记录");
                RequestRecord record = GsonUtil.parseJsonWithGson(new String(message.getPayload()), RequestRecord.class);
//                        Log.i(TAG,"进出油记录"+record.getLimit()+"  "+record.getOffset()+"  "+record.getBeginTime()+"  "+record.getEndTime()+"  "+record.isSort());
                if (record != null) {
                    sendOilStorage(record.getLimit(), record.getOffset(), record.getBeginTime(), record.getEndTime(), record.isSort());
                }
            }
        }

        /**
         * 发布报警数据
         * @param limit
         * @param offset
         * @param beginTime
         * @param endTime
         * @param sort
         */

        private void sendAlarms(int limit, int offset, String beginTime, String endTime, boolean sort) {
            List<Alarm> lists = DBDataManager.getInstance().findLimitRecord(Alarm.class, limit, offset, sort, beginTime, endTime);
            if (lists == null) {
                lists = new ArrayList<>();
            }
            String data = GsonUtil.getListToStringByGson(lists);
//            Log.i(TAG,"报警记录"+data);
            MqttMessage message = new MqttMessage();
            message.setQos(1);
            message.setRetained(false);
            message.setPayload(data.getBytes());
            MqttTopic topic = client.getTopic(MqttUtil.getPropert(productKey, deviceName, requestAlarm));
            try {
                publish(topic, message);
            } catch (MqttPersistenceException e) {
                Log.e(TAG, "sendAlarms MQTT操作失败(MqttPersistenceException)", e);
            } catch (MqttException e) {
                Log.e(TAG, "sendAlarms MQTT操作失败", e);
            }
        }


        /**
         * 发布报警数据
         * @param limit
         * @param offset
         * @param beginTime
         * @param endTime
         * @param sort
         */
        public void sendUnloadOils(int limit, int offset, String beginTime, String endTime, boolean sort) {
            Log.i(TAG, "卸油记录: 卸油记录  limit" + limit + " offset:" + offset + "sort: " + sort);

            List<OilInOut> lists = DBDataManager.getInstance().findLimitRecord(OilInOut.class, limit, offset, sort, beginTime, endTime);

            if (lists == null) {
                lists = new ArrayList<>();
            } else {
//                DetailInoutRecord detailInoutRecord;
//                for (OilInOut oilInOut : lists) {
//                    detailInoutRecord = (DetailInoutRecord) DBDataManager.getInstance().findObjectByID(DetailInoutRecord.class, oilInOut.getId());
//                    oilInOut.setRecord(detailInoutRecord);
//                }
            }
            String data = GsonUtil.getListToStringByGson(lists);
            Log.i(TAG, "卸油记录" + data);
            MqttMessage message = new MqttMessage();
            message.setQos(1);
            message.setRetained(false);
            message.setPayload(data.getBytes());
            MqttTopic topic = client.getTopic(MqttUtil.getPropert(productKey, deviceName, requestUnloadOil));
            try {
                publish(topic, message);
            } catch (MqttPersistenceException e) {
                Log.e(TAG, "sendUnloadOils MQTT操作失败(MqttPersistenceException)", e);
            } catch (MqttException e) {
                Log.e(TAG, "sendUnloadOils MQTT操作失败", e);
            }
        }

        /**
         * 发布交班记录
         * @param limit
         * @param offset
         * @param beginTime
         * @param endTime
         * @param sort
         */
        public void sendShifts(int limit, int offset, String beginTime, String endTime, boolean sort) {
            Log.i(TAG, "交班记录: 交班记录  limit" + limit + " offset:" + offset + "sort: " + sort);

            List<CommitWork> lists = DBDataManager.getInstance().findLimitRecord(CommitWork.class, limit, offset, sort, beginTime, endTime);
            if (lists == null) {
                lists = new ArrayList<>();
            }
            String data = GsonUtil.getListToStringByGson(lists);
            Log.i(TAG, "卸油记录" + data);
            MqttMessage message = new MqttMessage();
            message.setQos(1);
            message.setRetained(false);
            message.setPayload(data.getBytes());
            MqttTopic topic = client.getTopic(MqttUtil.getPropert(productKey, deviceName, requestShift));
            try {
                publish(topic, message);
            } catch (MqttPersistenceException e) {
                Log.e(TAG, "sendShifts MQTT操作失败(MqttPersistenceException)", e);
            } catch (MqttException e) {
                Log.e(TAG, "sendShifts MQTT操作失败", e);
            }
        }

        /**
         * 发布侧漏记录
         * @param limit
         * @param offset
         * @param beginTime
         * @param endTime
         * @param sort
         */
        public void sendLeakOils(int limit, int offset, String beginTime, String endTime, boolean sort) {
            Log.i(TAG, "测漏: 测漏记录  limit" + limit + " offset:" + offset + "sort: " + sort);

            List<Sideleakage> lists = DBDataManager.getInstance().findLimitRecord(Sideleakage.class, limit, offset, sort, beginTime, endTime);
            if (lists == null) {
                lists = new ArrayList<>();
            }
            String data = GsonUtil.getListToStringByGson(lists);
            Log.i(TAG, "卸油记录" + data);
            MqttMessage message = new MqttMessage();
            message.setQos(1);
            message.setRetained(false);
            message.setPayload(data.getBytes());
            MqttTopic topic = client.getTopic(MqttUtil.getPropert(productKey, deviceName, requestOilLeak));
            try {
                publish(topic, message);
            } catch (MqttPersistenceException e) {
                Log.e(TAG, "sendLeakOils MQTT操作失败(MqttPersistenceException)", e);
            } catch (MqttException e) {
                Log.e(TAG, "sendLeakOils MQTT操作失败", e);
            }
        }

        /**
         * 发布卸油记录
         * @param limit
         * @param offset
         * @param beginTime
         * @param endTime
         * @param sort
         */
        public void sendOilStorage(int limit, int offset, String beginTime, String endTime, boolean sort) {
            Log.i(TAG, "卸油记录: 卸油记录  limit" + limit + " offset:" + offset + "sort: " + sort);

            List<OilSaveInfo> lists = DBDataManager.getInstance().findLimitRecord(OilSaveInfo.class, limit, offset, sort, beginTime, endTime);
            if (lists == null) {
                lists = new ArrayList<>();
            }
            String data = GsonUtil.getListToStringByGson(lists);
            Log.i(TAG, "卸油记录" + data);
            MqttMessage message = new MqttMessage();
            message.setQos(1);
            message.setRetained(false);
            message.setPayload(data.getBytes());
            MqttTopic topic = client.getTopic(MqttUtil.getPropert(productKey, deviceName, requestOilStorage));
            try {
                publish(topic, message);
            } catch (MqttPersistenceException e) {
                Log.e(TAG, "sendOilStorage MQTT操作失败(MqttPersistenceException)", e);
            } catch (MqttException e) {
                Log.e(TAG, "sendOilStorage MQTT操作失败", e);
            }
        }
    }

    /**
     * 发布油罐信息
     */
    public void sendOilcanData() {
        List<NetOilCan> netOilcans = OilcanDataManager.getInstance(MyApplication.getInstance()).getNetOilcans();
        String data = JSONObject.toJSONString(netOilcans);
        Log.i(TAG, "油管信息" + data);
        MqttMessage message = new MqttMessage();
        message.setQos(1);
        message.setRetained(false);
        message.setPayload(data.getBytes());
        MqttTopic topic = client.getTopic(MqttUtil.getPropert(productKey, deviceName, requestOilcan));

        try {
            publish(topic, message);
        } catch (MqttPersistenceException e) {
            Log.e(TAG, "sendOilcanData MQTT操作失败(MqttPersistenceException)", e);
        } catch (MqttException e) {
            Log.e(TAG, "sendOilcanData MQTT操作失败", e);
        }
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        netListener.unregisterListener();
    }
}
