package asynmsg.manage;

import android.databinding.ObservableField;
import android.util.Log;

import com.sansen.yeweiyi.bean.Relay;
import com.sansen.yeweiyi.intf.IHardIntf;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;
import com.sansen.yeweiyi.utils.Singleton;
import com.sansen.yeweiyi.utils.ToastUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import asynmsg.intf.IAsyData;
import serialport.help.SerialPortHelper;
import serialport.manager.QCode;

/**
 * Created by PC on 2019-04-09.
 */

public class AsyManager implements IHardIntf {

    private static final String TAG = "AsyManager";
    public static volatile AsyManager instance;

    private AtomicInteger chainNumber = new AtomicInteger(1);

    public Map<Integer, Relay> relayMap = new LinkedHashMap<>();

    public Map<Integer, Integer> strengthMap = new HashMap<>();
    private ObservableManager manager;
    private boolean isYmode;

    private ObservableField<String> hardVersion = new ObservableField<>("");

    /**
     * 通信状态
     */
    public ObservableField<Boolean> isConnect = new ObservableField<>(false);

    public static AsyManager getInstance() {
        if (instance == null) {
            synchronized (AsyManager.class) {
                if (instance == null) {
                    instance = new AsyManager();
                }
            }
        }
        return instance;
    }


    private AsyManager() {
        if (relayMap.size() == 0) {
            for (int i = 1; i <= 8; i++) {
                relayMap.put(i, new Relay(i, false));
            }
        }
        manager = new ObservableManager();
        manager.setUpdateListener(new ObservableManager.UpdateListener() {
            int count = 0;

            @Override
            public void update(long aLong) {
//                Log.i(TAG, "update: aLong" + aLong);
//                100毫秒为单位
                if (aLong % 5 == 0) {
                    queryStatus(queryStatus);
                }
            }
        });

        errorListener(new ObservableManager.IErrorCodeListener() {
            @Override
            public void error(int error) {
            //    Log.i(TAG, "连接状态: " + error);
//                if (error > 5) {
//                    AsyManager.getInstance().isConnect.set(false);
//                    if (SharePreManager.getRelaySwitch()) {
//                      //  Log.i(TAG, "error: 驱动板连接失败11111");
//                        OilMessage oilMessage = new OilMessage();
//                        oilMessage.setDate(DateUtils.getYYYMMddHHmmss());
//                        oilMessage.setType(OilMessage.RELAY_DISCONNECT);
//                        AlarmManager.getInstance().addAlarmState(oilMessage);
//                        DBDataManager.getInstance().setAramRecord(oilMessage.toString(),"","");
//                        SystemState.getInstance().setDriverBoardConnect(false);
//                    }
//                } else {
//                    AsyManager.getInstance().isConnect.set(true);
//                    Log.i(TAG, "error: 驱动板连接成功11111");
//                }
            }
        });
    }

    public AtomicInteger getChainNumber() {
        chainNumber.incrementAndGet();
        if (chainNumber.get() > 254) {
            chainNumber.set(0);
        }
        return chainNumber;
    }

    public AsyManager setSerportHelp(SerialPortHelper serportHelp) {
        if (manager != null) {
            manager.setSerportHelp(serportHelp);
        } else {
            throw new RuntimeException();
        }
        return instance;
    }

    public void recive(IAsyData asyData) {
        manager.recive(asyData.getChainNo(), asyData);
    }


