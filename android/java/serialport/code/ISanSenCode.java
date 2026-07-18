package serialport.code;

import serialport.buff.IoBuffer;
import serialport.manager.IoBufferManager;

/**
 * Created by Administrator on 2019-04-28.
 */
public interface ISanSenCode {

    public boolean decode(IoBufferManager ioBufferManager, IoBuffer buffer);

    /**
     *  请重写该方法 务必对该方法加锁！
     */
    byte[] encode(byte ChainNo, byte commd, byte[] bytes);
}
