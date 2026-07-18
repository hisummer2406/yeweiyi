package com.sansen.yeweiyi.logic;

import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.db.Authorization;
import com.sansen.yeweiyi.db.User;
import com.sansen.yeweiyi.manager.DBDataManager;

import java.util.List;
public class LoginUser {
	private static LoginUser mInstance;
	
	private MyApplication myApplication;
	private DBDataManager db;

	private LoginUser() {
		myApplication = MyApplication.getInstance();
		db = myApplication.getInstance().getmDBManager();
	}

	public static LoginUser getInstance() {
		if (mInstance == null) {
			mInstance = new LoginUser();
		}
		return mInstance;
	}

	/**
	 * 用户登录检测
	 * 
	 * @param 用户名
	 * @param psw
	 * @param permission
	 * @return true 登录成功 false 登录失败
	 */
	public boolean loginUser(String id, String psw, int rember) {
		User login = new User();
		login.setId(id);
		login.setPsw(psw);
		
		login.setRemberPsd(rember);
		
		//检测数据库中是否有该对象
		if (db.isUserExist(login)) {
			//将用户存到公共对象中
			myApplication.setUser(login);
			//得到该用户用户的权限，并存到公共对象中
			Authorization authorization = myApplication.getInstance().getmDBManager().findAuthorization(login.getId());
			myApplication.setAuthorization(authorization);
			
			db.UpdateObject(login);
			return true;
		} else {
			return false;
		}

	}


	/**
	 * 用户登录检测
	 *
	 * @param 用户名
	 * @param psw
	 * @param permission
	 * @return true 登录成功 false 登录失败
	 */
	public boolean loginUser(String id, String psw) {
		User login = new User();
		login.setId(id);
		login.setPsw(psw);
		//检测数据库中是否有该对象
		if (db.isUserExist(login)) {
			//将用户存到公共对象中
			return true;
		} else {
			return false;
		}

	}

	/**
	 * 用户注册
	 * 
	 * @param id
	 * @param psw
	 * @param permission
	 * @return true 注册成功 false 注册失败（账户已存在）
	 */
	public boolean registerUser(String id, String psw) {
		/**
		 * 是否有跟当前id一样的账号
		 */
		User login = new User();
		login.setId(id);
		login.setPsw(psw);
		if (db.chekIsExists(User.class, id)) {
			return false;
		} else {
			db.saveObject(login);
			return true;
		}
	}

	/**
	 * 校验用户数据库是否为空
	 */
	private boolean checkUserNull() {
		return db.checkObjectNotNull(User.class);
	}
	/**
	 * 获取所有的用户
	 * @return
	 */
	public List<User> getAllUser(){
		return db.findAllObject(User.class);
	}

}
