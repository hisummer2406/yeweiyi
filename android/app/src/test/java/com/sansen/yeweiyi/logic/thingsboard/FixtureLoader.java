package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试专用 fixture 读取。两个目录：
 * <ul>
 *   <li>{@code /protocol/v1/}：单仓库 {@code cloud/fixtures/protocol/v1/} 的共享 fixture，由 build.gradle
 *       挂到测试 classpath，没有副本（{@link #loadProtocolV1}，见该目录 ANDROID.md）；</li>
 *   <li>{@code src/test/resources/thingsboard/}：Android 侧自己的样例和 TLS 证书
 *       （{@link #loadText}/{@link #openStream}）。</li>
 * </ul>
 */
public final class FixtureLoader {

    private FixtureLoader() {
    }

    /** 读取 cloud/fixtures 下的共享 fixture，去掉 {@code _comment}。 */
    public static JsonObject loadProtocolV1(String resourceName) {
        String text = readResource("/protocol/v1/" + resourceName);
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
        return readResource("/thingsboard/" + resourceName);
    }

    private static String readResource(String resourceName) {
        try (InputStream in = FixtureLoader.class.getResourceAsStream(resourceName)) {
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
