package com.sansen.yeweiyi.factory.io;

import org.apache.mina.core.buffer.IoBuffer;

public interface ISanSenCode {
	public byte[]  encode(byte commd,byte[]random,byte[]data);
	boolean decode(IoBufferManager manager, IoBuffer in);
}
