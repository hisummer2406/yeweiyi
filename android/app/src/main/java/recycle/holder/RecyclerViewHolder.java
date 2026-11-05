package recycle.holder;

import android.databinding.ViewDataBinding;
import android.support.v7.widget.RecyclerView;
import android.view.View;



/**
 * Created by PC on 2018-04-16.
 */

public class RecyclerViewHolder extends RecyclerView.ViewHolder{
    private ViewDataBinding binding;

    public RecyclerViewHolder(View itemView) {
        super(itemView);
    }

    public ViewDataBinding getBinding() {
        return binding;
    }

    public void setBinding(ViewDataBinding binding) {
        this.binding = binding;
    }

}
