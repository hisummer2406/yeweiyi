package com.sansen.yeweiyi.okGoUntils;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.BitmapCallback;
import com.lzy.okgo.callback.Callback;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.HttpParams;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.base.Request;
import com.sansen.yeweiyi.config.ApiConfig;
import com.sansen.yeweiyi.utils.DESUtils;
import com.sansen.yeweiyi.utils.LogUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import Decoder.BASE64Decoder;

public class OkgoNet {
    private Map<String, String> arguments = new HashMap<>();
    private final String url = "";
    private List<String> value = new ArrayList<>();
    private List<File> files = new ArrayList<>();
    private List<HttpParams.FileWrapper> fileWrappers = new ArrayList<>();
    byte[] key = new byte[0];
    private static volatile OkgoNet instance;

    public static OkgoNet getInstance() {
        if (instance == null) {
            synchronized (OkgoNet.class) {
                if (instance == null) {
                    instance = new OkgoNet();
                }
            }
        }
        return instance;
    }

    private void basicPost() {
        OkGo.<ServerModel>post(url)
                .tag(true)//请求的tag,主要用于取消对应的请求
                .isMultipart(true)//强制使用multipart/form-data表单上传
                .isSpliceUrl(true)//强制使用params的参数拼接到url后面
                .retryCount(3)//超时重连次数
                .cacheKey("cachKey")//设置当前请求的缓存key
                .cacheTime(5000)//缓存过期时间,单位毫秒
                .cacheMode(CacheMode.DEFAULT)//缓存模式
                .headers("", "")//添加请求头参数
                .headers("", "")//支持多请求头参数同时添加
                .params("", "")//添加请求参数
                .params("", "")//支持多请求参数同时添加
                .params("file", "paramValue")//可以添加文件上传
                .params("file2", "paramValue2")//支持多文件同时添加上传
                .addUrlParams("key", value)//支持一个key多个参数
                .addFileParams("key", files)//支持一个key多个文件
                .addFileWrapperParams("key", fileWrappers)//这里支持一个穿过个文件
                .execute(new Callback<ServerModel>() {
                    @Override
                    public void onStart(Request<ServerModel, ? extends Request> request) {
                        //UI线程 请求网络之前调用,可以显示对话框,添加/修改/移除 请求参数
                    }

                    @Override
                    public void onSuccess(Response<ServerModel> response) {

                        //UI线程  请求成功后回调
                    }

                    @Override
                    public void onCacheSuccess(Response<ServerModel> response) {

                        //UI线程  缓存读取成功后回调
                    }

                    @Override
                    public void onError(Response<ServerModel> response) {

                        //UI 线程 ,请求失败后回调
                    }

                    @Override
                    public void onFinish() {

                        //UI线程 请求结束后回调,无论网络请求成功还是失败,都会调用
                    }

                    @Override
                    public void uploadProgress(Progress progress) {

                        //UI线程  文件上传过程中回调, 只有请求方式包含请求体才会回调
                    }

                    @Override
                    public void downloadProgress(Progress progress) {

                        //UI线程 文件下载过程中回调
                    }

                    @Override
                    public ServerModel convertResponse(okhttp3.Response response) throws Throwable {
                        //子线程,可以做耗时操作
                        //根据传递进来的 response 对象,把数据解析成需要的ServerModel类型并返回
                        //可以根据自己的需求,抛出异常,在onError中处理
                        return null;
                    }
                });

    }

    public void simplenessGet(final RequestCallBack requestCallBack) {
        OkGo.<String>get("http://www.baidu.com")
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String body = response.body();
                        requestCallBack.Success(body);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        requestCallBack.Error(response.body());
                        super.onError(response);
                    }
                });
    }

    public void simplenessPost(String url, String argu, final RequestCallBack requestCallBack) {

        try {
            key = new BASE64Decoder().decodeBuffer(ApiConfig.key);
            byte[] bytes = Base64.encode(DESUtils.des3EncodeECB(key, argu.getBytes("UTF-8")), Base64.DEFAULT);
            String datas = new String(bytes, "UTF-8");
            arguments.put("arguments", datas);
            OkGo.<String>post(url)
                    .cacheTime(5000)
                    .params(arguments)
                    .execute(new StringCallback() {
                        @Override
                        public void onSuccess(Response<String> response) {
                            String body = response.body();
                            requestCallBack.Success(body);
                            System.out.println(body);
                        }

                        @Override
                        public void onError(Response<String> response) {
                            requestCallBack.Error(response.body());
                            super.onError(response);
                        }
                    });
        } catch (Exception e) {
            Log.e("OkgoNet", "onError 执行失败", e);
        }

    }

    public void simplenessUpstring(String url, String json, final RequestCallBack requestCallBack) {
        OkGo.<String>post("http://www.baidu.com")
                .tag(this)
                .upJson(json)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {

                        requestCallBack.Success(response.body());
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        requestCallBack.Error(response.message());
                    }

                    @Override
                    public void uploadProgress(Progress progress) {
                        super.uploadProgress(progress);
                        requestCallBack.Progress(progress);
                    }
                });
    }

    public void simplenessUpFile(String url, HashMap<String, String> map, List<File> files, final RequestCallBack requestCallBack) {
        OkGo.<String>post(url)
                .tag(this)
                .isMultipart(true)
                .params(map)
                .addFileParams("file", files)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {

                        requestCallBack.Success(response.body());
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        requestCallBack.Error(response.message());
                    }

                    @Override
                    public void uploadProgress(Progress progress) {
                        super.uploadProgress(progress);
                    }
                });
    }

    /**
     * 保存数据到服务器
     */
    public void saveToInternet(String url, HashMap<String, String> map,File file, final RequestCallBack requestCallBack) {
        //上传单个文件
        OkGo.<String>post(url)
                .tag(this)
                .params("file",file)
                .params(map)
                .isMultipart(true)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        requestCallBack.Success(response.body());
                    }

                    @Override
                    public void onError(Response<String> response) {
                        requestCallBack.Error(response.message());
                    }
                });
    }

    public void simplenessDownLoad(String url, HashMap<String,String>map, String file ,String fileName, final RequestCallBack requestCallBack) {
        OkGo.<File>get(url)
                .tag(this)
                .params(map)
                .execute(new FileCallback(file,fileName) {
                    @Override
                    public void onSuccess(Response<File> response) {
                        //file 即为文件数据,文件保存的指定路径
                       // //Log.e("randomcode","下载成功1 " + response.body());
                        requestCallBack.Success(response.body().getAbsolutePath());
                    }

                    @Override
                    public void downloadProgress(Progress progress) {
                        super.downloadProgress(progress);
                        //这里回调下载进度(该回调在主线程,可以直接更新UI)

                        requestCallBack.Progress(progress);
                       // //Log.e("randomcode"," " + progress.currentSize);
                    }
                });
    }

    private void simplenessLoadIamge() {
        OkGo.<Bitmap>post("http://image.uczzd.cn/7402592950813081745.jpg?id=0&from=export")
                .tag(this)
                .execute(new BitmapCallback() {
                    @Override
                    public void onSuccess(Response<Bitmap> response) {
                        Bitmap body = response.body();
                    }
                });
    }
}
