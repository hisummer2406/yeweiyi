package base;

import android.app.Activity;
import android.support.v4.app.Fragment;

/**
 * Created by Administrator on 2019-05-07.
 */

public abstract class BaseVM<T> {

    public static String TAG;




    public BaseVM(Fragment fragment, T dataBind) {
        TAG = dataBind.getClass().getName();
    }

    public BaseVM(Activity activity, T dataBind) {
        TAG = dataBind.getClass().getName();
    }

    public abstract void initView();

    public abstract void initData();

    public abstract void destory();


}
