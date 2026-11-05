package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.Log;

import com.sansen.yeweiyi.logic.thingsboard.IdentityScope;

import java.io.File;

/**
 * 设备身份名下的状态文件定位，规则见 {@link IdentityScope}。
 * <p>
 * 没有后缀的原文件名 = "尚未绑定身份时产生的状态"（包括升级前的旧文件）。第一次绑定到某个编码、
 * 且该编码名下还没有文件时，把它**改名**归给这个编码——这些状态是这台设备在配网前自己采集的，
 * 本来就属于它即将成为的那个身份。编码名下已经有文件时不动它，也不会把无后缀文件合并进去。
 */
final class IdentityScopedFile {

    private static final String TAG = "TBIdentityFile";

    private IdentityScopedFile() {
    }

    static File resolve(Context context, String baseName, String deviceId) {
        File dir = context.getFilesDir();
        String suffix = IdentityScope.fileSuffix(deviceId);
        File unscoped = new File(dir, baseName + ".json");
        if (suffix.isEmpty()) {
            return unscoped;
        }
        File scoped = new File(dir, baseName + suffix + ".json");
        if (!scoped.exists() && unscoped.exists()) {
            if (unscoped.renameTo(scoped)) {
                Log.i(TAG, "首次绑定身份，把未绑定期间的状态 " + unscoped.getName() + " 归给 " + scoped.getName());
            } else {
                Log.e(TAG, "无法把 " + unscoped.getName() + " 归给 " + scoped.getName() + "，该身份从空状态开始");
            }
        }
        return scoped;
    }
}
