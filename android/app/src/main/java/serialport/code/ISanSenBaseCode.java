package serialport.code;

public interface ISanSenBaseCode {
    public byte[]  encode(byte address);
    boolean decode(byte[] data);
}
