package com.sansen.yeweiyi.adapter;

import android.annotation.SuppressLint;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilCan;
import com.sansen.yeweiyi.db.OilInOut;
import com.sansen.yeweiyi.logic.Const;
import com.sansen.yeweiyi.manager.OilcanDataManager;
import com.sansen.yeweiyi.utils.NumberTools;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.util.ArrayList;
import java.util.List;

public class InOutAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<OilInOut> oilInOuts;
    private OnItemClickListener mOnItemClickListener;

    private double wendu = 0;


    public InOutAdapter() {

        oilInOuts = new ArrayList<OilInOut>();
    }

    public void setData(List<OilInOut> lists) {
        oilInOuts.clear();
        oilInOuts.addAll(lists);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recode_inout, parent, false);
        InOutAdapter.ViewHolder holder = new InOutAdapter.ViewHolder(v);
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
        holder.input_tv_endvolum = (TextView) v
                .findViewById(R.id.input_tv_endvolum);
        holder.inout_tv_zhiliangl = v.findViewById(R.id.inout_tv_zhiliangl);
        holder.inout_tv_zhiliangkg = v.findViewById(R.id.inout_tv_zhiliangkg);
        return holder;
    }

    OilcanDataManager oilcanDataManager = null;
   String jieZhi;
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") final int position) {
        if (position < oilInOuts.size()) {
            OilInOut oilInOut = (OilInOut) oilInOuts.get(position);
            InOutAdapter.ViewHolder viewHolder = (InOutAdapter.ViewHolder) holder;
            if (oilInOut != null) {
                double scaleHeight = 1;
                double scaleVolume = 1;
                double radio = 1;
                if (SharePrefUtil.getBoolean(MyApplication.getInstance(), SharePrefUtil.TEST_DATA, false)) {
//                    int tempHeight= OilcanDataManager.getInstance(MyApplication.getInstance()).getTestData(oilInOut.getOilId()).getMaxHeight();
//                    int curHeight= OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId()).getMaxHeight();
//                    radio=tempHeight/(double)curHeight;
                    if (oilcanDataManager == null)
                        oilcanDataManager = OilcanDataManager.getInstance(MyApplication.getInstance());

                    scaleHeight = oilcanDataManager.getTestData(oilInOut.getOilId()).getScaleHight();
                    scaleVolume = oilcanDataManager.getTestData(oilInOut.getOilId()).getScaleVolume();
                    if (scaleHeight == 0)
                        scaleHeight = 1;
                    if (scaleVolume == 0)
                        scaleVolume = 1;

                }


                viewHolder.inout_tv_id.setText(String.valueOf(position + 1));
                viewHolder.inout_tv_guan_hao.setText(String.valueOf(oilInOut.getOilId()));
                OilCan tankForThisRow = OilcanDataManager.getInstance(MyApplication.getInstance()).getData(oilInOut.getOilId());
                if (tankForThisRow==null){
                    jieZhi=Const.getOilType(0);
                }
                else {
                    jieZhi=Const.getOilType(tankForThisRow.getOilType());
                }
                viewHolder.inout_tv_jie_zhi.setText(jieZhi);
                viewHolder.inout_tv_time.setText(String.valueOf(oilInOut.getTime()));
                viewHolder.inout_tv_beginheight.setText(String.valueOf(NumberTools.formatDouble4(oilInOut.getBeginHeigth() / scaleHeight)));
                viewHolder.inout_tv_endheight.setText(String.valueOf(NumberTools.formatDouble4(oilInOut.getEndHeigth() / scaleHeight)));
                viewHolder.input_tv_beginvolum.setText(String.valueOf(NumberTools.formatDouble4(oilInOut.getBeginVolum() / scaleVolume)));
                viewHolder.input_tv_endvolum.setText(String.valueOf(NumberTools.formatDouble4(oilInOut.getEndVolum() / scaleVolume)));
                //Log.i("TAG:===== ",String.valueOf(oilInOut.getEndVolum() / scaleVolume-oilInOut.getBeginVolum() / scaleVolume));
                viewHolder.inout_tv_zhiliangl.setText(String.valueOf(NumberTools.formatDouble4(oilInOut.getEndVolum() / scaleVolume-oilInOut.getBeginVolum() / scaleVolume)));
                // [已更正-2026-09-27，二次更正] 上一版改成直接读 endWeight/beginWeight 相减，真机测试入库
                // 发现是负数——查了 InOilManager.java 才发现 beginWeight 那个字段本身写入时就是个 bug（存的是
                // 容积差，没乘密度，跟 endWeight 不是同一种量纲，两者相减没有意义）。改回按密度现算，但用这条
                // 记录自己所属的罐（oilInOut.getOilId()）现查密度，不再是 adapter 构造时缓存的"数据库第一个
                // 罐"密度。
                double densityForThisRow = tankForThisRow == null ? 0 : tankForThisRow.getDensity();
                viewHolder.inout_tv_zhiliangkg.setText(String.valueOf(NumberTools.formatDouble4(Double.parseDouble(viewHolder.inout_tv_zhiliangl.getText().toString())*densityForThisRow)));
                if (mOnItemClickListener != null) {
                    // [不可达-2026-09-21] 这个 onClick 是空实现，从来没有调用 mOnItemClickListener.onClick(position)——
                    // 短按列表项什么都不会发生（西语翻译覆盖率核查时发现）。只有长按会走下面的 onLongClick。
                    // InOilFragment.onClick(int) 整个方法（inflate dialog_detail_inout.xml 那段，含
                    // inoil_unload_stop/inoil_unloading 等字符串资源）因此是死代码，不用优先翻。
                    // 如果这是需求上的疏漏（短按本该弹详情），修法是在这里调 mOnItemClickListener.onClick(position)。
                    viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {

                        }
                    });
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
        return oilInOuts.size();
    }

    public Object getItem(int position) {
        if (position < oilInOuts.size()) {
            return oilInOuts.get(position);
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
        TextView input_tv_endvolum;//后量
        TextView inout_tv_zhiliangl;//入库量 l
        TextView inout_tv_zhiliangkg;//入库量 kg


        public ViewHolder(View itemView) {
            super(itemView);
        }
    }

    /**
     * 添加数据列表项
     */
    public void addNewsItem(List<OilInOut> newAlarm) {
        oilInOuts.addAll(newAlarm);
    }

    public interface OnItemClickListener {
        void onClick(int position);

        void onLongClick(int position);
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        this.mOnItemClickListener = onItemClickListener;
    }
}
