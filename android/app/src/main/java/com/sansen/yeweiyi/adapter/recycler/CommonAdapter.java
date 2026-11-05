package com.sansen.yeweiyi.adapter.recycler;


import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;


import java.util.List;

public abstract class CommonAdapter<T> extends RecyclerView.Adapter<CommonViewHolder> {

    protected Context mContext;
    protected int mLayoutId;
    protected int mFootLayoutId;
    protected List<T> mDatas;
    protected LayoutInflater mInflater;
    private int normalType = 1;
    private int footType = 2;
    protected boolean mIsLoadMore = false;


    public CommonAdapter(Context context, int layoutId, List<T> datas) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mLayoutId = layoutId;
        mDatas = datas;
    }

    public CommonAdapter(Context context, int normalLayoutId, int footLayoutId, List<T> datas) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mLayoutId = normalLayoutId;
        mFootLayoutId = footLayoutId;
        mDatas = datas;
        mIsLoadMore = true;
    }

    public void refresh(List<T> datas) {
        mDatas = datas;
        notifyDataSetChanged();
    }

    public void refresh() {
        notifyDataSetChanged();
    }


    /**
     * 只要继承RecyclerView.Adapter都会重新三个方法，这个是其一，构造view视图。
     *
     * @param parent
     * @param viewType 可以通过重写getItemViewType来控制这个值，从而控制相对的操作和布局和逻辑
     * @return
     */
    @Override
    public CommonViewHolder onCreateViewHolder(final ViewGroup parent, int viewType) {
        if (viewType == normalType) {
            CommonViewHolder viewHolder = CommonViewHolder.createViewHolder(mContext, parent, mLayoutId);
            return viewHolder;
        } else {
            CommonViewHolder viewHolder = CommonViewHolder.createViewHolder(mContext, parent, mFootLayoutId);
            return viewHolder;
        }

    }

    /**
     * 只要继承RecyclerView.Adapter都会重新三个方法，这个是其二，用于绑定数据，上边已经造好视图了，这里只管放数据，
     * 这里写了两个抽象方法，实现是在构造adapter中实现。
     *
     * @param holder
     * @param position
     */
    @Override
    public void onBindViewHolder(CommonViewHolder holder, int position) {
        //    holder.updatePosition(position);
        if (holder.getItemViewType() == normalType) {
            convert(holder, mDatas.get(position), position);
        } else {
            footConvert(holder, mDatas.size());
        }


    }

    public abstract void convert(CommonViewHolder holder, T t, int position);

    public abstract void footConvert(CommonViewHolder holder, int size);


    /**
     * 只要继承RecyclerView.Adapter都会重新三个方法，这个是其三，其作用是设置item个数
     *
     * @return
     */
    @Override
    public int getItemCount() {
        if (mDatas == null) {
            return 0;
        }
        if (mIsLoadMore == true) {
            return mDatas.size() + 1;
        } else {
            return mDatas.size();
        }

    }

    /**
     * 加载更多
     */
    public void addDataList(List<T> addDatas) {
        if (addDatas != null) {
            mDatas.addAll(addDatas);
        }
        notifyDataSetChanged();
    }

    /**
     * 重写RecyclerView.Adapter中的方法，主要目的是通过自己的数据来动态修改onCreateViewHolder中的type，从而生成不同的view。
     *
     * @param position
     * @return
     */
    @Override
    public int getItemViewType(int position) {
        if (mIsLoadMore == true) {
            if (position == getItemCount() - 1) {
                return footType;
            } else {
                return normalType;
            }
        } else {
            return normalType;
        }
    }

    public boolean getIsLoadMore() {
        return mIsLoadMore;
    }
}