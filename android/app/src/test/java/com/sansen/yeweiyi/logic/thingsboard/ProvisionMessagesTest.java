package com.sansen.yeweiyi.logic.thingsboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProvisionMessagesTest {

    @Test
    public void requestCarriesDeviceNameAndProvisionKeyPair() {
        JsonObject request = new JsonParser().parse(
                ProvisionMessages.buildRequest("SN-0001", "key-abc", "secret-xyz")).getAsJsonObject();
        assertEquals("SN-0001", request.get("deviceName").getAsString());
        assertEquals("key-abc", request.get("provisionDeviceKey").getAsString());
        assertEquals("secret-xyz", request.get("provisionDeviceSecret").getAsString());
        assertEquals(3, request.size());
    }

    @Test
    public void requestEscapesSpecialCharactersInDeviceName() {
        JsonObject request = new JsonParser().parse(
                ProvisionMessages.buildRequest("a\"b\\c", "k", "s")).getAsJsonObject();
        assertEquals("a\"b\\c", request.get("deviceName").getAsString());
    }

    @Test
    public void successWithAccessTokenReturnsToken() {
        ProvisionResult result = ProvisionMessages.parseResponse(
                "{\"status\":\"SUCCESS\",\"credentialsType\":\"ACCESS_TOKEN\",\"credentialsValue\":\"tok123\"}");
        assertEquals(ProvisionResult.Outcome.SUCCESS, result.getOutcome());
        assertEquals("tok123", result.getAccessToken());
    }

    @Test
    public void failureCarriesCloudErrorMessage() {
        ProvisionResult result = ProvisionMessages.parseResponse(
                "{\"status\":\"FAILURE\",\"errorMsg\":\"Failed to provision device!\"}");
        assertEquals(ProvisionResult.Outcome.REJECTED, result.getOutcome());
        assertEquals("Failed to provision device!", result.getErrorMessage());
        assertNull(result.getAccessToken());
    }

    @Test
    public void nonSuccessStatusWithoutErrorMsgFallsBackToStatus() {
        ProvisionResult result = ProvisionMessages.parseResponse("{\"status\":\"NOT_FOUND\"}");
        assertEquals(ProvisionResult.Outcome.REJECTED, result.getOutcome());
        assertEquals("NOT_FOUND", result.getErrorMessage());
    }

    @Test
    public void unsupportedCredentialsTypeIsRejected() {
        ProvisionResult result = ProvisionMessages.parseResponse(
                "{\"status\":\"SUCCESS\",\"credentialsType\":\"X509_CERTIFICATE\",\"credentialsValue\":\"pem\"}");
        assertEquals(ProvisionResult.Outcome.REJECTED, result.getOutcome());
        assertNull(result.getAccessToken());
    }

    @Test
    public void successWithoutTokenIsMalformed() {
        assertEquals(ProvisionResult.Outcome.MALFORMED, ProvisionMessages.parseResponse(
                "{\"status\":\"SUCCESS\",\"credentialsType\":\"ACCESS_TOKEN\",\"credentialsValue\":\"  \"}").getOutcome());
        assertEquals(ProvisionResult.Outcome.MALFORMED, ProvisionMessages.parseResponse(
                "{\"status\":\"SUCCESS\",\"credentialsType\":\"ACCESS_TOKEN\"}").getOutcome());
    }

    @Test
    public void invalidJsonIsMalformed() {
        assertEquals(ProvisionResult.Outcome.MALFORMED, ProvisionMessages.parseResponse("not json{").getOutcome());
        assertEquals(ProvisionResult.Outcome.MALFORMED, ProvisionMessages.parseResponse("[1,2]").getOutcome());
        assertEquals(ProvisionResult.Outcome.MALFORMED, ProvisionMessages.parseResponse("").getOutcome());
        assertEquals(ProvisionResult.Outcome.MALFORMED, ProvisionMessages.parseResponse(null).getOutcome());
        assertEquals(ProvisionResult.Outcome.MALFORMED, ProvisionMessages.parseResponse("{}").getOutcome());
    }

    @Test
    public void toStringDoesNotLeakToken() {
        String text = ProvisionResult.success("secret-token").toString();
        assertFalse(text.contains("secret-token"));
        assertTrue(text.contains("REDACTED"));
    }
}
