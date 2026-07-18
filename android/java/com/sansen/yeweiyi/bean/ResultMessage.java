package com.sansen.yeweiyi.bean;


public class ResultMessage {
	
	public final static int PSW_ERROR=5001;
	public final static int SESSION_EXPIRED=5002;
	public final static int SESSION_IsNoExit=5003;
	public final static int SEND_VERIFICATION_ERR=5004;
	public final static int VERIFICATION_ERR=5005;
	public static final int USER_EXIT_ERROR = 5006;
	public static final int REGISTER_VERIFICATION_ERR = 5007;
	public static final int SAVE_D2D_ERR = 5008;
	public static final int SAVE_D2D_EXIT = 5009;
	public static final int SESSION_LOGIN_ERROR = 5010;
	public final static int SAVE_INOUT_ERROR=5011;
	public final static int DELETE_D2D_ERROR=5012;
	public final static int REQUEST_D2D_ERRER = 5013;
	public final static int UPDATE_D2D_ERROR = 5014;
	
	public final static int LOGIN_SUCCESS=2001;
	public final static int SESSION_SUCCESS=2002;
	public final static int SEND_VERIFICATION_SUCCESS=2003;
	
	public final static int REGISTER_SUCCESS=2004;
	public static final int SAVE_D2D_SUCCESS =2005;
	public final static int SAVE_INOUT_SUCCESS=2006;
	public final static int DELETE_D2D_SUCCESS=2007;
	public final static int REQUEST_D2D_SUCCESS = 2008;
	public final static int UPDATE_D2D_SUCCESS = 2009;
	
	
	private static ResultMessage instance;
	
	private int code;
	private String msg;
	
	private ResultMessage(){
	}
	
	public static ResultMessage getInstance(int code){
		if (instance==null) {
			instance = new ResultMessage();
		}
		instance.setCode(code);
		return instance;
	}
	
	public int getCode() {
		return code;
	}

	public void setCode(int code) {
		this.code = code;
	}

	public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}
	
	@Override
	public String toString() {
		return "ResultMessage [code=" + code + ", msg=" + msg + "]";
	}

}
