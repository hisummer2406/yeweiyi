package com.sansen.yeweiyi.adapter;

import android.annotation.SuppressLint;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.listener.OnCurFouceViewListener;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.List;

/**
 * Created by PC on 2018-06-13.
 */

public class OilTypeAdapter  extends  RecyclerView.Adapter<RecyclerView.ViewHolder> {

    List <String>list;
    private int mTouchItemPosition = -1;
    private int left=0;
    private OnCurFouceViewListener curFouceViewListener;
    public void setData(List<String> list) {
        this.list = list;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v= LayoutInflater.from(parent.getContext()).inflate(R.layout.item_oiltype,parent,false);
        OilTypeAdapter.ViewHolder viewHolder = new OilTypeAdapter.ViewHolder(v);
        viewHolder.type_tv= (TextView) v.findViewById(R.id.type_tv);
        viewHolder.type_et= (EditText) v.findViewById(R.id.type_et);
        return viewHolder;
    }

    public void setOnCurFouceViewListener(OnCurFouceViewListener curFouceViewListener) {
        this.curFouceViewListener = curFouceViewListener;
    }

    @SuppressLint("RecyclerView")
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, final int position) {
        OilTypeAdapter.ViewHolder viewHolder = (OilTypeAdapter.ViewHolder) holder;
        if ("zh".equals(SharePrefUtil.getString(MyApplication.getInstance(),SharePrefUtil.LANGUAGE,"zh"))){
            viewHolder.type_tv.setText("自定义 "+position+" 号油品");
        }else if("en".equals(SharePrefUtil.getString(MyApplication.getInstance(),SharePrefUtil.LANGUAGE,"zh"))){
            viewHolder.type_tv.setText("Custom No. "+position+" oil product");
        }else if ("th".equals(SharePrefUtil.getString(MyApplication.getInstance(),SharePrefUtil.LANGUAGE,"zh"))){
            viewHolder.type_tv.setText("กำหนดเองหมายเลข "+position+" น้ำมัน");
        }
        viewHolder.type_et.setHint(list.get(position));
        viewHolder.type_et.setOnFocusChangeListener(new View.OnFocusChangeListener() {

            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                curFouceViewListener.curFouceView(v);
                mTouchItemPosition = position;
                left = 0;
                EditText vText = (EditText) v;
                String lastInfo = null;
                if (vText.getTag(R.id.tag_first) == null) {
                    lastInfo = vText.getText().toString();
                    vText.setTag(R.id.tag_first, lastInfo);
                } else {
                    lastInfo = vText.getTag(R.id.tag_first).toString();
                }

                if (!hasFocus) {
                    String cur = vText.getText().toString();
                    if (!cur.equals(lastInfo) && !cur.equals("")) {
                        list.set(position,cur);
                        if (SharePrefUtil.getString(MyApplication.getInstance(),SharePrefUtil.LANGUAGE,"zh").equals("zh")){
                            SharePrefUtil.saveString(MyApplication.getInstance(),SharePrefUtil.CN_OIlTYPE, JSONObject.toJSONString(list));
                        }else if(SharePrefUtil.getString(MyApplication.getInstance(),SharePrefUtil.LANGUAGE,"zh").equals("en")){
                            SharePrefUtil.saveString(MyApplication.getInstance(),SharePrefUtil.EN_OIlTYPE, JSONObject.toJSONString(list));
                        }else if(SharePrefUtil.getString(MyApplication.getInstance(),SharePrefUtil.LANGUAGE,"zh").equals("th")){
                            SharePrefUtil.saveString(MyApplication.getInstance(),SharePrefUtil.TH_OIlTYPE, JSONObject.toJSONString(list));
                        }
                        vText.setHint(cur);
                        vText.setText("");
                    }
                }else{
                    vText.setText("");
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return list.size();
    }
    public static class ViewHolder extends RecyclerView.ViewHolder{
        TextView type_tv;
        EditText type_et;
        public ViewHolder(View itemView) {
            super(itemView);
        }
    }
}
