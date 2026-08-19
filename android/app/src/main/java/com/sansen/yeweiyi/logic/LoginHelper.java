package com.sansen.yeweiyi.logic;

import android.util.Log;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.widget.Toast;

import com.lidroid.xutils.HttpUtils;
import com.lidroid.xutils.exception.HttpException;
import com.lidroid.xutils.http.ResponseInfo;
import com.lidroid.xutils.http.callback.RequestCallBack;
import com.lidroid.xutils.http.client.HttpRequest.HttpMethod;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.bean.UpdataBean;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.XmlParseUtil;

import java.io.File;

public class LoginHelper {

	private static LoginHelper login;
	private Activity context;
	private UpdataBean bean;

	private final int UPDATA = 11;// 更新
	private final int CONNECTERROR = 12;// 连接服务器失败
	private final int SERVICEERROR = 13;// 服务器出错
	private final int DOWNLOADERROR = 14;// 下载失败
	private ProgressDialog pd;

	private LoginHelper(Activity context) {
		this.context = context;
	}

	public static LoginHelper getInstance(Activity context) {
		if (login == null) {
			login = new LoginHelper(context);
		}
		return login;
	}

	/**
	 * 连接服务器
	 */
	public void loginConnect() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				connectNet();
			}
		}).start();
	}

	/**
	 * 访问服务器
	 */
	protected void connectNet() {
		String apkurl = context.getResources().getString(R.string.apkurl);
		final Message message =Message.obtain();
		HttpUtils http = new HttpUtils();
		http.send(HttpMethod.GET, apkurl, new RequestCallBack<String>() {
			@Override
			public void onLoading(long total, long current, boolean isUploading) {

			}

			@Override
			public void onSuccess(ResponseInfo<String> responseInfo) {

				try {
					bean = XmlParseUtil.getUpdataInfo(XmlParseUtil
							.StringTOInputStream(responseInfo.result));
				} catch (Exception e) {
				    Log.e("LoginHelper", "onSuccess 执行失败", e);
				}

				if (bean != null) {
					if (bean.getVersiion().equals(
							SystemUtils.getAppVersionCode(context))) {
						// 无需更新,已经是最新版本,进入主界面
						// enterMain();
					} else {
						// 有新版本,需要更新,弹开提示
						message.what = UPDATA;
						handler.sendMessage(message);
					}
				}
			}

			@Override
			public void onStart() {
			}

			@Override
			public void onFailure(HttpException error, String msg) {
				if (error.getExceptionCode() == 0) {
					System.out.println("服务器连不上");
					// 服务器连不上
					message.what = CONNECTERROR;
					handler.sendMessage(message);
				} else {
					System.out.println("连接失败,服务器出错");
					// 连接失败,服务器出错
					message.what = SERVICEERROR;
					handler.sendMessage(message);

				}

			}
		});
	}

	private Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {

			switch (msg.what) {
			case UPDATA:// 进行更新提示
				updateTipDialog();
				break;
			case CONNECTERROR:
				Toast.makeText(context, "连接服务器失败", 1).show();
	
				break;
			case SERVICEERROR:
				Toast.makeText(context, "服务器出错", 1).show();
		
				break;
			case DOWNLOADERROR:
				Toast.makeText(context, "下载失败", 1).show();
				break;
			}
			super.handleMessage(msg);
		}
	};

	

	/**
	 * 提示用户升级
	 */
	protected void updateTipDialog() {
		AlertDialog.Builder builder = new Builder(context);
		builder.setTitle("升级提示");
		builder.setMessage(bean.getDes());
		builder.setPositiveButton("升级", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				updateApk();

			}
		});
		builder.setNegativeButton("取消", new OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {

			}
		});
		builder.create().show();
	}

	/**
	 * 下载更新apk
	 */
	protected void updateApk() {
		// 在下载的时候,显示一个进度条:动画 下载了多少
		pd = new ProgressDialog(context);
		pd.setTitle("正在下载...");
		pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		pd.show();

		new Thread(new Runnable() {
			@Override
			public void run() {
				File file = DownloadHelper.getApkFile(bean.getApkUrl(), pd);
				pd.dismiss();
				if (file == null) {
					// 下载失败
					Message msg = Message.obtain();
					msg.what = DOWNLOADERROR;
					handler.sendMessage(msg);

				} else {
					// 进行安装
					/**
					 * <intent-filter> <action
					 * android:name="android.intent.action.VIEW" /> <category
					 * android:name="android.intent.category.DEFAULT" /> <data
					 * android:scheme="content" /> <data android:scheme="file"
					 * /> <data android:mimeType=
					 * "application/vnd.android.package-archive" />
					 * </intent-filter>
					 */
					Intent intent = new Intent();
					intent.setAction("android.intent.action.VIEW");
					intent.addCategory("android.intent.category.DEFAULT");
					intent.setDataAndType(Uri.fromFile(file),
							"application/vnd.android.package-archive");
					context.startActivity(intent);
					context.finish();

				}
			}
		}).start();

	}

	public void destory() {
		login = null;
	}
	/**
	 * 用户名校验
	 * @param info
	 * @return
	 */
	public static boolean loginUserNameCheck(String info) {
		if (TextUtils.isEmpty(info)) {
			SystemUtils.showRsToast(R.string.name_not_null);
			return false;
		}
		if (info.length() < 2) {
			SystemUtils.showRsToast(R.string.name_legth);
			return false;
		}
		return true;
	}
	/**
	 * 密码输入校验
	 * @param info
	 * @return
	 */
	public static boolean loginPsdCheck(String info) {
		if (TextUtils.isEmpty(info) && info.length() < 4) {
			SystemUtils.showRsToast(R.string.psw_not_null);
			return false;
		}
		if (info.length()< 4) {
			SystemUtils.showRsToast(R.string.psw_length);
			return false;
		} 
		
		return true;
	}
}
