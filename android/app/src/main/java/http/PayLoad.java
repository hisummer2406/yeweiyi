package http;


import java.util.function.Function;

/**
 *
 * 剥离 最终数据
 * Created by zhouwei on 16/11/10.
 */

public class PayLoad<T> implements Function<BaseResponse<T>,T> {

    @Override
    public T apply( BaseResponse<T> tBaseResponse) {
        if(!tBaseResponse.isSuccess()){
            throw new Fault(tBaseResponse.status,tBaseResponse.message);
        }
        return tBaseResponse.data;
    }
}
