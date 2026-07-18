package alarm;


import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import util.SoundUtils;

/**
 * Created by PC on 2019-04-11.
 */

public class SoundManger {
    private static final String TAG = "SoundManger";
    ConcurrentHashMap<String, Boolean> map = new ConcurrentHashMap<>();
    public boolean isPaly;
    Disposable disposable;
    private static SoundManger instance;
    final SoundUtils soundUtils;
    private boolean isFeeding;

    private long lastFeedTime;

    public static SoundManger getInstance() {
        if (instance == null) {
            synchronized (SoundManger.class) {
                if (instance == null) {
                    instance = new SoundManger();
                }
            }
            return instance;
        }
        return instance;
    }

    public SoundManger() {
        soundUtils = new SoundUtils(MyApplication.getInstance(), SoundUtils.RING_SOUND);
        soundUtils.putSound(0, R.raw.test);
        disposable = Observable.interval(1, 1, TimeUnit.SECONDS)
                .subscribe(new Consumer<Long>() {
                    @Override
                    public void accept(Long aLong) throws Exception {

                        if ((System.currentTimeMillis()-lastFeedTime)>3000){
                            map.put("1", false);
                        }

                        for (String key : map.keySet()) {
//                            Log.i(TAG, "accept: key" + key + " 值" + map.get(key));
                            if (map.get(key)) {
                                soundUtils.playSound(0, SoundUtils.RING_SOUND);
                                return;
                            }
                        }

                        isPaly = false;


                    }
                });
    }

    public void playSound(String key) {
        map.put(key, true);
        isPaly = true;
        lastFeedTime = System.currentTimeMillis();
    }

    public void stopSound(String key) {
            map.put(key, false);
    }

}
