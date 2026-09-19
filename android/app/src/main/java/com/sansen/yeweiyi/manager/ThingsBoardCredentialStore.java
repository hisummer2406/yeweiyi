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
 * （现场手动录入 broker 地址 + deviceId/accessToken，本类只负责存取）；
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

    private final Context context;

    public ThingsBoardCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public DeviceCredentials load() {
        String deviceId = SharePrefUtil.getString(context, SharePrefUtil.TB_DEVICE_ID, "");
        String stored = SharePrefUtil.getString(context, SharePrefUtil.TB_ACCESS_TOKEN, "");
        String accessToken = decode(stored);
        return new DeviceCredentials(deviceId, accessToken);
    }

    @Override
    public void save(DeviceCredentials credentials) {
        SharePrefUtil.saveString(context, SharePrefUtil.TB_DEVICE_ID, credentials.getDeviceId());
        SharePrefUtil.saveString(context, SharePrefUtil.TB_ACCESS_TOKEN, encode(credentials.getAccessToken()));
    }

    @Override
    public void clear() {
        SharePrefUtil.saveString(context, SharePrefUtil.TB_DEVICE_ID, "");
        SharePrefUtil.saveString(context, SharePrefUtil.TB_ACCESS_TOKEN, "");
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
            return "";
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
            return "";
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
