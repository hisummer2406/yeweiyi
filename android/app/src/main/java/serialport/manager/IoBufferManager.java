package serialport.manager;

import serialport.buff.IoBuffer;
import serialport.code.ISanSenCode;

/**
 * Created by Administrator on 2019-04-28.
 */

public class IoBufferManager {
    private static final String TAG ="IoBufferManager";
    private IoBuffer buffer;
    private ISanSenCode sanSenCode;
    private IoHandler handler;

    public  IoBufferManager(ISanSenCode sanSenCode) {
        this.sanSenCode=sanSenCode;
    }

    public synchronized void setBuffer(IoBuffer in) {
        if (buffer!=null&&buffer.remaining() > 0) {
            IoBuffer tempBuffer = IoBuffer.allocate(in.remaining() + buffer.remaining()).setAutoExpand(true);
            tempBuffer.put(buffer);
            tempBuffer.put(in);
            tempBuffer.flip();
            buffer.clear();
            buffer = tempBuffer;
        } else {
            buffer = in;
        }
        decode();
    }

    private void decode() {
        if (sanSenCode!=null) {

            while (buffer.hasRemaining()&&sanSenCode.decode(this,buffer)){
            }
        }
    }

    public synchronized void setBuffer(byte[] in) {
        IoBuffer inBuffer = null;
        if (in != null && in.length != 0) {
            inBuffer = IoBuffer.allocate(in.length);
            inBuffer.put(in);
            inBuffer.position(0);
        }
        if (buffer!=null&&buffer.remaining() > 0) {
            IoBuffer tempBuffer = IoBuffer.allocate(in.length + buffer.remaining()).setAutoExpand(true);
            tempBuffer.put(buffer);
            tempBuffer.put(inBuffer);
            tempBuffer.flip();
            buffer.clear();
            buffer = tempBuffer;
        } else {
            buffer = inBuffer;
        }
        decode();
    }
    public void setIoHandler(IoHandler handler){
        this.handler = handler;
    }
    public IoBuffer getBuffer() {
        return buffer;
    }
    public interface IoHandler{
        public void resultObjet(Object object);
    }
    public void write(Object object) {
        handler.resultObjet(object);
    }
}
