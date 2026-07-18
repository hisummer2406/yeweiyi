package rxjava;

/**
 * Created by PC on 2019-01-18.
 */

public abstract class Task<T> {
    private T t;

    public Task(T t) {
        this.t = t;
    }

    public void setT(T t) {
        this.t = t;
    }

    public T getT() {
        return t;
    }

    public abstract void doOnUIThread();

    public abstract void doOnIOThread();
}
