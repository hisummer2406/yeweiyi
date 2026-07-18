package UpdateVersion;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Window;

import com.sansen.yeweiyi.application.MyApplication;

import java.util.Locale;

public class AppUtil {
    public static int versionCode() {
        PackageManager manager = MyApplication.getInstance().getPackageManager();
        int code = 0;
        try {
            PackageInfo info = manager.getPackageInfo(MyApplication.getInstance().getPackageName(), 0);
            code = info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            //e.printStackTrace();
        }
        return code;
    }
    public static String versionName() {
        PackageManager manager = MyApplication.getInstance().getPackageManager();
        String name = null;
        try {
            PackageInfo info = manager.getPackageInfo(MyApplication.getInstance().getPackageName(), 0);
            name = info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            //e.printStackTrace();
        }

        return name;
    }

    public static void initLanguage(String loc) {
        //应用内配置语言
        Resources resources = MyApplication.getInstance().getResources();//获得res资源对象
        Configuration config = resources.getConfiguration();//获得设置对象
        DisplayMetrics dm = resources.getDisplayMetrics();//获得屏幕参数：主要是分辨率，像素等。
//        if (SDKConfig.getLanguage().equals("zh")) {
//            config.locale = Locale.SIMPLIFIED_CHINESE; //简体中文
//        } else {
//            config.locale = new Locale("en");
//        }

        config.locale = new Locale(loc);
        resources.updateConfiguration(config, dm);
    }



    public static void setStatusBarColor(Window window){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            //设置状态栏颜色
            window.setStatusBarColor(0xff393A3E);
        }
    }
}
