package asynmsg.intf;

/**
 * Created by Administrator on 2019/4/18.
 */

public interface IAsyData {

    void setData(byte[] bytes);

    byte[] getData();

    int getChainNo();

    void setChainNo(int chainNo);

    byte getCommd();

    void setCommd(byte commd);

}
