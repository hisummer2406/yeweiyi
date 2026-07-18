package com.sansen.yeweiyi.factory;

import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolEncoderAdapter;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

public class ByteArrayEncoder extends ProtocolEncoderAdapter {
	  
    @Override  
    public void encode(IoSession session, Object message,
                       ProtocolEncoderOutput out){
        out.write(message);  
        out.flush(); 
    }
    
}  
