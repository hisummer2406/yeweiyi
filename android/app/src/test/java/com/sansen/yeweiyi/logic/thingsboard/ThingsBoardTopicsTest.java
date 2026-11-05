package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ThingsBoardTopicsTest {

    @Test
    public void fixedTopicsMatchThingsBoardDeviceApi() {
        assertEquals("v1/devices/me/telemetry", ThingsBoardTopics.TELEMETRY);
        assertEquals("v1/devices/me/attributes", ThingsBoardTopics.ATTRIBUTES_PUBLISH);
        assertEquals("v1/devices/me/attributes/response/+", ThingsBoardTopics.ATTRIBUTES_RESPONSE_SUBSCRIBE);
        assertEquals("v1/devices/me/rpc/request/+", ThingsBoardTopics.RPC_REQUEST_SUBSCRIBE);
    }

    @Test
    public void provisionTopicsMatchThingsBoardDeviceApi() {
        assertEquals("provision", ThingsBoardTopics.PROVISION_USERNAME);
        assertEquals("/provision/request", ThingsBoardTopics.PROVISION_REQUEST);
        assertEquals("/provision/response", ThingsBoardTopics.PROVISION_RESPONSE);
    }

    @Test
    public void buildsRequestAndResponseTopicsById() {
        assertEquals("v1/devices/me/attributes/request/7", ThingsBoardTopics.attributesRequest(7));
        assertEquals("v1/devices/me/rpc/response/abc-123", ThingsBoardTopics.rpcResponse("abc-123"));
    }

    @Test
    public void extractsRequestIdFromRpcRequestTopic() {
        assertEquals("42", ThingsBoardTopics.extractRpcRequestId("v1/devices/me/rpc/request/42"));
    }

    @Test
    public void returnsNullForNonRpcRequestTopics() {
        assertNull(ThingsBoardTopics.extractRpcRequestId("v1/devices/me/telemetry"));
        assertNull(ThingsBoardTopics.extractRpcRequestId("v1/devices/me/rpc/request/"));
        assertNull(ThingsBoardTopics.extractRpcRequestId(null));
    }

    @Test
    public void extractsRequestIdFromAttributesResponseTopic() {
        assertEquals("7", ThingsBoardTopics.extractAttributesResponseRequestId("v1/devices/me/attributes/response/7"));
    }

    @Test
    public void returnsNullForNonAttributesResponseTopics() {
        assertNull(ThingsBoardTopics.extractAttributesResponseRequestId("v1/devices/me/attributes"));
        assertNull(ThingsBoardTopics.extractAttributesResponseRequestId("v1/devices/me/rpc/request/7"));
        assertNull(ThingsBoardTopics.extractAttributesResponseRequestId("v1/devices/me/attributes/response/"));
        assertNull(ThingsBoardTopics.extractAttributesResponseRequestId(null));
    }

    @Test
    public void noAlinkStyleTopicSurvives() {
        // 不保留 sys/{productKey}/{deviceName}/... 形状，见 issue #37 scope。
        String[] all = {
                ThingsBoardTopics.TELEMETRY,
                ThingsBoardTopics.ATTRIBUTES_PUBLISH,
                ThingsBoardTopics.ATTRIBUTES_RESPONSE_SUBSCRIBE,
                ThingsBoardTopics.RPC_REQUEST_SUBSCRIBE,
                ThingsBoardTopics.attributesRequest(1),
                ThingsBoardTopics.rpcResponse("1")
        };
        for (String topic : all) {
            org.junit.Assert.assertFalse(topic, topic.startsWith("sys/"));
        }
    }
}
