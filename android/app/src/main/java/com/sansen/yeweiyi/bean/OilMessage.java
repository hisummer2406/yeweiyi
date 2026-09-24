package com.sansen.yeweiyi.bean;


import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.utils.SystemUtils;

public class OilMessage {
    public static final int HEIGHT_OIL_ALARM = 1;
    public static final int HHEIGHT_OIL_ALARM = 2;

    public static final int LOW_OIL_ALARM = 3;
    public static final int LLOW_OIL_ALARM = 4;

    public static final int HEIGHT_WATER = 5;
    public static final int HEIGHT_TEMPER = 6;
    public static final int LOW_TEMPER = 7;
    public static final int OUT_OIL = 8;
    public static final int IN_OIL = 9;


    public static final int PRO_ERR_CONN = 10;//油罐连接失败

    public static final int NET_ERR_CONN = 11;

    public static final int RELAY_DISCONNECT = 12;

    public static final int OIl_EXPLORATION = 13;

    public static final int OUT_OILing = 14;

    public static final int IN_OILing = 15;
    private int id;

    private String d2d;
    //消息Id
    private String msg_id;
    //加油站名字
    private String gasName;
    //消息类型
    private int type;
    //时间
    private String date;
    //内容
    private double value;
    //油罐ID
    private int oilCanId;
    //当前消息重复显示了多少次
    private int size;
    private String jieZhi;

    public String getJieZhi() {
        return jieZhi;
    }

