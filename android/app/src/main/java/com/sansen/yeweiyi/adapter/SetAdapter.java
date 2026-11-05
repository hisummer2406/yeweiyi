package com.sansen.yeweiyi.adapter;


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.utils.SystemUtils;

public class SetAdapter extends BaseAdapter {
    private int[] icon;

    private MyApplication myApplication;

    public SetAdapter(int[] icon) {
        this.icon = icon;
        myApplication = MyApplication.getInstance();
       data = SystemUtils.getStringsById(R.array.set_array);
    }

    @Override
    public int getCount() {
        return icon.length;
    }

    @Override
    public Object getItem(int arg0) {
        return null;
    }

    @Override
    public long getItemId(int arg0) {
        return 0;
    }

    ImageView button;

    private String[] data;

    @Override
    public View getView(int position, View arg1, ViewGroup arg2) {

        View v = LayoutInflater.from(myApplication).inflate(R.layout.item_base_set, null);

        ImageView iv_set =  v.findViewById(R.id.base_button);
        TextView tv_name=v.findViewById(R.id.tv_name);
        iv_set.setImageResource(icon[position]);
        tv_name.setText(data[position]);
        return v;
    }

    public void setEnable(int index, View ib) {
        switch (index) {
            case 0:
                    ib.setEnabled(true);

                break;
            case 1:
                if (myApplication.getAuthorization().getOilcanSet() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 2:
                if (myApplication.getAuthorization().getOilcanTable() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }

                break;
            case 3:
                if (myApplication.getAuthorization().getOilExploration() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }

                break;

            case 4:
                if (myApplication.getAuthorization().getUserManager() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 5:
                if (myApplication.getAuthorization().getSystemSet()== 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 6:
                if (myApplication.getAuthorization().getAboutUser() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 7:
                if (myApplication.getAuthorization().getDataManager() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 8:
                if (myApplication.getAuthorization().getOilTest() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 9:
                if (myApplication.getAuthorization().getFyfManager() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 10:

                if (myApplication.getAuthorization().getUserManager() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            case 11:
                if (myApplication.getAuthorization().getOilTest() == 1) {
                    ib.setEnabled(true);
                } else {
                    ib.setEnabled(false);
                }
                break;
            default:
                break;
        }
    }
}
