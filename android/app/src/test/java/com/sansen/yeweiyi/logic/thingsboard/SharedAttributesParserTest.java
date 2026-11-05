package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SharedAttributesParserTest {

    // ---- 推送消息：v1/devices/me/attributes，根对象直接就是共享属性 ----

    @Test
    public void parsesPushedReportIntervalSecondsAsMillis() {
        Long millis = SharedAttributesParser.parsePushedReportIntervalMillis("{\"reportIntervalSec\":60}");
        assertEquals(Long.valueOf(60_000L), millis);
    }

    @Test
    public void pushedPayloadIgnoresUnknownKeysAlongsideReportIntervalSec() {
        // 协议规则（issue #39/protocol-v1.md 第 2 节）：接收方必须忽略不认识的 key，不报错。
        // alarmThresholds 目前故意不解析（见类注释），同一条推送里混着它不能影响
        // reportIntervalSec 的解析。
        Long millis = SharedAttributesParser.parsePushedReportIntervalMillis(
                "{\"reportIntervalSec\":45,\"alarmThresholds\":{\"tank1\":{\"high\":9000}},\"未来新增字段\":123}");
        assertEquals(Long.valueOf(45_000L), millis);
    }

    @Test
    public void pushedPayloadMissingKeyReturnsNull() {
        assertNull(SharedAttributesParser.parsePushedReportIntervalMillis("{\"alarmThresholds\":{}}"));
    }

    @Test
    public void pushedPayloadNonNumericValueReturnsNull() {
        assertNull(SharedAttributesParser.parsePushedReportIntervalMillis("{\"reportIntervalSec\":\"很快\"}"));
    }

    @Test
    public void pushedPayloadNonPositiveValueReturnsNull() {
        assertNull("0 秒周期没有意义，不能让 TelemetryScheduler 抛异常",
                SharedAttributesParser.parsePushedReportIntervalMillis("{\"reportIntervalSec\":0}"));
        assertNull(SharedAttributesParser.parsePushedReportIntervalMillis("{\"reportIntervalSec\":-30}"));
    }

    @Test
    public void pushedPayloadMalformedJsonReturnsNullNotThrows() {
        assertNull(SharedAttributesParser.parsePushedReportIntervalMillis("不是 JSON"));
        assertNull(SharedAttributesParser.parsePushedReportIntervalMillis(null));
        assertNull(SharedAttributesParser.parsePushedReportIntervalMillis("[1,2,3]"));
    }

    // ---- 应答消息：v1/devices/me/attributes/response/{id}，真正的值包在 shared 子对象里 ----

    @Test
    public void parsesResponseReportIntervalFromSharedObject() {
        Long millis = SharedAttributesParser.parseResponseReportIntervalMillis(
                "{\"shared\":{\"reportIntervalSec\":90},\"client\":{\"deviceModel\":\"x\"}}");
        assertEquals(Long.valueOf(90_000L), millis);
    }

    @Test
    public void responseWithoutSharedKeyReturnsNull() {
        // 请求的 sharedKeys 在服务端一个都没设置过时，ThingsBoard 应答里可能压根没有 shared 键。
        assertNull(SharedAttributesParser.parseResponseReportIntervalMillis("{\"client\":{}}"));
    }

    @Test
    public void responseDoesNotReadReportIntervalFromTopLevelOnlyFromShared() {
        // 防止实现退化成"哪里都找一下"——client 子对象或根对象里塞了同名 key 不能被误当成共享属性。
        Long millis = SharedAttributesParser.parseResponseReportIntervalMillis(
                "{\"reportIntervalSec\":999,\"client\":{\"reportIntervalSec\":999},\"shared\":{}}");
        assertNull(millis);
    }
}
