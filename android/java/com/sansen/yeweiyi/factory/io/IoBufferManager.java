package com.sansen.yeweiyi.factory.io;

import org.apache.mina.core.buffer.IoBuffer;

public class IoBufferManager {
	private static IoBufferManager instance;
	private IoBuffer buffer;
	private ISanSenCode sanSenCode;
	private IoHandler handler;
	public static synchronized IoBufferManager getInstance(ISanSenCode sanSenCode) {
		if (instance == null) {
			instance=new IoBufferManager(sanSenCode);
		}
		return instance;
	}

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

			while (buffer.hasRemaining()&&sanSenCode.decode(instance,buffer)){
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
			System.out.println(tempBuffer.limit());
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
	public synchronized IoBuffer getBuffer() {
		return buffer;
	}
	public interface IoHandler{
		public void resultObjet(Object object);
	}
	public void write(Object object) {
		handler.resultObjet(object);
	}
}