    public void setJieZhi(String jieZhi) {
        this.jieZhi = jieZhi;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getGasName() {
        return gasName;
    }

    public void setGasName(String gasName) {
        this.gasName = gasName;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double content) {
        value = content;
    }

    public int getOilCanId() {
        return oilCanId;
    }

    public void setOilCanId(int oilCanId) {
        this.oilCanId = oilCanId;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    @Override
    public boolean equals(Object o) {
        OilMessage oilMessage = (OilMessage) o;
        if (oilMessage.getType() == type && oilMessage.getOilCanId() == oilCanId) {
            return true;
        } else {
            return false;
        }
    }

    public String getMsg_id() {
        return msg_id;
    }

    public void setMsg_id(String msg_id) {
        this.msg_id = msg_id;
    }

    @Override
    public String toString() {

        return getState(oilCanId, type, value);
    }

    /**
     * 各状态对应的提示文案资源。文案本身（含罐号占位符 %1$d 的位置/语序）由
     * values / values-en 各自的资源串决定，这里不再按语言分支拼字符串——
     * 否则每加一种语言就要在这里多加一个 else if。
     */
    // [已更正-2026-09-23] 这里之前有一条 [不可达] 注释，说"整个方法都没有活的消费方"——是错的，
    // 用户复核时指出 AlarmDetection 的报警记录明明显示在报警记录页，我才发现漏查了第三条消费路径：
    // AlarmRepositoryImpl.saveAlarm(pos, oilMessage) 会把 oilMessage.toString()（也就是本方法的
    // 返回值）存进 Alarm.info 字段，AlarmAdapter.onBindViewHolder() 直接
    // alarm_tv_alarminfo.setText(alarm.getInfo()) 显示在报警记录页列表里——这条路径是活的，
    // 我之前只查了 AlarmCoordinator.barMainAlram（死）和 SocketManager.sendAlarm（死）两条，
    // 漏了这条。HEIGHT_OIL_ALARM/HHEIGHT_OIL_ALARM/LOW_OIL_ALARM/LLOW_OIL_ALARM/HEIGHT_WATER/
    // HEIGHT_TEMPER 这 6 个 case 经 AlarmDetection 对应方法 setType() 后确实会走到
    // alarmRepository.saveAlarm(...)，是可达的，要优先翻。RELAY_DISCONNECT 同理，AsyManager2 的
    // errorListener 直接调 AlarmRepositoryImpl.saveAlarmRecord(oilMessage.toString(),"","")，
    // 也可达（受 SharePreManager.getRelaySwitch() 开关控制，运行时可开可关，不是恒假分支）。
    // 真正确认不可达的只有：LOW_TEMPER（producer 侧的 tempLowDection() 从未被调用，见下方注释）、
    // IN_OIL/OUT_OIL/IN_OILing/OUT_OILing（全仓库没人 setType() 过这 4 个，见下方注释）、
    // OIl_EXPLORATION（producer 侧 GasExplorationAdapter 死，见下方注释）、PRO_ERR_CONN——这个比较
    // 特殊：AlarmRepositoryImpl.saveAlarm() 方法体第一行就是
    // `if (oilMessage.getType() == OilMessage.PRO_ERR_CONN) return;`，明确把它排除在存库逻辑外，
    // 所以虽然 SerialPortManager 里确实调用了 saveAlarm(...)，实际会被这个 return 挡掉，consumer
    // 侧仍是死的——oil_connect_error 不用优先翻；NET_ERR_CONN 的唯一 setType() 调用点在
    // BackService.java，而那个 Service 全仓库没有任何地方真正 startService() 过，producer 侧本身
    // 就没跑起来，net_connect_err 不用优先翻。
    private String getState(int oilid, int state, double value) {
        switch (state) {
            case HEIGHT_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oilHeight_alarm, oilid);
            case HHEIGHT_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oilHHeight_alarm, oilid);
            case LOW_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oillow_alarm, oilid);
            case LLOW_OIL_ALARM:
                return SystemUtils.getRsStr(R.string.oilllow_alarm, oilid);
            case HEIGHT_WATER:
                return SystemUtils.getRsStr(R.string.waterheight_alarm, oilid);
            case HEIGHT_TEMPER:
                return SystemUtils.getRsStr(R.string.tempheight_alarm, oilid);
            // [不可达-2026-09-21] LOW_TEMPER：唯一 setType 调用点是 AlarmDetection.tempLowDection()，
            // 而这个方法全仓库没有任何地方调用（西语翻译覆盖率核查时发现）。templow_alarm 不用优先翻。
            case LOW_TEMPER:
                // [已屏蔽-2026-09-23] templow_alarm 已删除（不可达，见上方注释）。
                return null; // return SystemUtils.getRsStr(R.string.templow_alarm, oilid);
            // [不可达-2026-09-21] IN_OIL/OUT_OIL/IN_OILing/OUT_OILing 这 4 个 type 全仓库没有任何地方
            // setType() 过（西语翻译覆盖率核查时发现）。inoil/outoil/inoiling/outoiling 不用优先翻。
            // [已屏蔽-2026-09-23] inoil/outoil/inoiling/outoiling 已删除（不可达，见上方注释）。
            case IN_OIL:
                return null; // return SystemUtils.getRsStr(R.string.inoil, oilid);
            case OUT_OIL:
                return null; // return SystemUtils.getRsStr(R.string.outoil, oilid);
            case IN_OILing:
                return null; // return SystemUtils.getRsStr(R.string.inoiling, oilid);
            case OUT_OILing:
                return null; // return SystemUtils.getRsStr(R.string.outoiling, oilid);
            // [不可达-2026-09-23] SerialPortManager 确实调用了 alarmRepository.saveAlarm(...)，但
            // AlarmRepositoryImpl.saveAlarm() 方法体第一行就是
            // `if (oilMessage.getType() == OilMessage.PRO_ERR_CONN) return;`——明确把这个类型
            // 排除在存库/报警记录页展示之外，consumer 侧仍是死的。oil_connect_error 不用优先翻。
            case PRO_ERR_CONN:
                // [已屏蔽-2026-09-23] oil_connect_error 已删除（不可达，见上方注释）。
                return null; // return SystemUtils.getRsStr(R.string.oil_connect_error, oilid);
            // [不可达-2026-09-21] 唯一 setType 调用点是 GasExplorationAdapter，而那个类的 getView()
            // 因为数据源从未赋值、getCount() 恒为 0 而永远不会被框架调用（见 GasExplorationAdapter.java
            // 里的注释，西语翻译覆盖率核查时发现）。tantou_height 不用优先翻。
            case OIl_EXPLORATION:
                // [已屏蔽-2026-09-23] tantou_height 已删除（不可达，见上方注释）。
                return null; // return SystemUtils.getRsStr(R.string.tantou_height, oilid);
            // [不可达-2026-09-21] 唯一 setType 调用点在 BackService.java，而这个 Service 在全仓库没有
            // 任何地方真正 startService() 过（西语翻译覆盖率核查时发现）。net_connect_err 不用优先翻。
            case NET_ERR_CONN:
                // [已屏蔽-2026-09-23] net_connect_err 已删除（不可达，见上方注释）。
                return null; // return SystemUtils.getRsStr(R.string.net_connect_err);
            // [可达-2026-09-23] AsyManager2 的 errorListener 直接调
            // AlarmRepositoryImpl.saveAlarmRecord(oilMessage.toString(),"","")，存进报警记录表、
            // 显示在报警记录页——受 SharePreManager.getRelaySwitch() 开关控制（运行时可开可关，
            // 不是恒假分支）。fyf_connect_error 要优先翻。
            case RELAY_DISCONNECT:
                return SystemUtils.getRsStr(R.string.fyf_connect_error);
            default:
                return null;
        }
    }

    public String getD2d() {
        return d2d;
    }

    public void setD2d(String d2d) {
        this.d2d = d2d;
    }

}
