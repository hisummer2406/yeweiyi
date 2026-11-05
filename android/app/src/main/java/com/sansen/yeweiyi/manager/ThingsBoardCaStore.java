package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 私有化部署的 CA 证书从哪来。
 * <p>
 * 完全离线的部署里没有公网 CA、也跑不了 ACME，服务端证书由云端 {@code deploy/gen-certs.sh}
 * 自签，信任链靠把那张 {@code ca.pem} 预置到每台设备上建立（见 issue #40 §4）。
 *
 * <h3>两个位置，先文件后 assets</h3>
 * <ol>
 *   <li>{@code <filesDir>/thingsboard_ca.pem}——换 CA 时不用重新出包，装机/换证时写进来即可；</li>
 *   <li>APK 里的 {@code assets/thingsboard_ca.pem}——按部署方出定制包时最省事的那条路。</li>
 * </ol>
 * 文件优先于 assets，这样内置了一张 CA 的包也还能被现场换掉，而不是被打进包里的那张钉死。
 * <p>
 * <b>不从外置存储读</b>（{@code /sdcard} 之类）：那里任何应用都能写，从那里读 CA 等于把
 * "谁是可信的云端"这件事交给随便哪个装在同一台设备上的程序决定，TLS 校验就白做了。
 * 应用私有目录只有本应用能写。
 * <p>
 * 两个位置都没有时返回 null，不是错误——公网 CA 签发证书的部署本来就不需要这个文件，
 * 那种情况走系统信任库（见 {@code BrokerTls.systemTrustSocketFactory}）。
 */
public final class ThingsBoardCaStore implements ThingsBoardMqttConnection.CaCertificateSource {

    private static final String TAG = "TBCaStore";

    /** 两个位置用同一个文件名，避免"我到底该放哪个名字"的现场困惑。 */
    public static final String FILE_NAME = "thingsboard_ca.pem";

    private final Context context;

    public ThingsBoardCaStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /** @return CA 证书流，调用方负责关闭；没有预置 CA 时返回 null */
    @Override
    public InputStream openCaCertificate() throws IOException {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.isFile() && file.length() > 0) {
            Log.i(TAG, "使用设备上预置的私有 CA: " + file);
            return new FileInputStream(file);
        }
        try {
            InputStream asset = context.getAssets().open(FILE_NAME);
            Log.i(TAG, "使用 APK 内置的私有 CA: assets/" + FILE_NAME);
            return asset;
        } catch (IOException e) {
            // assets 里没有这个文件时 open() 就是抛 IOException，这是"没配"而不是"出错"。
            Log.i(TAG, "未预置私有 CA，TLS 将使用系统信任库");
            return null;
        }
    }
}
