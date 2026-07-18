package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;

import com.lidroid.xutils.exception.DbException;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.activity.MainActivity;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.db.Alarm;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.db.DetailInoutRecord;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.db.OilSaveInfo;
import com.sansen.yeweiyi.db.Sideleakage;
import com.sansen.yeweiyi.db.TempRecord;
import com.sansen.yeweiyi.db.help.DaoHelp;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.ThreadManager;
import com.sansen.yeweiyi.manager.ThreadManager.ThreadPoolProxy;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecordManagerFragment extends BaseFragment implements OnItemClickListener {

    private GridView gridview;
    int[] icon = {R.drawable.sansen_clear_alarm_but_selector, R.drawable.sansen_clear_inoil_but_selector, R.drawable.sansen_clear_inoil_but_selector,
            R.drawable.sansen_clear_cunyou_but_selector, R.drawable.sansen_clear_jiaoban_but_selector, R.drawable.sansen_clear_celou_but_selector, R.drawable.sansen_clear_all_but_selector};

    String[] data = SystemUtils.getStringsById(R.array.data_manager);

    private ArrayList<Map<String, Object>> data_list;
    private ThreadPoolProxy shortPool;

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

    @Override
    protected void initData(Bundle savedInstanceState) {
        shortPool = ThreadManager.getShortPool();
        gridview = (GridView) rootView.findViewById(R.id.datamanager_gridview_data);
        gridview.setNumColumns(3);
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
    public void onItemClick(AdapterView<?> arg0, View arg1, int index, long arg3) {
        MainActivity main = (MainActivity) ct;
        switch (index) {
            case 0:
                Runnable clearAlarm = new Runnable() {
                    public void run() {
                        clearAlarm();

                    }
                };

                shortPool.execute(clearAlarm);
                SystemUtils.showRsToast(R.string.datamanager_clearalarm);

                break;
            case 1:
                Runnable clearOilInOut = new Runnable() {
                    public void run() {
                        clearRecord(OilInOut.class);
                        clearRecord(DetailInoutRecord.class);
                    }
                };
                shortPool.execute(clearOilInOut);
                SystemUtils.showRsToast(R.string.datamanager_clearinoutoil);
                break;
            case 2:
                Runnable clearTemp = new Runnable() {
                    public void run() {
                        clearRecord(TempRecord.class);
                    }
                };
                shortPool.execute(clearTemp);
                SystemUtils.showRsToast(R.string.datamanager_cleartemp);
                break;
            case 3:
                Runnable clearSaveInfo = new Runnable() {
                    public void run() {
                        clearRecord(OilSaveInfo.class);

                    }
                };
                shortPool.execute(clearSaveInfo);
                SystemUtils.showRsToast(R.string.datamanager_clearsaveoil);
                break;
            case 4:
                Runnable clearCommitWork = new Runnable() {
                    public void run() {
                        clearRecord(CommitWork.class);

                    }
                };
                shortPool.execute(clearCommitWork);

                SystemUtils
                        .showRsToast(R.string.datamanager_clarcommitwork);
                break;
            case 5:
                Runnable clearSideleakage = new Runnable() {
                    public void run() {
                        clearRecord(Sideleakage.class);

                    }
                };
                shortPool.execute(clearSideleakage);

                SystemUtils
                        .showRsToast(R.string.datamanager_clarsideleakage);
                break;
            case 6:
                Runnable clearAll = new Runnable() {
                    public void run() {
                        clearRecord(Alarm.class);
                        clearRecord(OilInOut.class);
                        clearRecord(OilSaveInfo.class);
                        clearRecord(CommitWork.class);
                        clearRecord(Sideleakage.class);
                    }
                };
                shortPool.execute(clearAll);
                SystemUtils.showRsToast(R.string.datamanager_clarall);
                break;
        }
    }

    /**
     * 清空报警记录
     */
    public void clearAlarm() {

        try {
            while (Const.isAlarm == 0) {

                dbManager.DeleteTable(Alarm.class);

                DaoHelp.getInstance(ct).createTableIfNotExist(Alarm.class);

                break;
            }

        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }

    }

    /**
     * 清空记录
     *
     * @param view
     */
    public void clearRecord(Class clazz) {
        try {
            dbManager.DeleteTable(clazz);
            DaoHelp.getInstance(ct).createTableIfNotExist(clazz);
        } catch (DbException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
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
}
