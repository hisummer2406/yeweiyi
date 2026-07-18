package UpdateVersion;

import com.sansen.yeweiyi.bean.NetResult;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.POST;
import retrofit2.http.Query;

/**
 * Created by PC on 2018-09-11.
 */
public interface IVersion {
    @POST("/system/versions/queryVersion")
    Call<NetResult<Map<String,String>>> queryVersion(@Query("oldVersin") int oldVersin, @Query("platform") String platform, @Query("appType") String appType);
    @POST("/system/versionsZhengxing/queryVersion1")
    Call<NetResult<Map<String,String>>> queryVersion1(@Query("oldVersin") int oldVersin, @Query("platform") String platform, @Query("appType") String appType);
}