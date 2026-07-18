package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;

import com.alibaba.fastjson.JSONObject;
import com.lidroid.xutils.exception.HttpException;
import com.lidroid.xutils.http.RequestParams;
import com.lidroid.xutils.http.ResponseInfo;
import com.lidroid.xutils.http.callback.RequestCallBack;
import com.lidroid.xutils.http.client.HttpRequest.HttpMethod;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.bean.LevelmeterInfo;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.OilCanTable;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.ProbeToOil;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.logic.ExportExcel;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.sharepre.manager.SharePreManager;
import com.sansen.yeweiyi.utils.FileSizeUtil;
import com.sansen.yeweiyi.utils.GsonUtil;
import com.sansen.yeweiyi.utils.NetUtils;
import com.sansen.yeweiyi.utils.OkHttpUtil;
import com.sansen.yeweiyi.utils.SharePrefUtil;
import com.sansen.yeweiyi.utils.SystemUtils;
import com.sansen.yeweiyi.utils.ToastUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.message.BasicNameValuePair;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DataStorageManagerFragment extends BaseFragment implements OnItemClickListener {

    private static final String TAG = "DataStorageManagerFragment";
    private GridView gridview;
    int[] icon = {R.drawable.sansen_save_clod_but_selector, R.drawable.sansen_read_clod_but_selector, R.drawable.sansen_save_u_but_selector, R.drawable.sansen_read_u_but_selector};
    String[] data = SystemUtils.getStringsById(R.array.data_service);
    private ArrayList<Map<String, Object>> data_list;

    @Override
    public void initTitle() {
        if (main != null) {
//            main.iv_right.setImageResource(R.drawable.laststep);
//            main.tv_left.setText(SystemUtils.getRsStr(R.string.back_page));
            OnClickListener backSetListener = new OnClickListener() {

                @Override
                public void onClick(View v) {
                    DataManagerListFragment dataManagerListFragment = new DataManagerListFragment();
                    main.switchContent(main.mContent, dataManagerListFragment);
                }
            };
//            main.tv_left.setOnClickListener(backSetListener);
//            main.iv_right.setOnClickListener(backSetListener);
        }
    }

    @Override
    protected View initView(LayoutInflater inflater) {
        // TODO Auto-generated method stub
        return inflater.inflate(R.layout.fragment_datamanager, null);
    }
    String deviceSecret = null;
    @Override
    protected void initData(Bundle savedInstanceState) {

        if (!StringUtils.isNotEmpty(SharePreManager.getIpBean().getAddr())) {
            String deviceName = SharePrefUtil.getString(getActivity(), SharePrefUtil.DEVICESECRET, "");
            if (StringUtils.isNotEmpty(deviceName)) {
                deviceSecret = deviceName;
            }

        } else {
            deviceSecret = SharePreManager.getIpBean().getAddr();
        }

        gridview = (GridView) rootView.findViewById(R.id.datamanager_gridview_data);
        gridview.setNumColumns(2);
        data_list = new ArrayList<Map<String, Object>>();
        // 获取数据
        getData();
        // 新建适配器
        String[] from = {"icon"};
        int[] to = {R.id.image};
        // 配置适配器
        gridview.setAdapter(new MyAdapter());
        gridview.setOnItemClickListener(this);
    }

    @Override
    protected void processClick(View v) {
        // TODO Auto-generated method stub

    }

    public List<Map<String, Object>> getData() {
        // cion和iconName的长度是相同的，这里任选其一都可以
        for (int i = 0; i < icon.length; i++) {
            Map<String, Object> map = new HashMap<String, Object>();

            map.put("icon", icon[i]);
            data_list.add(map);
        }

        return data_list;
    }

    @Override
    public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3) {
        switch (position) {
            case 0:
                saveCloud();
                break;
            case 1:
                readCloud();
                break;
            case 2:
                saveLocation();
                break;
            case 3:
                readLocation();
                break;
        }
    }

    public void saveLocation() {
        ThreadManager.getLongPool().execute(saveRunnable);
    }

    private void SaveInOIl(String path) {
        ExportExcel<OilInOut> exportExcel = new ExportExcel<OilInOut>();
        String headers[] = {"isNet", "record", "msg_id", SystemUtils.getRsStr(R.string.order_number), SystemUtils.getRsStr(R.string.tank_no), SystemUtils.getRsStr(R.string.time), SystemUtils.getRsStr(R.string.beginHeight), SystemUtils.getRsStr(R.string.endHeight), SystemUtils.getRsStr(R.string.in_out_volume), SystemUtils.getRsStr(R.string.in_out_weight)};
        DBDataManager db = MyApplication.getInstance().getmDBManager();
        List<OilInOut> dataset = db.findAllObject(OilInOut.class);

        // 存储到U盘
       // String path = "/storage/usbhost1";

        File file = new File(path);
            if (!file.exists())
                file.mkdir();
            OutputStream out = null;
            try {
                file = new File(path + "/" + "Oil_in_record" + ".xls");
                out = new FileOutputStream(file);
                exportExcel.exportExcel(headers, dataset,
                        new OilInOut().getParams(), out);
                out.close();
            } catch (Exception e) {
                // TODO Auto-generated catch block
                ////e.printStackTrace();
            }
            double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
    }

    private void SaveAlarm(String path) {
        ExportExcel<Alarm> exportExcel = new ExportExcel<Alarm>();
        String headers[] = {"序号", "时间", "报警信息"};
        DBDataManager db = MyApplication.getInstance().getmDBManager();
        List<Alarm> dataset = db.findAllObject(Alarm.class);

        // 存储到U盘
      //  String path = "/storage/usbhost1";

        File file = new File(path);
            if (!file.exists())
                file.mkdir();
            OutputStream out = null;
            try {
                file = new File(path + "/Alarm_record.xls");
                out = new FileOutputStream(file);
                exportExcel.exportExcel(headers, dataset,
                        new Alarm().getParams(), out);
                out.close();

            } catch (Exception e) {
                // TODO Auto-generated catch block
                ////e.printStackTrace();
            }
            double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
            System.out.println("报警记录文件的大小多少？" + size);

    }

    Handler handler = new Handler() {

        public void handleMessage(android.os.Message msg) {

            switch (msg.what) {

            }

        }

        ;
    };


    public void readLocation() {

        ThreadManager.getLongPool().execute(readRunnable);

    }

    Runnable readRunnable = new Runnable() {
        public void run() {
            String path = "/storage/usbhost1/SanSen";
            File file = new File(path);
            if (!SystemUtils.isUsbExit()) {
                path= Environment.getExternalStorageDirectory().getPath()+"/SanSen";
            }

            ReadProbeToOilCan(path);
            ReadOilCanSet(path);
            ReadOilCanTable(path);
            ReadAlarm(path);
            ReadInOutOil(path);


//            ReadCommitWork();
//            ReadSaveOIl();
        }
    };

    Runnable saveRunnable = new Runnable() {
        public void run() {
            String path = "/storage/usbhost1/SanSen";


            if (!SystemUtils.isUsbExit()) {
                path= Environment.getExternalStorageDirectory().getPath()+"/SanSen";
            }

            SaveOilCanSet(path);
            System.out.println("油罐设置保存成功");
            SaveProbeToOilCan(path);
            System.out.println("探针对照表保存成功");
            SaveInOIl(path);
            System.out.println("进出油表保存成功");
            SaveAlarm(path);
            System.out.println("报警记录表保存成功");
            dbManager.SaveOilCanTable(path);
            System.out.println("罐表保存成功");


            // SaveCommitWork();
            // SaveOilStroage();
        }
    };


    private void ReadSaveOIl() {
        System.out.println("====读取存油记录====");
        ExportExcel<Object> excel = new ExportExcel<Object>();
        String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/存油表.xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                List<OilSaveInfo> oilSaveInfos = excel
                        .readExcelSaveInfo(is);
                for (OilSaveInfo oilSaveInfo : oilSaveInfos) {
                    System.out.println("存油记录====" + oilSaveInfo);
                    dbManager.saveObject(oilSaveInfo);
                }

                is.close();
            } else {
                SystemUtils.showRsToast(R.string.datamanager_chek_out);
            }

        } catch (Exception e) {

            //e.printStackTrace();
        }
    }

    private void ReadAlarm(String path) {
        ExportExcel<Object> excel = new ExportExcel<Object>();
        //String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/Alarm_record.xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                List<Alarm> alarms = excel
                        .readExcelAlarmContent(is);

                for (Alarm alarm : alarms) {
                    dbManager.saveObject(alarm);
                }
                is.close();
            } else {
                SystemUtils.showRsToast(R.string.datamanager_chek_out);
            }
        } catch (Exception e) {

            //e.printStackTrace();
        }
    }

    private void ReadInOutOil(String path) {
        ExportExcel<Object> excel = new ExportExcel<Object>();
       // String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/Oil_in_record.xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                List<OilInOut> alarms = excel
                        .readExcelInOutContent(is);

                for (OilInOut alarm : alarms) {
                    dbManager.saveObject(alarm);
                    System.out.println("进出油记录" + alarm);
                }
                is.close();
            } else {
                SystemUtils.showRsToast(R.string.datamanager_chek_out);
            }
        } catch (Exception e) {

            //e.printStackTrace();
        }
    }

    private void ReadProbeToOilCan(String path) {
        ExportExcel<Object> excel = new ExportExcel<Object>();
       // String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/Probe_set.xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                List<ProbeToOil> readExcelProbeToOilCanContent = excel
                        .readExcelProbeToOilCanContent(is);

                for (ProbeToOil probeToOil : readExcelProbeToOilCanContent) {

                    dbManager.UpdateObject(probeToOil);
                }
                is.close();
            } else {
                SystemUtils.showRsToast(R.string.datamanager_chek_out);
            }
        } catch (Exception e) {

            //e.printStackTrace();
        }
    }

    /**
     * 从U盘中读取油罐设置表
     */
    private void ReadOilCanSet(String path) {
        ExportExcel<Object> excel = new ExportExcel<Object>();
       // String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/Oil_set.xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                List<OilCanSet> readExcelOilCanSetContent = excel
                        .readExcelOilCanSetContent(is);
                for (OilCanSet oilCanSet : readExcelOilCanSetContent) {

                    dbManager.UpdateObject(oilCanSet);
                }
                is.close();
            } else {
                SystemUtils.showRsToast(R.string.datamanager_chek_out);
            }
        } catch (Exception e) {

            //e.printStackTrace();
        }
    }

    /**
     * 从U盘中读取罐表
     */
    private void ReadOilCanTable(String path) {
        int age;
        age = 20;
        System.out.println(age);
        int score = 100;
        System.out.println(score);

        ExportExcel<Object> excel = new ExportExcel<Object>();
       // String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/" + "OilCan_set" + ".xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                List<OilCanTable> readExcelOilCanTableContent = excel
                        .readExcelOilCanTableContent(is);
                for (OilCanTable oilCanTable : readExcelOilCanTableContent) {
                    dbManager.UpdateObject(oilCanTable);
                }
                is.close();
                ToastUtils.showToastLong("Read Success");
            } else {
                ToastUtils.showToastLong(R.string.datamanager_chek_out);
            }

        } catch (Exception e) {
            ToastUtils.showToastLong(e.getMessage());
            //e.printStackTrace();
        }

    }

    private void ReadCommitWork() {
        System.out.println("====读取交班====");
        ExportExcel<Object> excel = new ExportExcel<Object>();
        String path = "/storage/usbhost1";
        InputStream is = null;
        File file = new File(path + "/交班表.xls");
        try {
            if (file.exists()) {

                is = new FileInputStream(file);

                List<CommitWork> commitWorks = excel
                        .readExcelCommitWorkContent(is);
                for (CommitWork commitWork : commitWorks) {
                    System.out.println("交班数据====" + commitWork);
                    dbManager.saveObject(commitWork);
                }

                is.close();
            } else {
                SystemUtils.showRsToast(R.string.datamanager_chek_out);
            }

        } catch (Exception e) {

            //e.printStackTrace();
        }
    }

    private void SaveOilCanSet(String path) {
        ExportExcel<OilCanSet> exportExcel = new ExportExcel<OilCanSet>();
        String headers[] = {"gmt_modified", "gmt_create", "pk_d2dId",  "罐号", "油高", "最大体积", "高油位报警", "高高油位报警", "低油位报警", "低低油位报警", "高水位报警",
                "高温度报警", "低温度报警", "油位补偿", "水位补偿", "温度补偿", "油品密度", "密度变化量",
                "报警允许位", "油品类型", "油品颜色"};
        DBDataManager db = MyApplication.getInstance().getmDBManager();
        List<OilCanSet> dataset = db.findAllObject(OilCanSet.class);

        // 存储到U盘
       // String path = "/storage/usbhost1";
        // 存储到本地

        File file = new File(path);


            if (!file.exists())
                file.mkdir();
            OutputStream out = null;
            try {
                file = new File(path + "/Oil_set.xls");
                out = new FileOutputStream(file);
                exportExcel.exportExcel(headers, dataset,
                        new OilCanSet().getParams(), out);
                out.close();
            } catch (Exception e) {
                //e.printStackTrace();
            }
            double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
            System.out.println("油罐设置文件的大小多少？" + size);
    }

    private void SaveProbeToOilCan(String path) {
        ExportExcel<ProbeToOil> exportExcel = new ExportExcel<ProbeToOil>();
        String headers[] = {"pk_d2dId", "油罐ID", "创建时间", "修改时间", "探针ID", "探针类型", "主页显示"};
        DBDataManager db = MyApplication.getInstance().getmDBManager();
        List<ProbeToOil> dataset = db.findAllObject(ProbeToOil.class);

        // 存储到U盘
       // String path = "/storage/usbhost1";

        File file = new File(path);
            if (!file.exists())
                file.mkdir();
            OutputStream out = null;
            try {
                file = new File(path + "/Probe_set.xls");
                out = new FileOutputStream(file);
                exportExcel.exportExcel(headers, dataset,
                        new ProbeToOil().getParams(), out);
                out.close();

            } catch (Exception e) {
                // TODO Auto-generated catch block
                //e.printStackTrace();
            }
            double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
            System.out.println("探针设置文件的大小多少？" + size);

    }

    private void SaveCommitWork() {
        ExportExcel<CommitWork> exportExcel = new ExportExcel<CommitWork>();
        String headers[] = {"时间", "用户", "油罐号", "接班高度", "交班高度", "售油体积", "售油重量"};
        DBDataManager db = MyApplication.getInstance().getmDBManager();
        List<CommitWork> dataset = db.findAllObject(CommitWork.class);

        // 存储到U盘
        String path = "/storage/usbhost1";

        File file = new File(path);
        if (SystemUtils.isUsbExit()) {
            if (!file.exists())
                file.mkdir();
            OutputStream out = null;
            try {
                file = new File(path + "/交班表.xls");
                out = new FileOutputStream(file);
                exportExcel.exportCommitWork(headers, dataset,
                        out, path);
                out.close();

            } catch (Exception e) {
                // TODO Auto-generated catch block
                //e.printStackTrace();
            }
            double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
            System.out.println("交班文件的大小多少？" + size);
        }

    }


    private void SaveOilStroage() {
        ExportExcel<OilSaveInfo> exportExcel = new ExportExcel<OilSaveInfo>();
        String headers[] = {"时间", "备注信息", "油罐号", "存油高度", "存水高度", "存油体积", "存油重量"};
        DBDataManager db = MyApplication.getInstance().getmDBManager();
        List<OilSaveInfo> dataset = db.findAllObject(OilSaveInfo.class);

        // 存储到U盘
        String path = "/storage/usbhost1";

        File file = new File(path);
        if (SystemUtils.isUsbExit()) {
            if (!file.exists())
                file.mkdir();
            OutputStream out = null;
            try {
                file = new File(path + "/存油表.xls");
                out = new FileOutputStream(file);
                exportExcel.exportOilStroage(headers, dataset,
                        out, path);
                out.close();

            } catch (Exception e) {
                // TODO Auto-generated catch block
                //e.printStackTrace();
            }
            double size = FileSizeUtil.getFileOrFilesSize(file.getAbsolutePath(), FileSizeUtil.SIZETYPE_KB);
            System.out.println("交班文件的大小多少？" + size);
        }

    }

    /**
     * 获取扩展存储路径，TF卡、U盘
     */
    public static String getExternalStorageDirectory() {
        String dir = new String();
        try {
            Runtime runtime = Runtime.getRuntime();
            Process proc = runtime.exec("mount");
            InputStream is = proc.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            String line;
            BufferedReader br = new BufferedReader(isr);
            String columns[];
            while ((line = br.readLine()) != null) {
                if (line.contains("secure"))
                    continue;
                if (line.contains("asec"))
                    continue;

                if (line.contains("fat")) {
                    columns = line.split(" ");
                    if (columns != null && columns.length > 1) {
                        dir = dir.concat(columns[1] + "\n");
                    }
                } else if (line.contains("fuse")) {
                    columns = line.split(" ");
                    if (columns != null && columns.length > 1) {
                        dir = dir.concat(columns[1] + "\n");
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return dir;
    }




    private void readOilCanTableFromCloud(LevelmeterInfo yeWeiYi) {
        List<OilCanTable> oilCanTables = dbManager.findAllObject(OilCanTable.class);
        for (OilCanTable oilCanTable : oilCanTables) {
            getOilCanTable(yeWeiYi, oilCanTable.getPk_oilCanId() + "");
        }

    }

    private void getOilCanTable(LevelmeterInfo yeWeiYi, String oilCanId) {
        System.out.println("D2d");
        saveData("d2d", yeWeiYi.getPk_d2dId() + "", "oilCanId", oilCanId,
                Const.ip + "oilcanCloud/getOilCanTableOne",
                new RequestCallBack() {

                    @Override
                    public void onFailure(HttpException error, String msg) {
                        SystemUtils.showRsToast(R.string.read_error);
                    }

                    @Override
                    public void onSuccess(ResponseInfo responseInfo) {
                        String result = responseInfo.result.toString();
                        OilCanTable oilCanTable = GsonUtil.parseJsonWithGson(result, OilCanTable.class);
                        dbManager.UpdateObject(oilCanTable);
                    }

                });
    }

    private void readOilCanSetFromCloud(LevelmeterInfo user) {
        // 读取油罐设置表数
        saveData("d2d", user.getPk_d2dId() + "",
                Const.ip + "oilcanCloud/getOilCanSetTableAll",
                new RequestCallBack() {

                    @Override
                    public void onFailure(HttpException error, String msg) {

                    }

                    @Override
                    public void onSuccess(ResponseInfo responseInfo) {
                        String result = responseInfo.result.toString();
                        List<OilCanSet> oilCanSets = dbManager.stringToArray(
                                result, OilCanSet[].class);
                        for (OilCanSet oilCanSet : oilCanSets) {
                            dbManager.UpdateObject(oilCanSet);
                        }
                    }

                });
    }

    private void readProbeToOilFromCloud(LevelmeterInfo user) {

        // 读取探针对照表数据
        saveData("d2d", user.getPk_d2dId() + "",
                Const.ip + "oilcanCloud/getProbeTableAll",
                new RequestCallBack() {

                    @Override
                    public void onFailure(HttpException error, String msg) {

                    }

                    @Override
                    public void onSuccess(ResponseInfo responseInfo) {
                        String result = responseInfo.result.toString();

                        List<ProbeToOil> probeToOils = dbManager.stringToArray(
                                result, ProbeToOil[].class);
                        for (ProbeToOil probeToOil : probeToOils) {
                            dbManager.UpdateObject(probeToOil);
                        }
                    }
                });


    }
    private String[]files=new String[]{"Probe_set.xls","Oil_set.xls","Oil_in_record.xls","Alarm_record.xls","OilCan_set.xls"};
    private void readCloud() {

       /* HashMap<String,String>map=new HashMap<>();
        map.put("bucketName", "oil");
        map.put("catalogue","OilSet/Log/");
        map.put("fileName",files[0]);
        List<File>list=new ArrayList<>();
        String file="storage/emulated/0/SanSen/";

        OkgoNet.getInstance().simplenessDownLoad("http://192.168.0.133:8080/minio/downloadFile", map, file,files[0], new com.sansen.yeweiyi.okGoUntils.RequestCallBack() {
            @Override
            public void Success(String message) {
                //Log.i("AAAAA",message);
            }

            @Override
            public void Error(String failing) {
                //Log.i("AAAAA",failing);
            }

            @Override
            public void Progress(Progress progress) {

            }
        });*/


//        for (int i=0;i<files.length;i++){
//            HashMap<String,String>map=new HashMap<>();
//            map.put("bucketName", "yeweiyi");
//            map.put("catalogue","OilSet/"+deviceSecret+"/");
//            map.put("fileName",files[i]);
//            String file="storage/emulated/0/SanSen/";
//            OkgoNet.getInstance().simplenessDownLoad("http://39.98.62.79:8080/minio/downloadFile", map, file,files[i], new com.sansen.yeweiyi.okGoUntils.RequestCallBack() {
//                @Override
//                public void Success(String message) {
//                    ToastUtils.showToast(message,1);
//                     Log.e("AAAAA",message+" ");
//                }
//
//                @Override
//                public void Error(String failing) {
//                    Log.e("AAAAA",failing+" ");
//                }
//
//                @Override
//                public void Progress(Progress progress) {
//
//                }
//            });
//            try {
//                Thread.sleep(2000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//
//        }




//        LevelmeterInfo yeWeiYi = myApplication.getYeWeiYi();
//        if (yeWeiYi != null) {
//            //getOilCanSetTableAll
//            //getOilCanTableAll
//            //getProbeTableAll
//            try {
//                getOilCanToCloud("getOilCanSetTableAll", OilCanSet.class);
//                getOilCanToCloud("getOilCanTableAll", OilCanTable.class);
//                getOilCanToCloud("getProbeTableAll", ProbeToOil.class);
//                SystemUtils.showRsToast(R.string.data_save_success);
//            } catch (Exception e) {
//                SystemUtils.showRsToast(R.string.data_save_error);
//            }
//        } else {
//            SystemUtils.showRsToast(R.string.please_set_d2d);
//        }
    }
    private void saveCloud() {
        //Log.i("aaaa",deviceSecret);
        HashMap<String,String>map=new HashMap<>();
        map.put("bucketName", "yeweiyi");
        map.put("catalogue","OilSet/"+deviceSecret+"/");
        List<File>list=new ArrayList<>();
        for (String str:files) {
           list.add(new File("storage/emulated/0/SanSen/"+str));
        }
//        OkgoNet.getInstance().simplenessUpFile("http://39.98.62.79:8080/minio/uploadMultifile", map, list, new com.sansen.yeweiyi.okGoUntils.RequestCallBack() {
//            @Override
//            public void Success(String message) {
//                ToastUtils.showToast(message,1);
//                Log.e("AAAAA",message+" ");
//            }
//
//            @Override
//            public void Error(String failing) {
//                Log.e("AAAAA",failing+" ");
//            }
//
//            @Override
//            public void Progress(Progress progress) {
//
//            }
//        });
//        if (file1.exists()){
//            OkgoNet.getInstance().saveToInternet("http://192.168.0.133:8080/minio/uploadFile",map,file1, new com.sansen.yeweiyi.okGoUntils.RequestCallBack() {
//                @Override
//                public void Success(String message) {
//                    Log.e("AAAAA",message);
//
//
//                }
//
//                @Override
//                public void Error(String failing) {
//                    Log.e("AAAAA",failing);
//                }
//
//                @Override
//                public void Progress(Progress progress) {
//
//                }
//            });
//        }



//        LevelmeterInfo yeWeiYi = myApplication.getYeWeiYi();
//        if (yeWeiYi != null) {
//            List<OilCanSet> oilCanSets = dbManager.findAllObject(OilCanSet.class);
//            for (OilCanSet oilCanSet : oilCanSets) {
//                if (oilCanSet.getGmt_modified() != null) {
//                    oilCanSet.setPk_d2dId(yeWeiYi.getPk_d2dId());
//                    String data = dbManager.parseObjectWithGson(oilCanSet,
//                            OilCanSet.class);
//                    saveOilCanToCloud("setOilCanSetTable", "oilCanSetTableGson", data);
//                }
//            }
//
//            List<ProbeToOil> probeToOils = dbManager
//                    .findAllObject(ProbeToOil.class);
//            for (ProbeToOil probeToOil : probeToOils) {
//                if (probeToOil.getGmt_modified() != null) {
//                    probeToOil.setPk_d2dId(yeWeiYi.getPk_d2dId());
//                    String data = dbManager.parseObjectWithGson(probeToOil,
//                            ProbeToOil.class);
//                    saveOilCanToCloud("setProbeTable", "probeTableGson", data);
//                }
//            }
//            List<OilCanTable> oilCanTables = dbManager
//                    .findAllObject(OilCanTable.class);
//            for (OilCanTable oilCanTable : oilCanTables) {
//                if (oilCanTable.getGmt_modified() != null) {
//                    oilCanTable.setPk_d2dId(yeWeiYi.getPk_d2dId());
//                    String data = dbManager.parseObjectWithGson(oilCanTable,
//                            OilCanTable.class);
//                    saveOilCanToCloud("setOilCanTable", "oilCanTableGson", data);
//                }
//            }
//            SystemUtils.showRsToast(R.string.save_oilcan_service_success);
//        } else {
//            SystemUtils.showRsToast(R.string.please_set_d2d);
//        }

    }

    protected void saveData(String parameter1, String data1, String parameter2, String data2, String url,
                            RequestCallBack requestCallBack) {

        RequestParams requestParams = new RequestParams("UTF-8");
        requestParams.addQueryStringParameter(parameter1, data1);
        requestParams.addQueryStringParameter(parameter2, data2);
        main.loadData(HttpMethod.POST, url, requestParams, requestCallBack);
    }

    protected void saveData(String parameter, String data, String url,
                            RequestCallBack requestCallBack) {

        RequestParams requestParams = new RequestParams("UTF-8");
        requestParams.addQueryStringParameter(parameter, data);
        main.loadData(HttpMethod.POST, url, requestParams, requestCallBack);
    }


    @Override
    public void onHiddenChanged(boolean hidden) {
        // TODO Auto-generated method stub
        super.onHiddenChanged(hidden);
        ////Log.i(TAG, "onHiddenChanged: "+hidden);
        if (hidden & Const.isUpdate == true) {
            updateData();
            Const.isUpdate = false;
        } else {
            onReflushData();
        }
    }

    class MyAdapter extends BaseAdapter {


        @Override
        public int getCount() {
            // TODO Auto-generated method stub
            return icon.length;
        }

        @Override
        public Object getItem(int arg0) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public long getItemId(int arg0) {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public View getView(int position, View arg1, ViewGroup arg2) {
            View v = LayoutInflater.from(ct).inflate(R.layout.item_base_set, null);

            Button iv_set = (Button) v.findViewById(R.id.base_button);
            iv_set.setBackgroundResource(icon[position]);
            iv_set.setText(data[position]);
            return v;
        }

    }


    protected void getOilCanToCloud(String url, final Class clazz) {

        LevelmeterInfo yeWeiYi = myApplication.getYeWeiYi();
        ////Log.i(TAG, "d2d: " + yeWeiYi.getPk_d2dId());
        url = OkHttpUtil.attachHttpGetParam("http://" + Const.ip + ":8080/SanSenTest/oilcanCloud/" + url, "d2d", yeWeiYi.getPk_d2dId() + "");
        // 登录
        final Request request = new Request.Builder().url(url).removeHeader("User-Agent").removeHeader("Set-Cookie").addHeader("Set-Cookie", NetUtils.getMacAddr())
                .addHeader("User-Agent", "levelmeter").build();
        OkHttpUtil.enqueue(request, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                ////Log.i(TAG, "onFailure: " + e.toString());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String result = response.body().string();
                ////Log.i(TAG, "onResponse: " + result);
                ////Log.i(TAG, "run: " + Thread.currentThread().getName());
                if (StringUtils.isNotEmpty(result)) {
                    List<Object> arr = JSONObject.parseArray(result, clazz);
                    for (Object o : arr) {
                        DBDataManager.getInstance().UpdateObject(o);
                    }
                }


            }
        });
    }

    //全部是post请求
    protected void saveOilCanToCloud(String action, String requestPararm, String json) {
        List<BasicNameValuePair> list = new ArrayList<BasicNameValuePair>();
        list.add(new BasicNameValuePair(requestPararm, json));
        RequestBody body = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded;charset=utf-8"),
                OkHttpUtil.formatParams(list));
        String url = "http://" + Const.ip + ":8080/SanSenTest/oilcanCloud/" + action;
        ////Log.i(TAG, "saveOilCanToCloud: "+url+"===== "+json);
        // 登录
        final Request request = new Request.Builder().url(url).removeHeader("User-Agent").removeHeader("Set-Cookie").addHeader("Set-Cookie", NetUtils.getMacAddr())
                .addHeader("User-Agent", "levelmeter").post(body).build();

        OkHttpUtil.enqueue(request, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                ////Log.i(TAG, "saveOilCanToCloud  onFailure: " + e.toString());
                //e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String result = response.body().string();
                ////Log.i(TAG, "saveOilCanToCloud onResponse: " + result);

            }
        });
    }


}