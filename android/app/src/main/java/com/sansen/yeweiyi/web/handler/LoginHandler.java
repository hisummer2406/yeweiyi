/*
 * Copyright © Yan Zhenjie. All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sansen.yeweiyi.web.handler;

import android.util.Log;

import com.sansen.yeweiyi.logic.LoginUser;
import com.sansen.yeweiyi.utils.EncryptionUtils;
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
import java.util.Map;

/**
 * <p>Login Handler.</p>
 * Created by Yan Zhenjie on 2016/6/13.
 */
public class LoginHandler implements RequestHandler {
    private String TAG ="LoginHandler";
    @RequestMapping(method = {RequestMethod.POST})
    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        Map<String, String> params = HttpRequestParser.parseParams(request);

        String userName = URLDecoder.decode(params.get("username"), "utf-8");
        String password = URLDecoder.decode(params  .get("password"), "utf-8");
        //Log.i(TAG, "handle: "+password);
        //Log.i(TAG, "handle: "+EncryptionUtils.EncoderByMd5(password));
        if (LoginUser.getInstance().loginUser(userName,password)) {
            StringEntity stringEntity = new StringEntity("200", "utf-8");
            response.setStatusCode(200);
            response.setEntity(stringEntity);
        } else {
            StringEntity stringEntity = new StringEntity("400", "utf-8");
            response.setStatusCode(200);
            response.setEntity(stringEntity);
        }
    }
}
