package com.sansen.yeweiyi.fragment;

import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;

import com.alibaba.fastjson.JSONObject;
import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.adapter.OilTypeAdapter;
import com.sansen.yeweiyi.base.BaseFragment;
import com.sansen.yeweiyi.listener.OnCurFouceViewListener;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.List;

/**
 * Created by PC on 2018-06-13.
 */
public class OilTypeFragment extends BaseFragment{
    private static final String TAG ="OilTypeFragment";
    RecyclerView recyclerView;
    private View curfouceView;
    @Override
    public void initTitle() {
// TODO Auto-generated method stub
        if (main!=null) {

        }
    }

    @Override
    protected View initView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.fragment_oiltype,null);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        recyclerView = (RecyclerView) rootView.findViewById(R.id.oiltype_custom);
        recyclerView.setLayoutManager(new LinearLayoutManager(ct));
        OilTypeAdapter typeAdapter= new OilTypeAdapter();
        typeAdapter.setOnCurFouceViewListener(new OnCurFouceViewListener() {

            @Override
            public void curFouceView(View v) {
                curfouceView = v;
            }
        });
        String data = "";
        if (SharePrefUtil.getString(ct,SharePrefUtil.LANGUAGE,"zh").equals("zh")){
            data=SharePrefUtil.getString(ct,SharePrefUtil.CN_OIlTYPE,"[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]");
        }else if(SharePrefUtil.getString(ct,SharePrefUtil.LANGUAGE,"zh").equals("en")){
            data=SharePrefUtil.getString(ct,SharePrefUtil.EN_OIlTYPE,"[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]");
        }else if(SharePrefUtil.getString(ct,SharePrefUtil.LANGUAGE,"zh").equals("th")){
            data=SharePrefUtil.getString(ct,SharePrefUtil.TH_OIlTYPE,"[\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]");
        }
        List<String> list = JSONObject.parseArray(data,String.class);
        typeAdapter.setData(list);
        recyclerView.setAdapter(typeAdapter);
    }

    @Override
    protected void processClick(View v) {

    }

    protected class MyScrollListener implements AbsListView.OnScrollListener {

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem,
                             int visibleItemCount, int totalItemCount) {
            // do nothing
        }

        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {
            if (SCROLL_STATE_TOUCH_SCROLL == scrollState) {
                View currentFocus = getCurrentFocus();
                if (currentFocus != null) {
                    currentFocus.clearFocus();
                }
            }
        }

    }

    public View getCurrentFocus() {
        return curfouceView;
    }
}
