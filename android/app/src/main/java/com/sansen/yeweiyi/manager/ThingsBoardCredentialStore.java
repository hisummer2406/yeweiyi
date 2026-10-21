package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.annotation.RequiresApi;
import android.util.Base64;
import android.util.Log;

import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentialStore;
import com.sansen.yeweiyi.logic.thingsboard.DeviceCredentials;
import com.sansen.yeweiyi.utils.SharePrefUtil;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * {@link DeviceCredentialStore} 的 Android 实现：存在现有 {@code SharePrefUtil} 的
 * SharedPreferences 文件里，跟设备名等其它现场配置一起，不新起一个存储文件。
 * <p>
 * 凭据写入时机是设备现场配网/换绑，入口见 {@code fragment.ThingsBoardSetFragment}
 * （现场录入 broker 地址 + 编号，token 自动领取或手填，本类只负责存取）；
 * 吊销/换绑时调用 {@link #clear()}。
 * <p>
 * {@code accessToken} 是真正的密钥材料，用 Android Keystore 的 AES/GCM 对称密钥加密后
 * 再落盘（PR #38 人工审核 item 6：明文 SharedPreferences 不满足安全存储要求）。密钥本身
 * 生成后留在 Keystore 硬件/系统密钥库里，不会被导出，卸载应用或调用
 * {@link KeyStore#deleteEntry} 才会失效。{@code deviceId} 只是 MQTT client ID，不是需要
 * 保密的材料，保持明文，方便调试时肉眼核对。Keystore 对称密钥需要 API 23（Android 6.0），
 * 低于这个版本的设备回退成明文存储，并记一条警告日志——不是本项目 minSdk 21 覆盖范围内的
 * 真实机型（这台液位仪终端跑的是 Android 6.0+），保留这条路径只是不让编译期 minSdk 校验失败。
 */
public final class ThingsBoardCredentialStore implements DeviceCredentialStore {

    private static final String TAG = "TBCredentialStore";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "thingsboard_access_token";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    /** 明文回退前缀：只有 API < 23 才会写这种格式，{@link #load()} 靠它判断要不要走解密。 */
    private static final String PLAINTEXT_PREFIX = "plain:";
    private static final String ENCRYPTED_PREFIX = "enc:";
    /** 配网页和 tb-reconnect 线程各自 new 实例，锁必须是静态的才能互斥。 */
    private static final Object LOCK = new Object();

    private final Context context;

    public ThingsBoardCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public DeviceCredentials load() {
        synchronized (LOCK) {
            String deviceId = SharePrefUtil.getString(context, SharePrefUtil.TB_DEVICE_ID, "");
            String stored = SharePrefUtil.getString(context, SharePrefUtil.TB_ACCESS_TOKEN, "");
            String accessToken = decode(stored);
            return new DeviceCredentials(deviceId, accessToken);
        }
    }

    /**
     * 盘上有 token 密文，但解不开（Keystore 密钥不在了）。典型来源：配置被备份恢复/复制到另一台硬件，
     * 或应用重装后恢复了备份——编码还在，密钥没跟过来。这种身份不能被静默地拿编码去重新领取，
     * 见 {@code ThingsBoardManager.attemptConnect}。刚保存的空 token 不算（空串也是可解的密文）。
     */
    public boolean hasUnreadableToken() {
        synchronized (LOCK) {
            String stored = SharePrefUtil.getString(context, SharePrefUtil.TB_ACCESS_TOKEN, "");
            return stored != null && !stored.isEmpty() && decodeOrNull(stored) == null;
        }
    }

    @Override
    public void save(DeviceCredentials credentials) {
        String encoded = encode(credentials.getAccessToken());
        synchronized (LOCK) {
            SharePrefUtil.saveStrings(context, SharePrefUtil.TB_DEVICE_ID, credentials.getDeviceId(),
                    SharePrefUtil.TB_ACCESS_TOKEN, encoded);
        }
    }

    /**
     * 自动领到 token 后写回：只有已存的仍是同一编号、且还没有有效 token 时才写，检查和写入在同一把锁里。
     * 返回 false 说明领取期间配网页改了编号或手填了 token，这次领到的作废。
     */
    public boolean saveIfAwaitingToken(DeviceCredentials provisioned) {
        synchronized (LOCK) {
            DeviceCredentials current = load();
            if (!provisioned.getDeviceId().equals(current.getDeviceId()) || current.isValid()) {
                return false;
            }
            save(provisioned);
            return true;
        }
    }

    /** 连接成功后记下编号；只在变化时写盘，重连不反复写。 */
    public void markConnected(String deviceId) {
        synchronized (LOCK) {
            String recorded = SharePrefUtil.getString(context, SharePrefUtil.TB_CONNECTED_DEVICE_ID, "");
            if (!deviceId.equals(recorded)) {
                SharePrefUtil.saveString(context, SharePrefUtil.TB_CONNECTED_DEVICE_ID, deviceId);
            }
        }
    }

    /**
     * 当前保存的编号是否用这台设备连接成功过。不看令牌能否解密，令牌解不开时是否锁定由调用方决定。
     */
    public boolean hasConnected() {
        synchronized (LOCK) {
            String deviceId = SharePrefUtil.getString(context, SharePrefUtil.TB_DEVICE_ID, "");
            String recorded = SharePrefUtil.getString(context, SharePrefUtil.TB_CONNECTED_DEVICE_ID, "");
            return deviceId != null && !deviceId.isEmpty() && deviceId.equals(recorded);
        }
    }

    @Override
    public void clear() {
        synchronized (LOCK) {
            SharePrefUtil.saveStrings(context, SharePrefUtil.TB_DEVICE_ID, "", SharePrefUtil.TB_ACCESS_TOKEN, "");
        }
    }

    private String encode(String accessToken) {
        if (accessToken == null) {
            accessToken = "";
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "系统版本低于 Android 6.0，access token 无法用 Keystore 加密，回退明文存储");
            return PLAINTEXT_PREFIX + accessToken;
        }
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(accessToken.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[1 + iv.length + ciphertext.length];
            combined[0] = (byte) iv.length;
            System.arraycopy(iv, 0, combined, 1, iv.length);
            System.arraycopy(ciphertext, 0, combined, 1 + iv.length, ciphertext.length);
            return ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            // Keystore 不可用（部分定制 ROM/模拟器）时不让配网彻底失败——退回明文存储，
            // 比"存不进去、设备永远连不上"更安全，仍然记一条醒目日志。
            Log.e(TAG, "Keystore 加密 access token 失败，回退明文存储", e);
            return PLAINTEXT_PREFIX + accessToken;
        }
    }

    private String decode(String stored) {
        String decoded = decodeOrNull(stored);
        return decoded == null ? "" : decoded;
    }

    /** @return 明文；解不开返回 null，空输入返回空串。 */
    private String decodeOrNull(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (stored.startsWith(PLAINTEXT_PREFIX)) {
            return stored.substring(PLAINTEXT_PREFIX.length());
        }
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            // 没有任何前缀：历史遗留数据（理论上不存在，save() 之前从没有调用方），
            // 按明文原样返回，不当成加密数据去解密。
            return stored;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // "enc:" 前缀只可能是 API 23+ 的 encode() 写的；系统被降级到这个版本以下
            // 不应该发生，但真出现时解不开也不能崩溃，当未配网处理。
            Log.e(TAG, "系统版本低于 Android 6.0，无法解密已加密的 access token，视为未配网");
            return null;
        }
        try {
            byte[] combined = Base64.decode(stored.substring(ENCRYPTED_PREFIX.length()), Base64.NO_WRAP);
            int ivLength = combined[0] & 0xFF;
            byte[] iv = new byte[ivLength];
            System.arraycopy(combined, 1, iv, 0, ivLength);
            byte[] ciphertext = new byte[combined.length - 1 - ivLength];
            System.arraycopy(combined, 1 + ivLength, ciphertext, 0, ciphertext.length);

            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            // Keystore 密钥丢失（比如恢复出厂设置后 Keystore 被清空但 SharedPreferences
            // 备份还在）或数据损坏时，宁可当作"未配网"处理，也不能抛异常崩溃整个连接流程。
            Log.e(TAG, "解密 access token 失败，视为未配网", e);
            return null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            KeyStore.Entry existing = keyStore.getEntry(KEY_ALIAS, null);
            if (existing instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
            }
        } catch (java.io.IOException | java.security.cert.CertificateException e) {
            throw new GeneralSecurityException("读取 Keystore 失败", e);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }
}
