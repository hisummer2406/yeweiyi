package com.sansen.yeweiyi.web.handler;

import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.yanzhenjie.andserver.RequestHandler;
import com.yanzhenjie.andserver.RequestMethod;
import com.yanzhenjie.andserver.annotation.RequestMapping;
import com.yanzhenjie.andserver.util.HttpRequestParser;

import org.apache.httpcore.HttpException;
import org.apache.httpcore.HttpRequest;
import org.apache.httpcore.HttpResponse;
import org.apache.httpcore.entity.StringEntity;
import org.apache.httpcore.protocol.HttpContext;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by PC on 2018-01-16.
 */

public class OilDischarge implements RequestHandler {
    private static final String TAG = "OilDischarge";

    @RequestMapping(method = {RequestMethod.GET})
    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext httpContext) throws HttpException, IOException {
        Map<String, String> params = HttpRequestParser.parseParams(request);

        String beginTime = URLDecoder.decode(params.get("beginTime"), "utf-8");
        String endTime = URLDecoder.decode(params.get("endTime"), "utf-8");
        boolean sort = URLDecoder.decode(params.get("order"), "utf-8").equals("asc")?true:false;
        String id = URLDecoder.decode(params.get("id"), "utf-8");
        int limit = Integer.parseInt(URLDecoder.decode(params.get("limit"), "utf-8"));
        int offset = Integer.parseInt(URLDecoder.decode(params.get("offset"), "utf-8"));
        //Log.i(TAG, "id："+id+" beginTime："+beginTime+" endTime："+endTime+" limit："+limit+" offset："+offset+" sor："+sort);

        HashMap<String,Object> maps= DBDataManager.getInstance().findObject(OilInOut.class,id,beginTime,endTime,limit,offset,sort);
        List<OilInOut> alarms= (List<OilInOut>) maps.get("list");
        long count= (long) maps.get("count");
        PageInfo pageInfo = new PageInfo(alarms, count, limit);

        pageInfo.setOffset(offset);

        String data= JSONObject.toJSONString(pageInfo);
        StringEntity stringEntity = new StringEntity(data, "utf-8");
        response.setStatusCode(200);
        response.setEntity(stringEntity);
    }
}