    private ObservableManager.ReciveListener queryStatus = new ObservableManager.ReciveListener() {
        @Override
        public void recive(int id, IAsyData object) {
            Log.i(TAG, "recive: 接收状态" + DataUtils.bytesToHexString(object.getData(), object.getData().length));
            byte[] bytes = object.getData();
            if (bytes != null && object.getData().length == 20) {
                relayMap.get(1).setStatus(getIsOpen(bytes[0]));
                relayMap.get(2).setStatus(getIsOpen(bytes[1]));
                relayMap.get(3).setStatus(getIsOpen(bytes[2]));
                relayMap.get(4).setStatus(getIsOpen(bytes[3]));
                relayMap.get(5).setStatus(getIsOpen(bytes[4]));
                relayMap.get(6).setStatus(getIsOpen(bytes[5]));
                relayMap.get(7).setStatus(getIsOpen(bytes[6]));
                relayMap.get(8).setStatus(getIsOpen(bytes[7]));

                if (getIsOpen(bytes[8])) {
                    byte[] content = new byte[2];
                    System.arraycopy(bytes, 9, content, 0, content.length);
                    strengthMap.put(1, (int) DataUtils.byte2Short(content));
                } else {
                    strengthMap.remove(1);
                }

                if (getIsOpen(bytes[11])) {
                    byte[] content = new byte[2];
                    System.arraycopy(bytes, 12, content, 0, content.length);
                    strengthMap.put(2, (int) DataUtils.byte2Short(content));
                } else {
                    strengthMap.remove(2);
                }

                if (getIsOpen(bytes[14])) {
                    byte[] content = new byte[2];
                    System.arraycopy(bytes, 15, content, 0, content.length);
                    strengthMap.put(3, (int) DataUtils.byte2Short(content));
                } else {
                    strengthMap.remove(3);
                }

                if (getIsOpen(bytes[17])) {
                    byte[] content = new byte[2];
                    System.arraycopy(bytes, 18, content, 0, content.length);
                    strengthMap.put(4, (int) DataUtils.byte2Short(content));
                } else {
                    strengthMap.remove(4);
                }
            }
        }

        @Override
        public void error(int id) {

//            Log.i(TAG, "error: " + id);
        }
    };

    public boolean getIsOpen(byte value) {
        if (value == (byte) 66) {
            return true;
        } else {
            return false;
        }
    }

    public void errorListener(ObservableManager.IErrorCodeListener listener) {
        manager.setIErrorCodeListener(listener);
    }

    public void openYmode() {
        manager.destory();
        isYmode = true;

    }

    public YmodeReciveLinstence listence;

    public void setListence(YmodeReciveLinstence listence) {
        this.listence = listence;
    }

    public void unListence() {
        this.listence = null;
    }

    public void stopYmode() {
        isYmode = false;
        manager.addDisposable();
    }


    @Override
    public void openOne(int relayNo, byte isAutoClose, ObservableManager.ReciveListener reciveListener) {
        byte chain = (byte) getChainNumber().shortValue();
        byte[] bytes = new byte[2];
        bytes[0] = (byte) relayNo;
        bytes[1] = isAutoClose;
        byte[] content = new QCode().encode(chain, (byte) 0xA1, bytes);
        manager.sendData(new byte[]{0, chain}, content, 3000, reciveListener);
    }

    @Override
    public void openOne(int relayNo, int isAutoClose, ObservableManager.ReciveListener reciveListener) {
        if (Singleton.getInstance().ChuanKou4){
            byte chain = (byte) getChainNumber().shortValue();
            byte[] bytes = new byte[1];
            bytes[0] = (byte) relayNo;
            StringBuffer sb = new StringBuffer();
            // sb.append(command);
            sb.append( "01");
            sb.append("0600");

            sb.append(String.format("%02d", relayNo-1));
            sb.append("00");
            sb.append(String.format("%02d", isAutoClose));
            String crc = getString(CRCheck.crc162(DataUtils.HexString2Bytes(sb.toString())));
            sb.append(crc);
            manager.sendData( new byte[]{0, chain},DataUtils.HexString2Bytes(sb.toString()), 3000, reciveListener);
        }
        else {
            byte chain = (byte) getChainNumber().shortValue();
            byte[] bytes = new byte[1];
            bytes[0] = (byte) relayNo;
            StringBuffer sb = new StringBuffer();
            // sb.append(command);
            sb.append("FEFE0101");
            sb.append(String.format("%02d", relayNo+2) );
            sb.append(String.format("%02d", isAutoClose));
            String crc = getString(CRCheck.crc12(sb.toString().length(),DataUtils.HexString2Bytes(sb.toString())));
            sb.append(crc);
            manager.sendData( new byte[]{0, chain},DataUtils.HexString2Bytes(sb.toString()), 3000, reciveListener);
        }

        // System.out.println("CRC:" + crc+"---"+sb.toString());


    }
    public String getString(String data) {
        while (data.length() < 4) {
            data = 0 + data;
        }
        return data;
    }

    @Override
    public void closeOne(int relayNo, ObservableManager.ReciveListener reciveListener) {
        byte chain = (byte) getChainNumber().shortValue();
        byte[] bytes = new byte[1];
        bytes[0] = (byte) relayNo;
        byte[] content = new QCode().encode(chain, (byte) 0xA2, bytes);
        manager.sendData(new byte[]{0, chain}, content, 3000, reciveListener);
    }

