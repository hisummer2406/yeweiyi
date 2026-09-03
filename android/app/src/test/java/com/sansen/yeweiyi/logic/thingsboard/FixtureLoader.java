package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试专用：从 {@code src/test/resources/thingsboard/} 读取跨仓库契约 fixture。
 * 这些 fixture 目前是 Android 侧单方面定义的（见各 fixture 文件里的 _comment），
 * 一旦 yeweiyi-cloud#7 协议冻结、双方对齐，就把这里换成共享 fixture 目录。
 */
public final class FixtureLoader {

    private FixtureLoader() {
    }

    public static JsonObject loadObject(String resourceName) {
        String text = loadText(resourceName);
        JsonElement element = new JsonParser().parse(text);
        JsonObject obj = element.getAsJsonObject();
        obj.remove("_comment");
        return obj;
    }

    /** 按流打开一份 fixture，调用方负责关闭。证书这类不是 UTF-8 文本的内容走这个。 */
    public static InputStream openStream(String resourceName) {
        InputStream in = FixtureLoader.class.getResourceAsStream("/thingsboard/" + resourceName);
        if (in == null) {
            throw new IllegalStateException("找不到 fixture: " + resourceName);
        }
        return in;
    }

    public static String loadText(String resourceName) {
        try (InputStream in = FixtureLoader.class.getResourceAsStream("/thingsboard/" + resourceName)) {
            if (in == null) {
                throw new IllegalStateException("找不到 fixture: " + resourceName);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 fixture 失败: " + resourceName, e);
        }
    }
}
