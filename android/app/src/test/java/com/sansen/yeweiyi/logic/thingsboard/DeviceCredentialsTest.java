package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeviceCredentialsTest {

    @Test
    public void validWhenBothFieldsPresent() {
        assertTrue(new DeviceCredentials("device-1", "token-abc").isValid());
    }

    @Test
    public void invalidWhenTokenMissing() {
        assertFalse(new DeviceCredentials("device-1", "").isValid());
        assertFalse(new DeviceCredentials("device-1", null).isValid());
        assertFalse(new DeviceCredentials("device-1", "   ").isValid());
    }

    @Test
    public void invalidWhenDeviceIdMissing() {
        assertFalse(new DeviceCredentials(null, "token-abc").isValid());
        assertFalse(new DeviceCredentials("", "token-abc").isValid());
    }

    @Test
    public void toStringDoesNotLeakAccessToken() {
        String s = new DeviceCredentials("device-1", "super-secret-token").toString();
        assertFalse(s.contains("super-secret-token"));
    }
}