    @Override
    public void openAll(ObservableManager.ReciveListener reciveListener) {
        byte chain = (byte) getChainNumber().shortValue();
        byte[] bytes = new byte[0];
        byte[] content = new QCode().encode(chain, (byte) 0xA3, bytes);
        manager.sendData(new byte[]{0, chain}, content, 3000, reciveListener);
    }

    @Override
    public void closeAll(ObservableManager.ReciveListener reciveListener) {
        byte chain = (byte) getChainNumber().shortValue();
        byte[] bytes = new byte[0];
        byte[] content = new QCode().encode(chain, (byte) 0xA4, bytes);
        manager.sendData(new byte[]{0, chain}, content, 3000, reciveListener);
    }

    //查询版本号
    @Override
    public void queryVersion(ObservableManager.ReciveListener reciveListener) {
        byte chain = (byte) getChainNumber().shortValue();
        byte[] bytes = new byte[0];
        byte[] content = new QCode().encode(chain, (byte) 0xA5, bytes);
        manager.sendData(new byte[]{0, chain}, content, 3000, reciveListener);
    }

    //查询状态
    @Override
    public void queryStatus(ObservableManager.ReciveListener reciveListener) {
        byte chain = (byte) getChainNumber().shortValue();
        byte[] bytes = new byte[0];
        byte[] content = new QCode().encode(chain, (byte) 0xA6, bytes);
        manager.sendData(new byte[]{0, chain}, content, 3000, reciveListener);
    }

    StateListener stateLIstener;


    public interface StateListener {
        void listener(int flowNo, int flowState);
    }

    public void setStateListener(StateListener listener) {
        this.stateLIstener = listener;
    }


    public interface YmodeReciveLinstence {
        public void reciveData(byte[] bytes);
    }

    public void queryVersion() {
        queryVersion(new ObservableManager.ReciveListener() {
            @Override
            public void recive(int id, IAsyData object) {
                if (object != null && object.getData().length == 3) {
                    byte[] bytes = new byte[2];
                    bytes[0] = object.getData()[0];
                    bytes[1] = object.getData()[1];
                    String version = new String(bytes);
                    hardVersion.set(version + "." + object.getData()[2]);
                    ToastUtils.showToastLong("版本: " + hardVersion.get());
                    Log.i(TAG, "版本: " + hardVersion.get());
                }
            }

            @Override
            public void error(int id) {

            }
        });
    }

    public void openOneRelay(final int relayNo, byte isAutoClose) {
        openOne(relayNo, isAutoClose, new ObservableManager.ReciveListener() {
            @Override
            public void recive(int id, IAsyData object) {
                if (object != null && object.getData().length == 1) {
                    if (object.getData()[0] == 0x66) {
                        Log.i(TAG, "recive: " + relayNo + "号继电器打开成功");
                    }
                }
            }

            @Override
            public void error(int id) {

            }
        });
    }

    public void closeOneRelay(final int relayNo, byte isAutoClose) {
        closeOne(relayNo, new ObservableManager.ReciveListener() {
            @Override
            public void recive(int id, IAsyData object) {
                if (object != null && object.getData().length == 1) {
                    if (object.getData()[0] == 0x66) {
                        Log.i(TAG, "recive: " + relayNo + "号继电器关闭成功");
                    }
                }
            }

            @Override
            public void error(int id) {

            }
        });
    }

    public void closeAllRelay() {
        closeAll(new ObservableManager.ReciveListener() {
            @Override
            public void recive(int id, IAsyData object) {
                if (object != null && object.getData().length == 1) {
                    if (object.getData()[0] == 0x66) {
                        Log.i(TAG, "recive:所有继电器关闭成功");
                    }
                }
            }

            @Override
            public void error(int id) {

            }
        });
    }

    public void openAllRelay() {
        if (SharePreManager.getRelayMod() == SharePreManager.XYB) {
            openAll(new ObservableManager.ReciveListener() {
                @Override
                public void recive(int id, IAsyData object) {
                    if (object != null && object.getData().length == 1) {
                        if (object.getData()[0] == 0x66) {
                            Log.i(TAG, "recive:所有继电器关闭成功");
                        }
                    }
                }

                @Override
                public void error(int id) {

                }
            });
        } else {
            ToastUtils.showToastLong("防溢阀模式无法使用全开命令");
        }
    }
}
