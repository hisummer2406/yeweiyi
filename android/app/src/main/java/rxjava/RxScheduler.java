package rxjava;


import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;
import rxjava.intf.IOTask;
import rxjava.intf.UITask;

/**
 * Created by PC on 2019-01-18.
 */

public class RxScheduler {
    public static <T> void doOnIOThread(final IOTask<T> task) {
        Observable.just(task)
                .observeOn(Schedulers.io())
                .subscribe(new DisposableObserver<IOTask<T>>() {

                    @Override
                    public void onNext(@NonNull IOTask<T> tioTask) {
                        tioTask.doOnIOThread();
                    }

                    @Override
                    public void onError(@NonNull Throwable throwable) {
                        //throwable.printStackTrace();
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    public static <T> void doOnUiThread(final UITask<T> task) {
        Observable.just(task)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new DisposableObserver<UITask<T>>() {
                    @Override
                    public void onNext(@NonNull UITask<T> tuiTask) {
                        tuiTask.doOnUIThread();
                    }

                    @Override

                    public void onError(@NonNull Throwable throwable) {
                       // throwable.printStackTrace();
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    public static <T> void doTask(final Task<T> task) {
       Observable.create(new ObservableOnSubscribe<T>() {
           @Override
           public void subscribe(@NonNull ObservableEmitter<T> observableEmitter) throws Exception {
               task.doOnIOThread();
               observableEmitter.onNext(task.getT());
               observableEmitter.onComplete();
           }
       }).subscribeOn(Schedulers.io())
               .observeOn(AndroidSchedulers.mainThread())
               .subscribe(new DisposableObserver<T>() {
                   @Override
                   public void onNext(@NonNull T t) {
                        task.doOnUIThread();
                   }

                   @Override
                   public void onError(@NonNull Throwable throwable) {
                       //throwable.printStackTrace();
                   }

                   @Override
                   public void onComplete() {

                   }
               });
    }

}
