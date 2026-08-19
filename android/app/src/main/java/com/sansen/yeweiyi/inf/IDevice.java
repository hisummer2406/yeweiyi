package com.sansen.yeweiyi.inf;

import com.sansen.yeweiyi.bean.NetResult;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface IDevice {
    @POST(value = "/system/product/registDevice")
    Call<NetResult<Map<String,String>>> registDevice(@Query("productKey") String productKey,
                                                     @Query("productSecret") String productSecret,
                                                     @Query("mac") String mac);

}
