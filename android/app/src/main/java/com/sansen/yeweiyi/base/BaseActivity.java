package com.sansen.yeweiyi.base;
import android.util.Log;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.databinding.Observable;
import android.databinding.ObservableField;
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.FragmentActivity;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.alibaba.fastjson.JSONObject;
import com.baidu.location.BDLocation;
import com.baidu.location.BDLocationListener;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.location.LocationClientOption.LocationMode;
import com.baidu.location.Poi;
import com.baidu.mobstat.StatService;
import com.google.gson.Gson;
import com.lidroid.xutils.HttpUtils;
import com.lidroid.xutils.http.RequestParams;
import com.lidroid.xutils.http.callback.RequestCallBack;
import com.lidroid.xutils.http.client.HttpRequest;
import com.lidroid.xutils.util.LogUtils;
import com.sansen.yeweiyi.BuildConfig;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.LevelmeterInfo;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.bean.SerialPortEntrity;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.languageUtil.LanguageUtil;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.AppManager;
import com.sansen.yeweiyi.manager.DataRefreshCallback;
import com.sansen.yeweiyi.manager.SerialPortManager;
import com.sansen.yeweiyi.repository.OilTankRepositoryImpl;
import com.sansen.yeweiyi.utils.DialogUtil;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.view.CustomToast;

import java.util.List;
import java.util.Locale;
public abstract class BaseActivity extends FragmentActivity implements
        OnClickListener {

    static {
        System.loadLibrary("serialport");
    }
    protected Context ct;
    protected MyApplication app;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageUtil.wrap(newBase));
    }

