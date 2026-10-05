package com.sansen.yeweiyi.logic.thingsboard;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ClientAttributesBuilderTest {

    @Test
    public void tankCountIsMaxTankIdLikeTheSnapshot() {
        Map<String, Object> attributes = ClientAttributesBuilder.build(Arrays.asList(
                new ClientAttributesBuilder.Tank(1, "01", 0, 30000),
                new ClientAttributesBuilder.Tank(5, "05", 0, 20000)));

        assertEquals(5, attributes.get("tankCount"));
        assertEquals(20000.0, attributes.get("tank5.capacityL"));
    }

    /** 名称跟串口设置页"串口 2"的三个选项一致。 */
    @Test
    public void probeTypeNamesMatchSerialSettingsPage() {
        Map<String, Object> attributes = ClientAttributesBuilder.build(Arrays.asList(
                new ClientAttributesBuilder.Tank(1, "01", 0, 1),
                new ClientAttributesBuilder.Tank(2, "02", 1, 1),
                new ClientAttributesBuilder.Tank(3, "03", 2, 1)));

        assertEquals("中盛探针协议", "ZHONGCHENG", attributes.get("tank1.probeType"));
        assertEquals("标准 Modbus 协议", "MODBUS", attributes.get("tank2.probeType"));
        assertEquals("4-20mA", "4-20MA", attributes.get("tank3.probeType"));
    }

    @Test
    public void unknownProbeTypeIsOmittedNotGuessed() {
        Map<String, Object> attributes = ClientAttributesBuilder.build(
                Collections.singletonList(new ClientAttributesBuilder.Tank(1, "01", 9, 1)));

        assertFalse(attributes.containsKey("tank1.probeType"));
        assertEquals("01", attributes.get("tank1.probeAddress"));
    }

    @Test
    public void noTanksStillReportsProtocolVersion() {
        Map<String, Object> attributes = ClientAttributesBuilder.build(Collections.<ClientAttributesBuilder.Tank>emptyList());

        assertEquals(1, attributes.get("protocolVersion"));
        assertEquals(0, attributes.get("tankCount"));
    }
}
