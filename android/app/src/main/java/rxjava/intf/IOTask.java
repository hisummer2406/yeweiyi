package rxjava.intf;

/**
 * Created by PC on 2019-01-18.
 */

public interface IOTask<T> {
    void doOnIOThread();
}
