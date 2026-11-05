package com.sansen.yeweiyi.manager;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.util.SparseIntArray;

public class SoundManager {

    private static SoundManager instance;
    private Context context;
    private SoundPool soundPool;
    private MediaPlayer bgmPlayer;
    private SparseIntArray soundPoolMap;    // 缓存已加载的音效ID
    private float effectVolume = 1.0f;      // 音效音量（0.0~1.0）
    private float bgmVolume = 1.0f;         // 背景音乐音量

    private SoundManager() { }

    public static synchronized SoundManager getInstance() {
        if (instance == null) {
            instance = new SoundManager();
        }
        return instance;
    }

    /**
     * 初始化工具类，建议在 Application.onCreate() 中调用
     */
    public void init(Context appContext) {
        this.context = appContext.getApplicationContext();
        soundPoolMap = new SparseIntArray();

        // 创建 SoundPool（兼容 Android 5.0+）
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(attributes)
                .build();
    }

    // ==================== 音效部分 (SoundPool) ====================

    /**
     * 从资源文件加载音效（通常放在 res/raw 下）
     * @param resId 资源ID，如 R.raw.click
     * @return 加载的音效ID，可用于后续播放
     */
    public int loadEffect(int resId) {
        int soundId = soundPool.load(context, resId, 1);
        soundPoolMap.put(resId, soundId);
        return soundId;
    }

    /**
     * 播放已加载的音效（需先调用 loadEffect）
     * @param resId 加载时使用的资源ID
     */
    public void playEffect(int resId) {
        int soundId = soundPoolMap.get(resId);
        if (soundId != 0) {
            soundPool.play(soundId, effectVolume, effectVolume, 0, 0, 1.0f);
        }
    }

    /**
     * 直接播放资源音效（加载并播放，适合一次性音效）
     * @param resId 资源ID
     */
    public void playEffectOnce(int resId) {
        int soundId = soundPool.load(context, resId, 1);
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (status == 0) {
                pool.play(sampleId, effectVolume, effectVolume, 0, 0, 1.0f);
            }
        });
    }

    /**
     * 播放 Assets 中的音效文件（一次性）
     * @param assetFileName 如 "effects/click.ogg"
     */
    public void playAssetEffect(String assetFileName) {
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(assetFileName);
            int soundId = soundPool.load(afd, 1);
            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) {
                    pool.play(sampleId, effectVolume, effectVolume, 0, 0, 1.0f);
                }
            });
            afd.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 设置音效音量
     */
    public void setEffectVolume(float volume) {
        this.effectVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    // ==================== 背景音乐部分 (MediaPlayer) ====================

    /**
     * 播放背景音乐（从资源文件）
     * @param resId 资源ID，如 R.raw.bgm
     * @param looping 是否循环
     */
    public void playBGM(int resId, boolean looping) {
        stopBGM(); // 停止当前可能正在播放的音乐
        bgmPlayer = MediaPlayer.create(context, resId);
        if (bgmPlayer != null) {
            bgmPlayer.setLooping(looping);
            bgmPlayer.setVolume(bgmVolume, bgmVolume);
            bgmPlayer.start();
        }
    }

    /**
     * 播放背景音乐（从文件路径或网络URL）
     * @param path 文件路径或http/https地址
     * @param looping 是否循环
     */
    public void playBGM(String path, boolean looping) {
        stopBGM();
        try {
            bgmPlayer = new MediaPlayer();
            bgmPlayer.setDataSource(path);
            bgmPlayer.setLooping(looping);
            bgmPlayer.setVolume(bgmVolume, bgmVolume);
            bgmPlayer.prepareAsync(); // 异步准备，避免阻塞
            bgmPlayer.setOnPreparedListener(mp -> mp.start());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 播放 Assets 中的背景音乐文件
     */
    public void playAssetBGM(String assetFileName, boolean looping) {
        stopBGM();
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(assetFileName);
            bgmPlayer = new MediaPlayer();
            bgmPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            bgmPlayer.setLooping(looping);
            bgmPlayer.setVolume(bgmVolume, bgmVolume);
            bgmPlayer.prepareAsync();
            bgmPlayer.setOnPreparedListener(mp -> mp.start());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 暂停背景音乐
     */
    public void pauseBGM() {
        if (bgmPlayer != null && bgmPlayer.isPlaying()) {
            bgmPlayer.pause();
        }
    }

    /**
     * 恢复暂停的背景音乐
     */
    public void resumeBGM() {
        if (bgmPlayer != null && !bgmPlayer.isPlaying()) {
            bgmPlayer.start();
        }
    }

    /**
     * 停止背景音乐（释放当前 MediaPlayer）
     */
    public void stopBGM() {
        if (bgmPlayer != null) {
            bgmPlayer.stop();
            bgmPlayer.release();
            bgmPlayer = null;
        }
    }

    /**
     * 设置背景音乐音量
     */
    public void setBGMVolume(float volume) {
        this.bgmVolume = Math.max(0.0f, Math.min(1.0f, volume));
        if (bgmPlayer != null) {
            bgmPlayer.setVolume(bgmVolume, bgmVolume);
        }
    }

    // ==================== 资源清理 ====================

    /**
     * 释放所有音频资源，应在应用退出或不需要时调用
     */
    public void releaseAll() {
        stopBGM();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        soundPoolMap.clear();
    }
}