//    public ImageButton iv_right;
//
//    // Title 控件
//    public TextView tv_left;
//    public TextView tv_right;
//    public TextView tv_filter;

    public TextView bar_tv_time;

    public SerialPortManager mSerialPortManager;

    public static final int DATA_REFRESH = 0;
    public static final int OILCAN_DISMISS = 1;

    public LocationClient mLocationClient = null;
    public BDLocationListener myListener = new MyLocationListener();
    public MyApplication myApplication;
    public InputMethodManager manager;
    Gson gson=new Gson();
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // TODO Auto-generated method stub
        // super.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        // 对所有Activity进行管理
        String str = JSONObject.toJSONString(savedInstanceState);
        // 没有标题栏
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 设置横屏模式
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        AppManager.getAppManager().addActivity(this);
        manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // 位置定位
        mLocationClient = new LocationClient(getApplicationContext()); // 声明LocationClient类
        mLocationClient.registerLocationListener(myListener); // 注册监听函数

        myApplication = MyApplication.getInstance();

        mSerialPortManager = myApplication.getmSerialPortManager();

        ct = this;

        // 初始化标题
        initTitleBar();
    }

    /**
     * 自定义标题栏 包含 左边按钮、右边按钮、中间文字 左边图片按钮、右边图片按钮
     */
    protected void initTitleBar() {
        ImageView icon = (ImageView) findViewById(R.id.bar_iv_icon);
        if (icon != null) {
//            String path = Environment.getExternalStorageDirectory().toString() + "/sanSenDrawable/sansen_logo.png";
//            File mFile = new File(path);
//            //若该文件存在
//            if (mFile.exists()) {
//                Bitmap bitmap = BitmapFactory.decodeFile(path);
//                icon.setImageBitmap(bitmap);
//            }
//
//            if (SharePrefUtil.getBoolean(this,"versionHowL",false)){
//                icon.setBackgroundResource(R.drawable.zhengxing_logo);
//            }else {
//                icon.setBackgroundResource(R.drawable.sansen_logo1);
//            }
           // //Log.e("
            // AAAA","---------"+SharePrefUtil.getInt(this,SharePrefUtil.VERSIONHOWL,0));
            switch (SharePrefUtil.getInt(this,SharePrefUtil.VERSIONHOWL,0)){

                case 0:
                    icon.setBackgroundResource(R.drawable.sansen_logo);
                    break;
                case 1:
                    icon.setBackgroundResource(R.drawable.space_image);
                    break;
                case 2:

                    icon.setBackgroundResource(R.drawable.zhengxing_logo);
                    break;
            }

        }
        TextView versionView = (TextView) findViewById(R.id.bar_tv_version);
        if (versionView != null) {
            versionView.setText("v" + BuildConfig.VERSION_NAME);
        }
//        tv_left = (TextView) findViewById(R.id.bar_tv_left);
//        tv_right = (TextView) findViewById(R.id.bar_tv_right);
//        tv_filter = (TextView) findViewById(R.id.bar_tv_filter);
//        iv_right = (ImageButton) findViewById(R.id.bar_imbut_left);
//        tv_unlogin = (TextView) findViewById(R.id.bar_tv_unlogin);
        bar_tv_time=findViewById(R.id.bar_tv_time);
        SerialPortEntrity.time.addOnPropertyChangedCallback(callback);
       // bar_tv_time.setText();


//        if (iv_right != null && tv_left != null) {
//            tv_right.setVisibility(View.GONE);
//            iv_right.setOnClickListener(this);
//            tv_left.setOnClickListener(this);
//            tv_right.setOnClickListener(this);
//            tv_filter.setOnClickListener(this);
//            tv_unlogin.setOnClickListener(this);
//            icon.setOnClickListener(this);
//        }

    }
    Observable.OnPropertyChangedCallback callback = new Observable.OnPropertyChangedCallback() {
        @Override
        public void onPropertyChanged(Observable sender, int propertyId) {
            // 如果需要区分是哪个字段，可通过 propertyId 判断
            // 直接获取当前值
            String newValue = ((ObservableField<String>) sender).get();
           // Log.d("监听", "新值：" + newValue);
            bar_tv_time.setText(newValue);
        }
    };
    protected void initLocation() {

//        mLocationClient.start();

        LocationClientOption option = new LocationClientOption();
        option.setLocationMode(LocationMode.Hight_Accuracy);// 可选，默认高精度，设置定位模式，高精度，低功耗，仅设备
        option.setCoorType("bd09ll");// 可选，默认gcj02，设置返回的定位结果坐标系
        int span = 0;
        option.setScanSpan(span);// 可选，默认0，即仅定位一次，设置发起定位请求的间隔需要大于等于1000ms才是有效的
        option.setIsNeedAddress(true);// 可选，设置是否需要地址信息，默认不需要
        option.setOpenGps(true);// 可选，默认false,设置是否使用gps
        option.setLocationNotify(true);// 可选，默认false，设置是否当gps有效时按照1S1次频率输出GPS结果
        option.setIsNeedLocationDescribe(true);// 可选，默认false，设置是否需要位置语义化结果，可以在BDLocation.getLocationDescribe里得到，结果类似于"在北京天安门附近”
        option.setIsNeedLocationPoiList(false);// 可选，默认false，设置是否需要POI结果，可以在BDLocation.getPoiList里得到
        option.setIgnoreKillProcess(true);// 可选，默认true，定位SDK内部是一个SERVICE，并放到了独立进程，设置是否在stop的时候杀死这个进程，默认不杀死
        option.SetIgnoreCacheException(false);// 可选，默认false，设置是否收集CRASH信息，默认收集
        option.setEnableSimulateGps(false);// 可选，默认false，设置是否需要过滤gps仿真结果，默认需要
//        mLocationClient.setLocOption(option);
//        mLocationClient.start();
    }

    @Override
    protected void onResume() {
        // TODO Auto-generated method stub
        super.onResume();
        mSerialPortManager.setDataRefreshCallback(dataRefreshCallback);
        StatService.onResume(this);

    }

    @Override
    protected void onPause() {
        // TODO Auto-generated method stub
        super.onPause();
        StatService.onPause(this);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppManager.getAppManager().finishActivity(this);
    }

    @Override
    public void onClick(View v) {
        processClick(v);
    }

    // 显示自定义Toast
    protected void showToast(String msg) {
        showToast(msg, 0);
    }

    // 延时显示自定义Toast
    protected void showToast(String msg, int time) {
        CustomToast customToast = new CustomToast(ct, msg, time);
        customToast.show();
    }

    /**
     * 显示自定义进度对话框
     */
    public void showProgressDialog() {
        if (Const.ProgressDialog == null && ct != null) {
            DialogUtil.createProgressDialog(ct);
        }

    }

    protected AlertDialog inputDialog;



    /**
     * 关闭自定义对话框
     */
    public void closeProgressDialog() {
        handler.sendEmptyMessage(9);
    }

    protected abstract void initView();

    protected abstract void initData();

    protected abstract void processClick(View v);

    /**
     * 网络端请求数据
     *
     */
    public void loadData(HttpRequest.HttpMethod method, String url,
                         RequestParams params, RequestCallBack<String> callback) {
        HttpUtils http = new HttpUtils();
        http.configCurrentHttpCacheExpiry(0);

        LogUtils.allowD = true;
        if (params != null) {
            if (params.getQueryStringParams() != null)
                LogUtils.d(url + "?" + params.getQueryStringParams().toString());
        } else {
            params = new RequestParams();

        }
        http.send(method, url, params, callback);
    }

    @Override
    protected void onActivityResult(int arg0, int arg1, Intent arg2) {
        super.onActivityResult(arg0, arg1, arg2);
    }

    /**
     * 当探针信息刷新时，在主线程中刷新信息
     */
    Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DATA_REFRESH:
                    refreshData((OilCan) msg.obj);
                    break;
                case OILCAN_DISMISS:
                    oilcanDismiss((OilCan) msg.obj);
                    break;

                case 9:
                    if (Const.ProgressDialog != null) {
                        Const.ProgressDialog.deostry();
                        Const.ProgressDialog = null;
                    } else {
                        handler.sendEmptyMessageDelayed(9, 100);
                    }

                    break;
            }
        }

        ;
    };

    /**
     * 注册给 SerialPortManager 编排层的数据刷新回调，内部转投到上面的 handler，
     * 保持"探头线程 -> 主线程"这一跳的行为与改动前完全一致。
     */
    private final DataRefreshCallback dataRefreshCallback = new DataRefreshCallback() {
        @Override
        public void onDataRefresh(OilCan oilCan) {
            Message msg = Message.obtain();
            msg.what = DATA_REFRESH;
            msg.obj = oilCan;
            handler.sendMessage(msg);
        }
    };
    public TextView tv_unlogin;

    /**
     * 回调方法 当油罐信息刷新时，回调给actvity层
     *
     * @param oilCan
     */
    public abstract void refreshData(OilCan oilCan);

    public abstract void oilcanDismiss(OilCan obj);

    // 百度定位的回调方法
    public class MyLocationListener implements BDLocationListener {

        @Override
        public void onReceiveLocation(BDLocation location) {
            // Receive Location
            StringBuffer sb = new StringBuffer(256);
            sb.append("time : ");
            sb.append(location.getTime());
            sb.append("\nerror code : ");
            sb.append(location.getLocType());
            sb.append("\nlatitude : ");
            sb.append(location.getLatitude());
            sb.append("\nlontitude : ");
            sb.append(location.getLongitude());
            sb.append("\nradius : ");
            sb.append(location.getRadius());
            if (location.getLocType() == BDLocation.TypeGpsLocation) {// GPS定位结果
                RequestLocation(location);
                mLocationClient.stop();
            } else if (location.getLocType() == BDLocation.TypeNetWorkLocation) {// 网络定位结果
                RequestLocation(location);
                mLocationClient.stop();
            } else if (location.getLocType() == BDLocation.TypeOffLineLocation) {// 离线定位结果
                RequestLocation(location);
                mLocationClient.stop();
            } else if (location.getLocType() == BDLocation.TypeServerError) {
                sb.append("\ndescribe : ");
                sb.append(SystemUtils.getRsStr(R.string.baidu_loc_server_error));
            } else if (location.getLocType() == BDLocation.TypeNetWorkException) {
                sb.append("\ndescribe : ");
                sb.append(SystemUtils.getRsStr(R.string.baidu_loc_network_error));
            } else if (location.getLocType() == BDLocation.TypeCriteriaException) {
                sb.append("\ndescribe : ");
                sb.append(SystemUtils.getRsStr(R.string.baidu_loc_no_result));
            }
            sb.append("\nlocationdescribe : ");
            sb.append(location.getLocationDescribe());// 位置语义化信息
            List<Poi> list = location.getPoiList();// POI数据
            SharePrefUtil.saveString(ct,SharePrefUtil.LONGITUDE,String.valueOf(location.getLongitude()));
            SharePrefUtil.saveString(ct,SharePrefUtil.LATITUDE,String.valueOf(location.getLatitude()));
            try {
                String deviceSecret=SharePrefUtil.getString(ct.getApplicationContext(),SharePrefUtil.DEVICESECRET,null);
                if (deviceSecret==null){
                    return;
                }
                List<ProbeToOil> probeToOils= OilTankRepositoryImpl.getInstance().findAllProbeToOil();
                int probeCount=0;
                for (int i=0;i<probeToOils.size();i++){
                    if (probeToOils.get(i).getEnable()==1){
                        probeCount=i+1;
                    }
                }
 //               if (SharePrefUtil.getBoolean(myApplication.getApplicationContext(),SharePrefUtil.ISUPLOADING,false)){
//                    Map<String,String> map=new HashMap<>();
//                    map.put("defaultName",deviceSecret);
//                    map.put("productId",ApiConfig.productKey);
//                    map.put("tankDataVolume",String.valueOf(probeCount));
//                    map.put("longitude", String.valueOf(location.getLongitude()));
//                    map.put("latitude",String.valueOf(location.getLatitude()));
//                    map.put("updateTime", String.valueOf(System.currentTimeMillis()));
//                    String jsonStr = JSONObject.toJSONString(map);
//                    OkgoNet.getInstance().simplenessPost(Constans.URL+Constans.Location, jsonStr, new com.sansen.yeweiyi.okGoUntils.RequestCallBack() {
//                        @Override
//                        public void Success(String message) {
//
//                            HttpBean bean=new HttpBean();
//                            bean=gson.fromJson(message,HttpBean.class);
//                            if (bean.getCode()==200){
//                                Log.e("AAAA","设备地址上传"+message);
//                            }
//                            else {
//                                Toast.makeText(ct,bean.getMsg(),Toast.LENGTH_LONG).show();
//                                return;
//                            }
//                        }
//
//                        @Override
//                        public void Error(String failing) {
//                            Log.e("AAAA","设备地址状态上传"+"failing");
//                        }
//
//                        @Override
//                        public void Progress(Progress progress) {
//
//                        }
//                    });
//
//                    map.remove("longitude");
//                    map.remove("latitude");
//                    map.remove("tankDataVolume");
//                    map.put("isOnline","true");
//                    String jsonStr2 = JSONObject.toJSONString(map);
//                    Log.e("AAAA","设备状态上传"+jsonStr);
//                    OkgoNet.getInstance().simplenessPost(Constans.URL+Constans.DeviceOnlinState, jsonStr2, new com.sansen.yeweiyi.okGoUntils.RequestCallBack() {
//                        @Override
//                        public void Success(String message) {
//
//                            HttpBean bean=new HttpBean();
//                            bean=gson.fromJson(message,HttpBean.class);
//                            if (bean.getCode()==200){
//                                Log.e("AAAA","设备在线状态上传"+message);
//
//                            }
//                            else {
//                                Toast.makeText(ct,bean.getMsg(),Toast.LENGTH_LONG).show();
//                                return;
//                            }
//                        }
//
//                        @Override
//                        public void Error(String failing) {
//                            Log.e("AAAA","设备在线状态上传"+"failing");
//                        }
//
//                        @Override
//                        public void Progress(Progress progress) {
//
//                        }
//                    });
//                }
//                if (list != null) {
//                sb.append("\npoilist size = : ");
//                sb.append(list.size());
//                for (Poi p : list) {
//                    sb.append("\npoi= : ");
//                    sb.append(p.getId() + " " + p.getName() + " " + p.getRank());
//                }
//            }
            } catch (Exception e) {
                Log.e("BaseActivity", "onReceiveLocation 执行失败", e);
            }


        }
    }


    //

    /**
     * 请求天气预报，只能用get获取
     */
    protected void RequestWeather() {
        //
        // loadData(HttpMethod.GET,
        // "http://203.171.228.177:8080/SanSen/servlet/LoginServlet",
        // new RequestParams().addBodyParameter("data", data), new
        // httpCallBack());
    }

    /**
     * \]
     */

    LevelmeterInfo yeWeiYi = new LevelmeterInfo();

    /**
     * 请求用户更新位置
     *
     * @param location
     */
    protected void RequestLocation(BDLocation location) {
        yeWeiYi = myApplication.getYeWeiYi();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            /**
             * 如果点击的诗EditText那么久不隐藏
             */
            if (isShouldHideKeyboard(v, ev)) {
                hideKeyboard(v.getWindowToken());
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 根据EditText所在坐标和用户点击的坐标相对比，来判断是否隐藏键盘，因为当用户点击EditText时则不能隐藏
     *
     * @param v
     * @param event
     * @return
     */
    private boolean isShouldHideKeyboard(View v, MotionEvent event) {
        if (v == null) {
            return false;
        }

        // 如果点击的是EditText那么就不隐藏
        if (v instanceof EditText) {

            // int[] l = { 0, 0 };
            // v.getLocationInWindow(l);
            // int left = l[0], top = l[1], bottom = top + v.getHeight(), right
            // = left
            // + v.getWidth();
            // if (event.getX() > left && event.getX() < right
            // && event.getY() > top && event.getY() < bottom) {
            //
            // }
            return false;
        } else {
            return true;
        }
        // 如果焦点不是EditText则忽略，这个发生在视图刚绘制完，第一个焦点不在EditText上，和用户用轨迹球选择其他的焦点

    }

    /**
     * 获取InputMethodManager，隐藏软键盘
     *
     * @param token
     */
    private void hideKeyboard(IBinder token) {
        if (token != null) {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(token,
                    InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }

    public static void settingLanguage(Locale locale) {

        Configuration config = MyApplication.getInstance().getResources()
                .getConfiguration();// 获得设置对象
        Resources resources = MyApplication.getInstance().getResources();// 获得res资源对象
        DisplayMetrics dm = resources.getDisplayMetrics();// 获得屏幕参数：主要是分辨率，像素等。
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        resources.updateConfiguration(config, dm);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {

        return true;
    }

    /**
     * /** 隐藏虚拟按键，并且全屏
     */
    protected void hideBottomUIMenu() {
        // 隐藏虚拟按键，并且全屏
        if (Build.VERSION.SDK_INT > 11 && Build.VERSION.SDK_INT < 19) { // lower
            // api
            View v = this.getWindow().getDecorView();
            v.setSystemUiVisibility(View.GONE);
        } else if (Build.VERSION.SDK_INT >= 19) {
            // for new api versions.
            View decorView = getWindow().getDecorView();
            int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }


    LocationListener locationListener = new LocationListener() {

        @Override
        public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onProviderEnabled(String arg0) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onProviderDisabled(String arg0) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onLocationChanged(Location arg0) {
            // TODO Auto-generated method stub
            // 更新当前经纬度
        }
    };
}
