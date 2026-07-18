package com.sansen.yeweiyi.factory;

import android.util.Log;

import com.sansen.yeweiyi.utils.CRCheck;
import com.sansen.yeweiyi.utils.DataUtils;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;

import java.util.HashMap;

public class ByteArrayDecoder extends CumulativeProtocolDecoder {
	public String TAG ="ByteArrayDecoder";
	@Override
	protected boolean doDecode(IoSession ioSession, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
		int startPosition;//记住最开始的位置

		int limit = in.limit();
		byte[] bytes = new byte[limit];
		in.mark();
		in.get(bytes);
		String hexData = DataUtils.bytesToHexString(bytes, bytes.length);
		//Log.i(TAG, "doDecode: "+"解析"+hexData);
		in.reset();

		while (in.hasRemaining()) {
			byte b = in.get();
			if (DataUtils.byteToHexString(b).equalsIgnoreCase("FA")) {
				startPosition = in.position()-1;
				b = in.get();
				if (DataUtils.byteToHexString(b).equalsIgnoreCase("FA")) {
					b = in.get();
					if (DataUtils.byteToHexString(b).equalsIgnoreCase("FA")) {
						in.mark();
						String type = DataUtils.byteToHexString(in.get()).toUpperCase();
						//Log.i(TAG, "doDecode: "+"类型"+type);
						byte length[]= new byte[2];
						in.get(length);
						String lengthHex= DataUtils.bytesToHexString(length);
						//Log.i(TAG, "doDecode: "+"数据长度Hex"+lengthHex);
						int lengthDec = Integer.parseInt(lengthHex, 16);

						//Log.i(TAG, "doDecode: "+"数据长度DEC"+lengthDec);
						if (in.remaining()<lengthDec-2) {//-2 减去FFFFFF跟+CRC
							in.position(startPosition);
							return false;
						}
						byte data[] = new byte[lengthDec];
						in.reset();
						in.get(data);
						String content= DataUtils.bytesToHexString(data);
						System.out.println("内容"+ DataUtils.bytesToHexString(data));
						byte crc=in.get();
						System.out.println("CRC"+ DataUtils.byteToHexString(crc));
						decodeSanSen(out,type,data,crc);
					}
				}
			}
		}
		return true;
	}

	private void decodeSanSen(ProtocolDecoderOutput out, String type, byte[] data, byte crc) {

		byte crcValue = CRCheck.getInstance().calculate_crc8_maxim(data, data.length);
		HashMap<String, Object> hashMap=new HashMap<String, Object>();
		if (crcValue==crc) {
			Log.i(TAG, "decodeSanSen: CRC校验成功 "+type);
			if (type.equals("D2")) {
				//解析油罐
				hashMap.put("D2", "数据流");
				out.write(hashMap);
			}else if(type.equals("D3")){
				//解析报警消息
//				hashMap.put("D3",decodeMsg(data));
				out.write(hashMap);
			}else if(type.equals("D4")){
				//解析进出油消息
//				hashMap.put("D4", decodeOilCan(data));
				out.write(hashMap);
			}else if(type.equals("D5")){
				//解析进出油消息
//				hashMap.put("D4", decodeOilCan(data));
				out.write(hashMap);
			}else if(type.equals("D6")){
				//解析进出油消息
//				hashMap.put("D4", decodeOilCan(data));
				out.write(hashMap);
			}

		}else{
			//Log.i(TAG, "decodeSanSen: CRC校验失败");
		}

	}

}