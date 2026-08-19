package com.sansen.yeweiyi.adapter;

import android.annotation.SuppressLint;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.db.OilCanSet;
import com.sansen.yeweiyi.db.StockSave;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.utils.NumberTools;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.ArrayList;
import java.util.List;

public class StockSaveAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<StockSave> stockSaves;
    private OnItemClickListener mOnItemClickListener;

    private double density = 0;
    private double wendu = 0;


    public StockSaveAdapter() {

        stockSaves = new ArrayList<StockSave>();
        List<OilCanSet> os = DBDataManager.getInstance().findAllObject(OilCanSet.class);
        if (os != null && os.size() > 0) {
            density = os.get(0).getDensity();
            Log.e("TAG","密度"+density);
        }
    }
 
    public void setData(List<StockSave> lists) {
        stockSaves.clear();
        stockSaves.addAll(lists);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recode_stock, parent, false);
        StockSaveAdapter.ViewHolder holder = new StockSaveAdapter.ViewHolder(v);
        holder.inout_tv_id = (TextView) v
                .findViewById(R.id.inout_tv_id);
        holder.inout_tv_guan_hao = (TextView) v
                .findViewById(R.id.inout_tv_guan_hao);
        holder.inout_tv_jie_zhi=v.findViewById(R.id.inout_tv_jie_zhi);
        holder.inout_tv_time = (TextView) v
                .findViewById(R.id.inout_tv_time);
        holder.inout_tv_beginheight = (TextView) v
                .findViewById(R.id.inout_tv_beginheight);
        holder.inout_tv_endheight = (TextView) v
                .findViewById(R.id.inout_tv_endheight);
        holder.input_tv_beginvolum = (TextView) v
                .findViewById(R.id.input_tv_beginvolum);
        holder.inout_tv_wen_du = v.findViewById(R.id.inout_tv_wen_du);
        holder.inout_tv_zhiliangkg = v.findViewById(R.id.inout_tv_zhiliangkg);
        return holder;
    }

    OilcanDataManager oilcanDataManager = null;
    String jieZhi="";
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") final int position) {
        if (position < stockSaves.size()) {
            StockSave stockSave = (StockSave) stockSaves.get(position);
            StockSaveAdapter.ViewHolder viewHolder = (StockSaveAdapter.ViewHolder) holder;
            if (stockSave != null) {
                double scaleHeight = 1;
                double scaleVolume = 1;
                double radio = 1;
                if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.TEST_DATA, false)) {
//                    int tempHeight= OilcanDataManager.getInstance(MyApplication.getInstance()).getTestData(stockSave.getOilId()).getMaxHeight();
//                    int curHeight= OilcanDataManager.getInstance(MyApplication.getInstance()).getData(stockSave.getOilId()).getMaxHeight();
//                    radio=tempHeight/(double)curHeight;
                    if (oilcanDataManager == null)
                        oilcanDataManager = OilcanDataManager.getInstance(MyApplication.getInstance());

                    scaleHeight = oilcanDataManager.getTestData(stockSave.getOilId()).getScaleHight();
                    scaleVolume = oilcanDataManager.getTestData(stockSave.getOilId()).getScaleVolume();
                  //  density = oilcanDataManager.getTestData(stockSave.getOilId()).getDensity();
                    if (scaleHeight == 0)
                        scaleHeight = 1;
                    if (scaleVolume == 0)
                        scaleVolume = 1;

                }


                viewHolder.inout_tv_id.setText(String.valueOf(position + 1));
                viewHolder.inout_tv_guan_hao.setText(String.valueOf(stockSave.getOilId()));
                int oilId=stockSave.getOilId();
                if (OilcanDataManager.getInstance(MyApplication.getInstance()).getData(stockSave.getOilId())==null){
                    jieZhi=Const.getOilType(0);
                }
                else {
                    jieZhi=Const.getOilType(OilcanDataManager.getInstance(MyApplication.getInstance()).getData(stockSave.getOilId()).getOilType());
                }
                viewHolder.inout_tv_jie_zhi.setText(jieZhi);
                viewHolder.inout_tv_time.setText(String.valueOf(stockSave.getTime()));
                viewHolder.inout_tv_beginheight.setText(String.valueOf(NumberTools.formatDouble4(stockSave.getHeight() / scaleHeight)));
                viewHolder.input_tv_beginvolum.setText(String.valueOf(NumberTools.formatDouble4(stockSave.getVolum() / scaleVolume)));
                viewHolder.inout_tv_endheight.setText(String.valueOf(NumberTools.formatDouble4(stockSave.getWaterheigth() / scaleHeight)));

             //   viewHolder.input_tv_endvolum.setText(String.valueOf(NumberTools.formatDouble4(stockSave.getWaterheigth() / scaleVolume)));
                viewHolder.inout_tv_wen_du.setText(String.valueOf(NumberTools.formatDouble4(stockSave.getTemperature())));
                viewHolder.inout_tv_zhiliangkg.setText(String.valueOf(NumberTools.formatDouble4(stockSave.getWeight())));//density
                if (mOnItemClickListener != null) {
//                    viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
//                        @Override
//                        public void onClick(View v) {
//                            mOnItemClickListener.onClick(position);
//                        }
//                    });
                    viewHolder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            mOnItemClickListener.onLongClick(position);
                            return false;
                        }
                    });
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return stockSaves.size();
    }

    public Object getItem(int position) {
        if (position < stockSaves.size()) {
            return stockSaves.get(position);
        }
        return null;
    }


    class ViewHolder extends RecyclerView.ViewHolder {
        TextView inout_tv_id;
        TextView inout_tv_jie_zhi;//介质
        TextView inout_tv_guan_hao;//罐号
        TextView inout_tv_time;//入库时间
        TextView inout_tv_beginheight;//前高
        TextView inout_tv_endheight;//后高
        TextView input_tv_beginvolum;//前量
        TextView inout_tv_wen_du;//入库量 l
        TextView inout_tv_zhiliangkg;//入库量 kg


        public ViewHolder(View itemView) {
            super(itemView);
        }
    }

    /**
     * 添加数据列表项
     */
    public void addNewsItem(List<StockSave> newAlarm) {
        stockSaves.addAll(newAlarm);
    }

    public interface OnItemClickListener {
        void onClick(int position);

        void onLongClick(int position);
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        this.mOnItemClickListener = onItemClickListener;
    }
}
