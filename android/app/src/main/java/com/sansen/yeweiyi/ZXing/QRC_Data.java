package com.sansen.yeweiyi.ZXing;

import android.graphics.Bitmap;

public class QRC_Data {
    private Boolean isSuccess;
    private Bitmap bitmap;

    public Boolean getSuccess() {
        return isSuccess;
    }

    public void setSuccess(Boolean success) {
        isSuccess = success;
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
    }
}
